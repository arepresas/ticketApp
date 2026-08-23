package com.ticketapp.bff.ai;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.Ticket.Status;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.ai.ReceiptExtraction;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionRequest;
import com.ticketapp.domain.ai.ReceiptExtractor;
import com.ticketapp.persistence.JdbcTicketExtractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates the AI extraction pipeline for one ticket (ADR 0006
 * + ADR 0007).
 *
 * <p>The class is stateless. {@link TicketExtractionJob} (the
 * scheduled bean) calls {@link #processTicket(Ticket)} once per
 * candidate ticket; a single failure never aborts the rest of the
 * batch.
 *
 * <p><strong>Transaction segmentation.</strong> The method is NOT
 * wrapped in a single transaction: the provider call is a long
 * external HTTP round-trip, and holding a DB transaction (and its
 * row lock) open for its duration starves the pool and hides
 * intermediate state from other readers. Instead the work runs in
 * three short programmatic segments via {@link TransactionTemplate}:
 * <ol>
 *   <li>Segment 1 commits attempts++ + {@code IN_ANALYSIS} <em>before</em>
 *       the provider call — so a dashboard reader sees "In analysis"
 *       while the AI works instead of a stale OPEN badge.</li>
 *   <li>The provider call itself runs with no transaction active.</li>
 *   <li>Segment 2 atomically inserts the extraction row and flips the
 *       ticket to {@code IN_PROGRESS} (success), or segment 3 in
 *       {@link #markError} lands it on {@code ON_ERROR} (failure).</li>
 * </ol>
 *
 * <p>Status transitions (ADR 0006, D4):
 * <ul>
 *   <li>Open → InAnalysis: segment 1, before the provider call.</li>
 *   <li>InAnalysis → InProgress (success): extraction row inserted in
 *       the same commit.</li>
 *   <li>InAnalysis → OnError (failure): ticket is marked terminally
 *       failed with the provider's message attached. The next cron
 *       tick filters on {@code Status.OPEN} only, so a failed ticket
 *       is never retried automatically. A user-initiated PATCH
 *       ({@code /api/tickets/{id}/status} → {@code OPEN}) clears the
 *       error and re-enqueues the ticket.</li>
 * </ul>
 *
 * <p>Crash window: if the process dies between segment 1 and
 * segment 2/3, the ticket stays IN_ANALYSIS and is not retried
 * automatically (the tick only picks OPEN). Recovery is the manual
 * PATCH → OPEN path above.
 *
 * <p>The orchestrator depends only on the {@link ReceiptExtractor}
 * port — never on a provider-specific class (ADR 0007). Provider
 * implementations own their own request-shape handling (image vs
 * PDF text, response parsing, raw-reply capture).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TicketExtractionService {

    /**
     * Cap on the persisted error message. The provider exception text
     * can include the full raw model reply (4096+ chars in the worst
     * case — see the {@code <think>} stripping incident from
     * 2026-07-05) which would otherwise bloat the {@code tickets}
     * row. 2000 chars is enough to keep the actionable headline
     * ("MiniMax returned 500: ...", "MiniMax reply contained only
     * thinking...") and stays well under the operator-scannable
     * threshold for the dashboard.
     */
    static final int ERROR_MESSAGE_MAX_CHARS = 2000;

    private final TicketRepository ticketRepository;
    private final TicketExtractionRepository ticketExtractionRepository;
    private final JdbcTicketExtractionRepository jdbcTicketExtractionRepository;
    private final ReceiptExtractor receiptExtractor;
    private final TransactionTemplate tx;

    /**
     * Process one ticket end-to-end. Returns {@code true} when an
     * extraction row was persisted, {@code false} when the ticket was
     * skipped (already extracted) or failed (marked ON_ERROR with the
     * failure reason attached).
     */
    public boolean processTicket(Ticket ticket) {
        UUID id = ticket.id();

        if (ticketExtractionRepository.findByTicketId(id).isPresent()) {
            log.warn("Ticket {} already has an extraction; skipping", id);
            return false;
        }

        // Segment 1 — short transaction, COMMITTED before the provider
        // call. Bump the in-domain attempt counter so the dashboard can
        // show "tried N times" next to the status badge, and flip the
        // status to IN_ANALYSIS ("AI is currently being called") so a
        // dashboard reader watching mid-extraction sees the cyan badge
        // instead of a stale OPEN. The success branch below flips the
        // ticket to IN_PROGRESS once the provider call returns, and the
        // failure path lands it on ON_ERROR. The badge colours
        // distinguish the two: IN_ANALYSIS = sky (working),
        // IN_PROGRESS = amber (awaiting user).
        Ticket marked = tx.execute(status -> {
            jdbcTicketExtractionRepository.recordAttempt(id);
            Ticket t = ticket.incrementAttempts().withStatus(Status.IN_ANALYSIS);
            ticketRepository.save(t);
            return t;
        });

        try {
            // No transaction active here — this is the long external
            // HTTP round-trip the segmentation exists to keep out of
            // the connection pool's way.
            ReceiptExtraction extraction = receiptExtractor.extract(
                    new ReceiptExtractionRequest(
                            ticket.fileData(),
                            ticket.contentType()));
            TicketExtraction persisted = new TicketExtraction(
                    ticket.id(),
                    extraction.result().merchant(),
                    extraction.result().purchaseDate(),
                    extraction.result().category(),
                    extraction.result().products(),
                    extraction.result().totalAmount(),
                    extraction.result().currency(),
                    extraction.model(),
                    Instant.now(),
                    extraction.rawReply());

            // Segment 2 — short transaction: the extraction row and the
            // IN_PROGRESS flip land in one commit, so a reader never
            // sees an extraction without its status flip (or vice
            // versa). Explicit save of the flipped copy (not just
            // chained on the next op) because the controller's
            // PATCH /status path may immediately overwrite this with
            // DONE; we want the audit log to show the intermediate
            // state regardless. Built off `marked` so the bumped
            // attempts counter survives the transition.
            tx.executeWithoutResult(status -> {
                ticketExtractionRepository.save(persisted);
                ticketRepository.save(marked.withStatus(Status.IN_PROGRESS));
            });
            log.info("Extracted ticket {} → merchant='{}' total={} {}",
                    id, persisted.merchant(),
                    persisted.totalAmount(), persisted.currency());
            return true;
        } catch (ReceiptExtractionException e) {
            String message = "status=" + e.statusCode() + " " + e.getMessage();
            markError(marked, message);
            log.warn("Extraction failed for ticket {} — marked ON_ERROR: {}",
                    id, message);
            return false;
        } catch (Exception e) {
            // Catch-all so a bug in the orchestrator (NPE, illegal
            // state from domain validation, DB constraint violation
            // on save) still lands the ticket in ON_ERROR instead of
            // leaving it stuck IN_ANALYSIS or silently retrying.
            markError(marked, e.getMessage());
            log.warn("Extraction failed for ticket {} — marked ON_ERROR: {}",
                    id, e.getMessage());
            return false;
        }
    }

    /**
     * Move the ticket to {@link Status#ON_ERROR} and persist the
     * truncated error message. Runs in its own short transaction
     * (segment 3) so the refresh-then-write pair is atomic even
     * though the surrounding {@link #processTicket} call no longer
     * carries a class-level transaction. No-op if the ticket vanished
     * between the failure and this call (concurrent delete) — silently
     * acceptable because the only effect would have been a log line.
     *
     * <p>The lookup is owner-scoped via {@code ticket.ownerId()} — the
     * scheduler operates as the ticket's owner (no separate user
     * session for the cron tick), so passing the owner id straight
     * from the entity matches the SQL filter without needing a
     * system-scope path.
     */
    private void markError(Ticket ticket, String message) {
        tx.executeWithoutResult(status ->
                ticketRepository.findById(ticket.id(), ticket.ownerId()).ifPresent(t ->
                        ticketRepository.save(t.markError(truncate(message)))));
    }

    /** Trim a message to the column budget so a giant raw-reply cannot bloat the row. */
    private static String truncate(String message) {
        if (message == null) return null;
        if (message.length() <= ERROR_MESSAGE_MAX_CHARS) return message;
        return message.substring(0, ERROR_MESSAGE_MAX_CHARS) + "...[truncated]";
    }
}
