package com.ticketapp.bff.api;

import com.ticketapp.bff.api.dto.ExtractionResponse;
import com.ticketapp.bff.api.dto.TicketResponse;
import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.domain.Ticket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for the atomic detail-screen edit, {@code PUT /api/tickets/{id}}.
 *
 * <p>The reason this endpoint exists: metadata and extraction used to be
 * two requests, so the metadata could commit and the extraction then
 * fail, leaving the server holding half of an edit the UI had reported
 * as failed. These tests pin the atomicity, not just the happy path —
 * {@link #rollsBackMetadataWhenTheExtractionIsRejected()} is the one that
 * matters, alongside {@code TicketEditRollbackIT}, which forces the
 * extraction write to fail <em>after</em> the metadata write has landed
 * so the rollback cannot pass through an "check first, write later"
 * implementation.
 *
 * <p>Isolation: each test seeds its own tickets and cleans up exactly
 * those rows. Nothing here deletes other rows, so the suite stays
 * correct even if JUnit parallel execution is switched on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestGoogleConfig.class)
class TicketEditControllerIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    .withDatabaseName("ticketapp")
                    .withUsername("ticketapp")
                    .withPassword("ticketapp");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Signed-in user's id, captured per test.
     *
     * <p>Resolved from the login response rather than
     * {@code SELECT id FROM app_users ORDER BY id DESC LIMIT 1}: the
     * fixed test token reuses one account across the class while
     * {@link #seedUnrelatedUserWithTicket()} creates another, so the
     * "latest row" is whichever test ran last. That made this suite
     * order-dependent — the same trap as the dashboard IT.
     */
    private long ownerId;
    private String token;

    /** Tickets this test seeded; {@link #dropFixtures()} removes exactly these. */
    private final List<Long> seededTickets = new ArrayList<>();

    @BeforeEach
    void signIn() {
        // No global DELETE: each test seeds and tears down exactly
        // its own rows (see dropFixtures), so a concurrent test's
        // in-flight request never loses its fixtures underneath it.
        var resp = web().post().uri("/api/auth/google")
                .bodyValue(new AuthController.GoogleLoginRequest(TestGoogleConfig.VALID_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthController.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(resp).isNotNull();
        token = resp.token();
        ownerId = resp.user().id();
    }

    @AfterEach
    void dropFixtures() {
        // Children first, then the parent rows this class created.
        if (!seededTickets.isEmpty()) {
            jdbc.update("DELETE FROM ticket_extractions WHERE ticket_id IN ("
                    + placeholders(seededTickets.size()) + ")",
                    seededTickets.toArray());
            jdbc.update("DELETE FROM tickets WHERE id IN ("
                    + placeholders(seededTickets.size()) + ")",
                    seededTickets.toArray());
        }
        seededTickets.clear();
        if (unrelatedOwnerId != null) {
            jdbc.update("DELETE FROM tickets WHERE owner_id = ?", unrelatedOwnerId);
            jdbc.update("DELETE FROM app_users WHERE id = ?", unrelatedOwnerId);
            unrelatedOwnerId = null;
        }
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private Long unrelatedOwnerId;

    @Test
    @DisplayName("rejects an unauthenticated request")
    void rejectsUnauthenticated() {
        // Spring Security's entry point answers 401 with an empty
        // body: no internals about why the token was rejected leak to
        // an anonymous caller.
        web().put().uri("/api/tickets/1")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().isEmpty();
    }

    @Test
    @DisplayName("writes metadata and extraction together")
    void writesBoth() {
        long ticketId = seedTicket(Ticket.Status.DONE, withExtraction());

        TicketResponse body = web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "title", "Weekly shop",
                        "description", "edited by hand",
                        "extraction", extractionBody("Mercadona Centro", "45.60")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.title()).isEqualTo("Weekly shop");

        // Persisted state, not the echo: reload the row so a server
        // that dropped the title or ignored the description fails here.
        assertThat(storedTitle(ticketId)).isEqualTo("Weekly shop");
        assertThat(storedDescription(ticketId)).isEqualTo("edited by hand");
        assertThat(extractionRowCount(ticketId))
                .as("exactly one extraction row, not a second insert")
                .isEqualTo(1);
        assertThat(extractionSnapshot(ticketId))
                .as("every editable extraction column lands")
                .containsEntry("merchant", "Mercadona Centro")
                .containsEntry("purchase_date", "2026-03-12")
                .containsEntry("category", "food")
                .containsEntry("total_amount", "45.60")
                .containsEntry("currency", "EUR");
    }

    /**
     * The atomicity guarantee.
     *
     * <p>The ticket carries metadata but no extraction row, so the
     * extraction half of the edit is rejected <em>after</em> the
     * metadata has already been written. If the two were separate
     * commits the title would be "Weekly shop" on a 404 response —
     * exactly the half-save this endpoint removes.
     *
     * <p>{@code TicketEditRollbackIT} closes the loop this test
     * deliberately leaves open: it fails the extraction write when a
     * row <em>does</em> exist, so an implementation that checks for
     * the missing row up front (no transaction) cannot pass both.
     */
    @Test
    @DisplayName("rolls back metadata when the extraction is rejected")
    void rollsBackMetadataWhenTheExtractionIsRejected() {
        long ticketId = seedTicket(Ticket.Status.DONE, null);

        web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "title", "Weekly shop",
                        "description", "edited by hand",
                        "extraction", extractionBody("Should Not Persist", "45.60")))
                .exchange()
                .expectStatus().isNotFound();

        assertThat(storedTitle(ticketId))
                .as("metadata must not survive a rejected extraction")
                .isEqualTo("Original title");
        assertThat(storedDescription(ticketId))
                .as("description rolls back with the title")
                .isEqualTo("Original description");
        assertThat(extractionRowCount(ticketId))
                .as("the rejected extraction must not have been inserted")
                .isZero();
    }

    @Test
    @DisplayName("keeps the extraction untouched when the metadata half is invalid")
    void keepsExtractionWhenMetadataInvalid() {
        long ticketId = seedTicket(Ticket.Status.DONE, withExtraction());
        // Full before-snapshot: checking only the merchant lets an
        // implementation that also wrote amount, date, category or
        // products pass.
        Map<String, String> before = extractionSnapshot(ticketId);
        assertThat(before).isNotEmpty();

        // Blank title is rejected before anything is written, so the
        // extraction must still read its original merchant.
        web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "title", "   ",
                        "description", "edited",
                        "extraction", extractionBody("Should Not Persist", "45.60")))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(extractionSnapshot(ticketId)).isEqualTo(before);
        assertThat(storedTitle(ticketId)).isEqualTo("Original title");
    }

    @Test
    @DisplayName("applies metadata alone when no extraction is supplied")
    void metadataOnly() {
        long ticketId = seedTicket(Ticket.Status.DONE, withExtraction());

        web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Just the title"))
                .exchange()
                .expectStatus().isOk();

        // A null extraction means "nothing to write", never "clear it"
        // — and the metadata half is not optional: assert it landed.
        assertThat(storedTitle(ticketId)).isEqualTo("Just the title");
        assertThat(storedDescription(ticketId)).isEqualTo("Original description");
        assertThat(extractionRowCount(ticketId)).isEqualTo(1);
        assertThat(storedMerchant(ticketId)).isEqualTo("Mercadona");
    }

    @Test
    @DisplayName("treats an explicit null extraction as \"nothing to write\"")
    void explicitNullExtraction() {
        // Same contract as an omitted extraction: the JSON null must
        // reach the service as "no extraction half", not as a signal
        // to clear the row. Uses HashMap because Map.of rejects a null
        // value outright.
        long ticketId = seedTicket(Ticket.Status.DONE, withExtraction());

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("title", "Touched");
        payload.put("extraction", null);

        web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(storedTitle(ticketId)).isEqualTo("Touched");
        assertThat(storedMerchant(ticketId)).isEqualTo("Mercadona");
    }

    @Test
    @DisplayName("rejects an invalid extraction payload before writing anything")
    void rejectsInvalidExtraction() {
        long ticketId = seedTicket(Ticket.Status.DONE, withExtraction());

        web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "title", "Weekly shop",
                        "extraction", extractionBody("   ", "10.00")))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(storedTitle(ticketId)).isEqualTo("Original title");
        assertThat(storedMerchant(ticketId)).isEqualTo("Mercadona");
    }

    @Test
    @DisplayName("answers 404 for a missing ticket without leaking why")
    void missingTicketIs404() {
        web().put().uri("/api/tickets/999999")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Anything"))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.error").isNotEmpty();
    }

    @Test
    @DisplayName("never touches another owner's ticket")
    void isOwnerScoped() {
        // The target is the *other* account's ticket. Seeding the
        // current user's and expecting 404 would pass for the wrong
        // reason: that is a ticket the caller legitimately owns.
        long theirsId = seedUnrelatedUserWithTicket();

        web().put().uri("/api/tickets/" + theirsId)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Hijacked"))
                .exchange()
                .expectStatus().isNotFound();

        assertThat(storedTitle(theirsId)).isEqualTo("Theirs");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record ExtractionSeed(String merchant, String amount) {
    }

    private static ExtractionSeed withExtraction() {
        return new ExtractionSeed("Mercadona", "25.28");
    }

    private static Map<String, Object> extractionBody(String merchant, String amount) {
        return Map.of(
                "merchant", merchant,
                "purchaseDate", "2026-03-12",
                "category", "food",
                "products", java.util.List.of(Map.of(
                        "name", "Aceite oliva 1L",
                        "quantity", new BigDecimal("1"),
                        "unit", "unit",
                        "pricePerUnit", new BigDecimal(amount),
                        "lineTotal", new BigDecimal(amount))),
                "totalAmount", new BigDecimal(amount),
                "currency", "EUR");
    }

    private long seedTicket(Ticket.Status status, ExtractionSeed extraction) {
        Long ticketId = jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, description, status, created_at, updated_at)
                VALUES (?, 'Original title', 'Original description', ?::ticket_status, now(), now())
                RETURNING id""", Long.class, ownerId, status.name());
        if (extraction != null) {
            jdbc.update("""
                    INSERT INTO ticket_extractions
                        (ticket_id, merchant, purchase_date, category, products,
                         total_amount, currency, model, extracted_at,
                         raw_response_text, extraction_payload)
                    VALUES (?, ?, '2026-03-12', 'food', '[]'::jsonb, ?, 'EUR',
                            'test-model', now(), '{}', '{}'::jsonb)""",
                    ticketId, extraction.merchant(), new BigDecimal(extraction.amount()));
        }
        seededTickets.add(ticketId);
        return ticketId;
    }

    private long seedUnrelatedUserWithTicket() {
        unrelatedOwnerId = jdbc.queryForObject("""
                INSERT INTO app_users (google_sub, email, name, created_at, last_login_at)
                VALUES (?, ?, 'Someone Else', now(), now()) RETURNING id""",
                Long.class, "unrelated-edit-it-" + UUID.randomUUID(),
                "unrelated-edit-it-" + UUID.randomUUID() + "@example.com");
        return jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, description, status, created_at, updated_at)
                VALUES (?, 'Theirs', '', 'DONE', now(), now()) RETURNING id""", Long.class, unrelatedOwnerId);
    }

    private String storedTitle(long ticketId) {
        return jdbc.queryForObject("SELECT title FROM tickets WHERE id = ?", String.class, ticketId);
    }

    private String storedDescription(long ticketId) {
        return jdbc.queryForObject(
                "SELECT description FROM tickets WHERE id = ?", String.class, ticketId);
    }

    private String storedMerchant(long ticketId) {
        return jdbc.queryForObject(
                "SELECT merchant FROM ticket_extractions WHERE ticket_id = ?", String.class, ticketId);
    }

    private int extractionRowCount(long ticketId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_extractions WHERE ticket_id = ?", Integer.class, ticketId);
    }

    /**
     * Every persisted extraction column as text — {@code ::text} on
     * each so JSONB and timestamps compare as stable strings across
     * before/after snapshots. Includes the AI audit columns: the edit
     * path must not rewrite them.
     */
    private Map<String, String> extractionSnapshot(long ticketId) {
        Map<String, Object> raw = jdbc.queryForMap("""
                SELECT merchant::text, purchase_date::text, category::text, products::text,
                       total_amount::text, currency::text, model::text, extracted_at::text,
                       raw_response_text::text, extraction_payload::text
                FROM ticket_extractions WHERE ticket_id = ?""", ticketId);
        return raw.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, e -> String.valueOf(e.getValue()),
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

}
