package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthController;
import com.ticketapp.bff.auth.TestGoogleConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end IT for the ticket upload endpoint.
 *
 * <p>The flow under test mirrors the production path:
 * <ol>
 *   <li>Authenticate via {@code POST /api/auth/google} (mocked verifier)
 *       to obtain a Bearer session token.</li>
 *   <li>Send a {@code multipart/form-data} request to
 *       {@code POST /api/tickets} with the file part + a Bearer header.</li>
 *   <li>Assert the response carries the new ticket metadata (no bytes).</li>
 * </ol>
 *
 * <p>Round-tripping the stored bytes is verified separately by reading
 * the row back through {@link com.ticketapp.domain.TicketRepository} —
 * the wire response intentionally omits {@code fileData} (see
 * {@link TicketController.TicketResponse}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestGoogleConfig.class)
class TicketControllerIT {

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
    com.ticketapp.domain.TicketRepository tickets;

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

    private MultiValueMap<String, HttpEntity<?>> pdfMultipart(String filename,
                                                              byte[] bytes,
                                                              String contentType,
                                                              String description) {
        var bb = new MultipartBodyBuilder();
        bb.part("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }).header("Content-Type", contentType);
        if (description != null) {
            bb.part("description", description);
        }
        return bb.build();
    }

    @Test
    void rejectsUnauthenticatedUpload() {
        var body = pdfMultipart("receipt.pdf", "%PDF-1.4\n".getBytes(),
                MediaType.APPLICATION_PDF_VALUE, null);
        web().post().uri("/api/tickets")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsMissingFile() {
        String token = loginAndGetToken();
        // Valid multipart envelope but without the required `file` part.
        // Spring translates the missing-part failure to a 400 via
        // MissingServletRequestPartException; we exercise that path here.
        var bb = new MultipartBodyBuilder();
        bb.part("description", "hello");
        web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(bb.build()))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsUnsupportedMime() {
        String token = loginAndGetToken();
        var body = pdfMultipart("notes.txt", "hello".getBytes(),
                MediaType.TEXT_PLAIN_VALUE, null);
        web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rejectsFileAboveTenMb() {
        String token = loginAndGetToken();
        byte[] oversized = new byte[(int) TicketController.MAX_FILE_BYTES + 1];
        var body = pdfMultipart("big.pdf", oversized, MediaType.APPLICATION_PDF_VALUE, null);
        web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void acceptsPdfAndStoresBytesInDb() {
        String token = loginAndGetToken();
        byte[] bytes = "%PDF-1.4\n%hello world\n%%EOF\n".getBytes();
        var body = pdfMultipart("receipt.pdf", bytes,
                MediaType.APPLICATION_PDF_VALUE, "Lunch at Mercadona");

        var response = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(com.ticketapp.domain.Ticket.Status.OPEN);
        assertThat(response.title()).isEqualTo("receipt.pdf");
        assertThat(response.description()).isEqualTo("Lunch at Mercadona");
        assertThat(response.contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(response.fileName()).isEqualTo("receipt.pdf");
        assertThat(response.sizeBytes()).isEqualTo(bytes.length);
        // Bytes are NOT echoed back on the wire — they're in the DB.
        assertThat(response.sizeBytes()).isPositive();

        // Verify the bytes round-tripped through the DB.
        var persisted = tickets.findById(response.id());
        assertThat(persisted).isPresent();
        assertThat(persisted.get().fileData()).isEqualTo(bytes);
        assertThat(persisted.get().contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
    }

    @Test
    void acceptsImageUpload() {
        String token = loginAndGetToken();
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        var body = pdfMultipart("photo.png", pngBytes, "image/png", null);

        var response = web().post().uri("/api/tickets")
                .header("authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TicketController.TicketResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.fileName()).isEqualTo("photo.png");
    }
}