package com.ticketapp.domain.ai;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Provider-neutral exception thrown by a
 * {@link DocumentTextExtractor} when the OCR step cannot complete.
 *
 * <p>Mirrors {@link ReceiptExtractionException} so the orchestrator
 * (BFF) can use the same try/catch shape around both ports. The
 * constructor with {@code statusCode == 0} covers failures that did
 * not involve an HTTP response (connection refused, parse error,
 * provider refusal, PDFBox I/O error, rasterize failure, etc.).
 *
 * <p>Carrying the upstream HTTP status where it exists lets the
 * dashboard distinguish "provider is down" (5xx, retriable) from
 * "provider rejected our request" (4xx, usually a config problem)
 * without reaching into a provider-specific exception class.
 */
@Getter
@ToString
@EqualsAndHashCode(callSuper = false)
@Accessors(fluent = true)
public class DocumentTextExtractionException extends Exception {

    private final int statusCode;

    public DocumentTextExtractionException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public DocumentTextExtractionException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
