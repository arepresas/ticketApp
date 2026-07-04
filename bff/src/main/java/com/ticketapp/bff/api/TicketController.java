package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthenticatedUser;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import jakarta.servlet.http.HttpServletRequest;
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

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST surface for tickets.
 *
 * <p>Create ({@code POST /api/tickets}) accepts a multipart payload with a
 * single {@code file} part plus optional {@code title}/{@code description}
 * form fields. The file bytes are stored verbatim in the {@code tickets}
 * table (see migration {@code V3__add_ticket_file.sql}); no filesystem
 * staging is involved.
 *
 * <p>Auth: every endpoint requires a Bearer session JWT, AND every
 * read/write is scoped by the authenticated user's id. Cross-tenant
 * reads return 404 (not 403) so existence itself is not leaked across
 * users. {@link com.ticketapp.bff.auth.SessionFilter} attaches the
 * resolved {@link AuthenticatedUser} to the request; this controller
 * reads it off the attribute and refuses requests without a session
 * (401). The repository enforces the same scope at the SQL layer as
 * defense in depth.
 */
@RestController
@RequestMapping("/api/tickets")
@Slf4j
@RequiredArgsConstructor
public class TicketController {

    /** 10 MB hard cap, mirrored in front/src/lib/new/validation.ts. */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_MIME = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/heic",
            "image/heif"
    );

    private final TicketRepository repository;

    @GetMapping
    public List<TicketResponse> list(HttpServletRequest req) {
        AuthenticatedUser user = requireUser(req);
        // No owner-scoped "list all" exists on the port — the
        // controller uses the status-filter overload with the user's
        // own id to cover OPEN + IN_PROGRESS + ON_ERROR + DONE +
        // CANCELLED in one pass. A wider scan with no status filter
        // would be cheaper if the dashboard ever needs it; until
        // then this single query covers everything.
        Set<Ticket.Status> all = Set.of(Ticket.Status.values());
        return repository.findByStatusIn(all, user.id()).stream()
                .map(TicketResponse::of)
                .toList();
    }

    /**
     * Return the caller's tickets that haven't reached a terminal
     * state. Used by the dashboard's "Pending tickets" view — backed
     * by a single SQL query so the wire response stays cheap
     * regardless of the total ticket count.
     *
     * <p>Terminal statuses excluded:
     * <ul>
     *   <li>{@code DONE}: extracted successfully.</li>
     *   <li>{@code CANCELLED}: dismissed by the user.</li>
     *   <li>{@code ON_ERROR}: the AI provider failed and the ticket
     *       is not retrying automatically. Surfacing it under
     *       "pending" would be misleading — the user has to act
     *       (retry via PATCH or cancel) before it moves again. The
     *       full {@code GET /api/tickets} still includes ON_ERROR
     *       rows so the dashboard's failed list view can show them
     *       with their {@code errorMessage}.</li>
     * </ul>
     *
     * <p>Auth: requires a valid session. The query is owner-scoped
     * so the response only contains the caller's own pending
     * tickets.
     */
    @GetMapping("/pending")
    public List<TicketResponse> pending(HttpServletRequest req) {
        AuthenticatedUser user = requireUser(req);
        Set<Ticket.Status> pending = Set.of(Ticket.Status.OPEN, Ticket.Status.IN_PROGRESS);
        return repository.findByStatusIn(pending, user.id()).stream()
                .map(TicketResponse::of)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> get(HttpServletRequest req, @PathVariable UUID id) {
        AuthenticatedUser user = requireUser(req);
        // Owner-scoped: returns 404 when the ticket doesn't exist OR
        // belongs to a different user. Same response shape for both
        // cases — never leak existence to another tenant.
        return repository.findById(id, user.id())
                .map(t -> ResponseEntity.ok(TicketResponse.of(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketResponse> create(
            HttpServletRequest req,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String titleOverride,
            @RequestParam(value = "description", required = false) String description) {

        AuthenticatedUser user = requireUser(req);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file too large (max 10 MB)");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_MIME.contains(contentType.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported file type: " + contentType);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read uploaded bytes for user {}", user.id(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read upload");
        }

        // Default title = the original filename so the dashboard list shows
        // what was uploaded without an extra form field. Callers can
        // override via the `title` field (e.g. a future "rename" flow).
        String title = (titleOverride == null || titleOverride.isBlank())
                ? file.getOriginalFilename()
                : titleOverride;
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title could not be derived");
        }
        String desc = description == null ? "" : description;

        // Ownership: the ticket's owner is the authenticated user.
        // The session id flows from the JWT through SessionFilter and
        // is captured here so it reaches the persistence layer.
        Ticket created = repository.save(Ticket.open(
                user.id(), title, desc, contentType, file.getOriginalFilename(), bytes));
        log.info("Created ticket {} for user {} (file={} {} bytes)",
                created.id(), user.id(), file.getOriginalFilename(), bytes.length);
        return ResponseEntity.status(201).body(TicketResponse.of(created));
    }

    /**
     * Manual status change. Used for two flows:
     * <ul>
     *   <li>Retry: PATCH a ticket in {@code ON_ERROR} back to
     *       {@code OPEN}. {@link Ticket#withStatus(Status)} clears
     *       the stored {@code errorMessage} on the way through, so
     *       the next scheduler tick re-extracts from a clean slate.</li>
     *   <li>Cancel: any pending ticket → {@code CANCELLED}.</li>
     * </ul>
     *
     * <p>Transitioning to {@code ON_ERROR} via this endpoint is
     * supported (mirrors what the orchestrator does on failure) but
     * the dashboard does not expose a button for it; the only normal
     * caller is the scheduler.
     *
     * <p>Owner-scoped: returns 404 if the ticket belongs to a
     * different user.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<TicketResponse> changeStatus(HttpServletRequest req,
                                                       @PathVariable UUID id,
                                                       @RequestBody ChangeStatusRequest changeReq) {
        AuthenticatedUser user = requireUser(req);
        return repository.findById(id, user.id())
                .map(t -> ResponseEntity.ok(
                        TicketResponse.of(repository.save(t.withStatus(changeReq.status())))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest req, @PathVariable UUID id) {
        AuthenticatedUser user = requireUser(req);
        // Owner-scoped delete: returns false (→ 404) when the ticket
        // doesn't exist or belongs to another user. Same response for
        // both cases — never leak existence.
        boolean removed = repository.deleteById(id, user.id());
        return removed
                ? ResponseEntity.noContent().<Void>build()
                : ResponseEntity.notFound().<Void>build();
    }

    private static AuthenticatedUser requireUser(HttpServletRequest req) {
        AuthenticatedUser user = (AuthenticatedUser) req.getAttribute("auth.user");
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return user;
    }

    public record ChangeStatusRequest(Ticket.Status status) {}

    /**
     * Wire response. Excludes {@code fileData} so the bytes don't round-trip
     * on every list call — clients fetch the file separately when needed.
     * {@code sizeBytes} is included so the UI can show the upload size.
     * {@code errorMessage} is included so the dashboard can show the
     * failure reason next to tickets in {@code ON_ERROR} status.
     * {@code ownerId} is included so the UI can render owner-aware
     * affordances and so the wire response is round-trippable to the
     * domain type when needed (tests, audit logs).
     */
    public record TicketResponse(
            UUID id,
            UUID ownerId,
            String title,
            String description,
            Ticket.Status status,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            String contentType,
            String fileName,
            Integer sizeBytes,
            String errorMessage) {

        static TicketResponse of(Ticket t) {
            Integer size = t.fileData() == null ? null : t.fileData().length;
            return new TicketResponse(
                    t.id(), t.ownerId(), t.title(), t.description(), t.status(),
                    t.createdAt(), t.updatedAt(),
                    t.contentType(), t.fileName(), size,
                    t.errorMessage());
        }
    }
}
