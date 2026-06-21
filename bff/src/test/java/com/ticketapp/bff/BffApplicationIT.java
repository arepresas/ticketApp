package com.ticketapp.bff;

import com.ticketapp.bff.api.TicketController;
import com.ticketapp.domain.Ticket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test: real Postgres (testcontainers) + Liquibase migration
 * + full Spring Boot context + HTTP via {@link WebTestClient}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
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
    TicketController ticketController;

    private WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
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
    void createGetChangeDeleteTicketViaHttp() {
        // CREATE
        Ticket created = web().post().uri("/api/tickets")
                .bodyValue(new TicketController.CreateTicketRequest("Via HTTP", "it works"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Ticket.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo(Ticket.Status.OPEN);

        // GET
        web().get().uri("/api/tickets/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Ticket.class)
                .value(t -> assertThat(t.title()).isEqualTo("Via HTTP"));

        // CHANGE STATUS
        web().patch().uri("/api/tickets/{id}/status", created.id())
                .bodyValue(new TicketController.ChangeStatusRequest(Ticket.Status.IN_PROGRESS))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Ticket.class)
                .value(t -> assertThat(t.status()).isEqualTo(Ticket.Status.IN_PROGRESS));

        // DELETE
        web().delete().uri("/api/tickets/{id}", created.id())
                .exchange()
                .expectStatus().isNoContent();

        // 404 after delete
        web().get().uri("/api/tickets/{id}", created.id())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void listReturnsAtLeastOneAfterCreate() {
        web().post().uri("/api/tickets")
                .bodyValue(new TicketController.CreateTicketRequest("list-test", "x"))
                .exchange().expectStatus().isCreated();

        web().get().uri("/api/tickets")
                .exchange().expectStatus().isOk()
                .expectBodyList(Ticket.class)
                .value(list -> assertThat(list).extracting(Ticket::title).contains("list-test"));
    }
}
