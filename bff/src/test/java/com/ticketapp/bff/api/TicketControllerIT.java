package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.bff.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for the ticket upload endpoint.
 *
 * <p>The flow under test mirrors the production path:
 * <ol>
 *   <li>Authenticate via {@code POST /api/auth/google} (mocked verifier)
 *       to obtain a Bearer session token.</li>
 *   <li>Send a {@code multipart/form-data} request to
 *       {@code POST /api/tickets} with the file part + a Bearer header.</li>
 *   <li>Assert the response carries the new ticket metadata (no bytes).</li>
 * </ol>
 *
 * <p>Round-tripping the stored bytes is verified separately by reading
 * the row back through {@link com.ticketapp.domain.TicketRepository} —
 * the wire response intentionally omits {@code fileData} (see
 * {@link TicketController.TicketResponse}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import({TestGoogleConfig.class, TestReceiptExtractorConfig.class})
class TicketControllerIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    .withDatabaseName("ticketapp")
                    .withUsername("ticketapp")
                    .withPassword("ticketapp")
                    .withEnv("PGDATA", "/var/lib/postgresql/data/pgdata");

    @LocalServerPort
    int port;

    @Autowired
    com.ticketapp.domain.TicketRepository tickets;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        // Reset DB between tests so the upserted user id from one
        // test doesn't leak into another (and so leftover tickets
        // don't pollute the list assertions). Order matters — the
        // catalogue tables all reference tickets, so they go
        // first to avoid FK violations. tickets is deleted BEFORE
        // shops because the V13 refactor moved the shop FK onto
        // tickets — a ticket row with shop_id set blocks
        // DELETE FROM shops via RESTRICT.
        jdbc.update("DELETE FROM line_tickets");
        jdbc.update("DELETE FROM prices");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM shops");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM app_users");
        // Reset the in-process receipt-extractor stub so per-test
        // call counts and the success/failure split don't leak
        // between tests.
        TestReceiptExtractorConfig.reset();
    }

    private WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    private String loginAndGetToken() {
        var resp = web().post().uri("/api/auth/google")
                .bodyValue(new AuthController.GoogleLoginRequest(TestGoogleConfig.VALID_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthController.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(resp).isNotNull();
        return resp.token();
    }

    private MultiValueMap<String, HttpEntity<?>> pdfMultipart(String filename,
                                                              byte[] bytes,
                                                              String contentType,
                                                              String description) {
        var bb = new MultipartBodyBuilder();
        bb.part("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }).header("Content-Type", contentType);
        if (description != null) {
            bb.part("description", description);
        }
        return bb.build();
    }

    @Test
    void rejectsUnauthenticatedUpload() {
        var body = pdfMultipart("receipt.pdf", "%PDF-1.4\n".getBytes(),
                MediaType.APPLICATION_PDF_VALUE, null);
        web().post().uri("/api/tickets")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsMissingFile() {
        String token = loginAndGetToken();
        // Valid multipart envelope but without the required `file` part.
        // Spring translates the missing-part failure to a 400 via
        // MissingServletRequestPartException; we exercise that path here.
        var bb = new MultipartBodyBuilder();
        bb.part("description", "hello");
        web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bb.build()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsUnsupportedMime() {
        String token = loginAndGetToken();
        var body = pdfMultipart("notes.txt", "hello".getBytes(),
                MediaType.TEXT_PLAIN_VALUE, null);
        web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsFileAboveTenMb() {
        String token = loginAndGetToken();
        byte[] oversized = new byte[(int) TicketController.MAX_FILE_BYTES + 1];
        var body = pdfMultipart("big.pdf", oversized, MediaType.APPLICATION_PDF_VALUE, null);
        web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void acceptsPdfAndStoresBytesInDb() {
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\n%hello world\n%%EOF\n".getBytes();
        var body = pdfMultipart("receipt.pdf", bytes,
                MediaType.APPLICATION_PDF_VALUE, "Lunch at Mercadona");

        var response = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(com.ticketapp.domain.Ticket.Status.OPEN);
        assertThat(response.title()).isEqualTo("receipt.pdf");
        assertThat(response.description()).isEqualTo("Lunch at Mercadona");
        assertThat(response.contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(response.fileName()).isEqualTo("receipt.pdf");
        assertThat(response.sizeBytes()).isEqualTo(bytes.length);
        // Bytes are NOT echoed back on the wire — they're in the DB.
        assertThat(response.sizeBytes()).isPositive();

        // Verify the bytes round-tripped through the DB.
        UUID owner = users.findByGoogleSub("google-sub-stub")
                .orElseThrow(() -> new IllegalStateException(
                        "test setup: user row not created — call loginAndGetToken first"))
                .id();
        var persisted = tickets.findById(response.id(), owner);
        assertThat(persisted).isPresent();
        assertThat(persisted.get().fileData()).isEqualTo(bytes);
        assertThat(persisted.get().contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(persisted.get().ownerId()).isEqualTo(owner);
    }

    @Test
    void acceptsImageUpload() {
        String token = loginAndGetToken();
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        var body = pdfMultipart("photo.png", pngBytes, "image/png", null);

        var response = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.fileName()).isEqualTo("photo.png");
    }

    @Test
    void fileEndpointStreamsBytesWithContentType() {
        // Upload a PNG and re-fetch it via the new file endpoint —
        // the browser will use the Content-Type header to render it
        // as <img> or <iframe>. Bytes must round-trip unchanged.
        String token = loginAndGetToken();
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};
        var body = pdfMultipart("photo.png", pngBytes, "image/png", null);
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();

        // Re-fetch via the file endpoint.
        byte[] fetched = web().get().uri("/api/tickets/{id}/file", created.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("image/png")
                .expectHeader().valueMatches("Content-Disposition", "inline; filename=\"photo\\.png\"")
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(fetched).isEqualTo(pngBytes);
    }

    @Test
    void fileEndpointReturns404ForOtherUsersTicket() {
        // Owner-scoped: another user can't pull the bytes. Same
        // 404 (not 403) pattern as the rest of the read paths so
        // we don't leak existence.
        String tokenA = loginAndGetToken();
        UUID ownerA = users.findByGoogleSub("google-sub-stub").orElseThrow().id();
        byte[] bytes = "%PDF-1.4\n%receipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + tokenA)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart("secret.pdf", bytes, "application/pdf", null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        // Seed user B directly (no Google login round-trip needed) and
        // mint a Bearer via the same helper the ownership IT uses.
        com.ticketapp.bff.auth.AuthenticatedUser userB = users.upsertFromGoogle(
                "google-sub-other", "other@example.com", "Other", null);
        String tokenB = mintTokenFor(userB);

        web().get().uri("/api/tickets/{id}/file", created.id())
                .header("authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isNotFound();

        // And the original owner still gets it normally.
        web().get().uri("/api/tickets/{id}/file", created.id())
                .header("authorization", "Bearer " + tokenA)
                .exchange()
                .expectStatus().isOk();

        // Suppress unused warning on ownerA — kept for readability.
        assertThat(ownerA).isNotNull();
    }

    @Test
    void extractionEndpointReturns404WhenNoExtraction() {
        // Newly-uploaded ticket has no extraction row yet (the
        // scheduler hasn't picked it up). 404, not 200-with-empty.
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart("r.pdf", bytes, "application/pdf", null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();

        web().get().uri("/api/tickets/{id}/extraction", created.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void extractionEndpointReturns404ForOtherUsersTicket() {
        // Owner-scoped: even the existence of an extraction is
        // hidden from a different tenant. The endpoint returns 404
        // when the ticket belongs to someone else, not the extraction.
        String tokenA = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + tokenA)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart("r.pdf", bytes, "application/pdf", null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        com.ticketapp.bff.auth.AuthenticatedUser userB = users.upsertFromGoogle(
                "google-sub-other", "other@example.com", "Other", null);
        String tokenB = mintTokenFor(userB);

        web().get().uri("/api/tickets/{id}/extraction", created.id())
                .header("authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Mint a BFF session JWT for the given user. Mirrors what the
     * production login flow does — persist an {@code auth_sessions}
     * row so the {@code SessionExistsValidator} recognises the
     * token, then sign the matching {@code sub}/{@code jti} claims
     * via the Spring {@code JwtEncoder}.
     */
    @Autowired
    org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;

    @Autowired
    com.ticketapp.bff.auth.SessionRepository sessionRepository;

    private String mintTokenFor(com.ticketapp.bff.auth.AuthenticatedUser user) {
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant exp = now.plus(java.time.Duration.ofHours(1));
        java.util.UUID jti = java.util.UUID.randomUUID();
        sessionRepository.save(new com.ticketapp.bff.auth.SessionRepository.Session(
                jti, user.id(), now, exp, null));
        org.springframework.security.oauth2.jwt.JwtClaimsSet claims =
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                        .issuer("ticketapp-bff")
                        .issuedAt(now)
                        .expiresAt(exp)
                        .subject(user.id().toString())
                        .id(jti.toString())
                        .build();
        return jwtEncoder.encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                        org.springframework.security.oauth2.jwt.JwsHeader.with(
                                org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                        claims)).getTokenValue();
    }

    // ---------------------------------------------------------------------
    // User-driven edits: PATCH /api/tickets/{id} + PUT /api/tickets/{id}/extraction
    // ---------------------------------------------------------------------
    //
    // Two endpoints ship together because the detail screen sends them
    // sequentially on Save: metadata first, then extraction when one
    // exists. They share auth (Bearer JWT), ownership scope (404 on
    // cross-tenant), and validation surface (manual 400s — no Bean
    // Validation on the controller).

    /**
     * Seed an extraction row directly (skipping the orchestrator)
     * so the PUT path has a row to update. Mirrors the JSONB shape
     * the persistence layer expects.
     */
    private void seedExtraction(UUID ticketId, String merchant, String category,
                                java.math.BigDecimal total, String currency) {
        jdbc.update("""
                INSERT INTO ticket_extractions
                  (ticket_id, merchant, purchase_date, category, products,
                   total_amount, currency, model, extracted_at,
                   raw_response, raw_response_text, extraction_payload)
                VALUES (?, ?, '2026-07-03', ?, ?::jsonb, ?, ?, 'test-model',
                        now(), NULL, 'seeded', NULL)
                """,
                ticketId, merchant, category, "[{\"name\":\"x\",\"quantity\":1,\"unit\":null,\"pricePerUnit\":1,\"lineTotal\":1}]",
                total, currency);
    }

    @Test
    void patchTicketUpdatesTitleAndDescription() {
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, "old desc")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();

        var patchBody = new java.util.HashMap<String, String>();
        patchBody.put("title", "Renamed");
        patchBody.put("description", "New description");

        TicketController.TicketResponse patched = web().patch().uri("/api/tickets/{id}", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(patchBody)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(patched).isNotNull();
        assertThat(patched.title()).isEqualTo("Renamed");
        assertThat(patched.description()).isEqualTo("New description");

        // Sanity check: GET round-trips the edited fields too.
        TicketController.TicketResponse fetched = web().get().uri("/api/tickets/{id}", created.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(fetched.title()).isEqualTo("Renamed");
        assertThat(fetched.description()).isEqualTo("New description");
    }

    @Test
    void patchTicketRejectsBlankTitle() {
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();

        web().patch().uri("/api/tickets/{id}", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("title", "   "))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void patchTicketReturns404ForOtherUsersTicket() {
        String tokenA = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + tokenA)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();

        var userB = users.upsertFromGoogle(
                "google-sub-other", "other@example.com", "Other", null);
        String tokenB = mintTokenFor(userB);

        web().patch().uri("/api/tickets/{id}", created.id())
                .header("authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("title", "Hostile"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void putExtractionUpdatesEditableFieldsAndPreservesAiAudit() {
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        seedExtraction(created.id(), "Mercadona", "food",
                new java.math.BigDecimal("3.00"), "EUR");

        var products = java.util.List.of(java.util.Map.of(
                "name", "Bread",
                "quantity", 2,
                "unit", "unit",
                "pricePerUnit", "1.50",
                "lineTotal", "3.00"));
        var payload = java.util.Map.of(
                "merchant", "Mercadona corrected",
                "purchaseDate", "2026-07-03",
                "category", "groceries",
                "products", products,
                "totalAmount", "3.00",
                "currency", "EUR");

        TicketController.ExtractionResponse updated = web().put()
                .uri("/api/tickets/{id}/extraction", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TicketController.ExtractionResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.merchant()).isEqualTo("Mercadona corrected");
        assertThat(updated.category()).isEqualTo("groceries");
        // AI audit preserved: model + extractedAt unchanged.
        assertThat(updated.model()).isEqualTo("test-model");
        assertThat(updated.totalAmount()).isEqualByComparingTo("3.00");
        assertThat(updated.products()).hasSize(1);
        assertThat(updated.products().get(0).name()).isEqualTo("Bread");
    }

    @Test
    void putExtractionReturns404WhenNoExtractionExists() {
        // Refusing silently when the AI hasn't run yet — the detail
        // screen disables editing until the row exists. A PUT on a
        // missing row must NOT silently insert one.
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();

        // HashMap because Map.of rejects null values — we want to
        // exercise the nullable `category` field too.
        var payload = new java.util.HashMap<String, Object>();
        payload.put("merchant", "x");
        payload.put("purchaseDate", "2026-07-03");
        payload.put("category", null);
        payload.put("products", java.util.List.of());
        payload.put("totalAmount", "0");
        payload.put("currency", "EUR");

        web().put()
                .uri("/api/tickets/{id}/extraction", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void putExtractionReturns404ForOtherUsersTicket() {
        String tokenA = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + tokenA)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        seedExtraction(created.id(), "Mercadona", "food",
                new java.math.BigDecimal("3.00"), "EUR");

        var userB = users.upsertFromGoogle(
                "google-sub-other", "other@example.com", "Other", null);
        String tokenB = mintTokenFor(userB);

        var payload = new java.util.HashMap<String, Object>();
        payload.put("merchant", "Hostile");
        payload.put("purchaseDate", "2026-07-03");
        payload.put("category", null);
        payload.put("products", java.util.List.of());
        payload.put("totalAmount", "0");
        payload.put("currency", "EUR");

        web().put()
                .uri("/api/tickets/{id}/extraction", created.id())
                .header("authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------------
    // Mark-as-done → catalogue (normalisation workflow).
    //
    // The normaliser fans a validated ticket's extraction out into
    // FOUR tables — `shops`, `products`, `prices`, `line_tickets` —
    // which mirrors the domain entities rather than collapsing
    // them into one. The tests below pin the resulting row counts
    // so a regression that drops one of the four layers surfaces
    // immediately. The `seedExtractionWithProducts` helper sets up
    // the source extraction row with the JSONB lines that drive
    // the orchestrator.
    // ---------------------------------------------------------------------

    /**
     * Insert an extraction row with a custom merchant + products
     * list. Products are serialised inline as JSONB (the production
     * shape); the merchant string is taken verbatim from the
     * {@code merchant} parameter so tests can pin normalisation
     * dedup behaviour.
     */
    private void seedExtractionWithProducts(UUID ticketId,
                                            String merchant,
                                            java.util.List<String> productJsons) {
        String productsJson = "[" + String.join(",", productJsons) + "]";
        jdbc.update("""
                INSERT INTO ticket_extractions
                  (ticket_id, merchant, purchase_date, category, products,
                   total_amount, currency, model, extracted_at,
                   raw_response, raw_response_text, extraction_payload)
                VALUES (?, ?, '2026-07-03', 'food', ?::jsonb,
                        100, 'EUR', 'test-model',
                        now(), NULL, 'seeded', NULL)
                """,
                ticketId, merchant, productsJson);
    }

    @Test
    void markAsDoneFansExtractionOutAcrossFourTables() {
        // Happy path: three-line extraction → one shop row + two
        // product rows (Bread collapses because both lines share
        // the same match key) + two price rows + two line_ticket
        // rows.
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        seedExtractionWithProducts(created.id(), "Mercadona", java.util.List.of(
                "{\"name\":\"Bread\",\"quantity\":1,\"unit\":null,\"pricePerUnit\":1.20,\"lineTotal\":1.20}",
                "{\"name\":\"Milk 1L\",\"quantity\":2,\"unit\":\"L\",\"pricePerUnit\":0.90,\"lineTotal\":1.80}",
                "{\"name\":\"Bread\",\"quantity\":1,\"unit\":null,\"pricePerUnit\":1.50,\"lineTotal\":1.50}"
        ));

        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();

        // One shop master row, regardless of capitalisation.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shops", Integer.class)).isEqualTo(1);

        // Two products: "Bread" + null unit collapses the two
        // Bread lines into one master row; "Milk 1L" + "L" stands
        // alone.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(2);

        // Two distinct price rows: Bread at €1.20 (last emission
        // wins — the upsert collapses the two Bread prices because
        // they're keyed on (product, ticket, amount) and the two
        // amounts happen to differ so both rows stay distinct)
        // and Milk at €0.90. Actually the two Bread amounts are
        // DIFFERENT (1.20 and 1.50) so we get TWO distinct prices
        // for Bread plus one for Milk = 3 total.
        // (Wait — revalidation same-ticket would collapse the two
        // prices. Here we only normalise ONCE, so both amounts stay
        // distinct rows.)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM prices WHERE ticket_id = ?",
                Integer.class, created.id())).isEqualTo(3);

        // Two line_ticket rows: the second Bread line collapses
        // with the first (UNIQUE on (ticket_id, product_id)
        // triggers an update, not an insert) — quantity / lineTotal
        // reflect the LAST emission.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_tickets WHERE ticket_id = ?",
                Integer.class, created.id())).isEqualTo(2);

        // The bread row's line_total should be the second
        // emission (1.50), since the upsert overwrites the
        // previously-stored 1.20.
        assertThat(jdbc.queryForObject(
                "SELECT line_total FROM line_tickets lt " +
                        "JOIN products p ON p.id = lt.product_id " +
                        "WHERE lt.ticket_id = ? AND p.normalised_name = 'bread'",
                java.math.BigDecimal.class, created.id()))
                .isEqualByComparingTo("1.50");
    }

    @Test
    void markAsDoneReusesExistingShopMasterAcrossTickets() {
        // Two tickets from the same merchant should share one shop
        // row — the dedup is on (normalised_name), not on (name).
        // This is what lets the dashboard show "spent at Mercadona
        // across all my tickets this month".
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse first = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "first.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        TicketController.TicketResponse second = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "second.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();

        // First ticket — merchant capitalised one way.
        seedExtractionWithProducts(first.id(), "MERCADONA", java.util.List.of(
                "{\"name\":\"Bread\",\"quantity\":1,\"unit\":null,\"pricePerUnit\":1.20,\"lineTotal\":1.20}"
        ));
        // Second ticket — capitalised differently, but same shop.
        seedExtractionWithProducts(second.id(), "Mercadona", java.util.List.of(
                "{\"name\":\"Bread\",\"quantity\":2,\"unit\":null,\"pricePerUnit\":1.50,\"lineTotal\":3.00}"
        ));

        web().patch().uri("/api/tickets/{id}/status", first.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();
        web().patch().uri("/api/tickets/{id}/status", second.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();

        // One Mercadona row across both tickets — same normalised
        // name "mercadona" → same shop.id.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shops", Integer.class)).isEqualTo(1);
        // One Bread master row across both tickets.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(1);
        // Two prices (one per ticket — different amounts), one
        // line_ticket per ticket — both pointing at the same
        // product id.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM prices", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_tickets", Integer.class)).isEqualTo(2);
    }

    @Test
    void markAsDoneIsIdempotentOnRevalidationWithEditedPrice() {
        // Round-trip: normalise → edit extraction (PUT) → mark as
        // cancelled → mark as done again. The shop and product
        // masters stay; prices and line_tickets are re-emitted.
        // The exact UPSERT semantics depend on which columns change:
        // - shop: stays (same normalised name, conflict → no-op)
        // - product: stays
        // - price: re-issued if amount differs, reuse if same
        // - line_ticket: same product → row updates quantity / lineTotal
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        seedExtractionWithProducts(created.id(), "Lidl", java.util.List.of(
                "{\"name\":\"Tea\",\"quantity\":1,\"unit\":null,\"pricePerUnit\":2.00,\"lineTotal\":2.00}"
        ));

        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();
        Integer shopCountAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shops", Integer.class);
        Integer productCountAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products", Integer.class);
        Integer priceCountAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prices WHERE ticket_id = ?",
                Integer.class, created.id());
        Integer lineCountAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_tickets WHERE ticket_id = ?",
                Integer.class, created.id());

        // Cancel then re-mark as DONE.
        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "CANCELLED"))
                .exchange()
                .expectStatus().isOk();
        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shops", Integer.class))
                .isEqualTo(shopCountAfterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM products", Integer.class))
                .isEqualTo(productCountAfterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM prices WHERE ticket_id = ?",
                Integer.class, created.id()))
                .isEqualTo(priceCountAfterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_tickets WHERE ticket_id = ?",
                Integer.class, created.id()))
                .isEqualTo(lineCountAfterFirst);
    }

    @Test
    void markAsDoneTriggersExtractionWhenMissing() {
        // A ticket that never had an extraction row (the typical
        // ON_ERROR case: AI provider failed before persisting any
        // JSON) must still produce a populated catalogue when the
        // user clicks "Mark as done". The endpoint treats that click
        // as "extract now, then normalise" so the button does what
        // the user expects instead of silently flipping status to
        // DONE with an empty catalogue. Stubbed
        // ReceiptExtractorConfig returns a minimal successful
        // extraction, so we end up with one shop + one product +
        // one price + one line_ticket.
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();

        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();

        // The stub was called exactly once — the controller triggered
        // the AI pipeline synchronously because no extraction row
        // existed when mark-as-done arrived.
        assertThat(TestReceiptExtractorConfig.calls()).hasSize(1);
        assertThat(TestReceiptExtractorConfig.successCount()).isEqualTo(1);
        // Catalogue populated end-to-end.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shops", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM prices WHERE ticket_id = ?",
                Integer.class, created.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_tickets WHERE ticket_id = ?",
                Integer.class, created.id())).isEqualTo(1);
    }

    @Test
    void markAsDoneOnAlreadyExtractedTicketSkipsStub() {
        // Counterpart: when an extraction row already exists (the
        // happy scheduler path: ticket was processed, then the
        // user confirms by clicking mark-as-done), the controller
        // must NOT trigger another AI call. The stub's call count
        // pins this — invoking the extractor twice would burn paid
        // tokens for no reason.
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        // Pre-seed an extraction row so the controller treats the
        // ticket as already-extracted on the mark-as-done path.
        seedExtractionWithProducts(created.id(), "Lidl", java.util.List.of(
                "{\"name\":\"Tea\",\"quantity\":1,\"unit\":null,\"pricePerUnit\":2.00,\"lineTotal\":2.00}"
        ));

        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();

        assertThat(TestReceiptExtractorConfig.calls()).isEmpty();
    }

    @Test
    void markAsCancelledDoesNotCreateCatalogueRows() {
        // CANCELLED must NOT trigger the normaliser. Cataloguing
        // cancelled spend would skew the dashboard's analytics.
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        seedExtractionWithProducts(created.id(), "Carrefour", java.util.List.of(
                "{\"name\":\"Bread\",\"quantity\":1,\"unit\":null,\"pricePerUnit\":1.20,\"lineTotal\":1.20}"
        ));

        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "CANCELLED"))
                .exchange()
                .expectStatus().isOk();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM shops", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM products", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM prices", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_tickets", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------------
    // Catalogue read API: GET /api/tickets/{id}/catalogue
    //
    // After a ticket is DONE, the detail screen reads the
    // normalised line view from this endpoint rather than from the
    // JSONB extraction. The tests below verify:
    //
    //   * the joined view comes back with the right shop +
    //     product rows after a successful DONE flow
    //   * the endpoint refuses cross-tenant reads with 404
    //   * the endpoint 404s a ticket that has no catalogue yet
    //   * the field values match what was seeded in the
    //     extraction (cataloguing doesn't rewrite the data, it
    //     just moves it into the relational tables)
    // ---------------------------------------------------------------------

    @Test
    void catalogueReturnsJoinedShopAndLinesAfterDone() {
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        seedExtractionWithProducts(created.id(), "Mercadona", java.util.List.of(
                "{\"name\":\"Bread\",\"quantity\":2,\"unit\":null,\"pricePerUnit\":1.20,\"lineTotal\":2.40}",
                "{\"name\":\"Milk 1L\",\"quantity\":1,\"unit\":\"L\",\"pricePerUnit\":0.90,\"lineTotal\":0.90}"
        ));

        // Trigger the normaliser.
        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();

        TicketController.CatalogueResponse cat = web().get()
                .uri("/api/tickets/{id}/catalogue", created.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TicketController.CatalogueResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(cat).isNotNull();
        assertThat(cat.shopName()).isEqualTo("Mercadona");
        assertThat(cat.shopId()).isNotNull();
        assertThat(cat.lines()).hasSize(2);

        // Order matches the seeded order (created_at ASC).
        var first = cat.lines().get(0);
        assertThat(first.productName()).isEqualTo("Bread");
        assertThat(first.unit()).isNull();
        assertThat(first.quantity()).isEqualByComparingTo("2");
        assertThat(first.pricePerUnit()).isEqualByComparingTo("1.20");
        assertThat(first.lineTotal()).isEqualByComparingTo("2.40");

        var second = cat.lines().get(1);
        assertThat(second.productName()).isEqualTo("Milk 1L");
        assertThat(second.unit()).isEqualTo("L");
        assertThat(second.quantity()).isEqualByComparingTo("1");
        assertThat(second.pricePerUnit()).isEqualByComparingTo("0.90");
        assertThat(second.lineTotal()).isEqualByComparingTo("0.90");
    }

    @Test
    void catalogueReturns404WhenNoExtractionHasBeenNormalised() {
        // No seedExtraction + mark-as-done — the catalogue table is
        // empty for this ticket. Frontend treats 404 as
        // "show the JSONB extraction view".
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();

        web().get()
                .uri("/api/tickets/{id}/catalogue", created.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void catalogueReturns404ForCrossTenant() {
        String tokenA = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\nreceipt\n%%EOF\n".getBytes();
        TicketController.TicketResponse created = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + tokenA)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(pdfMultipart(
                        "r.pdf", bytes, MediaType.APPLICATION_PDF_VALUE, null)))
                .exchange()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        seedExtractionWithProducts(created.id(), "Mercadona", java.util.List.of(
                "{\"name\":\"Bread\",\"quantity\":1,\"unit\":null,\"pricePerUnit\":1.20,\"lineTotal\":1.20}"
        ));
        web().patch().uri("/api/tickets/{id}/status", created.id())
                .header("authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("status", "DONE"))
                .exchange()
                .expectStatus().isOk();

        var userB = users.upsertFromGoogle(
                "google-sub-other", "other@example.com", "Other", null);
        String tokenB = mintTokenFor(userB);

        web().get()
                .uri("/api/tickets/{id}/catalogue", created.id())
                .header("authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isNotFound();
    }
}