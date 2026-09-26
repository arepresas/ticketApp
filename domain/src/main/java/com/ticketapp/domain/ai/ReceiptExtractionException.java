package com.ticketapp.domain.ai;

/**
 * Provider-neutral exception thrown by a {@link ReceiptExtractor}
 * when extraction cannot complete (ADR 0007).
 *
 * <p>Unchecked: orchestrators may let it propagate to a boundary
 * handler instead of declaring it at every layer. Provider
 * implementations are responsible for translating their internal
 * failures (HTTP 4xx/5xx, timeouts, parse errors, content filters)
 * into this single type so the orchestrator never depends on a
 * provider-specific exception class.
 *
 * <p>Carries an optional {@link #statusCode()} mirroring the upstream
 * HTTP status when applicable. {@code 0} means the failure did not
 * involve an HTTP response (connection refused, parse error, etc.).
 *
 * <p>No Lombok by project rule: the domain module depends only on
 * {@code java.*}.
 */
public class ReceiptExtractionException extends RuntimeException {

    private final int statusCode;

    public ReceiptExtractionException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ReceiptExtractionException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "[statusCode=" + statusCode + ", message=" + getMessage() + "]";
    }
}
