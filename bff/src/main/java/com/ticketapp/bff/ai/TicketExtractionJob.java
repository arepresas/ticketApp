package com.ticketapp.bff.ai;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.Ticket.Status;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduled bean that drives the AI extraction pipeline (ADR 0006).
 *
 * <p>Tick contract:
 * <ol>
 *   <li>Fetch up to {@code batch-size} OPEN tickets across all
 *       owners (system scope) via
 *       {@link TicketRepository#findOpenForExtraction(int)}.</li>
 *   <li>Exclude any ticket that already has a row in
 *       {@code ticket_extractions} (joined in-process via
 *       {@link com.ticketapp.domain.TicketExtractionRepository#findExtractedTicketIds()}
 *       — single round-trip instead of N point lookups).</li>
 *   <li>For each remaining ticket, delegate to
 *       {@link TicketExtractionService#processTicket(Ticket)}.</li>
 * </ol>
 *
 * <p>Why a system-scope query? The cron runs without a user session
 * — there is no {@code ownerId} to pass. The repository port exposes
 * this path explicitly (system-only — controller paths never call
 * it) and orders oldest-first so the backlog drains FIFO.
 *
 * <p>The job is a no-op when {@code ticketapp.ai.enabled=false} (test
 * profile). The kill switch is read once at startup — flipping it at
 * runtime requires a restart. That matches Spring's configuration
 * model and avoids surprising mid-flight reconfiguration.
 *
 * <p>Errors are isolated to the per-ticket transaction in the
 * service: one bad receipt never aborts the rest of the batch.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TicketExtractionJob {

    private final TicketRepository tickets;
    private final TicketExtractionService service;
    private final TicketExtractionRepository extractions;
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

        long started = System.currentTimeMillis();
        Set<UUID> extractedIds = new java.util.HashSet<>(extractions.findExtractedTicketIds());
        List<Ticket> candidates = tickets.findOpenForExtraction(properties.batchSize()).stream()
                // Defence in depth: the SQL filter already restricts
                // to OPEN, but if a future migration broadens the
                // query we don't want to silently start extracting
                // DONE/ON_ERROR tickets. Cheap status recheck at the
                // edge of the system.
                .filter(t -> Status.OPEN.equals(t.status()))
                .filter(t -> !extractedIds.contains(t.id()))
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
            } catch (Exception e) {
                // processOne handles its own revert; reaching here
                // means something catastrophic (e.g. DB down). Log
                // and bail on the rest of the batch — the next tick
                // will resume.
                log.error("Tick aborted after processing {} tickets: {}",
                        processed, e.getMessage(), e);
                break;
            }
        }
        log.info("Extraction tick finished: processed={} candidates={} elapsedMs={}",
                processed, candidates.size(), System.currentTimeMillis() - started);
    }
}
