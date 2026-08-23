package com.ticketapp.minimaxai;

import com.ticketapp.domain.ai.DocumentTextExtractionException;
import com.ticketapp.domain.ai.DocumentTextExtractor;
import com.ticketapp.minimaxai.autoconfigure.MinimaxAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * MiniMax-backed implementation of {@link DocumentTextExtractor}.
 *
 * <p>Picks up the {@link com.openai.client.OpenAIClient} bean that
 * {@link com.ticketapp.minimaxai.autoconfigure.MinimaxAiAutoConfiguration}
 * exposes, plus the {@link MinimaxAiProperties} the autoconfig binds
 * under {@code ticketapp.ai.minimax.*}. The actual provider
 * round-trip lives in
 * {@link MiniMaxApiClient#transcribeImage(String, byte[], String)};
 * this class is a thin adapter that maps
 * {@link DocumentTextExtractionException} onto provider failures
 * — the same shape {@link MiniMaxReceiptExtractor} uses to hide
 * MiniMax wire failures from the BFF's orchestrator (ADR 0007).
 *
 * <p><b>One port, two media types.</b> The orchestrator (BFF) feeds
 * the same {@link DocumentTextExtractor#extract(byte[], String)}
 * call with either an image MIME or {@code application/pdf}; the
 * implementation branches internally so the BFF stays
 * format-agnostic. Each path lives in its own private method
 * ({@link #extractFromImage}, {@link #extractFromPdf}) so the
 * top-level method stays at the cognitive-complexity ceiling
 * Sonar enforces.
 *
 * <p>For PDFs the flow is:
 * <ol>
 *   <li>Try {@link PdfTextExtractor#extract(byte[])} — works for
 *       digital-born receipts (selectable text) in microseconds,
 *       no model round-trip needed.</li>
 *   <li>If the PDF yields no text (scanned, image-only, zero-page
 *       edge cases), rasterize the first page to PNG via
 *       {@link PdfTextExtractor#rasterizeFirstPageAsPng(byte[])}
 *       and feed the PNG to the same vision-OCR call that an
 *       upload-time image takes.</li>
 * </ol>
 * Both branches converge on "give me the verbatim text" so the
 * upload preview is identical regardless of source format — the
 * provider swap story is the same as for image-only uploads: drop
 * a different {@code <dependency>} for OCR and the BFF gets a new
 * implementation for free.
 *
 * <p>Wired as a {@code @Component} under the same package scanned
 * by the autoconfiguration, so a future local-Tesseract module
 * follows the same drop-in pattern.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public final class MiniMaxDocumentTextExtractor implements DocumentTextExtractor {

    private final MiniMaxApiClient client;
    private final MinimaxAiProperties properties;
    private final PdfTextExtractor pdfExtractor;

    @Override
    public String extract(byte[] bytes, String contentType)
            throws DocumentTextExtractionException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("document bytes must not be empty");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType is required for OCR");
        }
        String mime = contentType.toLowerCase();
        if (mime.startsWith("image/")) {
            return extractFromImage(bytes, mime);
        }
        if (mime.startsWith("application/pdf")) {
            return extractFromPdf(bytes);
        }
        // The controller's upload-MIME whitelist already rejects
        // unknown media types; reaching here means a future
        // controller-path bypass. Fail loud rather than silently
        // return null.
        throw new DocumentTextExtractionException(0,
                "Unsupported content type for OCR: " + contentType);
    }

    /**
     * Image branch. Sends the bytes straight to the vision-OCR
     * call ({@code transcribeImage}). Kept separate from
     * {@link #extractFromPdf} so each branch's exception-mapping
     * stays in one method and the top-level {@link #extract} stays
     * flat.
     */
    private String extractFromImage(byte[] bytes, String mime)
            throws DocumentTextExtractionException {
        try {
            String text = client.transcribeImage(
                    properties.model(), bytes, mime);
            return collapseEmptyToNull(text);
        } catch (MiniMaxApiClient.MiniMaxApiException mae) {
            throw new DocumentTextExtractionException(mae.statusCode(),
                    "MiniMax OCR failed: " + mae.getMessage(), mae);
        } catch (java.io.IOException ioe) {
            throw new DocumentTextExtractionException(0,
                    "MiniMax OCR I/O failure: " + ioe.getMessage(), ioe);
        } catch (RuntimeException e) {
            throw new DocumentTextExtractionException(0,
                    "MiniMax OCR failed (" + e.getClass().getSimpleName()
                            + "): " + e.getMessage(), e);
        }
    }

    /**
     * PDF branch. Two sub-steps:
     * <ol>
     *   <li>{@link PdfTextExtractor#extract(byte[])} — works for
     *       digital-born receipts in microseconds; a non-blank
     *       result short-circuits the model call.</li>
     *   <li>Blank result means image-only / scan; rasterize the
     *       first page to PNG via
     *       {@link PdfTextExtractor#rasterizeFirstPageAsPng(byte[])}
     *       and feed the PNG to the same vision-OCR call as
     *       images. A null PNG (zero-page PDF) returns null.</li>
     * </ol>
     * PDFBox I/O failure on either sub-step surfaces as
     * {@link DocumentTextExtractionException} with status 0 — no
     * provider call happened, the failure is user-supplied input.
     */
    private String extractFromPdf(byte[] bytes)
            throws DocumentTextExtractionException {
        String pdfText;
        try {
            pdfText = pdfExtractor.extract(bytes);
        } catch (java.io.IOException ioe) {
            // PDFBox I/O — don't blame the model.
            throw new DocumentTextExtractionException(0,
                    "PDF text extraction failed: " + ioe.getMessage(), ioe);
        }
        if (pdfText != null && !pdfText.isBlank()) {
            return pdfText;
        }
        log.info("PDF text extraction returned empty for {} bytes — "
                + "falling back to rasterize + vision OCR", bytes.length);
        byte[] png;
        try {
            png = pdfExtractor.rasterizeFirstPageAsPng(bytes);
        } catch (java.io.IOException ioe) {
            throw new DocumentTextExtractionException(0,
                    "PDF rasterization failed: " + ioe.getMessage(), ioe);
        }
        if (png == null) {
            // Zero-page PDF or empty input — nothing to OCR. The
            // caller treats null as the legitimate "no text" outcome.
            return null;
        }
        return extractFromImage(png, "image/png");
    }

    /**
     * Collapse a blank / null model reply to {@code null} so the
     * BFF can stamp {@code ocr_text = NULL} without distinguishing
     * empty from null. Any non-blank value is returned verbatim —
     * the SPA renders the value directly below the upload
     * thumbnail.
     */
    private static String collapseEmptyToNull(String text) {
        if (text == null || text.isBlank()) {
            log.info("OCR returned no text");
            return null;
        }
        return text;
    }
}
