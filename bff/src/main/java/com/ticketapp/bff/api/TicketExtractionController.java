package com.ticketapp.bff.api;

import com.ticketapp.bff.api.dto.ExtractionResponse;
import com.ticketapp.bff.api.dto.ProductLineDto;
import com.ticketapp.bff.api.dto.UpdateExtractionRequest;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.exceptions.ResourceNotFoundException;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.identity.AuthenticatedUser;
import com.ticketapp.bff.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;


/**
 * REST surface for the AI-extracted structured payload of a ticket.
 *
 * <p>Split from {@code TicketController}: extraction reads/edits are
 * their own resource with their own validation rules and audit
 * preservation, and the combined controller had grown past what one
 * class should own.
 */
@RestController
@RequestMapping("/api/tickets")
@Slf4j
@RequiredArgsConstructor
public class TicketExtractionController {

    private final TicketRepository repository;
    private final TicketExtractionRepository extractions;

    /**
     * Return the AI-extracted structured payload for one ticket.
     * Owner-scoped via the ticket lookup, then a join through
     * {@code ticket_extractions} by ticket id — the FK enforces
     * "extraction belongs to a ticket that exists". 404 when the
     * ticket doesn't exist, is owned by someone else, or has no
     * extraction row yet (still pending AI processing, or marked
     * ON_ERROR).
     */
    @GetMapping("/{id}/extraction")
    public ResponseEntity<ExtractionResponse> extraction(@PathVariable long id) {
        AuthenticatedUser user = CurrentUser.get();
        // First gate on the ticket itself — refuses cross-tenant
        // access without leaking existence (returns 404 either way).
        java.util.Optional<Ticket> ticket = repository.findById(id, user.id());
        if (ticket.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return extractions.findByTicketId(id, user.id())
                .map(e -> ResponseEntity.ok(ExtractionResponse.of(e)))
                // 404 with no body — same shape as "ticket not
                // found" so the front end doesn't have to distinguish
                // "wrong id" from "not yet extracted" on the wire.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * User-driven edit of the AI-extracted fields (merchant,
     * purchaseDate, category, products, totalAmount, currency). The
     * detail screen sends the full editable payload — the BFF
     * preserves the AI-only fields ({@code model}, {@code extractedAt},
     * {@code rawResponse}, {@code extractionPayload}) server-side so
     * "extracted by X on Y" stays truthful. Full replacement
     * (PUT, not PATCH) because the fields are deeply interleaved;
     * partial PATCH would need a merge strategy the AI pipeline
     * doesn't have to reason about.
     *
     * <p>Rejects editing when no extraction row exists yet
     * (extraction not run, or status {@code ON_ERROR}); the
     * repository {@code replace} throws on zero rows updated,
     * which we translate to 404 so the dashboard's edit affordance
     * stays honest about its preconditions.
     *
     * <p>Owner-scoped: same 404 rule as the read paths.
     */
    @PutMapping("/{id}/extraction")
    public ResponseEntity<ExtractionResponse> replaceExtraction(@PathVariable long id,
                                                                @RequestBody UpdateExtractionRequest body) {
        // No null-body check: @RequestBody is required by default,
        // so Spring rejects a missing body before this runs.
        String currency = validateScalars(body);
        List<ProductLineDto> productDtos = validateProducts(body.products());

        AuthenticatedUser user = CurrentUser.get();
        repository.findById(id, user.id())
                // First gate on the ticket itself — refuses cross-tenant
                // access without leaking existence (returns 404 either way).
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Refuse silently when no extraction exists yet — let the
        // AI finish first, then edit. The detail screen is
        // already aligned: it disables the edit affordance while
        // extraction is null.
        TicketExtraction current = extractions.findByTicketId(id, user.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<TicketExtraction.ProductLine> domainProducts = productDtos.stream()
                .map(p -> new TicketExtraction.ProductLine(
                        p.name(), p.quantity(), p.unit(),
                        zeroIfNull(p.pricePerUnit()),
                        zeroIfNull(p.lineTotal())))
                .toList();
        TicketExtraction updated = new TicketExtraction(
                current.ticketId(),
                body.merchant(),
                body.purchaseDate(),
                body.category(),
                domainProducts,
                body.totalAmount(),
                currency,
                current.model(),
                current.extractedAt(),
                current.rawResponse(),
                current.extractionPayload());
        try {
            extractions.replace(updated);
        } catch (ResourceNotFoundException e) {
            // Race: row vanished between findByTicketId and replace
            // (a concurrent delete). Surface as 404 — the row is
            // gone from the operator's POV either way.
            log.warn("replaceExtraction raced with delete for ticket {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(ExtractionResponse.of(updated));
    }

    /**
     * Validates the scalar (non-line) fields of the edit payload and
     * returns the normalised currency. Split out so the endpoint reads
     * as validate → load → replace instead of a wall of guards.
     */
    private static String validateScalars(UpdateExtractionRequest body) {
        if (body.merchant() == null || body.merchant().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchant must not be blank");
        }
        if (body.purchaseDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "purchaseDate is required");
        }
        if (body.totalAmount() == null || body.totalAmount().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "totalAmount must be >= 0");
        }
        if (body.currency() == null || !body.currency().matches("[A-Za-z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "currency must be an ISO 4217 code (3 letters)");
        }
        // ISO codes are uppercase by convention (the AI pipeline
        // emits them that way); normalise so "eur" and "EUR"
        // collapse to one value instead of two catalogue variants.
        return body.currency().toUpperCase(Locale.ROOT);
    }

    /**
     * Validates the product lines and normalises a null list to empty.
     * Prices are deliberately not checked here — see
     * {@link #zeroIfNull(BigDecimal)} for why a null price is
     * accepted rather than rejected.
     */
    private static List<ProductLineDto> validateProducts(List<ProductLineDto> products) {
        List<ProductLineDto> productDtos = products == null ? List.of() : products;
        for (int i = 0; i < productDtos.size(); i++) {
            ProductLineDto p = productDtos.get(i);
            if (p == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "products[" + i + "] must not be null");
            }
            if (p.name() == null || p.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "products[" + i + "].name must not be blank");
            }
            if (p.quantity() == null || p.quantity().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "products[" + i + "].quantity must be > 0");
            }
        }
        return productDtos;
    }

    /**
     * Null prices coerce to ZERO (not rejected): the detail screen
     * models partially-typed rows as null and renders them as 0.00
     * (see front's computedLineTotal), so persisting 0 mirrors what
     * the user saw. A missing price is genuinely "free/unknown",
     * never a negative discount — discounts arrive as explicit
     * negatives.
     */
    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
