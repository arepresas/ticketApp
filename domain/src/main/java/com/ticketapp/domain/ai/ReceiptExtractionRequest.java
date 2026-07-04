package com.ticketapp.domain.ai;

import java.util.Arrays;

/**
 * Provider-neutral input to an AI extraction (ADR 0007).
 *
 * <p>The request carries the raw receipt bytes plus the MIME type
 * the uploader reported. The {@link ReceiptExtractor}
 * implementation decides what to do with that pair — for example,
 * the MiniMax implementation extracts text from PDFs before
 * sending (MiniMax's chat-completions endpoint does not accept
 * PDFs natively; ADR 0006 D3); a future implementation might
 * pass the bytes through unchanged.
 *
 * <p>The decision of "image bytes vs pre-extracted text" is
 * provider-shaped (some models accept PDFs as documents; others
 * don't) so it lives in the implementation, not in this record.
 * The domain stays shape-agnostic.
 *
 * <p>The model id is deliberately NOT carried in this record.
 * Each implementation reads its own model name from its
 * provider-specific configuration; the implementation reports the
 * model it used in {@link ReceiptExtraction#model()}. This keeps
 * the BFF free of provider-specific model identifiers and lets an
 * operator change models via configuration rather than code.
 *
 * @param content     raw receipt bytes (image or PDF, per
 *                    {@code contentType})
 * @param contentType MIME type as reported by the uploader; never
 *                    blank when {@code content} is non-null
 */
public record ReceiptExtractionRequest(
        byte[] content,
        String contentType
) {
    public ReceiptExtractionRequest {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("content must not be empty");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException(
                    "contentType is required when content is set");
        }
    }

    /**
     * {@code true} when {@code contentType} indicates a PDF. The
     * default {@link ReceiptExtractor} implementations are expected
     * to handle PDFs (text extraction, native PDF support, etc.);
     * this flag is informational and lets implementations branch on
     * the shape without parsing MIME strings themselves.
     */
    public boolean isPdf() {
        return contentType.toLowerCase().startsWith("application/pdf");
    }

    // Records auto-generate equals/hashCode/toString that treat
    // byte[] by reference — two requests with the same bytes compare
    // as different. Override so equality reflects the byte content
    // (matches the pattern used by Ticket#fileData, see domain/Ticket.java).
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReceiptExtractionRequest other)) return false;
        return Arrays.equals(content, other.content)
                && java.util.Objects.equals(contentType, other.contentType);
    }

    @Override
    public int hashCode() {
        int h = java.util.Objects.hashCode(contentType);
        return 31 * h + Arrays.hashCode(content);
    }

    @Override
    public String toString() {
        // Don't dump the raw bytes into a log line — just the size.
        return "ReceiptExtractionRequest[content=" + content.length + " bytes"
                + ", contentType=" + contentType + "]";
    }
}