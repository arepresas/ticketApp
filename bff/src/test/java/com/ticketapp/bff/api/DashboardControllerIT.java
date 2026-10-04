package com.ticketapp.bff.api;

import com.ticketapp.bff.api.dto.DashboardResponse;
import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.domain.Ticket;
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
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for {@link DashboardController} — the endpoint that
 * replaces the SPA's mock dashboard payload.
 *
 * <p>Covers the three things the browser cannot verify on its own: that
 * the response is owner-scoped, that the JSON field names match what
 * {@code front/src/lib/api/dashboard.ts} declares, and that the
 * currency travels with the amounts instead of being assumed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestGoogleConfig.class)
class DashboardControllerIT {

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

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    @DisplayName("rejects an unauthenticated request")
    void rejectsUnauthenticated() {
        web().get().uri("/api/dashboard")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("returns the KPI payload the SPA charts and cards expect")
    void returnsKpiPayload() {
        String token = loginAndGetToken();
        seedTicketForCurrentUser("done", Ticket.Status.DONE, "25.00", "EUR", "food");
        seedTicketForCurrentUser("open", Ticket.Status.OPEN, null, null, null);

        DashboardResponse body = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.kpis().totalTickets()).isEqualTo(2);
        assertThat(body.kpis().openTickets()).isEqualTo(1);
        assertThat(body.kpis().extractedTickets()).isEqualTo(1);
        assertThat(body.kpis().totalSpent()).isEqualByComparingTo("25.00");
        assertThat(body.kpis().currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("always returns four category slices and a gap-free monthly series")
    void returnsCompleteSeries() {
        String token = loginAndGetToken();
        seedTicketForCurrentUser("done", Ticket.Status.DONE, "25.00", "EUR", "food");

        DashboardResponse body = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.spendByCategory()).hasSize(4);
        assertThat(body.ticketsPerMonth()).isNotEmpty();
        // Last point must be the current month so the chart is anchored
        // to today rather than to the newest ticket.
        assertThat(body.ticketsPerMonth().get(body.ticketsPerMonth().size() - 1).month())
                .isEqualTo(java.time.YearMonth.now().toString());
    }

    @Test
    @DisplayName("returns zeros for a user with no tickets instead of failing")
    void returnsZerosForEmptyAccount() {
        String token = loginAndGetToken();

        DashboardResponse body = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.kpis().totalTickets()).isZero();
        assertThat(body.kpis().totalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.spendByCategory()).hasSize(4);
    }

    @Test
    @DisplayName("never exposes another owner's spending")
    void isOwnerScoped() {
        String token = loginAndGetToken();
        // A genuinely different account, not "the same user plus a
        // row" — the aggregate has to be owner-filtered in SQL, and
        // this is the test that would notice if it were not.
        seedUnrelatedUserWithTicket("theirs", "999.00");

        DashboardResponse body = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.kpis().totalTickets()).isZero();
        assertThat(body.kpis().totalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
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

    /**
     * Seeds a ticket for the user created by this test's login. Call
     * {@link #loginAndGetToken()} first — there is no current user to
     * attach to before that.
     */
    private void seedTicketForCurrentUser(String title, Ticket.Status status, String amount,
                                          String currency, String category) {
        Long ownerId = jdbc.queryForObject(
                "SELECT id FROM app_users ORDER BY id DESC LIMIT 1", Long.class);
        seedTicketFor(ownerId, title, status, amount, currency, category);
    }

    /**
     * Creates a second, unrelated account and gives it an expensive
     * ticket. Used to prove the aggregate is owner-filtered rather than
     * accidentally reading the whole {@code tickets} table.
     */
    private void seedUnrelatedUserWithTicket(String title, String amount) {
        Long otherOwner = jdbc.queryForObject("""
                INSERT INTO app_users (google_sub, email, name, created_at, last_login_at)
                VALUES (?, ?, 'Someone Else', now(), now()) RETURNING id""",
                Long.class, "unrelated-" + title, "unrelated-" + title + "@example.com");
        seedTicketFor(otherOwner, title, Ticket.Status.DONE, amount, "EUR", "food");
    }

    private void seedTicketFor(Long ownerId, String title, Ticket.Status status, String amount,
                               String currency, String category) {
        Long ticketId = jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, description, status, created_at, updated_at)
                VALUES (?, ?, '', ?, now(), now()) RETURNING id""",
                Long.class, ownerId, title, status.name());
        if (amount != null) {
            jdbc.update("""
                    INSERT INTO ticket_extractions
                        (ticket_id, merchant, purchase_date, category, products,
                         total_amount, currency, model, extracted_at,
                         raw_response_text, extraction_payload)
                    VALUES (?, 'Test Merchant', ?, ?, '[]'::jsonb, ?, ?, 'test-model', now(),
                            NULL, '{}'::jsonb)""",
                    ticketId, LocalDate.now(), category, new BigDecimal(amount), currency);
        }
    }
}