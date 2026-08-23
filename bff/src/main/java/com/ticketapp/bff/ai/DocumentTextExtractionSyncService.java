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
 * MiniMax typically returns in &lt; 2 s for a 1 MB photo or a
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

    /**
     * Run OCR on the freshly-saved ticket and stamp the verbatim
     * transcription onto {@code ticket.ocrText()}. Returns the
     * original ticket (with {@code ocrText == null}) when the OCR
     * step is a no-op (no bytes) or when the provider failed — see
     * the class javadoc for the rationale.
     */
    public Ticket runOnUpload(Ticket ticket) {
        if (ticket == null || ticket.fileData() == null || ticket.fileData().length == 0) {
            // Metadata-only ticket: nothing to OCR. The dashboard's
            // preview already renders the empty file slot; this
            // service never over-writes a non-null ocrText with
            // null.
            return ticket;
        }
        String mime = ticket.contentType();
        if (mime == null || mime.isBlank()) {
            // Defensive — the controller already validated the
            // upload MIME, so reaching here means a future
            // controller-path bypass. Skip quietly rather than
            // crash the upload.
            log.warn("Ticket {} has no contentType; skipping OCR step",
                    ticket.id());
            return ticket;
        }
        String text;
        try {
            text = documentTextExtractor.extract(ticket.fileData(), mime);
        } catch (DocumentTextExtractionException e) {
            // Failing closed: log enough for ops to see, return the
            // ticket untouched. The structured-extraction scheduler
            // still picks the ticket up; the missing OCR text just
            // means the upload preview shows no transcript.
            log.warn("OCR failed for ticket {}: status={} msg={}",
                    ticket.id(), e.statusCode(), e.getMessage());
            return ticket;
        } catch (RuntimeException e) {
            // Belt-and-braces: a port implementation bug or a
            // non-mapped SDK failure must not crash the upload.
            log.warn("OCR threw unexpectedly for ticket {}: {}",
                    ticket.id(), e.getMessage());
            return ticket;
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
            return ticketRepository.save(cleared);
        }
        Ticket stamped = ticket.withOcrText(text);
        return ticketRepository.save(stamped);
    }
}
