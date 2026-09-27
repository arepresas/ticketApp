package com.ticketapp.minimaxai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Pure parser for MiniMax assistant replies (ADR 0007).
 *
 * <p>Owns every model-specific parsing concern — {@code <think>} stripping,
 * code-fence stripping, JSON substring recovery — so
 * {@link MiniMaxReceiptExtractor} stays an orchestrator (PDF routing +
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

    /**
     * Known category labels. Anything else the model emits is stored
     * as {@code "other"}. Kept here (provider-side) rather than in
     * the domain because the label set is a property of the prompt,
     * not of the extraction concept.
     */
    private static final Set<String> KNOWN_CATEGORIES = Set.of(
            "food", "pharmacy", "restaurant", "fuel", "other");

    private final ObjectMapper objectMapper;

    /**
     * Parse the raw reply into a {@link ReceiptExtractionResult}.
     *
     * <p>Defensive:
     * <ul>
     *   <li>Strips {@code <think>...</think>} blocks (DeepSeek-style
     *       chain-of-thought that some MiniMax model revisions emit
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
                
                "MiniMax reply contained only thinking, no JSON payload"
                            + " (token budget likely exhausted mid-reasoning): "
                            + truncate(raw, 4096));
        }
        try {
            return parseJsonNode(stripped);
        } catch (ReceiptExtractionException primaryFailure) {
            ReceiptExtractionResult recovered = recoverFromSubstring(stripped, primaryFailure);
            if (recovered != null) {
                return recovered;
            }
            throw new ReceiptExtractionException(0, false,
                
                "MiniMax returned a non-JSON reply: " + truncate(raw, 4096),
                    primaryFailure);
        }
    }

    private ReceiptExtractionResult recoverFromSubstring(String stripped,
                                                         ReceiptExtractionException primaryFailure) {
        String lifted = extractFirstJsonObject(stripped);
        if (lifted == null || lifted.equals(stripped)) {
            return null;
        }
        try {
            log.debug("Primary JSON parse failed — recovered via substring extraction");
            return parseJsonNode(lifted);
        } catch (ReceiptExtractionException secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
            return null;
        }
    }

    private ReceiptExtractionResult parseJsonNode(String json) throws ReceiptExtractionException {
        final JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ReceiptExtractionException(0, false,
                
                "Invalid JSON payload", e);
        }
        try {
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
        } catch (IllegalStateException | IllegalArgumentException | DateTimeException e) {
            throw new ReceiptExtractionException(0, false,
                
                "Invalid extraction payload: " + e.getMessage(), e);
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
        return MiniMaxApiClient.stripThinkBlocks(s);
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
