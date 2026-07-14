package com.ticketapp.minimaxai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.ai.ReceiptExtraction;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionRequest;
import com.ticketapp.domain.ai.ReceiptExtractionResult;
import com.ticketapp.domain.ai.ReceiptExtractor;
import com.ticketapp.minimaxai.autoconfigure.MinimaxAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Provider implementation of the {@link ReceiptExtractor} port
 * backed by MiniMax (ADR 0007).
 *
 * <p>Composes three collaborators:
 * <ul>
 *   <li>{@link MiniMaxApiClient} — sends the chat-completion request
 *       and reads the raw assistant text. Translates HTTP, parse,
 *       and network failures into {@link ReceiptExtractionException}.</li>
 *   <li>{@link PdfTextExtractor} — pre-processes PDF receipts into
 *       plain text (MiniMax's chat-completions endpoint doesn't
 *       accept PDFs natively; ADR 0006 D3).</li>
 *   <li>{@link MinimaxAiProperties} — provider-specific configuration
 *       (model id, timeout). Read once at construction.</li>
 * </ul>
 *
 * <p>This class owns the model-specific parsing concerns — the
 * {@code <think>} stripper, code-fence stripper, JSON substring
 * fallback — that are intrinsic to the MiniMax wire format. Other
 * provider modules implement the same port with their own
 * parsers.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public final class MiniMaxReceiptExtractor implements ReceiptExtractor {

    /**
     * Known category labels. Anything else the model emits is stored
     * as {@code "other"}. Kept here (provider-side) rather than in
     * the domain because the label set is a property of the prompt,
     * not of the extraction concept.
     */
    private static final Set<String> KNOWN_CATEGORIES = Set.of(
            "food", "pharmacy", "restaurant", "fuel", "other");

    private final MiniMaxApiClient client;
    private final PdfTextExtractor pdfExtractor;
    private final ObjectMapper objectMapper;
    private final MinimaxAiProperties properties;

    @Override
    public ReceiptExtraction extract(ReceiptExtractionRequest request)
            throws ReceiptExtractionException {
        MiniMaxApiClient.ReceiptInput input;
        if (request.isPdf()) {
            // PDF preprocessing is a MiniMax concern: MiniMax's
            // chat-completions endpoint doesn't accept PDFs natively
            // (ADR 0006 D3). Future implementations with native PDF
            // support would skip this step.
            String text;
            try {
                text = pdfExtractor.extract(request.content());
            } catch (java.io.IOException ioe) {
                throw new ReceiptExtractionException(0,
                        "PDF text extraction failed: " + ioe.getMessage(), ioe);
            }
            if (text.isBlank()) {
                // Scanned / image-only PDF: no selectable text. The
                // upstream API rejects raw PDF bytes in image_url
                // (HTTP 400: "media type 'application/pdf' not
                // supported") so we can't just forward the original
                // bytes — the PDF has to be rasterized to PNG first.
                // 200 DPI is high enough to keep small print
                // legible; the resulting PNG is sent through the
                // same image_url branch as a normal photo upload.
                log.warn("PDF text extraction returned empty for {} bytes — "
                        + "rasterizing first page as PNG", request.content().length);
                byte[] pngBytes;
                try {
                    pngBytes = pdfExtractor.rasterizeFirstPageAsPng(request.content());
                } catch (java.io.IOException ioe) {
                    throw new ReceiptExtractionException(0,
                            "PDF rasterization failed: " + ioe.getMessage(), ioe);
                }
                if (pngBytes == null) {
                    // Either empty bytes (caught at the call site
                    // above) or zero-page document — both surface as
                    // the same "nothing to extract" hard failure.
                    throw new ReceiptExtractionException(0,
                            "PDF has no pages to rasterize");
                }
                input = MiniMaxApiClient.ReceiptInput.image(
                        properties.model(), pngBytes, "image/png");
            } else {
                input = MiniMaxApiClient.ReceiptInput.pdfText(properties.model(), text);
            }
        } else {
            input = MiniMaxApiClient.ReceiptInput.image(properties.model(),
                    request.content(), request.contentType());
        }

        try {
            String raw = client.extractReceipt(input);
            return new ReceiptExtraction(parse(raw), raw, properties.model());
        } catch (Exception e) {
            // Wrap any provider-level failure (HTTP, parse, network)
            // into the domain exception. The orchestrator never sees
            // a provider-specific class.
            if (e instanceof ReceiptExtractionException ree) {
                throw ree;
            }
            throw new ReceiptExtractionException(0,
                    "MiniMax extraction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Parse the raw reply into a {@link ReceiptExtractionResult}.
     * Defensive:
     * <ul>
     *   <li>Strips {@code <think>...</think>} blocks (DeepSeek-style
     *       chain-of-thought that some MiniMax model revisions emit
     *       even with {@code response_format: json_object}). If a
     *       {@code <think>} tag has no matching close (the model ran
     *       out of tokens mid-reasoning), the block is stripped to
     *       end-of-string rather than left intact.</li>
     *   <li>Strips code fences if present.</li>
     *   <li>If the reply is still not valid JSON (model wrapped it in
     *       markup like {@code <output>...</output>} or a
     *       {@code <|refusal|>...<|/refusal|>} block), extracts the
     *       first balanced JSON object substring as a fallback.</li>
     *   <li>If stripping the think block leaves nothing — the reply
     *       was pure reasoning with no JSON payload — surfaces a
     *       clear message so operators see "token budget exhausted in
     *       thinking" instead of a generic "non-JSON reply".</li>
     * </ul>
     */
    private ReceiptExtractionResult parse(String raw) throws Exception {
        String stripped = stripThinkBlocks(stripCodeFences(raw));
        if (stripped.isBlank()) {
            // Model spent the whole budget thinking and never emitted
            // JSON. Common cause: max_completion_tokens too tight for
            // long Lidl receipts. The BFF catches this and reverts the
            // ticket to OPEN; the message lets operators diagnose.
            throw new ReceiptExtractionException(0,
                    "MiniMax reply contained only thinking, no JSON payload"
                            + " (token budget likely exhausted mid-reasoning): "
                            + truncate(raw, 4096));
        }
        try {
            return parseJsonNode(stripped);
        } catch (Exception primaryFailure) {
            // Substring-recovery lives in its own helper so the
            // primary parse path stays linear and this method's
            // cognitive complexity stays under the sonar threshold.
            ReceiptExtractionResult recovered = recoverFromSubstring(stripped, primaryFailure);
            if (recovered != null) {
                return recovered;
            }
            throw new ReceiptExtractionException(0,
                    "MiniMax returned a non-JSON reply: " + truncate(raw, 4096),
                    primaryFailure);
        }
    }

    /**
     * Try to lift a balanced JSON object out of {@code stripped} and
     * parse it. Returns {@code null} when nothing recoverable is
     * present, or when the substring itself fails to parse — both
     * outcomes leave the caller to throw the original failure with
     * the suppressed secondary cause attached.
     */
    private ReceiptExtractionResult recoverFromSubstring(String stripped,
                                                         Exception primaryFailure) throws Exception {
        String lifted = extractFirstJsonObject(stripped);
        if (lifted == null || lifted.equals(stripped)) {
            return null;
        }
        try {
            log.debug("Primary JSON parse failed — recovered via substring extraction");
            return parseJsonNode(lifted);
        } catch (Exception secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
            return null;
        }
    }

    private ReceiptExtractionResult parseJsonNode(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        String merchant = requireText(root, "merchant");
        LocalDate purchaseDate = parseDate(requireText(root, "purchase_date"));
        BigDecimal totalAmount = parseDecimal(root.get("total_amount"), "total_amount");
        String currency = requireText(root, "currency").toUpperCase();
        if (currency.length() != 3) {
            throw new IllegalStateException("currency must be ISO 4217, got: " + currency);
        }
        String category = optionalText(root, "category");
        if (category != null) {
            String normalized = category.toLowerCase();
            if (!KNOWN_CATEGORIES.contains(normalized)) {
                normalized = "other";
            }
            category = normalized;
        }
        List<ProductLine> products = parseProducts(root.get("products"));

        return new ReceiptExtractionResult(
                merchant, purchaseDate, category, products, totalAmount, currency);
    }

    private List<ProductLine> parseProducts(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<ProductLine> out = new ArrayList<>();
        Iterator<JsonNode> it = arr.elements();
        while (it.hasNext()) {
            JsonNode p = it.next();
            String name = requireText(p, "name");
            BigDecimal qty = parseDecimal(p.get("quantity"), "quantity");
            String unit = optionalText(p, "unit");
            BigDecimal ppu = parseDecimal(p.get("price_per_unit"), "price_per_unit");
            BigDecimal total = parseDecimal(p.get("line_total"), "line_total");
            out.add(new ProductLine(name, qty, unit, ppu, total));
        }
        return out;
    }

    private static String stripCodeFences(String s) {
        String trimmed = s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String stripThinkBlocks(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int cursor = 0;
        while (cursor < s.length()) {
            int open = s.indexOf("<think>", cursor);
            if (open < 0) {
                out.append(s, cursor, s.length());
                return out.toString().trim();
            }
            out.append(s, cursor, open);
            int close = s.indexOf("</think>", open + "<think>".length());
            if (close < 0) {
                // Unclosed think block — model ran out of tokens mid
                // reasoning. Drop the rest of the string; the caller
                // will see a blank payload and raise a clear error
                // rather than try to parse half-formed JSON.
                return out.toString().trim();
            }
            cursor = close + "</think>".length();
        }
        return out.toString().trim();
    }

    private static String extractFirstJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                // Inside a JSON string: honour backslash escapes, then
                // only a closing unescaped quote ends the string.
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalStateException("missing or empty field: " + field);
        }
        return n.asText();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeException e) {
            throw new IllegalStateException("invalid purchase_date: " + s, e);
        }
    }

    private static BigDecimal parseDecimal(JsonNode n, String field) {
        if (n == null || n.isNull()) {
            throw new IllegalStateException("missing numeric field: " + field);
        }
        if (!n.isNumber()) {
            throw new IllegalStateException("field " + field + " is not a number: " + n);
        }
        return n.decimalValue();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "<null>";
        return s.length() > max ? s.substring(0, max) + "...[truncated]" : s;
    }
}
