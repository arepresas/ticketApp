package com.ticketapp.bff.api;

import com.ticketapp.bff.api.dto.ChangeStatusRequest;
import com.ticketapp.bff.api.dto.TicketResponse;
import com.ticketapp.bff.api.dto.UpdateTicketRequest;
import com.ticketapp.bff.application.TicketApplicationService;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.identity.AuthenticatedUser;
import com.ticketapp.bff.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST surface for tickets: lists, single reads, upload, metadata
 * edits, status flips and file streaming.
 *
 * <p>Multi-step writes (upload + OCR, status change + AI fallback +
 * normalisation) live in {@link TicketApplicationService}; this
 * class validates HTTP input, delegates and maps. The extraction
 * payload ({@code /{id}/extraction}) and the normalised catalogue
 * ({@code /{id}/catalogue}) live in
 * {@link TicketExtractionController} and
 * {@link TicketCatalogueController}.
 *
 * <p>Auth: every endpoint requires a Bearer session JWT, AND every
 * read/write is scoped by the authenticated user's id. Cross-tenant
 * reads return 404 (not 403) so existence itself is not leaked across
 * users. The principal is read from
 * {@link org.springframework.security.core.context.SecurityContextHolder}
 * via {@link CurrentUser}. The repository enforces the same
 * scope at the SQL layer as defense in depth.
 */
@RestController
@RequestMapping("/api/tickets")
@Slf4j
@RequiredArgsConstructor
public class TicketController {

    private final TicketRepository repository;
    private final TicketApplicationService applicationService;

    @GetMapping
    public List<TicketResponse> list() {
        AuthenticatedUser user = CurrentUser.get();
        // Lightweight projection: the dashboard list never needs the
        // receipt blobs (see findSummariesByStatusIn). Single-ticket
        // paths below keep returning the full ticket.
        Set<Ticket.Status> all = Set.of(Ticket.Status.values());
        return repository.findSummariesByStatusIn(all, user.id()).stream()
                .map(TicketResponse::of)
                .toList();
    }

    /**
     * Return the caller's tickets that still need attention from
     * either the scheduler or the user. Used by the dashboard's
     * "Pending tickets" view — backed by a single SQL query so the
     * wire response stays cheap regardless of the total ticket
     * count.
     *
     * <p>Statuses returned:
     * <ul>
     *   <li>{@code OPEN}: queued for the scheduler.</li>
     *   <li>{@code IN_PROGRESS}: the AI is working on it.</li>
     *   <li>{@code ON_ERROR}: the AI provider failed and the
     *       scheduler won't auto-retry. The ticket sits here until
     *       the user retries (PATCH → OPEN) or cancels it. Surfacing
     *       it under "pending" is deliberate: it's not done, and the
     *       operator's attention is what moves it forward.</li>
     * </ul>
     *
     * <p>Terminal statuses excluded:
     * <ul>
     *   <li>{@code DONE}: extracted successfully.</li>
     *   <li>{@code CANCELLED}: dismissed by the user.</li>
     * </ul>
     *
     * <p>Auth: requires a valid session. The query is owner-scoped
     * so the response only contains the caller's own pending
     * tickets.
     */
    @GetMapping("/pending")
    public List<TicketResponse> pending() {
        AuthenticatedUser user = CurrentUser.get();
        // "Pending" means "not yet terminal": anything that needs
        // attention from either the AI pipeline or the user. IN_ANALYSIS
        // is included so the caller sees a ticket the scheduler is
        // currently feeding to the provider — useful for "is this
        // stuck?" triage while a long extract is in flight.
        Set<Ticket.Status> pending = Set.of(
                Ticket.Status.OPEN,
                Ticket.Status.IN_ANALYSIS,
                Ticket.Status.IN_PROGRESS,
                Ticket.Status.ON_ERROR);
        return repository.findSummariesByStatusIn(pending, user.id()).stream()
                .map(TicketResponse::of)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> get(@PathVariable UUID id) {
        AuthenticatedUser user = CurrentUser.get();
        // Owner-scoped: returns 404 when the ticket doesn't exist OR
        // belongs to a different user. Same response shape for both
        // cases — never leak existence to another tenant.
        return repository.findById(id, user.id())
                .map(t -> ResponseEntity.ok(TicketResponse.of(t)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketResponse> create(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String titleOverride,
            @RequestParam(value = "description", required = false) String description) {
        AuthenticatedUser user = CurrentUser.get();
        Ticket created = applicationService.createTicket(user, file, titleOverride, description);
        return ResponseEntity.status(201).body(TicketResponse.of(created));
    }

    /**
     * Manual status change (retry → OPEN, cancel → CANCELLED,
     * validate → DONE with AI fallback + normalisation). See
     * {@link TicketApplicationService#changeStatus} for the flow
     * details. Owner-scoped: returns 404 if the ticket belongs to a
     * different user.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<TicketResponse> changeStatus(@PathVariable UUID id,
                                                       @RequestBody ChangeStatusRequest changeReq) {
        AuthenticatedUser user = CurrentUser.get();
        Ticket updated = applicationService.changeStatus(id, user, changeReq.status());
        return ResponseEntity.ok(TicketResponse.of(updated));
    }

    /**
     * User-driven metadata edit (detail screen's "Save" button on
     * the title / description fields). Both fields are optional in
     * the payload — only the fields the caller actually sends get
     * updated. {@link Ticket#withTitle(String)} rejects blank
     * input; {@link Ticket#withDescription(String)} normalises
     * {@code null} → {@code ""} to keep the wire shape canonical.
     *
     * <p>Validation is done manually here (no Bean Validation on
     * the controller, matching the rest of this class) and
     * surfaces as 400 via {@link ResponseStatusException}.
     *
     * <p>Owner-scoped: same 404 rule as the read paths.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<TicketResponse> update(@PathVariable UUID id,
                                                 @RequestBody UpdateTicketRequest body) {
        // No null-body check: @RequestBody is required by default,
        // so Spring rejects a missing body before this runs.
        if (body.title() != null && body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
        }
        AuthenticatedUser user = CurrentUser.get();
        return repository.findById(id, user.id())
                .map(t -> {
                    Ticket next = t;
                    if (body.title() != null) {
                        next = next.withTitle(body.title());
                    }
                    if (body.description() != null) {
                        next = next.withDescription(body.description());
                    }
                    return ResponseEntity.ok(TicketResponse.of(repository.save(next)));
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AuthenticatedUser user = CurrentUser.get();
        // Soft delete: flips the row to DELETED (history stays for
        // audit) and every read path treats it as missing from then
        // on. Returns false (→ 404) when the ticket doesn't exist,
        // belongs to another user, or is already deleted — same
        // response for all cases, never leak existence.
        boolean removed = repository.deleteById(id, user.id());
        return removed
                ? ResponseEntity.noContent().<Void>build()
                : ResponseEntity.notFound().<Void>build();
    }

    /**
     * Stream the raw uploaded bytes for the in-browser preview.
     * Owner-scoped via the ticket lookup — same 404 rule as the rest
     * of the read paths. {@code Content-Type} is taken from the
     * ticket's {@code contentType} column (set at upload time from
     * the browser's MIME) so the browser knows how to render
     * images vs PDFs.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        AuthenticatedUser user = CurrentUser.get();
        java.util.Optional<Ticket> ticketOpt = repository.findById(id, user.id());
        if (ticketOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Ticket ticket = ticketOpt.get();
        byte[] bytes = ticket.fileData();
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Prefer the ticket's stored content type; fall back to
        // application/octet-stream so browsers always treat the
        // response as a download. MediaType.parseMediaType throws
        // on a malformed value (defensive — every column value
        // went through the validator on upload), so we wrap.
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (ticket.contentType() != null) {
            try {
                mediaType = MediaType.parseMediaType(ticket.contentType());
            } catch (org.springframework.util.InvalidMimeTypeException e) {
                log.warn("Ticket {} has malformed contentType '{}', falling back to octet-stream",
                        ticket.id(), ticket.contentType());
            }
        }
        // Force Content-Disposition: inline so the browser renders
        // the file (img/iframe) instead of triggering a download.
        // The file's own filename is forwarded for the Save As…
        // dialog when the user does choose to download.
        String filename = ticket.fileName() == null ? "ticket-" + ticket.id() : ticket.fileName();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + filename.replace("\"", "") + "\"");
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }
}
