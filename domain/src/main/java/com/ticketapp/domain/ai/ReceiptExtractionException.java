package com.ticketapp.domain.ai;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Provider-neutral exception thrown by a {@link ReceiptExtractor}
 * when extraction cannot complete (ADR 0007).
 *
 * <p>Provider implementations are responsible for translating their
 * internal failures (HTTP 4xx/5xx, timeouts, parse errors, content
 * filters) into this single type so the orchestrator never depends on
 * a provider-specific exception class.
 *
 * <p>Carries an optional {@link #statusCode()} mirroring the upstream
 * HTTP status when applicable. {@code 0} means the failure did not
 * involve an HTTP response (connection refused, parse error, etc.).
 */
@Getter
@ToString
@EqualsAndHashCode(callSuper = false)
@Accessors(fluent = true)
public class ReceiptExtractionException extends Exception {

    private final int statusCode;

    public ReceiptExtractionException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ReceiptExtractionException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

}
