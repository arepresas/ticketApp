package com.ticketapp.bff.api.dto;

/**
 * Wire shape for {@code PUT /api/tickets/{id}} — the detail screen's
 * single atomic edit.
 *
 * <p>Both parts are sparse. A {@code null} metadata field is left alone,
 * matching {@code PATCH /api/tickets/{id}}. A {@code null}
 * {@code extraction} means the caller has no extraction to write — a
 * ticket the AI has not read yet — and emphatically <em>not</em>
 * "delete the extraction"; nothing in this API clears one.
 *
 * <p>Nested rather than two top-level fields so the relationship is
 * explicit: the extraction is the other half of the same edit, not an
 * independent request.
 */
public record UpdateTicketAndExtractionRequest(
        String title,
        String description,
        UpdateExtractionRequest extraction) {
}