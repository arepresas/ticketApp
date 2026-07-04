package com.ticketapp.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Structured data extracted from a ticket receipt by the AI extraction
 * pipeline (see ADR 0006). One row per ticket — the {@code ticketId}
 * primary key mirrors the 1:1 join to {@code tickets}.
 *
 * <p>Pure domain value object. No framework annotations, no JSON
 * binding concerns. The persistence layer is responsible for mapping
 * {@link #products()} and {@link #rawResponse()} to/from JSONB; the
 * domain only carries the typed {@link ProductLine} list and the raw
 * string.
 *
 * <p>{@code category} is constrained to a small set of free-form
 * strings (food, pharmacy, restaurant, fuel, other) emitted by the LLM
 * prompt — we deliberately don't model it as an enum because the model
 * may produce new categories that we want to surface in analytics
 * without a code change. Validation lives in the extraction service.
 *
 * <p>{@code currency} is stored as ISO 4217 (3-letter). Default in
 * the database is {@code EUR}; the LLM is prompted to detect it but
 * the column never falls back to NULL.
 */
public record TicketExtraction(
        UUID ticketId,
        String merchant,
        LocalDate purchaseDate,
        String category,
        List<ProductLine> products,
        BigDecimal totalAmount,
        String currency,
        String model,
        Instant extractedAt,
        String rawResponse,
        String extractionPayload
) {

    public TicketExtraction {
        if (ticketId == null) throw new NullPointerException("ticketId");
        if (merchant == null || merchant.isBlank()) {
            throw new IllegalArgumentException("merchant must not be blank");
        }
        if (purchaseDate == null) throw new NullPointerException("purchaseDate");
        if (products == null) throw new NullPointerException("products");
        if (totalAmount == null) throw new NullPointerException("totalAmount");
        if (totalAmount.signum() < 0) {
            throw new IllegalArgumentException("totalAmount must be >= 0");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (extractedAt == null) throw new NullPointerException("extractedAt");
        if (rawResponse == null) throw new NullPointerException("rawResponse");
        // extractionPayload is the parsed canonical object emitted by
        // the AI provider — kept here for downstream queries that
        // want discounts, pricePerKg, and full merchant/transaction
        // details without re-parsing the raw text. Nullable so
        // historical rows (pre-V7) can still be reconstructed; new
        // writes always populate it.
    }

    /**
     * Backwards-compatible constructor for callers that don't
     * yet produce an {@code extractionPayload} (legacy test
     * fixtures, hand-constructed rows). Maps the missing field
     * to {@code null}.
     */
    public TicketExtraction(UUID ticketId,
                            String merchant,
                            LocalDate purchaseDate,
                            String category,
                            List<ProductLine> products,
                            BigDecimal totalAmount,
                            String currency,
                            String model,
                            Instant extractedAt,
                            String rawResponse) {
        this(ticketId, merchant, purchaseDate, category, products,
                totalAmount, currency, model, extractedAt, rawResponse, null);
    }

    /**
     * One line on the receipt. Pricing is captured both per-unit and
     * for the line so consumers can show "X kg @ Y €/kg = Z €" without
     * recomputing.
     *
     * <p>{@code quantity} is the count on the receipt (e.g. 2 for "2
     * bottles of water"), {@code unit} the unit label as printed (kg,
     * L, unit, pack). {@code pricePerUnit} and {@code lineTotal} are
     * in the same currency as the parent extraction.
     *
     * <p><b>Discounts and credits.</b> Negative values on
     * {@code pricePerUnit} and {@code lineTotal} are allowed and
     * represent discount/credit lines ("Remise 6€", store credit,
     * loyalty reduction). The model's typical output for a discounted
     * receipt is one positive subscription line plus one negative
     * discount line whose {@code lineTotal} brings the sum to
     * {@link TicketExtraction#totalAmount()}. Negative {@code quantity}
     * is not modelled here — a discount is modelled as {@code quantity
     * = 1} with a negative price, not as a negative count.
     */
    public record ProductLine(
            String name,
            BigDecimal quantity,
            String unit,
            BigDecimal pricePerUnit,
            BigDecimal lineTotal
    ) {
        public ProductLine {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("product name must not be blank");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new IllegalArgumentException("quantity must be > 0");
            }
            // pricePerUnit and lineTotal are intentionally NOT
            // constrained to non-negative values — see the Javadoc
            // above. A discount line has e.g. pricePerUnit = -6.00
            // and lineTotal = -6.00.
        }
    }
}