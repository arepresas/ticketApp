package com.ticketapp.bff.application;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketExtraction.EditableFields;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.exceptions.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a detail-screen edit as one unit.
 *
 * <p>The detail screen has always saved in two requests — a metadata
 * {@code PATCH} then an extraction {@code PUT}. That is not atomic: the
 * metadata commits, the extraction can then fail, and the server is left
 * holding half of what the user typed while the UI reports a failure and
 * keeps the rest in memory. It became reachable as a silent data-loss
 * path once the status actions started flushing automatically (see
 * {@code TicketDetailApp.setStatus}), so this is the fix rather than a
 * refactor: one endpoint, one transaction.
 *
 * <p>Transactional boundary lives here rather than in
 * {@code TicketController} per {@code .rules/database.md}; the controller
 * only maps HTTP onto this method and lets the exception propagate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketEditService {

    private final TicketRepository tickets;
    private final TicketExtractionRepository extractions;

    /**
     * Updates the ticket's metadata and, when {@code extraction} is
     * supplied, its extraction — atomically.
     *
     * <p>Both parts are sparse: {@code null} metadata fields are left
     * alone, and a {@code null} extraction means the caller has nothing
     * to write (a ticket the AI has not read yet), not "clear it".
     *
     * <p>Ordering matters and is deliberate: metadata is written first so
     * that a later extraction failure has something to roll back. An
     * integration test forces that failure and asserts both tables stay
     * unchanged — see {@code TicketEditRollbackIT} (and the 409 path
     * in {@code TicketEditConflictIT}).
     *
     * <p>Concurrency: the ticket write is version-guarded
     * ({@link com.ticketapp.domain.TicketRepository#save} throws
     * {@link com.ticketapp.domain.exceptions.OptimisticLockException}
     * when another writer committed first, which the BFF maps to 409
     * rather than overwriting the winner), and the extraction write is
     * owner-scoped inside the statement itself.
     *
     * @return the ticket as it now stands, for the caller's response
     * @throws ResourceNotFoundException when the ticket does not exist,
     *                                   belongs to another owner, or has
     *                                   no extraction row to update. The
     *                                   BFF translates it to 404.
     */
    @Transactional
    public Ticket applyEdit(long id, long ownerId, String title, String description, EditableFields extraction) {
        Ticket ticket = tickets.findById(id, ownerId).orElseThrow(() -> new ResourceNotFoundException("ticket", id));

        Ticket next = ticket;
        if (title != null) {
            next = next.withTitle(title);
        }
        if (description != null) {
            next = next.withDescription(description);
        }
        if (next != ticket) {
            ticket = tickets.save(next);
        }

        if (extraction != null) {
            // Column-scoped write: the audit columns are never read
            // into memory to be written back unchanged, and the owner
            // predicate travels with the UPDATE. Zero rows means "no
            // extraction row for this ticket" (the AI has not run
            // yet) — the detail screen disables the extraction half
            // in that case, and turning a missing row into one here
            // would mask it.
            boolean updated = extractions.updateEditableFields(id, ownerId, extraction);
            if (!updated) {
                throw new ResourceNotFoundException("ticket extraction", id);
            }
        }

        log.debug("Applied detail edit to ticket {} (extraction={})", id, extraction != null);
        return ticket;
    }
}
