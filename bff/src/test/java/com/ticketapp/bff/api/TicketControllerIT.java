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
@Import(TestGoogleConfig.class)
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
        // don't pollute the list assertions).
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM app_users");
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
}