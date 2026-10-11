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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for {@link DashboardController} — the endpoint that
 * replaces the SPA's mock dashboard payload.
 *
 * <p>Covers the three things the browser cannot verify on its own: that
 * the response is owner-scoped, that the JSON field names match what
 * {@code front/src/lib/api/dashboard.ts} declares, and that the
 * currency travels with the amounts instead of being assumed.
 *
 * <p>"Now" is pinned to {@link #NOW} through a {@code @Primary}
 * {@link Clock}, and every seeded purchase date derives from it, so the
 * six-month window is asserted literally instead of against a calendar
 * that moves under the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import({TestGoogleConfig.class, DashboardControllerIT.FixedClockConfig.class})
class DashboardControllerIT {

    /**
     * Pins "now" for the whole context.
     *
     * <p>The reporting window is derived from the injected
     * {@link Clock}, so deriving the expectation from
     * {@code YearMonth.now()} instead only proves the two agree — and
     * disagrees the moment the suite crosses a month boundary, or runs
     * in a JVM zone that is not the reporting zone. Every date assertion
     * below is written against {@link #NOW}.
     */
    static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    /** The reporting month under {@link #NOW}, and the last point of the chart. */
    static final String REPORTING_MONTH = "2026-06";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

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
    @DisplayName("rejects an unauthenticated request with the resource server's 401")
    void rejectsUnauthenticated() {
        // The status alone is not a contract: a servlet container's HTML
        // error page also answers 401, and the SPA's error parser would
        // find nothing to show. What the BFF actually promises is the
        // OAuth2 challenge header with an empty body — RFC 7807 applies
        // to failures the controller throws (`GlobalExceptionHandler`),
        // not to the filter chain that rejects before dispatch.
        String body = web().get().uri("/api/dashboard")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNull();
    }

    @Test
    @DisplayName("rejects a malformed bearer token the same way a missing one is rejected")
    void rejectsMalformedToken() {
        // The missing-header case proves the chain runs; this proves it
        // rejects a token that is present but unusable, and that the SPA
        // cannot tell the two apart by accident.
        web().get().uri("/api/dashboard")
                .header("authorization", "Bearer not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    @DisplayName("returns the KPI payload the SPA charts and cards expect")
    void returnsKpiPayload() {
        Login login = loginAndGetTokenAndUser();
        String token = login.token();
        seedTicketForCurrentUser(login.userId(), "done", Ticket.Status.DONE, "25.00", "EUR", "food");
        seedTicketForCurrentUser(login.userId(), "open", Ticket.Status.OPEN, null, null, null);

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
        assertThat(body.kpis().extractedTicketsInCurrency()).isEqualTo(1);
        assertThat(body.kpis().totalSpent()).isEqualByComparingTo("25.00");
        assertThat(body.kpis().currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("always returns four category slices and a gap-free monthly series")
    void returnsCompleteSeries() {
        Login login = loginAndGetTokenAndUser();
        String token = login.token();
        seedTicketForCurrentUser(login.userId(), "done", Ticket.Status.DONE, "25.00", "EUR", "food",
                LocalDate.of(2026, 1, 12));

        DashboardResponse body = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.spendByCategory()).extracting(DashboardResponse.CategoryAmount::category)
                .containsExactly("transport", "food", "lodging", "other");
        // Exact, not "isNotEmpty()": every month of the six-month window
        // in order, gap-filled, ending on the reporting month so the chart
        // is anchored to today rather than to the newest ticket. A window
        // that quietly grew to seven months, or lost February, fails here.
        assertThat(body.ticketsPerMonth()).extracting(DashboardResponse.MonthlyTicket::month)
                .containsExactly("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", REPORTING_MONTH);
        assertThat(body.ticketsPerMonth()).extracting(DashboardResponse.MonthlyTicket::count)
                .containsExactly(1L, 0L, 0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("aggregates several tickets across months and categories")
    void aggregatesKnownFixture() {
        // Every EUR assertion above used a single extracted ticket, so an
        // implementation that took MAX(...) or the first row instead of
        // SUM(...) passed every one of them. Five tickets, two categories,
        // three months, one with no extraction:
        Login login = loginAndGetTokenAndUser();
        String token = login.token();
        long owner = login.userId();
        seedTicketForCurrentUser(owner, "jan-food", Ticket.Status.DONE, "10.00", "EUR", "food",
                LocalDate.of(2026, 1, 5));
        seedTicketForCurrentUser(owner, "jan-transport", Ticket.Status.DONE, "20.00", "EUR", "transport",
                LocalDate.of(2026, 1, 20));
        seedTicketForCurrentUser(owner, "mar-food", Ticket.Status.OPEN, "30.00", "EUR", "food",
                LocalDate.of(2026, 3, 9));
        seedTicketForCurrentUser(owner, "may-lodging", Ticket.Status.DONE, "40.00", "EUR", "lodging",
                LocalDate.of(2026, 5, 2));
        seedTicketForCurrentUser(owner, "jun-pending", Ticket.Status.OPEN, null, null, null);

        DashboardResponse body = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.kpis().totalTickets()).isEqualTo(5);
        // OPEN + the one with no extraction still awaiting work.
        assertThat(body.kpis().openTickets()).isEqualTo(2);
        // Only the four extracted ones carry an amount, and the average
        // is taken over those four: (10 + 20 + 30 + 40) / 4.
        assertThat(body.kpis().extractedTicketsInCurrency()).isEqualTo(4);
        assertThat(body.kpis().totalSpent()).isEqualByComparingTo("100.00");
        assertThat(body.kpis().avgTicketValue()).isEqualByComparingTo("25.00");
        assertThat(body.kpis().currency()).isEqualTo("EUR");

        assertThat(body.spendByCategory())
                .filteredOn(row -> row.category().equals("food"))
                .singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("40.00"));
        assertThat(body.spendByCategory())
                .filteredOn(row -> row.category().equals("transport"))
                .singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("20.00"));
        assertThat(body.spendByCategory())
                .filteredOn(row -> row.category().equals("lodging"))
                .singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("40.00"));

        // The monthly axis counts extracted tickets only: June holds the
        // unextracted one and must still read zero.
        assertThat(body.ticketsPerMonth()).extracting(DashboardResponse.MonthlyTicket::month)
                .containsExactly("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", REPORTING_MONTH);
        assertThat(body.ticketsPerMonth()).extracting(DashboardResponse.MonthlyTicket::count)
                .containsExactly(2L, 0L, 1L, 0L, 1L, 0L);
    }

    @Test
    @DisplayName("speaks the JSON field names the SPA client declares")
    void exposesWireFieldNames() {
        // Decoding into DashboardResponse is self-referential: it only
        // proves the backend can read its own output, so both sides can
        // drift together. These are the literal bytes the browser sees —
        // the old `*Eur` names, the ISO month format and the lowercase
        // category slugs included.
        Login login = loginAndGetTokenAndUser();
        seedTicketForCurrentUser(login.userId(), "done", Ticket.Status.DONE, "25.00", "EUR", "food",
                LocalDate.of(2026, 3, 9));

        String json = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + login.token())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(json)
                .contains("\"totalTickets\":1")
                .contains("\"extractedTicketsInCurrency\":1")
                .contains("\"totalSpent\":25.00")
                .contains("\"avgTicketValue\":25.00")
                .contains("\"currency\":\"EUR\"")
                .contains("\"month\":\"2026-03\"")
                .contains("\"category\":\"food\"")
                .doesNotContain("totalSpentEur")
                .doesNotContain("avgTicketEur");
    }

    @Test
    @DisplayName("returns zeros for a user with no tickets instead of failing")
    void returnsZerosForEmptyAccount() {
        String token = loginAndGetTokenAndUser().token();

        DashboardResponse body = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.kpis().totalTickets()).isZero();
        assertThat(body.kpis().openTickets()).isZero();
        assertThat(body.kpis().extractedTicketsInCurrency()).isZero();
        assertThat(body.kpis().totalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.kpis().avgTicketValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.kpis().currency()).isEqualTo("EUR");
        assertThat(body.spendByCategory())
                .hasSize(4)
                .extracting(DashboardResponse.CategoryAmount::category)
                .containsExactly("transport", "food", "lodging", "other");
        assertThat(body.spendByCategory())
                .allSatisfy(row -> assertThat(row.amount()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(body.ticketsPerMonth())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.count()).isZero());
    }

    @Test
    @DisplayName("keeps non-EUR tickets in the counts but out of the EUR spend")
    void mixedCurrencyKeepsTicketsInCounts() {
        // The two counters answer different questions on purpose:
        // totalTickets counts everything the owner has, while
        // extractedTicketsInCurrency only counts what the average was
        // taken over. This pins that split against real SQL — a mock
        // could not catch a currency filter leaking into the counts.
        Login login = loginAndGetTokenAndUser();
        String token = login.token();
        seedTicketForCurrentUser(login.userId(), "eur", Ticket.Status.DONE, "25.00", "EUR", "food");
        seedTicketForCurrentUser(login.userId(), "gbp", Ticket.Status.DONE, "40.00", "GBP", "food");
        seedTicketForCurrentUser(login.userId(), "open-gbp", Ticket.Status.OPEN, null, null, null);

        DashboardResponse body = web().get().uri("/api/dashboard")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DashboardResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.kpis().totalTickets()).isEqualTo(3);
        assertThat(body.kpis().openTickets()).isEqualTo(1);
        assertThat(body.kpis().extractedTicketsInCurrency()).isEqualTo(1);
        assertThat(body.kpis().totalSpent()).isEqualByComparingTo("25.00");
        assertThat(body.kpis().avgTicketValue()).isEqualByComparingTo("25.00");
        assertThat(body.kpis().currency()).isEqualTo("EUR");
        // The GBP row must not appear in the donut either.
        assertThat(body.spendByCategory())
                .filteredOn(row -> row.category().equals("food"))
                .singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("25.00"));
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
        assertThat(body.kpis().openTickets()).isZero();
        assertThat(body.kpis().extractedTicketsInCurrency()).isZero();
        assertThat(body.kpis().totalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(body.kpis().avgTicketValue()).isEqualByComparingTo(BigDecimal.ZERO);
        // Both series too: an unscoped aggregate would leak another
        // owner's activity even with the KPIs correct.
        assertThat(body.spendByCategory())
                .hasSize(4)
                .allSatisfy(row -> assertThat(row.amount()).isEqualByComparingTo(BigDecimal.ZERO));
        assertThat(body.ticketsPerMonth())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.count()).isZero());
    }

    private WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String loginAndGetToken() {
        return loginAndGetTokenAndUser().token();
    }

    /**
     * Signs in and returns the session plus the user's database id.
     *
     * <p>The id has to come from the login response rather than
     * {@code SELECT id FROM app_users ORDER BY id DESC}: the fixed test
     * token reuses one account across every test, so a fixture row
     * created by an earlier test can carry the highest id and silently
     * receive the next test's tickets.
     */
    private record Login(String token, long userId) {
    }

    private Login loginAndGetTokenAndUser() {
        var resp = web().post().uri("/api/auth/google")
                .bodyValue(new AuthController.GoogleLoginRequest(TestGoogleConfig.VALID_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthController.SessionResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(resp).isNotNull();
        return new Login(resp.token(), resp.user().id());
    }

    /**
     * Seeds a ticket for the user that just logged in. Takes the owner
     * id explicitly rather than looking it up, so a fixture row from an
     * earlier test cannot be mistaken for the current user.
     *
     * <p>The default purchase date is derived from {@link #NOW} rather
     * than {@code LocalDate.now()}: the reporting window is pinned to
     * {@link #REPORTING_MONTH}, so a seed dated "today" falls outside it
     * whenever the suite runs in a later month, and the monthly
     * assertions silently start reading zeros.
     */
    private void seedTicketForCurrentUser(long ownerId, String title, Ticket.Status status,
                                          String amount, String currency, String category) {
        seedTicketForCurrentUser(ownerId, title, status, amount, currency, category,
                LocalDate.ofInstant(NOW, ZoneOffset.UTC));
    }

    private void seedTicketForCurrentUser(long ownerId, String title, Ticket.Status status,
                                          String amount, String currency, String category,
                                          LocalDate purchaseDate) {
        seedTicketFor(ownerId, title, status, amount, currency, category, purchaseDate);
    }

    /**
     * Creates a second, unrelated account and gives it an expensive
     * ticket. Used to prove the aggregate is owner-filtered rather than
     * accidentally reading the whole {@code tickets} table.
     *
     * <p>Upsert rather than plain insert: {@code google_sub} is unique,
     * so a retried run (or a container kept alive between runs) would
     * otherwise fail this fixture on a constraint and mask whatever the
     * test was actually about.
     */
    private void seedUnrelatedUserWithTicket(String title, String amount) {
        Long otherOwner = jdbc.queryForObject("""
                INSERT INTO app_users (google_sub, email, name, created_at, last_login_at)
                VALUES (?, ?, 'Someone Else', now(), now())
                ON CONFLICT (google_sub) DO UPDATE
                    SET email = EXCLUDED.email, last_login_at = EXCLUDED.last_login_at
                RETURNING id""",
                Long.class, "unrelated-" + title, "unrelated-" + title + "@example.com");
        seedTicketFor(otherOwner, title, Ticket.Status.DONE, amount, "EUR", "food",
                LocalDate.ofInstant(NOW, ZoneOffset.UTC));
    }

    private void seedTicketFor(Long ownerId, String title, Ticket.Status status, String amount,
                               String currency, String category) {
        seedTicketFor(ownerId, title, status, amount, currency, category,
                LocalDate.ofInstant(NOW, ZoneOffset.UTC));
    }

    private void seedTicketFor(Long ownerId, String title, Ticket.Status status, String amount,
                               String currency, String category, LocalDate purchaseDate) {
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
                    ticketId, purchaseDate, category, new BigDecimal(amount), currency);
        }
    }
}