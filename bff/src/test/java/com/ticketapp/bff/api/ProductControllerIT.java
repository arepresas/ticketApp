package com.ticketapp.bff.api;

import com.ticketapp.bff.api.dto.ProductSearchResponse;
import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.bff.auth.TestGoogleConfig;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for {@link ProductController#search(String, Integer)}.
 * Spins up the shared Postgres, seeds a few products through direct
 * JDBC, and exercises the wire shape via {@link WebTestClient} just
 * like the SPA does.
 *
 * <p>Mirrors {@link ShopControllerIT} — catalogue endpoints share
 * the same auth-gating posture and the same test scaffolding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestGoogleConfig.class)
class ProductControllerIT {

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

    private WebTestClient web() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM line_tickets");
        jdbc.update("DELETE FROM prices");
        jdbc.update("DELETE FROM products");
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

    private long seedProduct(String name, String unit) {
        return jdbc.queryForObject("""
                INSERT INTO products (name, normalised_name, unit, created_at)
                VALUES (?, ?, ?, ?) RETURNING id""", Long.class, name, name.trim().toLowerCase(), unit,
                Timestamp.from(Instant.now()));
    }

    @Test
    void searchReturnsMatchingProducts() {
        String token = loginAndGetToken();
        long breadId = seedProduct("Bread", null);
        long bananaId = seedProduct("Banana", null);
        seedProduct("Milk", null);

        List<ProductSearchResponse> body = web()
                .get().uri(uri -> uri.path("/api/products/search").queryParam("name", "B").build())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductSearchResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).extracting(ProductSearchResponse::id)
                .containsExactlyInAnyOrder(breadId, bananaId);
    }

    @Test
    void searchLabelIncludesUnitWhenPresent() {
        String token = loginAndGetToken();
        seedProduct("Bread", "kg");

        List<ProductSearchResponse> body = web()
                .get().uri(uri -> uri.path("/api/products/search").queryParam("name", "bread").build())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductSearchResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).label()).isEqualTo("Bread (kg)");
        assertThat(body.get(0).unit()).isEqualTo("kg");
    }

    @Test
    void searchRespectsLimit() {
        String token = loginAndGetToken();
        for (String n : new String[]{"Apple", "Avocado", "Almond", "Artichoke"}) {
            seedProduct(n, null);
        }

        List<ProductSearchResponse> body = web()
                .get().uri(uri -> uri
                        .path("/api/products/search")
                        .queryParam("name", "A")
                        .queryParam("limit", 2)
                        .build())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductSearchResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).hasSize(2);
    }

    @Test
    void searchClampsLimitToMax() {
        // A buggy client asking for limit=9999 must not pull the
        // whole catalogue. The endpoint clamps to MAX_LIMIT.
        String token = loginAndGetToken();
        for (int i = 0; i < 30; i++) {
            seedProduct("Apple " + i, null);
        }

        List<ProductSearchResponse> body = web()
                .get().uri(uri -> uri
                        .path("/api/products/search")
                        .queryParam("name", "Apple")
                        .queryParam("limit", 9999)
                        .build())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductSearchResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).hasSizeLessThanOrEqualTo(ProductController.MAX_LIMIT);
    }

    @Test
    void searchReturnsEmptyForBlankName() {
        String token = loginAndGetToken();
        seedProduct("Bread", null);

        List<ProductSearchResponse> body = web()
                .get().uri(uri -> uri
                        .path("/api/products/search")
                        .queryParam("name", "   ")
                        .build())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductSearchResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).isEmpty();
    }

    @Test
    void searchRejectsUnauthenticated() {
        web().get().uri(uri -> uri.path("/api/products/search").queryParam("name", "B").build())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);
}
