package com.ticketapp.bff.application;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.exceptions.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
     * that a later extraction failure has something to roll back. A test
     * asserts exactly that — metadata unchanged when the extraction is
     * rejected.
     *
     * @return the ticket as it now stands, for the caller's response
     * @throws ResourceNotFoundException when the ticket does not exist,
     *                                   belongs to another owner, or has
     *                                   no extraction row to update. The
     *                                   BFF translates it to 404.
     */
    @Transactional
    public Ticket applyEdit(long id, long ownerId,
                            String title, String description,
                            ExtractionEdit extraction) {
        Ticket ticket = tickets.findById(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("ticket", id));

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
            // Preserves the AI's audit fields from the existing row —
            // same rule as the standalone extraction endpoint.
            TicketExtraction existing = extractions.findByTicketId(id, ownerId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ticket extraction", id));
            extractions.replace(new TicketExtraction(
                    existing.ticketId(),
                    extraction.merchant(),
                    extraction.purchaseDate(),
                    extraction.category(),
                    extraction.products(),
                    extraction.totalAmount(),
                    extraction.currency(),
                    existing.model(),
                    existing.extractedAt(),
                    existing.rawResponse(),
                    existing.extractionPayload()));
        }

        log.debug("Applied detail edit to ticket {} (extraction={})",
                id, extraction != null);
        return ticket;
    }

    /**
     * The editable half of an extraction. A nested record rather than a
     * domain type because it exists only to carry one request's worth of
     * user input into the transaction.
     *
     * @param products line items, already validated by the controller
     */
    public record ExtractionEdit(
            String merchant,
            java.time.LocalDate purchaseDate,
            String category,
            List<com.ticketapp.domain.TicketExtraction.ProductLine> products,
            java.math.BigDecimal totalAmount,
            String currency) {
    }
}