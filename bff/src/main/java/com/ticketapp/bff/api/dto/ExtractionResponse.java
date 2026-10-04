package com.ticketapp.bff.api.dto;

import com.ticketapp.domain.TicketExtraction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;


/**
 * Wire response for {@code GET /api/tickets/{id}/extraction}.
 * Mirrors {@link TicketExtraction} but flattens the {@code products}
 * list into a plain array (it was a JSONB column on the server
 * side). The {@code model} and {@code extractedAt} fields are
 * surfaced for the UI's audit trail ("extracted by gpt-4o-mini on
 * …") so the user can see when the AI did its work.
 *
 * <p>The full {@code rawResponse} (the model's raw reply before
 * parsing) is NOT exposed — it's verbose and the structured
 * fields above already convey the actionable data. It's still
 * kept server-side for audit / debugging.</p>
 */
public record ExtractionResponse(
        long ticketId,
        String merchant,
        LocalDate purchaseDate,
        String category,
        List<ProductLineDto> products,
        BigDecimal totalAmount,
        String currency,
        String model,
        Instant extractedAt) {

    public static ExtractionResponse of(TicketExtraction e) {
        List<ProductLineDto> products = e.products().stream()
                .map(p -> new ProductLineDto(
                        p.name(),
                        p.quantity(),
                        p.unit(),
                        p.pricePerUnit(),
                        p.lineTotal()))
                .toList();
        return new ExtractionResponse(
                e.ticketId(),
                e.merchant(),
                e.purchaseDate(),
                e.category(),
                products,
                e.totalAmount(),
                e.currency(),
                e.model(),
                e.extractedAt());
    }
}
