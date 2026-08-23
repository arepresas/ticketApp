package com.ticketapp.domain.ai;

/**
 * Provider-agnostic port for "what text is printed on this document?"
 *
 * <p>Sits next to {@link ReceiptExtractor} as its upstream sibling:
 * {@code DocumentTextExtractor} is the lossy step that turns an
 * uploaded document (image or PDF) into the raw text it contains;
 * {@code ReceiptExtractor} is the downstream step that turns the
 * raw text (or the original bytes when no text is available) into
 * structured merchant / total / line-item fields.
 *
 * <p>The split lets the orchestrator (BFF) call the OCR step at
 * upload time, surface the transcription to the user as a preview,
 * and later forward it to {@code ReceiptExtractor} as supplementary
 * context — without forcing every provider to ship an OCR-capable
 * model and without making the structured-extraction prompt depend
 * on whether the model can see.
 *
 * <p>Supported input kinds:
 * <ul>
 *   <li>Image content types ({@code image/png}, {@code image/jpeg},
 *       {@code image/webp}, {@code image/heic}).</li>
 *   <li>{@code application/pdf} — text-extractable (digital-born)
 *       PDFs return their text directly; image-only / scanned PDFs
 *       fall through to the same vision-OCR path that image bytes
 *       use after a one-page rasterize step.</li>
 * </ul>
 *
 * <p>Implementations:
 * <ul>
 *   <li>MiniMax today — sends image bytes as {@code image_url} with
 *       a "transcribe verbatim" prompt; for PDFs, extracts text via
 *       a provider-side {@link com.ticketapp.minimaxai.PdfTextExtractor}
 *       helper first and only falls back to the vision branch when
 *       the PDF carries no selectable text.</li>
 *   <li>A future local-Tesseract module — no network round-trip,
 *       suitable when the operator wants to skip the AI bill
 *       entirely for image-only OCR.</li>
 * </ul>
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Translate their internal failures (HTTP, parse, network,
 *       model refusal) into {@link DocumentTextExtractionException}.</li>
 *   <li>Return {@code null} when the provider could not derive any
 *       text (blank receipt, model explicitly says "no text
 *       visible", zero-page PDF). That's a normal outcome, not an
 *       error.</li>
 *   <li>Return plain UTF-8 text without code fences, markdown, or a
 *       preamble. The caller renders the value verbatim in the
 *       upload preview.</li>
 *   <li>Not retain the request bytes beyond the call (mirrors
 *       {@link ReceiptExtractor}'s privacy posture — receipts are
 *       personal financial data).</li>
 * </ul>
 */
public interface DocumentTextExtractor {

    /**
     * Transcribe the text carried in the supplied document into a
     * single UTF-8 string. Returns {@code null} when the provider
     * produced no usable text (blank receipt, image-only result,
     * zero-page PDF, model reply "no text visible").
     *
     * @param bytes       the raw document bytes (image or PDF, per
     *                    {@code contentType}). Never {@code null} or
     *                    empty.
     * @param contentType the MIME type as reported by the uploader;
     *                    never blank. Implementation uses it to pick
     *                    the right encoder / text-extraction helper
     *                    or fail fast on unsupported media.
     * @return the transcribed text, or {@code null} when the provider
     *         could not produce any.
     * @throws DocumentTextExtractionException on any provider failure
     *         (HTTP 4xx/5xx, timeout, network, etc.). Provider-specific
     *         exception classes must not leak across the port.
     */
    String extract(byte[] bytes, String contentType)
            throws DocumentTextExtractionException;
}
