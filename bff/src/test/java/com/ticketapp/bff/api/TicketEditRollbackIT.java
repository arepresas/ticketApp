package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.TestGoogleConfig;
import com.ticketapp.failureinjection.FailingExtractionUpdateConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code @Transactional} boundary on
 * {@code TicketEditService.applyEdit} covers a late extraction failure.
 *
 * <p>The missing-extraction 404 path in {@link TicketEditControllerIT}
 * can be satisfied by an implementation that checks for the row before
 * writing anything — no transaction required. This test closes that
 * hole: a ticket <em>with</em> an extraction row is edited, the
 * metadata write lands, and the extraction update then fails. Both
 * tables must come back unchanged; if the writes were separate commits
 * the new title would survive.
 *
 * <p>The injected failure also asserts the ordering the service
 * documents (metadata first, extraction second): it reads the title
 * inside the transaction and fails loudly if the new value is not there
 * yet, so a reordered implementation cannot pass for the wrong reason.
 *
 * @see FailingExtractionUpdateConfig — why the injection lives outside
 *      the component-scanned {@code com.ticketapp.bff} tree
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import({TestGoogleConfig.class, FailingExtractionUpdateConfig.class})
class TicketEditRollbackIT extends AbstractTicketEditIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    .withDatabaseName("ticketapp")
                    .withUsername("ticketapp")
                    .withPassword("ticketapp");

    @Test
    @DisplayName("rolls back the metadata write when the extraction update fails")
    void rollsBackBothWrites() {
        long ticketId = seedTicketWithExtraction(
                "Original title", "Original description", "Mercadona", "25.28");
        Map<String, String> extractionBefore = extractionSnapshot(ticketId);

        web().put().uri("/api/tickets/" + ticketId)
                .header("authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(editBody("Weekly shop", "edited by hand", "Should Not Persist", "45.60"))
                .exchange()
                .expectStatus().is5xxServerError();

        assertThat(storedTitle(ticketId))
                .as("metadata write must not survive the failed extraction update")
                .isEqualTo("Original title");
        assertThat(storedDescription(ticketId)).isEqualTo("Original description");
        assertThat(extractionSnapshot(ticketId)).isEqualTo(extractionBefore);
    }
}