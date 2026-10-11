package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.domain.Ticket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixtures for the {@code PUT /api/tickets/{id}} transaction
 * tests: real Postgres, real HTTP, one signed-in account.
 *
 * <p>Two concrete ITs extend this — {@link TicketEditRollbackIT}
 * (extraction write fails after the metadata write landed) and
 * {@link TicketEditConflictIT} (a concurrent writer bumps the row
 * version between the service's read and write). They cannot share one
 * class because each needs its own failure injection wired as a
 * different {@code @Primary} repository bean.
 *
 * <p>Isolation: every test seeds its own rows and the teardown removes
 * exactly those. Nothing here deletes rows a concurrent test might own,
 * so these suites stay correct if JUnit parallel execution is ever
 * switched on.
 *
 * <p>The {@code test} profile is mandatory: it supplies the AI-provider
 * property placeholders ({@code application-test.yml}) the application
 * context binds on boot, independent of which failure is injected.
 */
@ActiveProfiles("test")
abstract class AbstractTicketEditIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private long ownerId;
    private String token;

    /** Tickets this test seeded; teardown removes exactly these. */
    private final List<Long> seededTickets = new ArrayList<>();
    private Long unrelatedOwnerId;

    @BeforeEach
    void signIn() {
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
        if (!seededTickets.isEmpty()) {
            String in = String.join(",", java.util.Collections.nCopies(seededTickets.size(), "?"));
            jdbc.update("DELETE FROM ticket_extractions WHERE ticket_id IN (" + in + ")",
                    seededTickets.toArray());
            jdbc.update("DELETE FROM tickets WHERE id IN (" + in + ")", seededTickets.toArray());
        }
        seededTickets.clear();
        if (unrelatedOwnerId != null) {
            jdbc.update("DELETE FROM tickets WHERE owner_id = ?", unrelatedOwnerId);
            jdbc.update("DELETE FROM app_users WHERE id = ?", unrelatedOwnerId);
            unrelatedOwnerId = null;
        }
    }

    protected String token() {
        return token;
    }

    protected WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    protected long seedTicketWithExtraction(String title, String description,
                                            String merchant, String amount) {
        Long ticketId = jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, description, status, created_at, updated_at)
                VALUES (?, ?, ?, 'DONE', now(), now())
                RETURNING id""", Long.class, ownerId, title, description);
        jdbc.update("""
                INSERT INTO ticket_extractions
                    (ticket_id, merchant, purchase_date, category, products,
                     total_amount, currency, model, extracted_at,
                     raw_response_text, extraction_payload)
                VALUES (?, ?, '2026-03-12', 'food', '[]'::jsonb, ?, 'EUR',
                        'test-model', now(), '{}', '{}'::jsonb)""",
                ticketId, merchant, new BigDecimal(amount));
        seededTickets.add(ticketId);
        return ticketId;
    }

    protected String storedTitle(long ticketId) {
        return jdbc.queryForObject("SELECT title FROM tickets WHERE id = ?", String.class, ticketId);
    }

    protected String storedDescription(long ticketId) {
        return jdbc.queryForObject(
                "SELECT description FROM tickets WHERE id = ?", String.class, ticketId);
    }

    protected int storedVersion(long ticketId) {
        return jdbc.queryForObject(
                "SELECT version FROM tickets WHERE id = ?", Integer.class, ticketId);
    }

    /**
     * Every persisted extraction column as text ({@code ::text} keeps
     * JSONB and timestamps comparable as strings), audit columns
     * included — an edit must not rewrite what the AI wrote.
     */
    protected Map<String, String> extractionSnapshot(long ticketId) {
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

    protected static Map<String, Object> editBody(String title, String description,
                                                  String merchant, String amount) {
        return Map.of(
                "title", title,
                "description", description,
                "extraction", Map.of(
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
                        "currency", "EUR"));
    }
}
