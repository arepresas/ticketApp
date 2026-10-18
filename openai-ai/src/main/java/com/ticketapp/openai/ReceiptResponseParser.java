package com.ticketapp.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.QuantityUnit;
import com.ticketapp.domain.SpendCategory;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Pure parser for the provider assistant replies (ADR 0007).
 *
 * <p>Owns every model-specific parsing concern — {@code <think>} stripping,
 * code-fence stripping, JSON substring recovery — so
 * {@link OpenAiReceiptExtractor} stays an orchestrator (PDF routing +
 * provider call + delegate to parse). No Spring SDK types, no HTTP, no
 * PDFBox: plain text in, typed result out. Unit-testable without mocks
 * for the SDK.
 *
 * <p>Every model-data problem surfaces as {@link ReceiptExtractionException}.
 * Programming bugs (null {@code ObjectMapper}, etc.) propagate as
 * {@code RuntimeException} so callers can tell "bad model output" apart
 * from "bug in our code".
 */
@Slf4j
@RequiredArgsConstructor
public class ReceiptResponseParser {

    private final ObjectMapper objectMapper;

    /** Clock for the {@code purchase_date} fallback — injected so the
     *  "extraction day" is explicit (Sonar java:S8688) and tests can
     *  pin it. */
    private final Clock clock;

    /**
     * Parse the raw reply into a {@link ReceiptExtractionResult}.
     *
     * <p>Defensive:
     * <ul>
     *   <li>Strips {@code <think>...</think>} blocks (DeepSeek-style
     *       chain-of-thought that some the provider model revisions emit
     *       even with {@code response_format: json_object}).</li>
     *   <li>Strips code fences if present.</li>
     *   <li>Falls back to the first balanced JSON object substring
     *       when the model wraps the reply in markup.</li>
     * </ul>
     *
     * @throws ReceiptExtractionException when the reply carries no usable JSON
     */
    public ReceiptExtractionResult parse(String raw) throws ReceiptExtractionException {
        String stripped = stripThinkBlocks(stripCodeFences(raw == null ? "" : raw));
        if (stripped.isBlank()) {
            throw new ReceiptExtractionException(0, false,
                
                "the reply contained only thinking, no JSON payload"
                            + " (token budget likely exhausted mid-reasoning): "
                            + truncate(raw, 4096));
        }
        JsonNode root;
        Exception structuralFailure = null;
        try {
            root = objectMapper.readTree(stripped);
        } catch (Exception e) {
            root = null;
            structuralFailure = e;
        }
        if (root != null) {
            // The reply IS JSON — a payload problem is a schema
            // mismatch, and naming the missing field matters: wrapping
            // this as "non-JSON reply" hides the real cause from
            // operators (2026-10-18 ALDI incident: log blamed JSON it
            // never inspected). Substring recovery cannot help when the
            // reply already parsed — the object IS the object.
            return toResult(root, raw);
        }
        ReceiptExtractionResult recovered =
                recoverFromSubstring(stripped, structuralFailure, raw);
        if (recovered != null) {
            return recovered;
        }
        throw new ReceiptExtractionException(0, false,

            "the provider returned a non-JSON reply: " + truncate(raw, 4096),
                structuralFailure);
    }

    /**
     * Lift the first balanced JSON object out of markup-wrapped or
     * truncated replies and parse it.
     *
     * @return the recovered result, or {@code null} when recovery is
     *         impossible — the original structural failure then keeps
     *         any suppressed secondary failure for the diagnostic
     */
    private ReceiptExtractionResult recoverFromSubstring(String stripped,
                                                         Exception structuralFailure,
                                                         String raw) {
        String lifted = extractFirstJsonObject(stripped);
        if (lifted == null || lifted.equals(stripped)) {
            return null;
        }
        JsonNode recoveredRoot;
        try {
            recoveredRoot = objectMapper.readTree(lifted);
        } catch (Exception readFailure) {
            structuralFailure.addSuppressed(readFailure);
            return null;
        }
        log.debug("Primary JSON parse failed — recovered via substring extraction");
        try {
            return toResult(recoveredRoot, raw);
        } catch (ReceiptExtractionException secondaryFailure) {
            // Valid JSON recovered from inside the reply, but the
            // payload does not match the schema — record it on the
            // structural failure and let the caller report the reply
            // as unusable.
            structuralFailure.addSuppressed(secondaryFailure);
            return null;
        }
    }

    /**
     * Map a parsed JSON object to the domain result.
     *
     * <p>Every payload problem (missing field, bad date, non-numeric
     * total…) surfaces as one schema-mismatch message that names the
     * field and keeps the raw reply snippet for the DEBUG diagnostic.
     *
     * @throws ReceiptExtractionException when the payload does not match
     *         the extraction schema
     */
    private ReceiptExtractionResult toResult(JsonNode root, String raw)
            throws ReceiptExtractionException {
        try {
            String merchant = requireText(root, "merchant");
            LocalDate purchaseDate = purchaseDateOrDefault(root);
            BigDecimal totalAmount = parseDecimal(root.get("total_amount"), "total_amount");
            String currency = requireText(root, "currency").toUpperCase();
            if (currency.length() != 3) {
                throw new IllegalStateException("currency must be ISO 4217, got: " + currency);
            }
            // The model emits whatever label fits the receipt; the
            // column holds a closed set. Folding here rather than
            // rejecting means a prompt regression narrows what is
            // stored, never what is asked for — and it is the same
            // table the V24 backfill applies to rows already written.
            SpendCategory category = SpendCategory.fromExtractionValue(
                    optionalText(root, "category"));
            List<ProductLine> products = parseProducts(root.get("products"));
            return new ReceiptExtractionResult(
                    merchant, purchaseDate, category, products, totalAmount, currency);
        } catch (IllegalStateException | IllegalArgumentException | DateTimeException e) {
            throw new ReceiptExtractionException(0, false,
                "the provider returned JSON that does not match"
                        + " the expected schema: " + e.getMessage()
                        + "; reply: " + truncate(raw, 4096), e);
        }
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
            // The model emits free text here ("kg", "L", "pack", or
            // nothing at all); QuantityUnit folds all of it. Rejecting
            // an unrecognised label would fail the whole extraction over
            // a litre of milk.
            QuantityUnit unit = QuantityUnit.fromLabel(optionalText(p, "unit"));
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
        return OpenAiApiClient.stripThinkBlocks(s);
    }

    static String extractFirstJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
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

    /**
     * {@code purchase_date} is optional: missing, {@code null} or blank
     * all fall back to the extraction day (2026-10-18 ALDI incident:
     * date genuinely absent from a cropped receipt — the model
     * correctly refused to guess and emitted {@code null}, which
     * failed the extraction even though every other field was usable).
     * The fallback keeps the {@code NOT NULL} invariant of
     * {@code ticket_extractions.purchase_date} without a migration;
     * an unreadable-but-present value ({@code "n/a"}) still fails so
     * garbage does not silently become "today".
     */
    private LocalDate purchaseDateOrDefault(JsonNode parent) {
        String raw = optionalText(parent, "purchase_date");
        if (raw == null || raw.isBlank()) {
            log.debug("purchase_date absent in provider reply — defaulting to extraction day");
            return LocalDate.now(clock);
        }
        return parseDate(raw);
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
