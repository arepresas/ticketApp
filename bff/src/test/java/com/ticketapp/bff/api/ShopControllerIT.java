package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.bff.auth.TestGoogleConfig;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for {@link ShopController}: GET and PATCH
 * {@code /api/shops/{id}}. Shops are seeded directly via JDBC
 * rather than going through the extraction normaliser, so the
 * controller can be exercised in isolation — the normaliser's own
 * wiring is covered by {@link TicketControllerIT}'s
 * {@code markAsDone*} tests.
 *
 * <p>Each test logs in fresh (a new google-sub is generated per
 * test invocation by {@link TestGoogleConfig}), so the user table
 * ends up with one row per test. That's fine: the shop catalogue
 * is global, not user-scoped, so any authenticated user can read
 * or patch any shop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@Import(TestGoogleConfig.class)
class ShopControllerIT {

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
    void cleanTables() {
        // CASCADE from shops isn't enough — we want a clean slate
        // so shop id stability across tests is predictable.
        jdbc.update("DELETE FROM line_tickets");
        jdbc.update("DELETE FROM prices");
        jdbc.update("DELETE FROM shops");
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

    private UUID seedShop(String name, String addressLine, String city,
                          String country, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shops
                    (id, name, normalised_name, address_line, city, country, phone, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, name, name.toLowerCase(), addressLine, city, country, phone,
                java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    @Test
    void getReturnsExistingShop() {
        String token = loginAndGetToken();
        UUID id = seedShop("Mercadona", "Calle Mayor 1", "Madrid", "ES", "+34 911 22 33 44");

        web().get().uri("/api/shops/{id}", id)
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ShopController.ShopResponse.class)
                .value(resp -> {
                    assertThat(resp.id()).isEqualTo(id);
                    assertThat(resp.name()).isEqualTo("Mercadona");
                    assertThat(resp.normalisedName()).isEqualTo("mercadona");
                    assertThat(resp.addressLine()).isEqualTo("Calle Mayor 1");
                    assertThat(resp.city()).isEqualTo("Madrid");
                    assertThat(resp.country()).isEqualTo("ES");
                    assertThat(resp.phone()).isEqualTo("+34 911 22 33 44");
                });
    }

    @Test
    void getReturns404WhenShopUnknown() {
        String token = loginAndGetToken();
        web().get().uri("/api/shops/{id}", UUID.randomUUID())
                .header("authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getRejectsUnauthenticated() {
        UUID id = seedShop("Dia", null, null, null, null);
        web().get().uri("/api/shops/{id}", id)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void patchAppliesAllFieldsInOneCall() {
        String token = loginAndGetToken();
        UUID id = seedShop("Carrefour", null, null, null, null);

        web().patch().uri("/api/shops/{id}", id)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "addressLine", "Avenida Diagonal 3",
                        "postalCode", "08001",
                        "city", "Barcelona",
                        "country", "ES",
                        "phone", "+34 930 11 22 33",
                        "taxId", "B12345678",
                        "website", "https://carrefour.es"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ShopController.ShopResponse.class)
                .value(resp -> {
                    assertThat(resp.addressLine()).isEqualTo("Avenida Diagonal 3");
                    assertThat(resp.postalCode()).isEqualTo("08001");
                    assertThat(resp.city()).isEqualTo("Barcelona");
                    assertThat(resp.country()).isEqualTo("ES");
                    assertThat(resp.phone()).isEqualTo("+34 930 11 22 33");
                    assertThat(resp.taxId()).isEqualTo("B12345678");
                    assertThat(resp.website()).isEqualTo("https://carrefour.es");
                });
    }

    @Test
    void patchAppliesOnlySuppliedFields() {
        String token = loginAndGetToken();
        UUID id = seedShop("Lidl",
                "Old Street 1", "Madrid", "ES", "+34 900 00 00 00");

        // Patch only phone — the other contact fields must stay
        // untouched. This is the "set one field" UI flow the
        // dashboard uses most of the time.
        web().patch().uri("/api/shops/{id}", id)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phone", "+34 901 00 00 00"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ShopController.ShopResponse.class)
                .value(resp -> {
                    assertThat(resp.phone()).isEqualTo("+34 901 00 00 00");
                    assertThat(resp.addressLine()).isEqualTo("Old Street 1");
                    assertThat(resp.city()).isEqualTo("Madrid");
                    assertThat(resp.country()).isEqualTo("ES");
                });
    }

    @Test
    void patchRejectsInvalidCountryCode() {
        String token = loginAndGetToken();
        UUID id = seedShop("Lidl", null, null, null, null);

        web().patch().uri("/api/shops/{id}", id)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("country", "ESP"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void patchRejectsEmptyBody() {
        String token = loginAndGetToken();
        UUID id = seedShop("Lidl", null, null, null, null);

        web().patch().uri("/api/shops/{id}", id)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void patchReturns404WhenShopUnknown() {
        String token = loginAndGetToken();
        web().patch().uri("/api/shops/{id}", UUID.randomUUID())
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phone", "+34 900 00 00 00"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void patchRejectsUnauthenticated() {
        UUID id = seedShop("Aldi", null, null, null, null);
        web().patch().uri("/api/shops/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phone", "+34 900 00 00 00"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void persistedRowMatchesResponse() {
        // Round-trip: PATCH, then read the row back via SQL and
        // confirm the bytes on disk match what the controller
        // returned. Catches any SELECT/UPSERT drift between the
        // wire response and the persistence layer.
        String token = loginAndGetToken();
        UUID id = seedShop("Consum", null, null, null, null);

        web().patch().uri("/api/shops/{id}", id)
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "addressLine", "Plaza del Ayuntamiento 5",
                        "postalCode", "46002",
                        "city", "Valencia",
                        "country", "ES",
                        "phone", "+34 963 00 00 00",
                        "taxId", "C76543210",
                        "website", "https://consum.es"))
                .exchange()
                .expectStatus().isOk();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT address_line, postal_code, city, country, phone, tax_id, website "
              + "FROM shops WHERE id = ?", id);
        assertThat(row.get("address_line")).isEqualTo("Plaza del Ayuntamiento 5");
        assertThat(row.get("postal_code")).isEqualTo("46002");
        assertThat(row.get("city")).isEqualTo("Valencia");
        assertThat(row.get("country")).isEqualTo("ES");
        assertThat(row.get("phone")).isEqualTo("+34 963 00 00 00");
        assertThat(row.get("tax_id")).isEqualTo("C76543210");
        assertThat(row.get("website")).isEqualTo("https://consum.es");
    }
}