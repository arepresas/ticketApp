package com.ticketapp.bff.api.dto;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketSummary;



/**
 * Wire response for ticket reads. Excludes {@code fileData} so the bytes don't round-trip
 * on every list call — clients fetch the file separately when needed.
 * {@code sizeBytes} is included so the UI can show the upload size.
 * {@code errorMessage} is included so the dashboard can show the
 * failure reason next to tickets in {@code ON_ERROR} status.
 * {@code attempts} is included so the dashboard can show how many
 * times the AI extraction has been tried — actionable signal when
 * a ticket is stuck on the same ON_ERROR after multiple retries.
 * {@code ownerId} is included so the UI can render owner-aware
 * affordances and so the wire response is round-trippable to the
 * domain type when needed (tests, audit logs).
 * {@code ocrText} is the verbatim OCR transcription produced at
 * upload time (see the BFF's {@code DocumentTextExtractionSyncService}).
 * {@code null} for tickets that predate the OCR step, or when
 * the provider returned no text — the SPA renders the image
 * preview either way, but uses the transcript when present so
 * the user can sanity-check the upload without opening the
 * file. List views (summaries) always carry {@code null} here;
 * the upload and detail screens read it through the
 * single-ticket paths.
 * {@code ocrStatus} says what the OCR step did with the file.
 * It is populated <strong>only</strong> on the upload response,
 * because that is the only moment we know: a later read sees
 * {@code ocrText == null} and cannot tell "OCR failed" from "row
 * predates the OCR step" from "AI disabled". Values are the
 * {@code OcrStatus} enum names — {@code SKIPPED},
 * {@code TRANSCRIBED}, {@code NO_TEXT}, {@code FAILED} — and
 * {@code null} everywhere else. Added 2026-10-18 after a receipt
 * upload came back 201 with an empty transcript and no signal to
 * the user at all.
 */
public record TicketResponse(
        long id,
        long ownerId,
        String title,
        String description,
        Ticket.Status status,
        java.time.Instant createdAt,
        java.time.Instant updatedAt,
        String contentType,
        String fileName,
        Integer sizeBytes,
        String errorMessage,
        Integer attempts,
        String ocrText,
        String ocrStatus) {

    /**
     * Read-path factory: the OCR status is unknown here, so it stays
     * {@code null} rather than being derived from {@code ocrText}.
     */
    public static TicketResponse of(Ticket t) {
        return of(t, null);
    }

    /**
     * Upload-path factory, carrying the status the OCR step actually
     * reported.
     */
    public static TicketResponse of(Ticket t, String ocrStatus) {
        Integer size = t.fileData() == null ? null : t.fileData().length;
        return new TicketResponse(
                t.id(), t.ownerId(), t.title(), t.description(), t.status(),
                t.createdAt(), t.updatedAt(),
                t.contentType(), t.fileName(), size,
                t.errorMessage(), t.attempts(),
                t.ocrText(), ocrStatus);
    }

    public static TicketResponse of(TicketSummary s) {
        // Fail loud on overflow rather than silently truncating:
        // unreachable under the 10 MB upload cap, but the wire
        // field stays Integer by contract.
        Integer size = s.sizeBytes() == null ? null : Math.toIntExact(s.sizeBytes());
        return new TicketResponse(
                s.id(), s.ownerId(), s.title(), s.description(), s.status(),
                s.createdAt(), s.updatedAt(),
                s.contentType(), s.fileName(), size,
                s.errorMessage(), s.attempts(),
                null, null);
    }
}
