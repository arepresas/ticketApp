package com.ticketapp.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
 * <p>{@code category} is a {@link SpendCategory}, never null: the
 * column is constrained to that set and a receipt whose category could
 * not be read is {@link SpendCategory#OTHER}. What the model emits is
 * folded onto the set at the provider boundary
 * ({@link SpendCategory#fromExtractionValue(String)}) rather than
 * trusted, so a prompt regression widens the set's input without
 * widening its output.
 *
 * <p>{@code currency} is stored as ISO 4217 (3-letter). Default in
 * the database is {@code EUR}; the LLM is prompted to detect it but
 * the column never falls back to NULL.
 */
public record TicketExtraction(
        long ticketId,
        String merchant,
        LocalDate purchaseDate,
        SpendCategory category,
        List<ProductLine> products,
        BigDecimal totalAmount,
        String currency,
        String model,
        Instant extractedAt,
        String rawResponse,
        String extractionPayload) {

    public TicketExtraction {
        if (merchant == null || merchant.isBlank()) {
            throw new IllegalArgumentException("merchant must not be blank");
        }
        if (purchaseDate == null) throw new NullPointerException("purchaseDate");
        if (category == null) {
            // "No readable category" is OTHER, not an absent value —
            // accepting null here would put the same receipt in two
            // reporting buckets depending on who read it.
            throw new IllegalArgumentException("category must not be null");
        }
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
        // extractionPayload was meant to be the provider's structured
        // response as a separate JSONB column. The orchestrator never
        // populated it, and nothing reads it: the verbatim reply in
        // rawResponse already carries the whole provider object (the
        // shop block included, because the provider's parser only
        // reads the typed fields). Kept as a nullable field so
        // historical rows still round-trip; do not read from it, and
        // do not expect new writes to fill it.
    }

    /**
     * Convenience constructor for callers with no
     * {@code extractionPayload} (hand-constructed rows, tests).
     * Maps the missing field to {@code null}, which is what the
     * orchestrator does for every write today.
     */
    public TicketExtraction(
            long ticketId,
            String merchant,
            LocalDate purchaseDate,
            SpendCategory category,
            List<ProductLine> products,
            BigDecimal totalAmount,
            String currency,
            String model,
            Instant extractedAt,
            String rawResponse) {
        this(
                ticketId,
                merchant,
                purchaseDate,
                category,
                products,
                totalAmount,
                currency,
                model,
                extractedAt,
                rawResponse,
                null);
    }

    /**
     * One line on the receipt. Pricing is captured both per-unit and
     * for the line so consumers can show "X kg @ Y €/kg = Z €" without
     * recomputing.
     *
     * <p>{@code quantity} is the count on the receipt (e.g. 2 for "2
     * bottles of water"), {@code unit} the {@link QuantityUnit} that
     * count is expressed in — never null, because a line that printed
     * no unit is a {@link QuantityUnit#UNIT} line, not an absent one.
     * {@code pricePerUnit} and {@code lineTotal} are in the same
     * currency as the parent extraction.
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
            String name, BigDecimal quantity, QuantityUnit unit, BigDecimal pricePerUnit, BigDecimal lineTotal) {
        public ProductLine {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("product name must not be blank");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new IllegalArgumentException("quantity must be > 0");
            }
            if (unit == null) {
                // A line always has a unit; a line that printed none is
                // UNIT. Rejecting null here keeps "no unit" from
                // reappearing downstream as a blank catalogue label.
                throw new IllegalArgumentException("unit must not be null");
            }
            // pricePerUnit and lineTotal are intentionally NOT
            // constrained to non-negative values — see the Javadoc
            // above. A discount line has e.g. pricePerUnit = -6.00
            // and lineTotal = -6.00.
        }
    }

    /**
     * The user-editable half of an extraction: the six columns a
     * detail-screen edit may change. The AI's audit columns
     * ({@code model}, {@code extractedAt}, {@code rawResponse},
     * {@code extractionPayload}) are deliberately absent — they are
     * preserved server-side on every edit path so the "extracted by X
     * on Y" attribution stays truthful.
     *
     * <p>A domain type rather than a BFF DTO because it crosses the
     * port boundary: {@link TicketExtractionRepository#updateEditableFields}
     * takes exactly this shape and updates exactly those columns.
     */
    public record EditableFields(
            String merchant,
            LocalDate purchaseDate,
            SpendCategory category,
            List<ProductLine> products,
            BigDecimal totalAmount,
            String currency) {
        public EditableFields {
            if (merchant == null || merchant.isBlank()) {
                throw new IllegalArgumentException("merchant must not be blank");
            }
            if (purchaseDate == null) throw new NullPointerException("purchaseDate");
            if (category == null) {
                // An edit with no category is not "leave it blank": the
                // column is constrained, so the user's choice has to be
                // an actual member. OTHER is the explicit way to say
                // "I don't know".
                throw new IllegalArgumentException("category must not be null");
            }
            if (products == null) throw new NullPointerException("products");
            if (totalAmount == null) throw new NullPointerException("totalAmount");
            if (totalAmount.signum() < 0) {
                throw new IllegalArgumentException("totalAmount must be >= 0");
            }
            if (currency == null || currency.length() != 3) {
                throw new IllegalArgumentException("currency must be an ISO 4217 code");
            }
        }
    }
}
