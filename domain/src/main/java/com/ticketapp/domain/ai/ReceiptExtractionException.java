package com.ticketapp.domain.ai;

import com.ticketapp.domain.exceptions.TicketAppException;

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
public class ReceiptExtractionException extends TicketAppException {

    /** Stable code for logs and API responses. */
    public static final String CODE = "RECEIPT_EXTRACTION_FAILED";

    private final int statusCode;
    private final boolean retryable;

    /**
     * @param statusCode provider-reported status, for logs only —
     *                  {@code 0} when the provider has no HTTP
     *                  concept. Never the basis of a retry decision.
     * @param retryable  whether the caller may try again. The
     *                  provider knows the answer (timeouts and 5xx
     *                  are worth another attempt, a 400 is not);
     *                  the caller must not re-derive it from
     *                  {@code statusCode}.
     */
    public ReceiptExtractionException(int statusCode, boolean retryable, String message) {
        super(CODE, message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public ReceiptExtractionException(int statusCode, boolean retryable,
                                      String message, Throwable cause) {
        super(CODE, message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Whether another attempt could plausibly succeed. */
    public boolean retryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "[statusCode=" + statusCode + ", message=" + getMessage() + "]";
    }
}
