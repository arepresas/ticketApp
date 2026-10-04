package com.ticketapp.domain;

import java.time.Instant;


/**
 * Lightweight ticket projection for list views. Carries every
 * {@link Ticket} field the dashboard renders except the heavy
 * ones: {@code fileData} is replaced by its byte size (computed in
 * SQL via {@code octet_length}, {@code null} when the ticket has
 * no attachment) and {@code ocrText} is omitted
 * (the upload and detail screens read it through the single-ticket
 * paths that return the full {@link Ticket}).
 *
 * <p>Pure value object. The repository maps it straight from the
 * row so a dashboard list never loads receipt blobs into memory.
 */
public record TicketSummary(
        long id,
        long ownerId,
        String title,
        String description,
        Ticket.Status status,
        Instant createdAt,
        Instant updatedAt,
        String contentType,
        String fileName,
        Long sizeBytes,
        String errorMessage,
        int attempts,
        Long shopId
) {
    public TicketSummary {
        if (title == null) throw new NullPointerException("title");
        if (description == null) description = "";
        if (status == null) throw new NullPointerException("status");
        if (createdAt == null) throw new NullPointerException("createdAt");
        if (updatedAt == null) throw new NullPointerException("updatedAt");
        if (sizeBytes != null && sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must be >= 0");
        if (attempts < 0) throw new IllegalArgumentException("attempts must be >= 0");
    }
}
