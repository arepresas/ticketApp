package com.ticketapp.minimaxai;

import com.ticketapp.domain.ai.DocumentTextExtractionException;
import com.ticketapp.minimaxai.MiniMaxApiClient.MiniMaxApiException;
import com.ticketapp.minimaxai.autoconfigure.MinimaxAiProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MiniMaxDocumentTextExtractor}.
 *
 * <p>Pins the contract the BFF's
 * {@code DocumentTextExtractionSyncService} depends on:
 * <ul>
 *   <li>Image MIME types go straight to the vision-OCR
 *       ({@code transcribeImage}) call and the verbatim text is
 *       forwarded as-is.</li>
 *   <li>PDFs with selectable text short-circuit on
 *       {@link PdfTextExtractor#extract(byte[])} — no model
 *       round-trip, the PDFBox text is returned directly.</li>
 *   <li>PDFs with no selectable text rasterize the first page to
 *       PNG and funnel through the same vision-OCR call as
 *       images.</li>
 *   <li>Empty provider replies collapse to {@code null} so the BFF
 *       can stamp {@code ocr_text = NULL} and the read path can
 *       distinguish "OCR ran, empty" from "OCR never ran".</li>
 *   <li>Provider HTTP failures translate into
 *       {@link DocumentTextExtractionException} with the same HTTP
 *       status the client saw; {@link MiniMaxApiException}
 *       (I/O, parse) maps to status 0.</li>
 *   <li>Argument validation — non-empty bytes, non-blank MIME —
 *       surfaces as {@link IllegalArgumentException} so the BFF
 *       can debug a misconfigured call without seeing a wrapped
 *       provider exception.</li>
 *   <li>Unsupported MIME types fail with status 0 and a clear
 *       message rather than silently swallowing them.</li>
 * </ul>
 */
class MiniMaxDocumentTextExtractorTest {

    private static final byte[] ONE_PIXEL_PNG = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};

    private MiniMaxApiClient client;
    private PdfTextExtractor pdfExtractor;
    private MinimaxAiProperties properties;
    private MiniMaxDocumentTextExtractor extractor;

    @BeforeEach
    void setUp() {
        client = mock(MiniMaxApiClient.class);
        pdfExtractor = mock(PdfTextExtractor.class);
        properties = new MinimaxAiProperties(
                "https://api.minimax.io/v1", "sk-test", "MiniMax-M3", 30_000L);
        extractor = new MiniMaxDocumentTextExtractor(client, properties, pdfExtractor);
    }

    // ------------------------------------------------------------------
    // Image path
    // ------------------------------------------------------------------

    @Test
    void imageTextIsForwardedVerbatimToTheCaller() throws Exception {
        // The provider's verbatim transcription must reach the SPA
        // unchanged — the upload preview renders it directly below
        // the thumbnail, so a trimming/uppercasing rewrite would
        // mislead the user about what the model "saw".
        when(client.transcribeImage("MiniMax-M3", ONE_PIXEL_PNG, "image/png"))
                .thenReturn("Mercadona\nC/ Gran Vía 12\nTOTAL 12,34 EUR");

        String got = extractor.extract(ONE_PIXEL_PNG, "image/png");

        assertThat(got).isEqualTo("Mercadona\nC/ Gran Vía 12\nTOTAL 12,34 EUR");
        verify(pdfExtractor, never()).extract(ONE_PIXEL_PNG);
    }

    @Test
    void imageMimeIsCaseInsensitive() throws Exception {
        // Some browsers / clients send `IMAGE/PNG` rather than
        // `image/png`. The port normalises to lowercase before
        // branching so the vision path still picks up.
        when(client.transcribeImage("MiniMax-M3", ONE_PIXEL_PNG, "image/png"))
                .thenReturn("hi");

        assertThat(extractor.extract(ONE_PIXEL_PNG, "IMAGE/PNG")).isEqualTo("hi");
    }

    @Test
    void imageEmptyProviderReplyCollapsesToNull() throws Exception {
        when(client.transcribeImage("MiniMax-M3", ONE_PIXEL_PNG, "image/png"))
                .thenReturn("   \n  ");

        assertThat(extractor.extract(ONE_PIXEL_PNG, "image/png")).isNull();
    }

    // ------------------------------------------------------------------
    // PDF happy path (digital-born, text-extractable)
    // ------------------------------------------------------------------

    @Test
    void pdfWithSelectableTextSkipsTheVisionCall() throws Exception {
        // The cheap PDFBox route must win when the PDF has text —
        // no model round-trip, less token burn, faster response.
        byte[] pdfBytes = "%PDF-1.4 fake".getBytes();
        when(pdfExtractor.extract(pdfBytes))
                .thenReturn("MERCADONA\nTOTAL 12.34 EUR");

        String got = extractor.extract(pdfBytes, "application/pdf");

        assertThat(got).isEqualTo("MERCADONA\nTOTAL 12.34 EUR");
        verify(client, never()).transcribeImage(anyString(), any(), anyString());
        verify(pdfExtractor, never()).rasterizeFirstPageAsPng(any());
    }

    @Test
    void pdfEmptyTextFallsThroughToRasterizeAndVisionOcr() throws Exception {
        // Scanned / image-only PDF: PDFBox yields nothing, so the
        // extractor rasterizes the first page and feeds the PNG to
        // the same vision call that images use.
        byte[] pdfBytes = "%PDF-1.4 fake".getBytes();
        byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        when(pdfExtractor.extract(pdfBytes)).thenReturn("   ");
        when(pdfExtractor.rasterizeFirstPageAsPng(pdfBytes)).thenReturn(pngBytes);
        when(client.transcribeImage("MiniMax-M3", pngBytes, "image/png"))
                .thenReturn("OCR FROM RASTERIZED PNG");

        String got = extractor.extract(pdfBytes, "application/pdf");

        assertThat(got).isEqualTo("OCR FROM RASTERIZED PNG");
        verify(pdfExtractor).extract(pdfBytes);
        verify(pdfExtractor).rasterizeFirstPageAsPng(pdfBytes);
    }

    @Test
    void pdfZeroPageDocumentReturnsNull() throws Exception {
        // Edge case: a PDF whose header is valid but which carries
        // zero pages. PDFBox yields both blank text and a null
        // rasterize result — the extractor must surface null, not
        // blow up on the null PNG.
        byte[] pdfBytes = "%PDF-1.4 empty".getBytes();
        when(pdfExtractor.extract(pdfBytes)).thenReturn("");
        when(pdfExtractor.rasterizeFirstPageAsPng(pdfBytes)).thenReturn(null);

        assertThat(extractor.extract(pdfBytes, "application/pdf")).isNull();
        verify(client, never()).transcribeImage(anyString(), any(), anyString());
    }

    @Test
    void pdfBoxIoFailureSurfacesAsDomainExceptionWithStatusZero() throws Exception {
        // A malformed PDF is a user-supplied error, not a provider
        // failure. Status 0 lets the BFF log/return without
        // attributing it to MiniMax.
        byte[] pdfBytes = "%PDF-1.4 garbled".getBytes();
        when(pdfExtractor.extract(pdfBytes))
                .thenThrow(new java.io.IOException("malformed PDF trailer"));

        assertThatThrownBy(() -> extractor.extract(pdfBytes, "application/pdf"))
                .isInstanceOf(DocumentTextExtractionException.class)
                .satisfies(t -> {
                    DocumentTextExtractionException ex =
                            (DocumentTextExtractionException) t;
                    assertThat(ex.statusCode()).isZero();
                    assertThat(ex.getMessage()).contains("malformed PDF trailer");
                });
    }

    @Test
    void pdfRasterizeFailureSurfacesAsDomainExceptionWithStatusZero() throws Exception {
        // Image-only PDF where PDFBox text extraction succeeds as
        // empty AND the rasterize step itself fails. Both are
        // status-0 conditions — no provider call happened.
        byte[] pdfBytes = "%PDF-1.4 image-only".getBytes();
        when(pdfExtractor.extract(pdfBytes)).thenReturn("");
        when(pdfExtractor.rasterizeFirstPageAsPng(pdfBytes))
                .thenThrow(new java.io.IOException("PDFRenderer bombed"));

        assertThatThrownBy(() -> extractor.extract(pdfBytes, "application/pdf"))
                .isInstanceOf(DocumentTextExtractionException.class)
                .satisfies(t -> {
                    DocumentTextExtractionException ex =
                            (DocumentTextExtractionException) t;
                    assertThat(ex.statusCode()).isZero();
                    assertThat(ex.getMessage()).contains("rasterization failed");
                });
    }

    // ------------------------------------------------------------------
    // Provider-failure mapping
    // ------------------------------------------------------------------

    @Test
    void providerHttpErrorSurfacesAsDocumentTextExtractionExceptionWithStatus()
            throws Exception {
        when(client.transcribeImage("MiniMax-M3", ONE_PIXEL_PNG, "image/png"))
                .thenThrow(new MiniMaxApiException(502, "MiniMax returned 502: provider overloaded"));

        assertThatThrownBy(() -> extractor.extract(ONE_PIXEL_PNG, "image/png"))
                .isInstanceOf(DocumentTextExtractionException.class)
                .satisfies(t -> {
                    DocumentTextExtractionException ex =
                            (DocumentTextExtractionException) t;
                    assertThat(ex.statusCode()).isEqualTo(502);
                    assertThat(ex.getMessage()).contains("502");
                });
    }

    @Test
    void clientIoExceptionSurfacesWithStatusZero() throws Exception {
        when(client.transcribeImage("MiniMax-M3", ONE_PIXEL_PNG, "image/png"))
                .thenThrow(new java.io.IOException("DNS lookup failed"));

        assertThatThrownBy(() -> extractor.extract(ONE_PIXEL_PNG, "image/png"))
                .isInstanceOf(DocumentTextExtractionException.class)
                .satisfies(t -> {
                    DocumentTextExtractionException ex =
                            (DocumentTextExtractionException) t;
                    assertThat(ex.statusCode()).isZero();
                });
    }

    @Test
    void unexpectedRuntimeExceptionStillBecomesDocumentTextExtractionException()
            throws Exception {
        when(client.transcribeImage("MiniMax-M3", ONE_PIXEL_PNG, "image/png"))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> extractor.extract(ONE_PIXEL_PNG, "image/png"))
                .isInstanceOf(DocumentTextExtractionException.class)
                .satisfies(t -> {
                    DocumentTextExtractionException ex =
                            (DocumentTextExtractionException) t;
                    assertThat(ex.statusCode()).isZero();
                });
    }

    // ------------------------------------------------------------------
    // Argument validation
    // ------------------------------------------------------------------

    @Test
    void emptyBytesAreRejectedSynchronously() {
        assertThatThrownBy(() -> extractor.extract(new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> extractor.extract(null, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankContentTypeIsRejectedSynchronously() {
        assertThatThrownBy(() -> extractor.extract(ONE_PIXEL_PNG, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> extractor.extract(ONE_PIXEL_PNG, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedContentTypeFailsClosed() {
        // The controller whitelist already rejects non-image, non-PDF
        // uploads — a future controller-path bypass must surface as
        // a domain exception rather than a silent null.
        assertThatThrownBy(() -> extractor.extract(ONE_PIXEL_PNG, "video/mp4"))
                .isInstanceOf(DocumentTextExtractionException.class)
                .satisfies(t -> {
                    DocumentTextExtractionException ex =
                            (DocumentTextExtractionException) t;
                    assertThat(ex.statusCode()).isZero();
                    assertThat(ex.getMessage()).contains("Unsupported");
                });
    }
}
