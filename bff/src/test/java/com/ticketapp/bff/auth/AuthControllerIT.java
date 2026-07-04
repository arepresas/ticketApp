package com.ticketapp.bff.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for the auth surface:
 *   POST /api/auth/google  → 200 + session token
 *   GET  /api/auth/me      → 200 with the same user, with Bearer
 *                           → 401 without Bearer
 *   POST /api/auth/logout  → 204; subsequent /me returns 401
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestGoogleConfig.class)
class AuthControllerIT {

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

    private WebTestClient web() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Autowired
    SessionRepository sessions;

    @Test
    void rejectsBlankIdToken() {
        web().post().uri("/api/auth/google")
                .bodyValue(new AuthController.GoogleLoginRequest(""))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsInvalidGoogleToken() {
        web().post().uri("/api/auth/google")
                .bodyValue(new AuthController.GoogleLoginRequest("bogus"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void exchangesValidGoogleTokenThenMeThenLogout() {
        // 1) exchange
        var resp = web().post().uri("/api/auth/google")
                .bodyValue(new AuthController.GoogleLoginRequest(TestGoogleConfig.VALID_TOKEN))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthController.SessionResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.token()).isNotBlank();
        assertThat(resp.user().email()).isEqualTo("stub@example.com");

        // 2) /me with Bearer → 200
        web().get().uri("/api/auth/me")
                .header("authorization", "Bearer " + resp.token())
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthController.UserDto.class)
                .value(u -> assertThat(u.email()).isEqualTo("stub@example.com"));

        // 3) /me without Bearer → 401
        web().get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();

        // 4) logout → 204
        web().post().uri("/api/auth/logout")
                .header("authorization", "Bearer " + resp.token())
                .exchange()
                .expectStatus().isNoContent();

        // 5) /me after logout → 401 (session revoked)
        web().get().uri("/api/auth/me")
                .header("authorization", "Bearer " + resp.token())
                .exchange()
                .expectStatus().isUnauthorized();
    }
}