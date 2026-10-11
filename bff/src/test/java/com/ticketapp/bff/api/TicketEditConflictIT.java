package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.failureinjection.ConcurrentWriterTicketRepositoryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the detail edit cannot silently overwrite a concurrent change.
 *
 * <p>The service reads the ticket, then writes it back. Without a
 * guard, a commit that lands between those two steps (another device's
 * status flip, the extraction scheduler touching the same row) would be
 * reverted to the stale snapshot this request loaded — exactly the
 * read-modify-write hazard a full-row save carries.
 *
 * <p>{@code JdbcTicketRepository.save} is version-guarded, so the race
 * surfaces as {@code OptimisticLockException}, which
 * {@code GlobalExceptionHandler} maps to a 409
 * {@code application/problem+json} telling the client to re-read and
 * retry. The injected {@link ConcurrentWriterTicketRepositoryConfig}
 * replays that interleaving deterministically.
 *
 * @see ConcurrentWriterTicketRepositoryConfig — why the injection lives
 *      outside the component-scanned {@code com.ticketapp.bff} tree
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({TestGoogleConfig.class, ConcurrentWriterTicketRepositoryConfig.class})
class TicketEditConflictIT extends AbstractTicketEditIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    .withDatabaseName("ticketapp")
                    .withUsername("ticketapp")
                    .withPassword("ticketapp");

    @Test
    @DisplayName("answers 409 ProblemDetail instead of overwriting the newer row")
    void conflictsInsteadOfOverwriting() {
        long ticketId = seedTicketWithExtraction(
                "Original title", "Original description", "Mercadona", "25.28");
        Map<String, String> extractionBefore = extractionSnapshot(ticketId);

        web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(editBody("Weekly shop", "edited by hand", "Should Not Persist", "45.60"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.title").isEqualTo("Concurrent modification")
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.code").exists();

        // The stale snapshot must not have landed. The version bump
        // the simulated writer performed is rolled back with the
        // failed transaction (it ran on the request's own connection),
        // so the row is left exactly as it was — nothing the refused
        // request touched survives.
        assertThat(storedTitle(ticketId)).isEqualTo("Original title");
        assertThat(storedDescription(ticketId)).isEqualTo("Original description");
        assertThat(storedVersion(ticketId)).isZero();
        assertThat(extractionSnapshot(ticketId)).isEqualTo(extractionBefore);
    }
}