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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates the AI extraction pipeline for one ticket (ADR 0006
 * + ADR 0007).
 *
 * <p>The class is stateless and transactional per call. The
 * {@link TicketExtractionJob} (the scheduled bean) calls
 * {@link #processTicket(Ticket)} inside a per-ticket transaction so a
 * single failure rolls back only that ticket's state, not the whole
 * batch.
 *
 * <p>Status transitions (ADR 0006, D4):
 * <ul>
 *   <li>Open → InProgress: happens at the start of the call.</li>
 *   <li>InProgress → InProgress (success): extraction row inserted.</li>
 *   <li>InProgress → OnError (failure): ticket is marked terminally
 *       failed with the provider's message attached. The next cron
 *       tick filters on {@code Status.OPEN} only, so a failed ticket
 *       is never retried automatically. A user-initiated PATCH
 *       ({@code /api/tickets/{id}/status} → {@code OPEN}) clears the
 *       error and re-enqueues the ticket.</li>
 * </ul>
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

    /**
     * Process one ticket end-to-end. Returns {@code true} when an
     * extraction row was persisted, {@code false} when the ticket was
     * skipped (already extracted) or failed (marked ON_ERROR with the
     * failure reason attached).
     */
    @Transactional
    public boolean processTicket(Ticket ticket) {
        UUID id = ticket.id();

        if (ticketExtractionRepository.findByTicketId(id).isPresent()) {
            log.debug("Ticket {} already has an extraction; skipping", id);
            return false;
        }

        jdbcTicketExtractionRepository.recordAttempt(id);
        ticketRepository.save(ticket.withStatus(Status.IN_PROGRESS));

        try {
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
            ticketExtractionRepository.save(persisted);
            log.info("Extracted ticket {} → merchant='{}' total={} {}",
                    id, persisted.merchant(),
                    persisted.totalAmount(), persisted.currency());
            return true;
        } catch (ReceiptExtractionException e) {
            String message = "status=" + e.statusCode() + " " + e.getMessage();
            markError(ticket, message);
            log.warn("Extraction failed for ticket {} — marked ON_ERROR: {}",
                    id, message);
            return false;
        } catch (Exception e) {
            // Catch-all so a bug in the orchestrator (NPE, illegal
            // state from domain validation, DB constraint violation
            // on save) still lands the ticket in ON_ERROR instead of
            // leaving it stuck IN_PROGRESS or silently retrying.
            markError(ticket, e.getMessage());
            log.warn("Extraction failed for ticket {} — marked ON_ERROR: {}",
                    id, e.getMessage());
            return false;
        }
    }

    /**
     * Move the ticket to {@link Status#ON_ERROR} and persist the
     * truncated error message. No-op if the ticket vanished between
     * the failure and this call (concurrent delete) — silently
     * acceptable because the only effect would have been a log line.
     *
     * <p>The lookup is owner-scoped via {@code ticket.ownerId()} — the
     * scheduler operates as the ticket's owner (no separate user
     * session for the cron tick), so passing the owner id straight
     * from the entity matches the SQL filter without needing a
     * system-scope path.
     */
    private void markError(Ticket ticket, String message) {
        ticketRepository.findById(ticket.id(), ticket.ownerId()).ifPresent(t ->
                ticketRepository.save(t.markError(truncate(message))));
    }

    /** Trim a message to the column budget so a giant raw-reply cannot bloat the row. */
    private static String truncate(String message) {
        if (message == null) return null;
        if (message.length() <= ERROR_MESSAGE_MAX_CHARS) return message;
        return message.substring(0, ERROR_MESSAGE_MAX_CHARS) + "...[truncated]";
    }
}
