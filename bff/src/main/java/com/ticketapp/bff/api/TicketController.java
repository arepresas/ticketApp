package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthenticatedUser;
import com.ticketapp.bff.security.CurrentUser;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
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
import org.springframework.web.bind.annotation.PutMapping;
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
 * users. The principal is read from
 * {@link org.springframework.security.core.context.SecurityContextHolder}
 * via {@link CurrentUser}; the request never carries an
 * {@code HttpServletRequest} arg now that the custom
 * {@code SessionFilter} is gone. The repository enforces the same
 * scope at the SQL layer as defense in depth.
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

    private final TicketExtractionRepository extractions;

    private final com.ticketapp.domain.ProductRepository products;
    private final com.ticketapp.domain.PriceRepository prices;
    private final com.ticketapp.domain.LineTicketRepository lineTickets;
    private final com.ticketapp.domain.ShopRepository shops;

    private final com.ticketapp.bff.extraction.TicketExtractionNormaliser normaliser;

    private final com.ticketapp.bff.ai.TicketExtractionService extractionService;

    @GetMapping
    public List<TicketResponse> list() {
        AuthenticatedUser user = CurrentUser.get();
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
        Set<Ticket.Status> pending = Set.of(
                Ticket.Status.OPEN, Ticket.Status.IN_PROGRESS, Ticket.Status.ON_ERROR);
        return repository.findByStatusIn(pending, user.id()).stream()
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
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketResponse> create(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String titleOverride,
            @RequestParam(value = "description", required = false) String description) {

        AuthenticatedUser user = CurrentUser.get();

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
        // The session id flows from the JWT through Spring Security's
        // filter chain and is captured here so it reaches the
        // persistence layer.
        Ticket created = repository.save(Ticket.open(
                user.id(), title, desc, contentType, file.getOriginalFilename(), bytes));
        log.info("Created ticket {} for user {} (file={} {} bytes)",
                created.id(), user.id(), file.getOriginalFilename(), bytes.length);
        return ResponseEntity.status(201).body(TicketResponse.of(created));
    }

    /**
     * Manual status change. Used for three flows:
     * <ul>
     *   <li>Retry: PATCH a ticket in {@code ON_ERROR} back to
     *       {@code OPEN}. {@link Ticket#withStatus(Status)} clears
     *       the stored {@code errorMessage} on the way through, so
     *       the next scheduler tick re-extracts from a clean slate.</li>
     *   <li>Cancel: any pending ticket → {@code CANCELLED}.</li>
     *   <li>Validate: any pending ticket → {@code DONE} triggers the
     *       {@link com.ticketapp.bff.extraction.TicketExtractionNormaliser}
     *       which snapshots each line into the products catalogue.
     *       The normalisation runs in the same controller method;
     *       a normaliser failure logs a WARN but does not roll back
     *       the status change (the next mark-as-done is a
     *       no-op idempotent refresh).</li>
     * </ul>
     *
     * <p><b>Mark-as-done triggers extraction when missing.</b> A
     * ticket that reached {@code ON_ERROR} without ever producing an
     * extraction row (e.g. the AI provider timed out before emitting
     * JSON, or the user's previous mark-as-done raced the scheduler
     * and skipped the queue) has nothing for the normaliser to
     * snapshot. Treating "mark as done" as "extract now, then
     * normalise" makes the button do what the user expects: a failed
     * ticket they retry by clicking "Mark as done" either lands in
     * {@code DONE} with a populated catalogue or bounces back to
     * {@code ON_ERROR} with the new failure reason. Without this
     * fallback the ticket silently flips to {@code DONE} with an
     * empty catalogue — the bug this method documents.
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
    public ResponseEntity<TicketResponse> changeStatus(@PathVariable UUID id,
                                                       @RequestBody ChangeStatusRequest changeReq) {
        AuthenticatedUser user = CurrentUser.get();
        return repository.findById(id, user.id())
                .map(t -> {
                    Ticket target = t;
                    if (changeReq.status() == Ticket.Status.DONE
                            && extractions.findByTicketId(id).isEmpty()) {
                        // If the ticket has no extraction row yet
                        // (typical for ON_ERROR retries, or any
                        // ticket that landed in DONE before the
                        // scheduler could pick it up), trigger the
                        // AI pipeline synchronously. processTicket
                        // sets the status to IN_PROGRESS at start,
                        // marks ON_ERROR on failure, and leaves the
                        // row ready for the normaliser on success.
                        // We re-read the ticket afterwards so the
                        // response reflects whatever status the
                        // pipeline actually landed on.
                        Ticket fresh = repository.findById(id, user.id()).orElse(t);
                        extractionService.processTicket(fresh);
                        target = repository.findById(id, user.id()).orElse(t);
                    }
                    Ticket updated = repository.save(target.withStatus(changeReq.status()));
                    if (changeReq.status() == Ticket.Status.DONE) {
                        try {
                            normaliser.normaliseOnDone(updated.id());
                        } catch (RuntimeException ex) {
                            // Best-effort: don't fail the status flip
                            // because of a catalogue hiccup. The next
                            // mark-as-done is idempotent and re-runs
                            // the normaliser.
                            log.warn("normaliseOnDone failed for ticket {}: {}",
                                    updated.id(), ex.getMessage());
                        }
                    }
                    return ResponseEntity.ok(TicketResponse.of(updated));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
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
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
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
                .orElseGet(() -> ResponseEntity.notFound().build());
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
    public ResponseEntity<ExtractionResponse> replaceExtraction(@PathVariable UUID id,
                                                                @RequestBody UpdateExtractionRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (body.merchant() == null || body.merchant().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchant must not be blank");
        }
        if (body.purchaseDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "purchaseDate is required");
        }
        if (body.totalAmount() == null || body.totalAmount().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "totalAmount must be >= 0");
        }
        if (body.currency() == null || body.currency().length() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "currency must be an ISO 4217 code (3 letters)");
        }
        java.util.List<ProductLineDto> productDtos = body.products() == null
                ? java.util.List.of()
                : body.products();
        for (int i = 0; i < productDtos.size(); i++) {
            ProductLineDto p = productDtos.get(i);
            if (p.name() == null || p.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "products[" + i + "].name must not be blank");
            }
            if (p.quantity() == null || p.quantity().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "products[" + i + "].quantity must be > 0");
            }
        }

        AuthenticatedUser user = CurrentUser.get();
        java.util.Optional<Ticket> ticketOpt = repository.findById(id, user.id());
        if (ticketOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Refuse silently when no extraction exists yet — let the
        // AI finish first, then edit. The detail screen is
        // already aligned: it disables the edit affordance while
        // extraction is null.
        java.util.Optional<TicketExtraction> existing = extractions.findByTicketId(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TicketExtraction current = existing.get();
        java.util.List<TicketExtraction.ProductLine> domainProducts = productDtos.stream()
                .map(p -> new TicketExtraction.ProductLine(
                        p.name(), p.quantity(), p.unit(),
                        p.pricePerUnit() == null ? java.math.BigDecimal.ZERO : p.pricePerUnit(),
                        p.lineTotal() == null ? java.math.BigDecimal.ZERO : p.lineTotal()))
                .toList();
        TicketExtraction updated = new TicketExtraction(
                current.ticketId(),
                body.merchant(),
                body.purchaseDate(),
                body.category(),
                domainProducts,
                body.totalAmount(),
                body.currency(),
                current.model(),
                current.extractedAt(),
                current.rawResponse(),
                current.extractionPayload());
        try {
            extractions.replace(updated);
        } catch (IllegalStateException e) {
            // Race: row vanished between findByTicketId and replace
            // (a concurrent delete). Surface as 404 — the row is
            // gone from the operator's POV either way.
            log.warn("replaceExtraction raced with delete for ticket {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ExtractionResponse.of(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AuthenticatedUser user = CurrentUser.get();
        // Owner-scoped delete: returns false (→ 404) when the ticket
        // doesn't exist or belongs to another user. Same response for
        // both cases — never leak existence.
        boolean removed = repository.deleteById(id, user.id());
        return removed
                ? ResponseEntity.noContent().<Void>build()
                : ResponseEntity.notFound().<Void>build();
    }

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
    public ResponseEntity<ExtractionResponse> extraction(@PathVariable UUID id) {
        AuthenticatedUser user = CurrentUser.get();
        // First gate on the ticket itself — refuses cross-tenant
        // access without leaking existence (returns 404 either way).
        java.util.Optional<Ticket> ticket = repository.findById(id, user.id());
        if (ticket.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return extractions.findByTicketId(id)
                .map(e -> ResponseEntity.ok(ExtractionResponse.of(e)))
                // 404 with no body — same shape as "ticket not
                // found" so the front end doesn't have to distinguish
                // "wrong id" from "not yet extracted" on the wire.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

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
     * <p>Three round trips total: one for the lines, one each for
     * the joined products and prices via IN-list batch. Acceptable
     * because line counts per ticket are small (single-digit to a
     * few dozen at worst) and the JOIN keeps the SQL understandable
     * without resorting to a wide denormalised read view.
     */
    @GetMapping("/{id}/catalogue")
    public ResponseEntity<CatalogueResponse> catalogue(@PathVariable UUID id) {
        AuthenticatedUser user = CurrentUser.get();
        if (repository.findById(id, user.id()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        java.util.List<com.ticketapp.domain.LineTicket> lines =
                lineTickets.findByTicketId(id);
        if (lines.isEmpty()) {
            // No normalised lines yet — ticket wasn't validated or
            // the normaliser hasn't run for some reason. Caller
            // treats 404 as "show the JSONB extraction view".
            return ResponseEntity.notFound().build();
        }
        java.util.Set<UUID> productIds = lines.stream()
                .map(com.ticketapp.domain.LineTicket::productId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<UUID> priceIds = lines.stream()
                .map(com.ticketapp.domain.LineTicket::priceId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<UUID, com.ticketapp.domain.Product> productMap =
                products.findAllByIds(productIds);
        java.util.Map<UUID, com.ticketapp.domain.Price> priceMap =
                prices.findAllByIds(priceIds);
        // All line_tickets for one ticket share the same shop row
        // (the normaliser resolves the shop once per ticket), so
        // picking the first is safe. Returning Optional.empty()
        // only happens if a row is deleted out from under us; we
        // surface that as a 500-class failure (shouldn't reach prod).
        com.ticketapp.domain.Shop shop = shops.findById(lines.get(0).shopId())
                .orElseThrow(() -> new IllegalStateException(
                        "catalogue line " + lines.get(0).id()
                                + " references missing shop " + lines.get(0).shopId()));
        java.util.List<CatalogueLine> wireLines = lines.stream()
                .map(lt -> {
                    com.ticketapp.domain.Product p = productMap.get(lt.productId());
                    com.ticketapp.domain.Price pr = priceMap.get(lt.priceId());
                    return new CatalogueLine(
                            p != null ? p.name() : null,
                            p != null ? p.unit() : null,
                            lt.quantity(),
                            pr != null ? pr.amount() : null,
                            lt.lineTotal());
                })
                .toList();
        return ResponseEntity.ok(new CatalogueResponse(
                shop.id(), shop.name(), wireLines));
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
            return ResponseEntity.notFound().build();
        }
        Ticket ticket = ticketOpt.get();
        byte[] bytes = ticket.fileData();
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
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

    public record ChangeStatusRequest(Ticket.Status status) {}

    /**
     * Wire shape for {@code PATCH /api/tickets/{id}}. Both fields
     * are optional — the controller only updates the fields the
     * caller actually sent. {@code title} is validated non-blank
     * by {@link Ticket#withTitle(String)}; {@code description} is
     * normalised to {@code ""} by {@link Ticket#withDescription(String)}
     * when {@code null}.
     */
    public record UpdateTicketRequest(String title, String description) {}

    /**
     * Wire shape for {@code PUT /api/tickets/{id}/extraction}.
     * Carries the user-editable portion of the extraction; the
     * AI's audit fields ({@code model}, {@code extractedAt},
     * {@code rawResponse}, {@code extractionPayload}) are not
     * part of this DTO — the controller preserves them
     * server-side by reading the existing row.
     */
    public record UpdateExtractionRequest(
            String merchant,
            java.time.LocalDate purchaseDate,
            String category,
            java.util.List<ProductLineDto> products,
            java.math.BigDecimal totalAmount,
            String currency) {}

    /**
     * Wire response for {@code GET /api/tickets/{id}/extraction}.
     * Mirrors {@link TicketExtraction} but flattens the {@code products}
     * list into a plain array (it was a JSONB column on the server
     * side). The {@code model} and {@code extractedAt} fields are
     * surfaced for the UI's audit trail ("extracted by MiniMax-M3 on
     * …") so the user can see when the AI did its work.
     *
     * <p>The full {@code rawResponse} (the model's raw reply before
     * parsing) is NOT exposed — it's verbose and the structured
     * fields above already convey the actionable data. It's still
     * kept server-side for audit / debugging.</p>
     */
    public record ExtractionResponse(
            java.util.UUID ticketId,
            String merchant,
            java.time.LocalDate purchaseDate,
            String category,
            java.util.List<ProductLineDto> products,
            java.math.BigDecimal totalAmount,
            String currency,
            String model,
            java.time.Instant extractedAt) {

        static ExtractionResponse of(TicketExtraction e) {
            java.util.List<ProductLineDto> products = e.products().stream()
                    .map(p -> new ProductLineDto(
                            p.name(),
                            p.quantity(),
                            p.unit(),
                            p.pricePerUnit(),
                            p.lineTotal()))
                    .toList();
            return new ExtractionResponse(
                    e.ticketId(),
                    e.merchant(),
                    e.purchaseDate(),
                    e.category(),
                    products,
                    e.totalAmount(),
                    e.currency(),
                    e.model(),
                    e.extractedAt());
        }
    }

    public record ProductLineDto(
            String name,
            java.math.BigDecimal quantity,
            String unit,
            java.math.BigDecimal pricePerUnit,
            java.math.BigDecimal lineTotal) { }

    /**
     * Wire response for {@code GET /api/tickets/{id}/catalogue}.
     *
     * <p>The catalogue view is what the user sees after
     * {@code DONE}: shop + lines joined from {@code shops},
     * {@code products} and {@code prices}, fanned out through
     * {@code line_tickets}. The endpoint is a read-only mirror of
     * what the normaliser wrote; there's intentionally no edit
     * surface — validated tickets are immutable.
     *
     * <p>{@code lineTotal} comes from {@code line_tickets.line_total}
     * (the exact value the AI or the user finalised). The
     * {@code pricePerUnit} comes from the price snapshot the
     * normaliser captured; a ticket with two distinct amounts for
     * the same product (e.g. loyalty discount variant) keeps both
     * rows here.
     */
    public record CatalogueResponse(
            java.util.UUID shopId,
            String shopName,
            java.util.List<CatalogueLine> lines) { }

    public record CatalogueLine(
            String productName,
            String unit,
            java.math.BigDecimal quantity,
            java.math.BigDecimal pricePerUnit,
            java.math.BigDecimal lineTotal) { }

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