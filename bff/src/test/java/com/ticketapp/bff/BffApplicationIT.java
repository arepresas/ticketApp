package com.ticketapp.bff;

import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: real Postgres (testcontainers) + Liquibase migration
 * + full Spring Boot context + HTTP via {@link WebTestClient}.
 *
 * <p>Smoke checks only — the dedicated {@code TicketControllerIT} covers the
 * full multipart upload flow and the auth path; this stays focused on
 * context wiring, health, and the status-change/delete endpoints which
 * are unchanged from before the upload migration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
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
    com.ticketapp.bff.api.TicketController ticketController;

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
        Ticket seeded = tickets.save(Ticket.open("smoke", "smoke test"));

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
        tickets.save(Ticket.open("list-seed", "x"));

        web().get().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBodyList(Ticket.class)
                .value(list -> assertThat(list).extracting(Ticket::title).contains("list-seed"));
    }
}