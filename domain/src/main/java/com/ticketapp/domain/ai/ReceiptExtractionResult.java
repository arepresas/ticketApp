package com.ticketapp.domain.ai;

import com.ticketapp.domain.SpendCategory;
import com.ticketapp.domain.TicketExtraction.ProductLine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Provider-neutral result of an AI extraction (ADR 0007).
 *
 * <p>The fields are exactly the structured data the orchestrator
 * persists to {@code ticket_extractions}. No provider-specific
 * concepts leak into the domain (no model id, no token counts, no
 * raw response — those live in the {@link ReceiptExtractor}
 * implementation, not in the port contract).
 *
 * <p>This is a record, not a {@link com.ticketapp.domain.TicketExtraction},
 * because it does not yet carry the persistence-side fields
 * ({@code ticketId}, {@code model}, {@code extractedAt}, {@code rawResponse}).
 * The orchestrator combines a {@code ReceiptExtractionResult} with the
 * originating ticket's id and metadata to build the {@code TicketExtraction}
 * it then saves.
 *
 * @param merchant       store name as printed on the receipt
 * @param purchaseDate   ISO date of the purchase
 * @param category       the receipt's category, already folded onto
 *                       {@link SpendCategory} by the provider adapter
 *                       (ADR 0007 keeps that fold out of the port, so a
 *                       new provider cannot widen the set the column
 *                       accepts)
 * @param products       line items; may be empty when the provider
 *                       could not parse any
 * @param totalAmount    sum the customer paid
 * @param currency       ISO 4217 code (e.g. {@code "EUR"})
 */
public record ReceiptExtractionResult(
        String merchant,
        LocalDate purchaseDate,
        SpendCategory category,
        List<ProductLine> products,
        BigDecimal totalAmount,
        String currency
) {
    public ReceiptExtractionResult {
        if (merchant == null || merchant.isBlank()) {
            throw new IllegalArgumentException("merchant must not be blank");
        }
        if (purchaseDate == null) throw new NullPointerException("purchaseDate");
        if (products == null) throw new NullPointerException("products");
        if (totalAmount == null) throw new NullPointerException("totalAmount");
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
        if (category == null) {
            // A provider that cannot classify the receipt answers
            // OTHER, not null: the column is constrained, and a null
            // would have to be written as a hole in it.
            throw new IllegalArgumentException("category must not be null");
        }
    }
}