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

    /** Stable code for logs and API responses. Named ERROR_CODE rather than
     *  CODE so it cannot be confused with the inherited {@code code()} accessor. */
    public static final String ERROR_CODE = "RECEIPT_EXTRACTION_FAILED";

    /**
     * Fallback for the client-facing message when the adapter does
     * not supply one. Deliberately a fixed constant: deriving it from
     * the diagnostic would reintroduce whatever the provider put in
     * the diagnostic (response bodies, endpoint URLs, model ids).
     */
    public static final String GENERIC_SAFE_MESSAGE =
            "the AI provider could not complete the extraction";

    private final int statusCode;
    private final boolean retryable;
    private final String safeMessage;

    /**
     * @param statusCode provider-reported status, for logs only —
     *                  {@code 0} when the provider has no HTTP
     *                  concept. Never the basis of a retry decision.
     * @param retryable  whether the caller may try again. The
     *                  provider knows the answer (timeouts and 5xx
     *                  are worth another attempt, a 400 is not);
     *                  the caller must not re-derive it from
     *                  {@code statusCode}.
     * @param message    diagnostic text for the LOG. May contain
     *                   whatever the provider returned; never
     *                   persisted or sent to a client.
     */
    public ReceiptExtractionException(int statusCode, boolean retryable, String message) {
        this(statusCode, retryable, message, GENERIC_SAFE_MESSAGE);
    }

    /**
     * @param safeMessage short, provider-neutral text an adapter
     *                    vouches for: a category ("provider returned
     *                    503"), never provider output. This is the
     *                    only part the orchestrator persists.
     */
    public ReceiptExtractionException(int statusCode, boolean retryable,
                                      String message, String safeMessage) {
        this(statusCode, retryable, message, safeMessage, null);
    }

    /**
     * The only text from this exception that may reach a client or
     * the {@code tickets} row. Adapters are responsible for keeping
     * it free of vendor identity, endpoints, request data and
     * provider payloads; the orchestrator does not filter it,
     * because a denylist cannot be exhaustive.
     */
    public String safeMessage() {
        return safeMessage;
    }

    public ReceiptExtractionException(int statusCode, boolean retryable,
                                      String message, Throwable cause) {
        // Convenience overload for adapters that have no status-aware
        // category to offer: the `message` it accepts is the
        // diagnostic, which is never persisted or sent to clients, so
        // it must NOT be reused as the safe message. Callers fall
        // through to GENERIC_SAFE_MESSAGE rather than leaking
        // provider text through an old call site.
        this(statusCode, retryable, message, GENERIC_SAFE_MESSAGE, cause);
    }

    /**
     * Canonical constructor: every other overload funnels here. Takes
     * the full contract — provider status, the retry decision, the
     * diagnostic {@code message}, the client-facing {@code safeMessage},
     * and an optional {@code cause}.
     *
     * <p>The five-argument form is NOT legacy: an adapter that knows
     * both a provider status and a client-safe category must be able to
     * attach the original cause as well, and that is exactly what
     * {@code OpenAiReceiptExtractor} does for every failed provider
     * call.
     */
    public ReceiptExtractionException(int statusCode, boolean retryable,
                                      String message, String safeMessage,
                                      Throwable cause) {
        super(ERROR_CODE, message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.safeMessage = safeMessage == null || safeMessage.isBlank()
                ? GENERIC_SAFE_MESSAGE
                : safeMessage;
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
        // Deliberately omits `message=...`: the diagnostic may carry
        // provider text (endpoint, response body, request id) and
        // should never reach a logger or a `Throwable.toString()`
        // chain that may eventually get serialised. Status + safe
        // message is all an operator needs.
        return getClass().getSimpleName()
                + "[statusCode=" + statusCode + ", safeMessage=" + safeMessage + "]";
    }
}
