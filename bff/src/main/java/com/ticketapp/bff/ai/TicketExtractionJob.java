package com.ticketapp.bff.ai;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.Ticket.Status;
import com.ticketapp.domain.TicketExtractionQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled bean that drives the AI extraction pipeline (ADR 0006).
 *
 * <p>Tick contract:
 * <ol>
 *   <li>Fetch up to {@code batch-size} OPEN tickets without an
 *       extraction row across all owners (system scope) via
 *       {@link TicketExtractionQueue#findOpenForExtraction(int)} — the
 *       exclusion runs as a SQL anti-join, not an in-memory list.</li>
 *   <li>For each remaining ticket, delegate to
 *       {@link TicketExtractionService#processTicket(Ticket)}.</li>
 * </ol>
 *
 * <p>Why a system-scope query? The cron runs without a user session
 * — there is no {@code ownerId} to pass. The queue port exposes
 * this path explicitly (system-only — controller paths depend on
 * the owner-scoped repository instead) and orders oldest-first so
 * the backlog drains FIFO.
 *
 * <p>The job is a no-op when {@code ticketapp.ai.enabled=false} (test
 * profile). The kill switch is read once at startup — flipping it at
 * runtime requires a restart. That matches Spring's configuration
 * model and avoids surprising mid-flight reconfiguration.
 *
 * <p>Errors are isolated per ticket inside the service's short
 * transaction segments: one bad receipt never aborts the rest of the
 * batch.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TicketExtractionJob {

    private final TicketExtractionQueue tickets;
    private final TicketExtractionService service;
    private final AiProperties properties;

    /**
     * Cron-driven tick. {@code fixedDelay} is implicit with a cron
     * expression — Spring waits for the previous tick to complete
     * before scheduling the next one (see
     * {@link Scheduled}). Overlap on a single instance is therefore
     * impossible.
     */
    @Scheduled(cron = "${ticketapp.ai.cron}")
    public void tick() {
        if (!properties.enabled()) {
            log.debug("ticketapp.ai.enabled=false — skipping tick");
            return;
        }

        log.info("Extraction tick started: batchSize={}", properties.batchSize());

        // Recover claims abandoned by a dead worker BEFORE picking
        // candidates, so a ticket re-queued here is eligible for
        // processing in this very tick instead of waiting a full
        // cycle for nothing.
        int requeued = requeueAbandonedClaims();
        if (requeued > 0) {
            log.warn("Re-queued {} ticket(s) abandoned in IN_ANALYSIS for more than {}",
                    requeued, properties.staleAnalysisTimeout());
        }

        long started = System.currentTimeMillis();
        List<Ticket> candidates = tickets.findOpenForExtraction(properties.batchSize()).stream()
                // Defence in depth: the SQL filter already restricts
                // to OPEN without an extraction row, but if a future
                // migration broadens the query we don't want to
                // silently start extracting DONE/ON_ERROR tickets.
                // Cheap status recheck at the edge of the system.
                .filter(t -> Status.OPEN.equals(t.status()))
                .toList();
        if (candidates.isEmpty()) {
            log.debug("No OPEN tickets to extract on this tick");
            return;
        }

        log.info("Found {} candidates for extraction", candidates.size());

        int processed = 0;
        for (Ticket t : candidates) {
            try {
                if (service.processTicket(t)) {
                    processed++;
                }
            } catch (DataAccessException | IllegalStateException e) {
                // processTicket handles its own provider failures and
                // reverts the ticket itself. Reaching here means the
                // database is the problem: log it and bail on the
                // rest of the batch, the next tick resumes.
                log.error("Tick aborted after processing {} tickets: {}",
                        processed, e.getMessage(), e);
                break;
            }
        }
        log.info("Extraction tick finished: processed={} candidates={} elapsedMs={}",
                processed, candidates.size(), System.currentTimeMillis() - started);
    }

    /**
     * Re-queue tickets whose worker died mid-flight. Returns how many
     * were moved back to {@code OPEN}.
     *
     * <p>Capped by {@code batchSize} so a large backlog of stale rows
     * (a database outage, a fleet-wide deploy) can't turn one tick
     * into thousands of writes; the remainder drains on the next
     * tick.
     */
    private int requeueAbandonedClaims() {
        Instant cutoff = Instant.now().minus(properties.staleAnalysisTimeout());
        return tickets.requeueAbandonedAnalysis(cutoff, properties.batchSize()).size();
    }
}
