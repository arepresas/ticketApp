package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthController;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT coverage for {@code GET /api/tickets/pending}.
 *
 * <p>The endpoint must:
 * <ul>
 *   <li>Return only the authenticated user's
 *       {@link Ticket.Status#OPEN}, {@link Ticket.Status#IN_PROGRESS},
 *       and {@link Ticket.Status#ON_ERROR} tickets, newest first.
 *       ON_ERROR is included so a failed ticket is visible on the
 *       operator's work queue — it needs manual retry or
 *       cancellation, both surfaced via the same UI.</li>
 *   <li>Exclude tickets in terminal states
 *       ({@link Ticket.Status#DONE},
 *       {@link Ticket.Status#CANCELLED}).</li>
 *   <li>Return an empty list (not 404) when no pending tickets exist.</li>
 *   <li>Reject unauthenticated callers with 401.</li>
 *   <li>Never echo the stored {@code fileData} bytes in the response.</li>
 * </ul>
 *
 * <p>Ownership scoping: seeded tickets are owned by the user the test
 * logs in as, so they appear under {@code /pending}. A second user
 * with their own tickets is exercised by
 * {@link TicketOwnershipIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestGoogleConfig.class)
class PendingTicketsIT {

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
    JdbcTemplate jdbc;

    /**
     * Wipe the seeded rows before every test. The Testcontainers
     * Postgres container is reused across tests in this class (static
     * `@Container` + `@ServiceConnection`), so without a clean slate a
     * test that expects an empty list would see the rows seeded by
     * an earlier test in the same run. We also wipe the users table
     * so each test's {@code loginAndGetToken} creates a fresh
     * {@link com.ticketapp.bff.auth.AuthenticatedUser} with a stable,
     * predictable id (UUID derived from the google-sub).
     */
    @BeforeEach
    void cleanTickets() {
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

    /** Log in with the stub token, return the Bearer to use on
     * subsequent requests. */
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

    /** Resolve the logged-in user's id by looking up the stub
     * google-sub. After {@link #loginAndGetToken()} the user row
     * exists, so this never returns empty in a test. */
    private UUID ownerId() {
        return users.findByGoogleSub("google-sub-stub")
                .orElseThrow(() -> new IllegalStateException(
                        "test setup: user row not created — call loginAndGetToken first"))
                .id();
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        web().get().uri("/api/tickets/pending")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void returnsEmptyListWhenNoPendingTickets() {
        String token = loginAndGetToken();
        UUID owner = ownerId();
        // Seed only terminal rows for the owner — none should be returned.
        tickets.save(Ticket.open(owner, "done.pdf", "done",
                "application/pdf", "done.pdf", new byte[]{1})
                .withStatus(Ticket.Status.DONE));
        tickets.save(Ticket.open(owner, "cancelled.pdf", "cancelled",
                "application/pdf", "cancelled.pdf", new byte[]{2})
                .withStatus(Ticket.Status.CANCELLED));

        List<?> body = web().get().uri("/api/tickets/pending")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(List.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isEmpty();
    }

    @Test
    void returnsOwnersOpenInProgressAndOnErrorTickets() {
        String token = loginAndGetToken();
        UUID owner = ownerId();
        Ticket openOld = tickets.save(Ticket.open(owner, "old-open.pdf", "first",
                "application/pdf", "old.pdf", new byte[]{1}));
        Ticket inProgress = tickets.save(Ticket.open(owner, "wip.pdf", "second",
                "application/pdf", "wip.pdf", new byte[]{2}));
        Ticket errored = tickets.save(Ticket.open(owner, "broken.pdf", "third",
                "application/pdf", "broken.pdf", new byte[]{3}));
        Ticket done = tickets.save(Ticket.open(owner, "done.pdf", "fourth",
                "application/pdf", "done.pdf", new byte[]{4}));
        Ticket cancelled = tickets.save(Ticket.open(owner, "cancelled.pdf", "fifth",
                "application/pdf", "cancelled.pdf", new byte[]{5}));

        tickets.save(inProgress.withStatus(Ticket.Status.IN_PROGRESS));
        tickets.save(errored.markError("MiniMax returned 500"));
        tickets.save(done.withStatus(Ticket.Status.DONE));
        tickets.save(cancelled.withStatus(Ticket.Status.CANCELLED));

        List<TicketController.TicketResponse> body = web().get().uri("/api/tickets/pending")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).extracting(TicketController.TicketResponse::id)
                .containsExactlyInAnyOrder(openOld.id(), inProgress.id(), errored.id())
                .doesNotContain(done.id(), cancelled.id());
        assertThat(body).extracting(TicketController.TicketResponse::status)
                .containsOnly(
                        Ticket.Status.OPEN,
                        Ticket.Status.IN_PROGRESS,
                        Ticket.Status.ON_ERROR);
        // Bytes never leave the wire — sizeBytes is the only signal.
        assertThat(body).allSatisfy(t -> assertThat(t.sizeBytes()).isPositive());

        // Sanity-check the freshly-seeded rows so a broken assertion
        // above doesn't hide a missing-seed bug.
        assertThat(tickets.findById(openOld.id(), owner)).hasValueSatisfying(
                t -> assertThat(t.status()).isEqualTo(Ticket.Status.OPEN));
        assertThat(tickets.findById(inProgress.id(), owner)).hasValueSatisfying(
                t -> assertThat(t.status()).isEqualTo(Ticket.Status.IN_PROGRESS));
        assertThat(tickets.findById(errored.id(), owner)).hasValueSatisfying(
                t -> assertThat(t.status()).isEqualTo(Ticket.Status.ON_ERROR));
    }

    @Test
    void onErrorTicketsAppearInPending() {
        // ON_ERROR needs manual action (retry via PATCH, or cancel),
        // so it surfaces in the work queue next to the genuine
        // pending tickets. The error_message travels in the response
        // so the operator can read why without opening detail.
        String token = loginAndGetToken();
        UUID owner = ownerId();
        Ticket failed = tickets.save(Ticket.open(owner, "broken.pdf", "still pending",
                "application/pdf", "broken.pdf", new byte[]{1})
                .markError("MiniMax returned 500: bad gateway"));

        List<TicketController.TicketResponse> body = web().get().uri("/api/tickets/pending")
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).id()).isEqualTo(failed.id());
        assertThat(body.get(0).status()).isEqualTo(Ticket.Status.ON_ERROR);
        assertThat(body.get(0).errorMessage()).isEqualTo("MiniMax returned 500: bad gateway");
    }

    @Test
    void errorMessageRoundTripsThroughWire() {
        // The errorMessage column must travel through GET /api/tickets
        // so the dashboard can show the failure reason next to a
        // ticket in ON_ERROR. This pins both the mapper (column → domain)
        // and the controller mapper (domain → JSON → wire).
        String token = loginAndGetToken();
        UUID owner = ownerId();
        Ticket failed = tickets.save(Ticket.open(owner, "broken.pdf", "broken",
                "application/pdf", "broken.pdf", new byte[]{1})
                .markError("MiniMax returned 502: bad gateway"));

        TicketController.TicketResponse body = web().get().uri("/api/tickets/" + failed.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(Ticket.Status.ON_ERROR);
        assertThat(body.errorMessage()).isEqualTo("MiniMax returned 502: bad gateway");
    }

    @Test
    void patchingBackToOpenClearsErrorMessage() {
        // Manual retry flow: PATCH /api/tickets/{id}/status → OPEN
        // must clear the stored errorMessage via Ticket.withStatus so
        // the dashboard no longer shows a stale failure reason after
        // the operator re-enqueues the ticket.
        String token = loginAndGetToken();
        UUID owner = ownerId();
        Ticket failed = tickets.save(Ticket.open(owner, "retry.pdf", "retry me",
                "application/pdf", "retry.pdf", new byte[]{1})
                .markError("MiniMax returned 500"));

        TicketController.TicketResponse patched = web().patch()
                .uri("/api/tickets/" + failed.id() + "/status")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TicketController.ChangeStatusRequest(Ticket.Status.OPEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(patched).isNotNull();
        assertThat(patched.status()).isEqualTo(Ticket.Status.OPEN);
        assertThat(patched.errorMessage()).isNull();

        // Confirm the clear reached the DB, not just the DTO.
        assertThat(tickets.findById(failed.id(), owner)).hasValueSatisfying(t -> {
            assertThat(t.status()).isEqualTo(Ticket.Status.OPEN);
            assertThat(t.errorMessage()).isNull();
        });
    }
}