package com.ticketapp.bff.ai;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.ai.DocumentTextExtractionException;
import com.ticketapp.domain.ai.DocumentTextExtractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentTextExtractionSyncService}.
 *
 * <p>Pins the upload-time OCR contract:
 * <ul>
 *   <li>Successful OCR stamps the verbatim text on the ticket and
 *       persists via {@link TicketRepository#save(Object)}.</li>
 *   <li>Provider failures do NOT abort the upload — the ticket is
 *       returned unchanged, no save, a WARN logged. The
 *       structured-extraction scheduler still picks the ticket up.</li>
 *   <li>Empty / metadata-only tickets skip OCR entirely. PDFs and
 *       images both go through the same port — the service makes
 *       no MIME filter of its own.</li>
 *   <li>The {@code ocrText} field is cleared (to null) on the
 *       empty-reply path so a re-fetched ticket row can distinguish
 *       "OCR ran, empty" from "OCR never ran" downstream.</li>
 * </ul>
 */
class DocumentTextExtractionSyncServiceTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DocumentTextExtractor extractor;
    private TicketRepository tickets;
    private DocumentTextExtractionSyncService service;

    @BeforeEach
    void setUp() {
        extractor = mock(DocumentTextExtractor.class);
        tickets = mock(TicketRepository.class);
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new DocumentTextExtractionSyncService(extractor, tickets);
    }

    private static Ticket ticketWithFile(UUID id, String mime) {
        return new Ticket(id, OWNER, "r.png", "", Ticket.Status.OPEN,
                Instant.now(), Instant.now(),
                mime, "r.png", new byte[]{1, 2, 3}, null, 0, null, null);
    }

    @Test
    void successfulOcrOnImageStampsTextOnTicketAndPersists() throws Exception {
        UUID id = UUID.randomUUID();
        Ticket png = ticketWithFile(id, "image/png");
        when(extractor.extract(png.fileData(), "image/png"))
                .thenReturn("MERCADONA\nTOTAL 12.34");

        Ticket stamped = service.runOnUpload(png);

        assertThat(stamped.ocrText()).isEqualTo("MERCADONA\nTOTAL 12.34");
        verify(tickets).save(stamped);
    }

    @Test
    void successfulOcrOnPdfStampsTextOnTicketAndPersists() throws Exception {
        // The user asked for image and PDF to share the same OCR
        // pipeline. This test pins the service side: PDFs flow
        // through the same upload-time path, the only difference
        // (vs. an image) being the contentType the service passes
        // to the port.
        UUID id = UUID.randomUUID();
        Ticket pdf = ticketWithFile(id, "application/pdf");
        when(extractor.extract(pdf.fileData(), "application/pdf"))
                .thenReturn("MERCADONA\nTOTAL 12.34 EUR");

        Ticket stamped = service.runOnUpload(pdf);

        assertThat(stamped.ocrText()).isEqualTo("MERCADONA\nTOTAL 12.34 EUR");
        verify(tickets).save(stamped);
    }

    @Test
    void providerFailureReturnsOriginalTicketWithoutSaving() throws Exception {
        // OCR failure must not abort the upload (the file is already
        // persisted; the structured pipeline still picks the ticket
        // up). The service swallows the exception and returns the
        // original entity with ocrText still null.
        UUID id = UUID.randomUUID();
        Ticket png = ticketWithFile(id, "image/png");
        when(extractor.extract(png.fileData(), "image/png"))
                .thenThrow(new DocumentTextExtractionException(502, "MiniMax returned 502"));

        Ticket returned = service.runOnUpload(png);

        assertThat(returned.ocrText()).isNull();
        verify(tickets, never()).save(any());
    }

    @Test
    void unexpectedRuntimeExceptionAlsoLeavesTicketUntouched() throws Exception {
        // Belt-and-braces: a non-mapped SDK failure (e.g. an
        // unchecked IllegalStateException from a port bug) must not
        // crash the upload. The catch-all RuntimeException guard
        // absorbs it.
        UUID id = UUID.randomUUID();
        Ticket png = ticketWithFile(id, "image/png");
        when(extractor.extract(any(), anyString()))
                .thenThrow(new RuntimeException("SDK surprise"));

        Ticket returned = service.runOnUpload(png);

        assertThat(returned.ocrText()).isNull();
        verify(tickets, never()).save(any());
    }

    @Test
    void emptyReplyMakesOcrTextNullButStillPersists() throws Exception {
        // The provider returned no text (legitimate blank-receipt
        // outcome). The service explicitly stamps ocrText = null —
        // combined with V15's NULLable column this lets the read
        // path distinguish "OCR ran" (NULL row, attempts>0) from "pre-V15 row".
        UUID id = UUID.randomUUID();
        Ticket png = ticketWithFile(id, "image/png");
        when(extractor.extract(png.fileData(), "image/png")).thenReturn(null);

        Ticket stamped = service.runOnUpload(png);

        assertThat(stamped.ocrText()).isNull();
        verify(tickets).save(stamped);
    }

    @Test
    void metadataOnlyTicketsAreSkipped() throws Exception {
        // A ticket without fileData has nothing to OCR. Returning
        // the entity unchanged is correct — the dashboard will show
        // the "no preview" state for metadata-only rows regardless.
        UUID id = UUID.randomUUID();
        Ticket meta = new Ticket(id, OWNER, "title", "", Ticket.Status.OPEN,
                Instant.now(), Instant.now(),
                null, null, null, null, 0, null, null);

        Ticket returned = service.runOnUpload(meta);

        assertThat(returned.ocrText()).isNull();
        verify(extractor, never()).extract(any(), anyString());
        verify(tickets, never()).save(any());
    }

    @Test
    void nullContentTypeIsSkippedQuietly() throws Exception {
        // The controller's MIME whitelist guarantees a non-null
        // contentType on every upload; a future controller-path
        // bypass that lands null must skip OCR rather than throw.
        UUID id = UUID.randomUUID();
        Ticket broken = new Ticket(id, OWNER, "x", "", Ticket.Status.OPEN,
                Instant.now(), Instant.now(),
                null, "name.bin", new byte[]{1, 2, 3}, null, 0, null, null);

        Ticket returned = service.runOnUpload(broken);

        assertThat(returned.ocrText()).isNull();
        verify(extractor, never()).extract(any(), anyString());
        verify(tickets, never()).save(any());
    }
}
