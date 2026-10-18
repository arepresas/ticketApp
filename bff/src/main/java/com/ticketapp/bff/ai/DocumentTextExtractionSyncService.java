package com.ticketapp.bff.ai;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.ai.DocumentTextExtractionException;
import com.ticketapp.domain.ai.DocumentTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * Upload-time document text step. Runs once per created ticket, right
 * after the BFF persists the bytes, so the SPA can show the verbatim
 * transcription alongside the upload preview without a second round
 * trip.
 *
 * <p><b>One process for image and PDF.</b> The same
 * {@link DocumentTextExtractor#extract(byte[], String)} call covers
 * both media types — the implementation branches internally
 * (PDFBox text → rasterize → vision OCR for PDFs; vision OCR for
 * images). The BFF stays format-agnostic and never has to know
 * whether the bytes are a photo or a PDF.
 *
 * <p><b>Why synchronous.</b> The alternative — fire-and-forget OCR
 * on a background thread, expose the result through polling or
 * Server-Sent Events — buys snappier uploads but trades them for a
 * preview UI that has to reconcile state. The OCR call against
 * The provider typically returns in &lt; 2 s for a 1 MB photo or a
 * digital-born PDF (the cheap text path returns in microseconds);
 * the upload UX already shows a "Uploading…" progress bar during
 * the multipart POST, so the user perceives the OCR cost as part
 * of the upload itself rather than a delayed follow-up. A scanned
 * PDF (rasterize + vision path) is the slowest case; the
 * progress bar gives the user something to look at while the
 * round-trip completes.
 *
 * <p><b>Failure mode.</b> OCR failures NEVER fail the upload — the
 * ticket is already persisted, the file is already on the row, the
 * structured-extraction scheduler will still pick the ticket up on
 * the next tick. Surfacing an OCR 4xx/5xx to the SPA would lock the
 * user out of the screen for a reason that has zero impact on the
 * downstream flow. The result is the original ticket with
 * {@code ocrText == null}; the dashboard can later distinguish a
 * never-attempted OCR (pre-V15 row) from a tried-but-empty OCR by
 * the presence/absence of {@code ocrText}, but the user-visible
 * behaviour is identical.
 *
 * <p><b>…but it is no longer silent.</b> The paragraph above
 * describes what the upload does with the failure; it does not
 * describe what the operator or the user is told about it. On
 * 2026-10-18 a hybrid-reasoning provider returned
 * {@code content: null} after spending the whole completion budget
 * on reasoning, the step logged one WARN line, the upload returned
 * 201 with {@code ocr=0 chars}, and nothing anywhere said the
 * transcript was missing. {@link #runOnUpload} therefore returns an
 * {@link Outcome} carrying an explicit {@link OcrStatus}, the
 * failure is logged at ERROR, and the upload response carries the
 * status so the SPA can tell the user. The failure reason itself
 * stays in the log: {@link Outcome#detail()} can quote a provider
 * body and must not reach the wire.
 *
 * <p><b>Why not the scheduler.</b> Calling the OCR step at upload
 * keeps the scheduler focused on the expensive structured-extraction
 * call (which already takes a few seconds and consumes a chunk of
 * the AI budget per ticket). Splitting the two pipelines also makes
 * it cheap to swap the OCR provider later (port
 * {@link DocumentTextExtractor}) without touching the
 * receipt-extraction pipeline that already has its own ADR-driven
 * port contract.
 *
 * <p><b>Empty input.</b> A metadata-only ticket (no fileData)
 * bypasses OCR entirely; the dashboard already treats the file slot
 * as empty for those rows and the OCR step would have nothing to
 * operate on.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentTextExtractionSyncService {

    private final DocumentTextExtractor documentTextExtractor;
    private final TicketRepository ticketRepository;
    private final AiProperties properties;

    /**
     * What the OCR step did with a ticket. Surfaced to the caller so
     * the upload response can tell the user, and used to pick the log
     * level.
     */
    public enum OcrStatus {
        /** No OCR was attempted: AI disabled, no bytes, or no MIME type. */
        SKIPPED,
        /** OCR ran and a transcript was stamped onto the ticket. */
        TRANSCRIBED,
        /** OCR ran and the provider reported no readable text. */
        NO_TEXT,
        /** OCR ran and failed — provider error, budget exhausted, network. */
        FAILED
    }

    /**
     * Result of the upload-time OCR step.
     *
     * @param ticket the ticket to persist/return — never null
     * @param status what happened; never null
     * @param detail operator-facing reason, {@code null} unless the
     *               step failed or produced no text. <strong>Log
     *               only</strong>: it can quote a provider response
     *               body, which must not reach the SPA.
     */
    public record Outcome(Ticket ticket, OcrStatus status, String detail) {
        public Outcome {
            java.util.Objects.requireNonNull(ticket, "ticket is required");
            java.util.Objects.requireNonNull(status, "status is required");
        }
    }

    /**
     * Run OCR on the freshly-saved ticket and stamp the verbatim
     * transcription onto {@code ticket.ocrText()}.
     *
     * <p>The returned ticket is the original one (with
     * {@code ocrText == null}) whenever the step did not run or did
     * not produce text; {@link Outcome#status()} is what tells the
     * caller which of those it was, and {@link Outcome#detail()} is
     * the log-facing reason.
     */
    public Outcome runOnUpload(Ticket ticket) {
        if (!properties.enabled()) {
            // The same kill switch the scheduled extraction honours
            // (see TicketExtractionJob#tick). Without this check the
            // upload path was the one entry point to a paid provider
            // that `ticketapp.ai.enabled=false` could not silence —
            // which is how `mvn verify` ended up issuing real OCR
            // requests against the live provider endpoint with the test
            // key. That was a 2026-09 bug; the suite is hermetic now.
            log.debug("ticketapp.ai.enabled=false — skipping OCR step");
            return new Outcome(ticket, OcrStatus.SKIPPED, null);
        }
        if (ticket == null || ticket.fileData() == null || ticket.fileData().length == 0) {
            // Metadata-only ticket: nothing to OCR. The dashboard's
            // preview already renders the empty file slot; this
            // service never over-writes a non-null ocrText with
            // null.
            return new Outcome(ticket, OcrStatus.SKIPPED, null);
        }
        String mime = ticket.contentType();
        if (mime == null || mime.isBlank()) {
            // Defensive — the controller already validated the
            // upload MIME, so reaching here means a future
            // controller-path bypass. Skip quietly rather than
            // crash the upload.
            log.warn("Ticket {} has no contentType; skipping OCR step",
                    ticket.id());
            return new Outcome(ticket, OcrStatus.SKIPPED, "ticket has no contentType");
        }
        String text;
        try {
            text = documentTextExtractor.extract(ticket.fileData(), mime);
        } catch (DocumentTextExtractionException e) {
            // Failing closed on the ticket — the upload itself
            // succeeds and the structured-extraction scheduler still
            // picks the ticket up. ERROR, not WARN: this is the line
            // an operator greps when a user says "the transcript is
            // empty", and it must survive a default-level WARN
            // filter.
            log.error("OCR failed for ticket {}: status={} msg={}",
                    ticket.id(), e.statusCode(), e.getMessage());
            return new Outcome(ticket, OcrStatus.FAILED,
                    "status=" + e.statusCode() + " " + e.getMessage());
        } catch (RuntimeException e) {
            // Belt-and-braces: a port implementation bug or a
            // non-mapped SDK failure must not crash the upload.
            log.error("OCR threw unexpectedly for ticket {}", ticket.id(), e);
            return new Outcome(ticket, OcrStatus.FAILED,
                    "unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (text == null) {
            // Provider explicitly returned no text (blank receipt,
            // model replied with one empty line). Persist the null
            // explicitly — the row's ocr_text will be NULL, the
            // same as a pre-V15 row, but the upsert's updated_at
            // stamp lets the dashboard tell the two apart if it ever
            // needs to. The persistence ensures the read path can
            // reason about the same `ocr_text IS NULL` literal and
            // not need to second-guess whether OCR ever ran.
            log.info("OCR returned no text for ticket {}", ticket.id());
            Ticket cleared = ticket.withOcrText(null);
            return new Outcome(ticketRepository.save(cleared), OcrStatus.NO_TEXT,
                    "provider returned no readable text");
        }
        Ticket stamped = ticket.withOcrText(text);
        return new Outcome(ticketRepository.save(stamped), OcrStatus.TRANSCRIBED, null);
    }
}
