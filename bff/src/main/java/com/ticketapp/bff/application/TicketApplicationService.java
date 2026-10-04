package com.ticketapp.bff.application;

import com.ticketapp.bff.ai.DocumentTextExtractionSyncService;
import com.ticketapp.bff.ai.TicketExtractionService;
import com.ticketapp.domain.identity.AuthenticatedUser;
import com.ticketapp.bff.extraction.TicketExtractionNormaliser;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Set;


/**
 * Application service for ticket write flows that span more than one
 * step: upload (+OCR stamp) and status changes (AI fallback +
 * catalogue normalisation). Single-purpose reads and deletes stay on
 * the controllers; anything that orchestrates several repositories
 * or services lives here so controllers stay thin HTTP adapters
 * (validate → delegate → map).
 *
 * <p>Owner scoping is enforced at the SQL layer by the repositories
 * (same contract as before the extraction from the controller): a
 * ticket owned by someone else behaves as missing and surfaces as
 * 404, never 403.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TicketApplicationService {

    /** 10 MB hard cap, mirrored in front/src/lib/new/validation.ts. */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_MIME = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/heic",
            "image/heif"
    );

    private final TicketRepository tickets;
    private final TicketExtractionRepository extractions;
    private final TicketExtractionService extractionService;
    private final DocumentTextExtractionSyncService documentTextExtractionService;
    private final TicketExtractionNormaliser normaliser;

    /**
     * Persist an upload and stamp the OCR transcription. The file
     * bytes are stored verbatim in the {@code tickets} table (see
     * migration {@code V3__add_ticket_file.sql}); no filesystem
     * staging is involved.
     *
     * <p>The OCR step is intentionally non-throwing: an OCR failure
     * never aborts the upload (the file is already persisted; the
     * structured-extraction scheduler still picks the ticket up).
     */
    public Ticket createTicket(AuthenticatedUser user,
                               MultipartFile file,
                               String titleOverride,
                               String description) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file too large (max 10 MB)");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_MIME.contains(contentType.toLowerCase(java.util.Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported file type: " + contentType);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read uploaded bytes for user {}", user.id(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read upload");
        }

        // Default title = the original filename so the dashboard list shows
        // what was uploaded without an extra form field. Callers can
        // override via the `title` field (e.g. a future "rename" flow).
        String title = (titleOverride == null || titleOverride.isBlank())
                ? file.getOriginalFilename()
                : titleOverride;
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title could not be derived");
        }
        String desc = description == null ? "" : description;

        // Ownership: the ticket's owner is the authenticated user.
        Ticket created = tickets.save(Ticket.open(
                user.id(), title, desc, contentType, file.getOriginalFilename(), bytes));
        // Sanitise the user-controlled filename for the log line
        // (CR/LF would forge log lines); the stored value is
        // untouched.
        String rawName = file.getOriginalFilename();
        String logName = rawName == null ? "" : rawName.replace('\n', '_').replace('\r', '_');
        Ticket withOcr = documentTextExtractionService.runOnUpload(created);
        log.info("Created ticket {} for user {} (file={} {} bytes, ocr={} chars)",
                withOcr.id(), user.id(), logName, bytes.length,
                withOcr.ocrText() == null ? 0 : withOcr.ocrText().length());
        return withOcr;
    }

    /**
     * Manual status change. Used for three flows:
     * <ul>
     *   <li>Retry: a ticket in {@code ON_ERROR} back to
     *       {@code OPEN}. {@link Ticket#withStatus(Status)} clears
     *       the stored {@code errorMessage} on the way through, so
     *       the next scheduler tick re-extracts from a clean slate.</li>
     *   <li>Cancel: any pending ticket → {@code CANCELLED}.</li>
     *   <li>Validate: any pending ticket → {@code DONE} triggers the
     *       {@link TicketExtractionNormaliser}
     *       which snapshots each line into the products catalogue.
     *       The normalisation runs in the same call; a normaliser
     *       failure logs a WARN but does NOT roll the status flip
     *       back (deliberate: a catalogue hiccup must not undo a
     *       DONE the user asked for).</li>
     * </ul>
     *
     * <p><b>The accepted cost of best-effort: DONE without a
     * catalogue.</b> The status flip commits in its own
     * transaction; the catalogue apply runs in the normaliser's own
     * transaction. If the normaliser fails, the row stays
     * {@code DONE} with an empty or partial catalogue. That state is
     * reachable on purpose, so it has a recovery path: re-issuing
     * {@code PATCH /api/tickets/{id}/status} with {@code DONE} runs
     * the normaliser again (the branch is keyed on the requested
     * target status, not on a transition), and the apply is
     * idempotent — every step re-resolves the catalogue master rows
     * by match key. Note the dashboard hides status actions on
     * terminal tickets, so today that retry is an API-level action.
     * </p>
     *
     * <p><b>Mark-as-done triggers extraction when missing.</b> A
     * ticket that reached {@code ON_ERROR} without ever producing an
     * extraction row (e.g. the AI provider timed out before emitting
     * JSON, or the user's previous mark-as-done raced the scheduler
     * and skipped the queue) has nothing for the normaliser to
     * snapshot. Treating "mark as done" as "extract now, then
     * normalise" makes the button do what the user expects: a failed
     * ticket they retry by clicking "Mark as done" either lands in
     * {@code DONE} with a populated catalogue or bounces back to
     * {@code ON_ERROR} with the new failure reason. Without this
     * fallback the ticket silently flips to {@code DONE} with an
     * empty catalogue — the bug this method documents.
     *
     * <p>Transitioning to {@code ON_ERROR} via this path is
     * supported (mirrors what the orchestrator does on failure) but
     * the dashboard does not expose a button for it; the only normal
     * caller is the scheduler.
     */
    public Ticket changeStatus(long id, AuthenticatedUser user, Ticket.Status status) {
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        return tickets.findById(id, user.id())
                .map(t -> {
                    Ticket target = t;
                    if (status == Ticket.Status.DONE
                            && extractions.findByTicketId(id, user.id()).isEmpty()) {
                        // If the ticket has no extraction row yet
                        // (typical for ON_ERROR retries, or any
                        // ticket that landed in DONE before the
                        // scheduler could pick it up), trigger the
                        // AI pipeline synchronously (`t` is still
                        // current — nothing wrote between the two
                        // reads, so no re-read needed here).
                        // processTicket sets the status to IN_PROGRESS
                        // at start, marks ON_ERROR on failure, and
                        // leaves the row ready for the normaliser on
                        // success. Its boolean drives the branch: a
                        // concurrent scheduler tick may have written
                        // the row meanwhile (its success is as good
                        // as ours — the catalogue exists either way),
                        // so on failure we still re-check the table
                        // before giving up. We re-read the ticket
                        // afterwards so the response reflects whatever
                        // status the pipeline actually landed on.
                        boolean extracted = extractionService.processTicket(t);
                        target = tickets.findById(id, user.id()).orElse(t);
                        if (!extracted
                                && extractions.findByTicketId(id, user.id()).isEmpty()) {
                            // Pipeline failed: keep its terminal state
                            // (ON_ERROR with the failure reason).
                            // Forcing DONE here would silently flip a
                            // failed ticket to DONE with an empty
                            // catalogue — the bug this fallback exists
                            // to prevent.
                            return target;
                        }
                    }
                    Ticket updated = tickets.save(target.withStatus(status));
                    if (status == Ticket.Status.DONE) {
                        try {
                            // The normaliser now takes the full
                            // Ticket so it can stamp shop_id on the
                            // ticket itself (V13 refactor: shop is
                            // anchored on the ticket, not per line).
                            normaliser.normaliseOnDone(updated);
                        } catch (RuntimeException ex) {
                            // Best-effort: don't fail the status flip
                            // because of a catalogue hiccup. The next
                            // mark-as-done is idempotent and re-runs
                            // the normaliser.
                            log.warn("normaliseOnDone failed for ticket {}",
                                    updated.id(), ex);
                        }
                    }
                    return updated;
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
