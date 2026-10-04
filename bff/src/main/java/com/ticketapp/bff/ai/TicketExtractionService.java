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
import com.ticketapp.domain.exceptions.OptimisticLockException;
import com.ticketapp.persistence.JdbcTicketRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;


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
public class TicketExtractionService {

    /**
     * Recover a segment-1 IN_ANALYSIS orphaned by a lost segment-2
     * race. If the winner moved the ticket on, their state stands
     * and there is nothing to do. If it is still IN_ANALYSIS (a
     * crashed or concurrent run that will never come back for it,
     * and the cron only selects OPEN), land it on ON_ERROR with a
     * visible message so the user can re-queue via PATCH to OPEN
     * instead of staring at a stuck badge.
     *
     * <p>A concurrent run still in flight loses its own segment-2
     * race against this mark and skips — the ticket converges to a
     * visible ON_ERROR rather than a silent stuck row.
     */
    private void recoverStaleAnalysis(long id, Ticket marked) {
        try {
            tx.executeWithoutResult(status ->
                    ticketRepository.findById(id, marked.ownerId()).ifPresent(current -> {
                        if (current.status() == Status.IN_ANALYSIS) {
                            ticketRepository.save(current.markError(truncate(
                                    "lost optimistic race during extraction;"
                                            + " re-queue via PATCH to OPEN")));
                        } else {
                            log.info("Ticket {} changed during extraction; winner state {} stands",
                                    id, current.status());
                        }
                    }));
        } catch (OptimisticLockException e) {
            log.debug("Ticket {} changed while recovering; skipping", id);
        }
    }

    /**
     * Call the provider port with retries for transient failures.
     *
     * <p>Total attempts = {@code max(1, retryAttempts)}: {@code 1} is
     * the initial call, the rest are retries. Retriable = no HTTP
     * response (status 0: network, timeout, parse) plus 429 and 5xx.
     * Other 4xx (bad key, bad request) fail fast — retrying them
     * burns budget for nothing. No sleep between attempts: the
     * scheduler's cron is the backoff, and {@code Thread.sleep} is
     * banned outside tests.
     */
    private ReceiptExtraction extractWithRetry(Ticket ticket) throws ReceiptExtractionException {
        int maxAttempts = Math.max(1, properties.retryAttempts());
        ReceiptExtractionRequest request = new ReceiptExtractionRequest(
                ticket.fileData(), ticket.contentType());
        ReceiptExtractionException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return receiptExtractor.extract(request);
            } catch (ReceiptExtractionException e) {
                lastFailure = e;
                if (!e.retryable() || attempt == maxAttempts) {
                    throw e;
                }
                log.warn("Extraction attempt {}/{} failed for ticket {} (retryable, status={}): {}",
                        attempt, maxAttempts, ticket.id(), e.statusCode(), e.safeMessage());
            }
        }
        throw lastFailure;
    }

    /**
     * Cap on the persisted error message. The provider exception text
     * can include the full raw model reply (4096+ chars in the worst
     * case — see the {@code <think>} stripping incident from
     * 2026-07-05) which would otherwise bloat the {@code tickets}
     * row. 2000 chars is enough to keep the actionable headline
     * ("the AI provider returned 500: ...", "the reply contained only
     * thinking...") and stays well under the operator-scannable
     * threshold for the dashboard.
     */
    static final int ERROR_MESSAGE_MAX_CHARS = 2000;

    private final TicketRepository ticketRepository;
    private final TicketExtractionRepository ticketExtractionRepository;
    private final JdbcTicketRepository jdbcTicketRepository;
    private final ReceiptExtractor receiptExtractor;
    private final TransactionTemplate tx;
    private final AiProperties properties;
    private final MeterRegistry meters;

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Counter skippedCounter;

    public TicketExtractionService(TicketRepository ticketRepository,
                                   TicketExtractionRepository ticketExtractionRepository,
                                   JdbcTicketRepository jdbcTicketRepository,
                                   ReceiptExtractor receiptExtractor,
                                   TransactionTemplate tx,
                                   AiProperties properties,
                                   MeterRegistry meters) {
        this.ticketRepository = ticketRepository;
        this.ticketExtractionRepository = ticketExtractionRepository;
        this.jdbcTicketRepository = jdbcTicketRepository;
        this.receiptExtractor = receiptExtractor;
        this.tx = tx;
        this.properties = properties;
        this.meters = meters;
        // Pre-built: the outcome tag values are stable, so register
        // once instead of rebuilding the counter per ticket.
        this.successCounter = outcomeCounter("success");
        this.failureCounter = outcomeCounter("failure");
        this.skippedCounter = outcomeCounter("skipped");
    }

    // Outcome semantics for the counter below: success = extraction
    // row persisted; failure = this run marked ON_ERROR; skipped =
    // every other false return (already extracted, lost race). The
    // recovery path counts as skipped even when it marks ON_ERROR —
    // the mark belongs to a race, not to a provider verdict.
    private Counter outcomeCounter(String result) {
        return Counter.builder("ticket.extraction.outcome")
                .tag("result", result)
                .description("Tickets processed by the AI extraction pipeline")
                .register(meters);
    }

    /**
     * Process one ticket end-to-end. Returns {@code true} when an
     * extraction row was persisted, {@code false} when the ticket was
     * skipped (already extracted) or failed (marked ON_ERROR with the
     * failure reason attached).
     */
    public boolean processTicket(Ticket ticket) {
        long id = ticket.id();

        if (ticketExtractionRepository.findByTicketId(id, ticket.ownerId()).isPresent()) {
            log.warn("Ticket {} already has an extraction; skipping", id);
            skippedCounter.increment();
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
        Ticket marked;
        try {
            marked = tx.execute(status -> {
                jdbcTicketRepository.recordAttempt(id);
                Ticket t = ticket.incrementAttempts().withStatus(Status.IN_ANALYSIS);
                // Chain the save result: it carries the bumped
                // version the next segment must write against. The
                // pre-save copy is stale from here on.
                return ticketRepository.save(t);
            });
        } catch (OptimisticLockException e) {
            // Another writer (user PATCH, concurrent tick) won the
            // race between our read and this write. Their write
            // stands — do not clobber it with our state.
            log.info("Ticket {} changed concurrently; skipping extraction", id);
            skippedCounter.increment();
            return false;
        }

        try {
            // No transaction active here — this is the long external
            // HTTP round-trip the segmentation exists to keep out of
            // the connection pool's way. Retried up to
            // `ticketapp.ai.retry-attempts` total attempts for transient
            // failures (network, 429, 5xx); client errors fail fast.
            // Timed for capacity planning (the provider call dominates
            // the tick); the counter below splits success/failure.
            Timer.Sample sample = Timer.start(meters);
            final ReceiptExtraction extraction;
            try {
                extraction = extractWithRetry(ticket);
            } finally {
                sample.stop(meters.timer("ticket.extraction.duration"));
            }
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
            try {
                tx.executeWithoutResult(status -> {
                    ticketExtractionRepository.save(persisted);
                    ticketRepository.save(marked.withStatus(Status.IN_PROGRESS));
                });
            } catch (OptimisticLockException e) {
                // The row moved while the provider call was in
                // flight (user action). The segment never committed,
                // so nothing was persisted — recover instead of
                // leaving the segment-1 IN_ANALYSIS behind.
                skippedCounter.increment();
                recoverStaleAnalysis(id, marked);
                return false;
            }
            log.info("Extracted ticket {} → merchant='{}' total={} {}",
                    id, persisted.merchant(),
                    persisted.totalAmount(), persisted.currency());
            successCounter.increment();
            return true;
        } catch (ReceiptExtractionException e) {
            // Only safeMessage() is persisted: the short,
            // provider-neutral category the adapter vouched for. The
            // raw diagnostic (which may carry the provider's response
            // body, endpoint or request data) is intentionally not
            // logged at WARN either — it stays in DEBUG so secret-bearing
            // provider text never lands in production logs. Status +
            // safe message is enough for the operator dashboard.
            String persisted = "status=" + e.statusCode() + " " + e.safeMessage();
            markError(marked, persisted);
            log.warn("Extraction failed for ticket {} — marked ON_ERROR: {}",
                    id, persisted);
            if (log.isDebugEnabled()) {
                log.debug("Extraction diagnostic for ticket {} (status={}): {}",
                        id, e.statusCode(), e.getMessage());
            }
            failureCounter.increment();
            return false;
        } catch (DataAccessException | IllegalArgumentException | IllegalStateException e) {
            // The provider honours its port contract now, so what is
            // left is ours and worth naming: a constraint violation or
            // a dead connection, or a domain invariant rejecting what
            // the provider sent. All three mean "this ticket could not
            // be written", which is exactly what ON_ERROR records.
            // Type-only — never the message — so a bound value from a
            // malformed query never lands in the row or the log. An
            // unnamed broad handler would hide the type again, and
            // CONVENTIONS §7 forbids one.
            // Class name + truncated message: surfaces the failure
            // category without trusting the provider-supplied text.
            // For DAO exceptions the message is operator-FORMED (SQL
            // state, constraint) — bounded by construction because
            // we keep the type-only prefix and truncate the rest.
            markError(marked, e.getClass().getSimpleName() + ": " + truncate(e.getMessage()));
            log.warn("Extraction failed for ticket {} — marked ON_ERROR: {}",
                    id, e.getClass().getSimpleName());
            log.debug("Extraction diagnostic for ticket {}: {}",
                    id, e.getMessage());
            failureCounter.increment();
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
        try {
            tx.executeWithoutResult(status ->
                    ticketRepository.findById(ticket.id(), ticket.ownerId()).ifPresent(t ->
                            ticketRepository.save(t.markError(truncate(message)))));
        } catch (OptimisticLockException e) {
            // Lost a micro-race with another writer after the
            // re-read. Their write stands; the error mark is stale
            // news next to a fresher user action.
            log.debug("Ticket {} changed while marking error; skipping", ticket.id());
        }
    }

    /** Trim a message to the column budget so a giant raw-reply cannot bloat the row. */
    private static String truncate(String message) {
        if (message == null) return null;
        if (message.length() <= ERROR_MESSAGE_MAX_CHARS) return message;
        return message.substring(0, ERROR_MESSAGE_MAX_CHARS) + "...[truncated]";
    }
}
