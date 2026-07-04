package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.bff.auth.AuthenticatedUser;
import com.ticketapp.bff.auth.SessionTokenService;
import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.bff.auth.UserRepository;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-tenant isolation IT for {@link TicketController}.
 *
 * <p>Pins the contract that one user can never read, mutate, or delete
 * another user's tickets — regardless of which endpoint, regardless of
 * who created the ticket. The repository layer refuses the cross-tenant
 * read with an empty result, and the controller translates that to
 * 404 so existence itself is not leaked.
 *
 * <p>Setup: user A logs in via the Google stub. User B is created
 * directly via {@link UserRepository#upsertFromGoogle} (bypassing the
 * Google verifier) and authenticated via
 * {@link SessionTokenService#issue} — that mints a real JWT we can
 * attach to the Bearer header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestGoogleConfig.class)
class TicketOwnershipIT {

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
    TicketRepository tickets;

    @Autowired
    UserRepository users;

    @Autowired
    SessionTokenService sessions;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
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

    /** Log in with the Google stub — returns a Bearer for the
     * implicitly-upserted "stub" user. */
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

    /** Create a second user directly (bypassing Google) and mint a
     * real session JWT for it. */
    private String createUserAndGetToken(String googleSub, String email, String name) {
        AuthenticatedUser user = users.upsertFromGoogle(
                googleSub, email, name, null);
        return sessions.issue(user).token();
    }

    private Ticket seedTicket(java.util.UUID ownerId, String title) {
        return tickets.save(Ticket.open(ownerId, title, "test",
                "application/pdf", title + ".pdf", new byte[]{1, 2, 3}));
    }

    @Test
    void listReturnsOnlyTheCallersTickets() {
        // User A: 2 tickets. User B: 1 ticket. A's list must not
        // contain B's ticket.
        String tokenA = loginAndGetToken();
        AuthenticatedUser userA = users.findByGoogleSub("google-sub-stub").orElseThrow();
        // User B must be created through upsertFromGoogle so its
        // session id matches the row id used on the ticket.
        AuthenticatedUser userB = users.upsertFromGoogle(
                "google-sub-other", "other@example.com", "Other User", null);
        String tokenB = sessions.issue(userB).token();

        Ticket a1 = seedTicket(userA.id(), "a1.pdf");
        Ticket a2 = seedTicket(userA.id(), "a2.pdf");
        Ticket b1 = seedTicket(userB.id(), "b1.pdf");

        // User A: only a1 and a2.
        List<TicketController.TicketResponse> listA = web().get().uri("/api/tickets")
                .header("authorization", "Bearer " + tokenA)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(listA).extracting(TicketController.TicketResponse::id)
                .containsExactlyInAnyOrder(a1.id(), a2.id())
                .doesNotContain(b1.id());

        // User B: only b1.
        List<TicketController.TicketResponse> listB = web().get().uri("/api/tickets")
                .header("authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(listB).extracting(TicketController.TicketResponse::id)
                .containsExactly(b1.id())
                .doesNotContain(a1.id(), a2.id());
    }

    @Test
    void pendingReturnsOnlyTheCallersTickets() {
        String tokenA = loginAndGetToken();
        AuthenticatedUser userA = users.findByGoogleSub("google-sub-stub").orElseThrow();
        AuthenticatedUser userB = users.upsertFromGoogle(
                "google-sub-other", "other@example.com", "Other User", null);
        String tokenB = sessions.issue(userB).token();

        Ticket aOpen = seedTicket(userA.id(), "a-open.pdf");
        Ticket bOpen = seedTicket(userB.id(), "b-open.pdf");

        List<TicketController.TicketResponse> pendingA = web()
                .get().uri("/api/tickets/pending")
                .header("authorization", "Bearer " + tokenA)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(pendingA).extracting(TicketController.TicketResponse::id)
                .containsExactlyInAnyOrder(aOpen.id())
                .doesNotContain(bOpen.id());

        List<TicketController.TicketResponse> pendingB = web()
                .get().uri("/api/tickets/pending")
                .header("authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(pendingB).extracting(TicketController.TicketResponse::id)
                .containsExactly(bOpen.id())
                .doesNotContain(aOpen.id());
    }

    @Test
    void getReturns404ForOtherUsersTicket() {
        // Cross-tenant read: user B requests user A's ticket. The
        // repository filters on owner, the controller answers 404.
        // Existence is NOT leaked (we don't say 403 — that would
        // tell B that the id exists in some other tenant).
        String tokenA = loginAndGetToken();
        AuthenticatedUser userA = users.findByGoogleSub("google-sub-stub").orElseThrow();
        String tokenB = createUserAndGetToken("google-sub-other",
                "other@example.com", "Other User");

        Ticket aOnly = seedTicket(userA.id(), "secret.pdf");

        web().get().uri("/api/tickets/{id}", aOnly.id())
                .header("authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isNotFound();

        // And the owner can still read it normally.
        web().get().uri("/api/tickets/{id}", aOnly.id())
                .header("authorization", "Bearer " + tokenA)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void changeStatusReturns404ForOtherUsersTicket() {
        loginAndGetToken();
        AuthenticatedUser userA = users.findByGoogleSub("google-sub-stub").orElseThrow();
        String tokenB = createUserAndGetToken("google-sub-other",
                "other@example.com", "Other User");

        Ticket aOnly = seedTicket(userA.id(), "secret.pdf");

        web().patch().uri("/api/tickets/{id}/status", aOnly.id())
                .header("authorization", "Bearer " + tokenB)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new TicketController.ChangeStatusRequest(Ticket.Status.CANCELLED))
                .exchange()
                .expectStatus().isNotFound();

        // A's ticket status unchanged.
        assertThat(tickets.findById(aOnly.id(), userA.id()))
                .hasValueSatisfying(t -> assertThat(t.status()).isEqualTo(Ticket.Status.OPEN));
    }

    @Test
    void deleteReturns404ForOtherUsersTicket() {
        loginAndGetToken();
        AuthenticatedUser userA = users.findByGoogleSub("google-sub-stub").orElseThrow();
        String tokenB = createUserAndGetToken("google-sub-other",
                "other@example.com", "Other User");

        Ticket aOnly = seedTicket(userA.id(), "secret.pdf");

        web().delete().uri("/api/tickets/{id}", aOnly.id())
                .header("authorization", "Bearer " + tokenB)
                .exchange()
                .expectStatus().isNotFound();

        // A's ticket still exists.
        assertThat(tickets.findById(aOnly.id(), userA.id())).isPresent();
    }

    @Test
    void schedulerProcessesTicketsFromAnyOwner() {
        // System path: the cron-driven scheduler operates without a
        // user session. findOpenForExtraction returns tickets across
        // all owners. This pins the system-scope path stays open while
        // the controller paths stay owner-scoped.
        // Login to seed user A via the standard upsert flow.
        loginAndGetToken();
        AuthenticatedUser userA = users.findByGoogleSub("google-sub-stub").orElseThrow();
        // Seed user B directly so the FK target is reachable.
        AuthenticatedUser userB = users.upsertFromGoogle(
                "google-sub-scheduler", "sched@example.com", "Sched", null);

        Ticket aOpen = seedTicket(userA.id(), "a-open.pdf");
        Ticket bOpen = seedTicket(userB.id(), "b-open.pdf");

        // System-scope: returns both.
        List<Ticket> open = tickets.findOpenForExtraction(10);
        assertThat(open).extracting(Ticket::id)
                .contains(aOpen.id(), bOpen.id());
    }
}