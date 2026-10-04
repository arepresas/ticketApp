package com.ticketapp.bff.api;

import com.ticketapp.bff.api.dto.CatalogueResponse;
import com.ticketapp.domain.TicketCatalogueRepository;
import com.ticketapp.domain.identity.AuthenticatedUser;
import com.ticketapp.bff.security.CurrentUser;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;



/**
 * REST surface for the normalised catalogue view of a ticket.
 *
 * <p>Split from {@code TicketController}: the catalogue read joins
 * four aggregates and deserves its own class instead of sharing one
 * with uploads, status flips and file streaming.
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketCatalogueController {

    private final TicketCatalogueRepository catalogue;

    /**
     * Read the normalised view of a ticket. Populated by
     * {@code TicketExtractionNormaliser} when a ticket transitions
     * to {@code DONE}; falls through with 404 when the ticket either
     * doesn't belong to the caller or hasn't been validated yet.
     *
     * <p>Used by the detail screen once a ticket is in {@code DONE}
     * — the JSONB extraction becomes the AI's provenance record
     * at that point, and the catalogue is the source of truth for
     * what the user actually bought. The detail screen freezes
     * editing when this endpoint is in play (see the frontend's
     * ticket-status-driven branch in {@code TicketDetailApp.svelte}).
     *
     * <p>One port call. The fan-out (shop + lines + product and
     * price master rows) and the owner scope live in the adapter, and
     * the "no catalogue yet" rules are the port's contract rather
     * than three different 404s in this method.
     */
    @GetMapping("/{id}/catalogue")
    public ResponseEntity<CatalogueResponse> catalogue(@PathVariable long id) {
        AuthenticatedUser user = CurrentUser.get();
        return catalogue.findByTicketId(id, user.id())
                .map(c -> ResponseEntity.ok(CatalogueResponse.of(c)))
                // One 404 for all four empty cases the port folds
                // together: no such ticket, not yours, not normalised
                // yet, no catalogue lines.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
