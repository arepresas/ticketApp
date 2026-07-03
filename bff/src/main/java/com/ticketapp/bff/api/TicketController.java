package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthenticatedUser;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * REST surface for tickets.
 *
 * <p>Create ({@code POST /api/tickets}) accepts a multipart payload with a
 * single {@code file} part plus optional {@code title}/{@code description}
 * form fields. The file bytes are stored verbatim in the {@code tickets}
 * table (see migration {@code V3__add_ticket_file.sql}); no filesystem
 * staging is involved.
 *
 * <p>Auth: every mutating endpoint requires a Bearer session JWT.
 * {@link com.ticketapp.bff.auth.SessionFilter} attaches the resolved
 * {@link AuthenticatedUser} to the request; the controller reads it off
 * the attribute and refuses requests without a session (401).
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

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

    public TicketController(TicketRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<TicketResponse> list() {
        return repository.findAll().stream().map(TicketResponse::of).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> get(@PathVariable UUID id) {
        return repository.findById(id)
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

        Ticket created = repository.save(Ticket.open(title, desc, contentType, file.getOriginalFilename(), bytes));
        log.info("Created ticket {} for user {} (file={} {} bytes)",
                created.id(), user.id(), file.getOriginalFilename(), bytes.length);
        return ResponseEntity.status(201).body(TicketResponse.of(created));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<TicketResponse> changeStatus(@PathVariable UUID id,
                                                       @RequestBody ChangeStatusRequest req) {
        return repository.findById(id)
                .map(t -> ResponseEntity.ok(TicketResponse.of(repository.save(t.withStatus(req.status())))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
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
     */
    public record TicketResponse(
            UUID id,
            String title,
            String description,
            Ticket.Status status,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            String contentType,
            String fileName,
            Integer sizeBytes) {

        static TicketResponse of(Ticket t) {
            Integer size = t.fileData() == null ? null : t.fileData().length;
            return new TicketResponse(
                    t.id(), t.title(), t.description(), t.status(),
                    t.createdAt(), t.updatedAt(),
                    t.contentType(), t.fileName(), size);
        }
    }
}