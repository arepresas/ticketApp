package com.ticketapp.openai;

import com.ticketapp.domain.ai.ReceiptExtraction;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionRequest;
import com.ticketapp.domain.ai.ReceiptExtractionResult;
import com.ticketapp.domain.ai.ReceiptExtractor;
import com.ticketapp.openai.autoconfigure.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider implementation of the {@link ReceiptExtractor} port
 * backed by the provider (ADR 0007).
 *
 * <p>Composes four collaborators:
 * <ul>
 *   <li>{@link OpenAiApiClient} — sends the chat-completion request
 *       and reads the raw assistant text.</li>
 *   <li>{@link PdfTextExtractor} — pre-processes PDF receipts into
 *       plain text (the provider's chat-completions endpoint doesn't
 *       accept PDFs natively; ADR 0006 D3).</li>
 *   <li>{@link ReceiptResponseParser} — owns the model-specific parsing
 *       concerns ({@code <think>} stripper, code-fence stripper, JSON
 *       substring fallback).</li>
 *   <li>{@link OpenAiProperties} — provider-specific configuration
 *       (model id, timeout). Read once at construction.</li>
 * </ul>
 *
 * <p>Failure contract: provider I/O problems (HTTP, network, PDFBox)
 * are wrapped into {@link ReceiptExtractionException}; model-data
 * problems already arrive as {@code ReceiptExtractionException} from
 * the parser. Unexpected {@code RuntimeException}s (programming bugs)
 * propagate unwrapped so operators can tell them apart from
 * retriable provider failures.
 */
@Slf4j
@RequiredArgsConstructor
public final class OpenAiReceiptExtractor implements ReceiptExtractor {

    private final OpenAiApiClient client;
    private final PdfTextExtractor pdfExtractor;
    private final ReceiptResponseParser parser;
    private final OpenAiProperties properties;

    @Override
    public ReceiptExtraction extract(ReceiptExtractionRequest request)
            throws ReceiptExtractionException {
        OpenAiApiClient.ReceiptInput input;
        if (request.isPdf()) {
            // PDF preprocessing is a provider concern: the provider's
            // chat-completions endpoint doesn't accept PDFs natively
            // (ADR 0006 D3). Future implementations with native PDF
            // support would skip this step.
            String text;
            try {
                text = pdfExtractor.extract(request.content());
            } catch (java.io.IOException ioe) {
                throw new ReceiptExtractionException(0, false,
                        "PDF text extraction failed: " + ioe.getMessage(), ioe);
            }
            if (text.isBlank()) {
                // Scanned / image-only PDF: no selectable text. The
                // upstream API rejects raw PDF bytes in image_url
                // (HTTP 400: "media type 'application/pdf' not
                // supported") so we can't just forward the original
                // bytes — the PDF has to be rasterized to PNG first.
                // 200 DPI is high enough to keep small print
                // legible; the resulting PNG is sent through the
                // same image_url branch as a normal photo upload.
                log.warn("PDF text extraction returned empty for {} bytes — "
                        + "rasterizing first page as PNG", request.content().length);
                byte[] pngBytes;
                try {
                    pngBytes = pdfExtractor.rasterizeFirstPageAsPng(request.content());
                } catch (java.io.IOException ioe) {
                    throw new ReceiptExtractionException(0, false,
                            "PDF rasterization failed: " + ioe.getMessage(), ioe);
                }
                if (pngBytes == null) {
                    // Either empty bytes (caught at the call site
                    // above) or zero-page document — both surface as
                    // the same "nothing to extract" hard failure.
                    throw new ReceiptExtractionException(0, false,
                            "PDF has no pages to rasterize");
                }
                input = OpenAiApiClient.ReceiptInput.image(
                        properties.model(), pngBytes, "image/png");
            } else {
                input = OpenAiApiClient.ReceiptInput.pdfText(properties.model(), text);
            }
        } else {
            input = OpenAiApiClient.ReceiptInput.image(properties.model(),
                    request.content(), request.contentType());
        }

        final String raw;
        try {
            raw = client.extractReceipt(input);
        } catch (OpenAiApiClient.OpenAiApiException mae) {
            // The provider knows whether its own failure is worth
            // another attempt, so it says so here instead of leaving
            // the caller to read the status code.
            throw new ReceiptExtractionException(mae.statusCode(),
                    isRetriableStatus(mae.statusCode()),
                    "the extraction failed: " + mae.getMessage(),
                    safeMessageFor(mae.statusCode()), mae);
        } catch (java.io.IOException ioe) {
            throw new ReceiptExtractionException(0, true,
                    "the extraction failed: " + ioe.getMessage(),
                    "the AI provider could not be reached", ioe);
        } catch (RuntimeException e) {
            // Provider-call bugs (mock failures in tests, SDK transport
            // errors) are retriable provider failures — wrap them so the
            // orchestrator marks ON_ERROR.
            throw new ReceiptExtractionException(0, true,
                    "the extraction failed: " + e.getMessage(), e);
        }
        try {
            // Parsing and domain validation are inside the guard on
            // purpose: the port promises that every failure leaves as
            // ReceiptExtractionException, and a malformed reply is a
            // provider failure, not a bug in the caller.
            ReceiptExtractionResult result = parser.parse(raw);
            return new ReceiptExtraction(result, raw, properties.model());
        } catch (ReceiptExtractionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ReceiptExtractionException(0, false,
                    "the provider returned an unusable extraction: " + e.getMessage(), e);
        }
    }

    /**
     * Timeouts, rate limits and server errors are worth another
     * attempt; a client error is not (the same request would fail
     * again). Unknown statuses are treated as permanent so a
     * surprise does not turn into a retry storm.
     */
    /**
     * Client-facing text for a provider status. A category, never the
     * provider's own output: the orchestrator persists this on the
     * ticket and the dashboard renders it, and an upstream body can
     * contain endpoints, model ids or request data.
     */
    private static String safeMessageFor(int status) {
        if (status <= 0) {
            return "the AI provider could not be reached";
        }
        if (status == 401 || status == 403) {
            return "the AI provider rejected the request (check the configured key)";
        }
        if (status == 429) {
            return "the AI provider is rate limiting this deployment";
        }
        if (status >= 500) {
            return "the AI provider is failing on its side (status " + status + ")";
        }
        return "the AI provider rejected the extraction (status " + status + ")";
    }

    private static boolean isRetriableStatus(int status) {
        return status == 429 || (status >= 500 && status <= 599);
    }
}
