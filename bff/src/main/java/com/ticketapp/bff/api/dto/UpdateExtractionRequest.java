package com.ticketapp.bff.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire shape for {@code PUT /api/tickets/{id}/extraction}.
 * Carries the user-editable portion of the extraction; the
 * AI's audit fields ({@code model}, {@code extractedAt},
 * {@code rawResponse}, {@code extractionPayload}) are not
 * part of this DTO — the controller preserves them
 * server-side by reading the existing row.
 */
public record UpdateExtractionRequest(
        String merchant,
        LocalDate purchaseDate,
        String category,
        List<ProductLineDto> products,
        BigDecimal totalAmount,
        String currency) { }
