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
 * matters.
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

    @BeforeEach
    void signIn() {
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
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
        jdbc.update("DELETE FROM tickets WHERE owner_id IN (?, ?)", ownerId, unrelatedOwnerId);
        jdbc.update("DELETE FROM app_users WHERE id = ?", unrelatedOwnerId);
    }

    private Long unrelatedOwnerId;

    @Test
    @DisplayName("rejects an unauthenticated request")
    void rejectsUnauthenticated() {
        web().put().uri("/api/tickets/1")
                .exchange()
                .expectStatus().isUnauthorized();
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

        ExtractionResponse extraction = web().get()
                .uri("/api/tickets/" + ticketId + "/extraction")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ExtractionResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(extraction).isNotNull();
        assertThat(extraction.merchant()).isEqualTo("Mercadona Centro");
        assertThat(extraction.totalAmount()).isEqualByComparingTo("45.60");
    }

    /**
     * The atomicity guarantee.
     *
     * <p>The ticket carries metadata but no extraction row, so the
     * extraction half of the edit is rejected <em>after</em> the
     * metadata has already been written. If the two were separate
     * commits the title would be "Weekly shop" on a 404 response —
     * exactly the half-save this endpoint removes.
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
    }

    @Test
    @DisplayName("keeps the extraction untouched when the metadata half is invalid")
    void keepsExtractionWhenMetadataInvalid() {
        long ticketId = seedTicket(Ticket.Status.DONE, withExtraction());

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

        assertThat(storedMerchant(ticketId)).isEqualTo("Mercadona");
    }

    @Test
    @DisplayName("applies metadata alone when no extraction is supplied")
    void metadataOnly() {
        long ticketId = seedTicket(Ticket.Status.DONE, withExtraction());

        web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Just the title"))
                .exchange();
        // A null extraction means "nothing to write", never "clear it".
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
                VALUES (?, 'Original title', 'Original description', ?, now(), now())
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

    private String storedMerchant(long ticketId) {
        return jdbc.queryForObject(
                "SELECT merchant FROM ticket_extractions WHERE ticket_id = ?", String.class, ticketId);
    }

    private WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

}