package com.ticketapp.bff;

import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.bff.auth.AuthController;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: real Postgres (testcontainers) + Liquibase migration
 * + full Spring Boot context + HTTP via {@link WebTestClient}.
 *
 * <p>Smoke checks only — the dedicated {@code TicketControllerIT} covers the
 * full multipart upload flow and the auth path; this stays focused on
 * context wiring, health, and the status-change/delete endpoints which
 * are unchanged from before the upload migration.
 *
 * <p>Ownership scoping: seeded tickets carry the logged-in user's id
 * so the auth-protected endpoints can read them back. A separate
 * {@link com.ticketapp.bff.api.TicketOwnershipIT} exercises the
 * cross-tenant case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestGoogleConfig.class)
class BffApplicationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    .withDatabaseName("ticketapp")
                    .withUsername("ticketapp")
                    .withPassword("ticketapp")
                    // Postgres 18 stores data under a major-version subdir.
                    .withEnv("PGDATA", "/var/lib/postgresql/data/pgdata");

    @LocalServerPort
    int port;

    @Autowired
    TicketRepository tickets;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.ticketapp.bff.api.TicketController ticketController;

    @BeforeEach
    void cleanSlate() {
        // Wipe per-test so each scenario starts from a clean DB.
        // The Testcontainers container is reused across tests in the
        // class (static @Container + @ServiceConnection), so a clean
        // slate is required.
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

    /** After login, look up the persisted user id (UUID derived from
     * the google-sub in the upsert flow). */
    private UUID ownerId() {
        return users.findByGoogleSub("google-sub-stub")
                .orElseThrow(() -> new IllegalStateException(
                        "test setup: user row not created — call loginAndGetToken first"))
                .id();
    }

    @Test
    void contextLoadsAndControllerIsRegistered() {
        assertThat(ticketController).isNotNull();
    }

    @Test
    void healthEndpointIsUp() {
        web().get().uri("/actuator/health")
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    @Test
    void getChangeDeleteTicketViaHttp() {
        // Seed directly through the repository — the upload path is covered
        // by TicketControllerIT. This test exercises GET/PATCH/DELETE only.
        String token = loginAndGetToken();
        UUID owner = ownerId();
        Ticket seeded = tickets.save(Ticket.open(owner, "smoke", "smoke test"));

        // GET
        web().get().uri("/api/tickets/{id}", seeded.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Ticket.class)
                .value(t -> assertThat(t.title()).isEqualTo("smoke"));

        // CHANGE STATUS
        web().patch().uri("/api/tickets/{id}/status", seeded.id())
                .header("authorization", "Bearer " + token)
                .bodyValue(new com.ticketapp.bff.api.TicketController.ChangeStatusRequest(Ticket.Status.IN_PROGRESS))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Ticket.class)
                .value(t -> assertThat(t.status()).isEqualTo(Ticket.Status.IN_PROGRESS));

        // DELETE
        web().delete().uri("/api/tickets/{id}", seeded.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNoContent();

        // 404 after delete
        web().get().uri("/api/tickets/{id}", seeded.id())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void listReturnsAtLeastOneAfterSeed() {
        String token = loginAndGetToken();
        UUID owner = ownerId();
        tickets.save(Ticket.open(owner, "list-seed", "x"));

        web().get().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBodyList(Ticket.class)
                .value(list -> assertThat(list).extracting(Ticket::title).contains("list-seed"));
    }
}