package com.ticketapp.minimaxai;

import com.ticketapp.domain.ai.ReceiptExtraction;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionRequest;
import com.ticketapp.domain.ai.ReceiptExtractionResult;
import com.ticketapp.domain.ai.ReceiptExtractor;
import com.ticketapp.minimaxai.autoconfigure.MinimaxAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider implementation of the {@link ReceiptExtractor} port
 * backed by MiniMax (ADR 0007).
 *
 * <p>Composes four collaborators:
 * <ul>
 *   <li>{@link MiniMaxApiClient} — sends the chat-completion request
 *       and reads the raw assistant text.</li>
 *   <li>{@link PdfTextExtractor} — pre-processes PDF receipts into
 *       plain text (MiniMax's chat-completions endpoint doesn't
 *       accept PDFs natively; ADR 0006 D3).</li>
 *   <li>{@link ReceiptResponseParser} — owns the model-specific parsing
 *       concerns ({@code <think>} stripper, code-fence stripper, JSON
 *       substring fallback).</li>
 *   <li>{@link MinimaxAiProperties} — provider-specific configuration
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
public final class MiniMaxReceiptExtractor implements ReceiptExtractor {

    private final MiniMaxApiClient client;
    private final PdfTextExtractor pdfExtractor;
    private final ReceiptResponseParser parser;
    private final MinimaxAiProperties properties;

    @Override
    public ReceiptExtraction extract(ReceiptExtractionRequest request)
            throws ReceiptExtractionException {
        MiniMaxApiClient.ReceiptInput input;
        if (request.isPdf()) {
            // PDF preprocessing is a MiniMax concern: MiniMax's
            // chat-completions endpoint doesn't accept PDFs natively
            // (ADR 0006 D3). Future implementations with native PDF
            // support would skip this step.
            String text;
            try {
                text = pdfExtractor.extract(request.content());
            } catch (java.io.IOException ioe) {
                throw new ReceiptExtractionException(0,
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
                    throw new ReceiptExtractionException(0,
                            "PDF rasterization failed: " + ioe.getMessage(), ioe);
                }
                if (pngBytes == null) {
                    // Either empty bytes (caught at the call site
                    // above) or zero-page document — both surface as
                    // the same "nothing to extract" hard failure.
                    throw new ReceiptExtractionException(0,
                            "PDF has no pages to rasterize");
                }
                input = MiniMaxApiClient.ReceiptInput.image(
                        properties.model(), pngBytes, "image/png");
            } else {
                input = MiniMaxApiClient.ReceiptInput.pdfText(properties.model(), text);
            }
        } else {
            input = MiniMaxApiClient.ReceiptInput.image(properties.model(),
                    request.content(), request.contentType());
        }

        final String raw;
        try {
            raw = client.extractReceipt(input);
        } catch (MiniMaxApiClient.MiniMaxApiException mae) {
            throw new ReceiptExtractionException(mae.statusCode(),
                    "MiniMax extraction failed: " + mae.getMessage(), mae);
        } catch (java.io.IOException ioe) {
            throw new ReceiptExtractionException(0,
                    "MiniMax extraction failed: " + ioe.getMessage(), ioe);
        } catch (RuntimeException e) {
            // Provider-call bugs (mock failures in tests, SDK transport
            // errors) are retriable provider failures — wrap them so the
            // orchestrator marks ON_ERROR. Bugs outside the provider call
            // (parser internals, domain validation below) propagate unwrapped.
            throw new ReceiptExtractionException(0,
                    "MiniMax extraction failed: " + e.getMessage(), e);
        }
        ReceiptExtractionResult result = parser.parse(raw);
        return new ReceiptExtraction(result, raw, properties.model());
    }
}
