package com.ticketapp.domain.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReceiptExtractionException} (ADR 0007).
 *
 * <p>The exception is the only failure signal the orchestrator sees
 * from any provider implementation. Both constructor variants
 * (with and without cause) must surface the upstream HTTP status,
 * the provider's retryability verdict and the message text so the
 * WARN log has
 * actionable context.
 */
class ReceiptExtractionExceptionTest {

    @Test
    void carriesStatusCodeRetryabilityAndMessage() {
        ReceiptExtractionException e = new ReceiptExtractionException(
                502, true, "provider returned 502");

        assertEquals(502, e.statusCode());
        assertTrue(e.retryable());
        assertEquals("provider returned 502", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void carriesAStableCodeForTheApi() {
        ReceiptExtractionException e = new ReceiptExtractionException(
                400, false, "bad request");

        assertEquals(ReceiptExtractionException.ERROR_CODE, e.code());
        assertFalse(e.retryable());
    }

    @Test
    void carriesCauseWhenProvided() {
        IllegalStateException cause = new IllegalStateException("upstream boom");
        ReceiptExtractionException e = new ReceiptExtractionException(
                0, true, "provider call failed", cause);

        assertEquals(0, e.statusCode());
        assertEquals("provider call failed", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void statusCodeZeroMeansNonHttpFailure() {
        // The contract documented on the field: 0 means the failure
        // did not involve an HTTP response (DNS, parse error, etc.).
        // Verify a fresh construction preserves that sentinel value.
        ReceiptExtractionException e = new ReceiptExtractionException(0, false,
                "x");

        assertEquals(0, e.statusCode());
    }

    @Test
    void toStringIncludesStatusCode() {
        // Lombok's default @ToString on an Exception subclass
        // renders only the subclass fields, not the inherited
        // Throwable.message. Verify the statusCode (the only
        // domain-specific field) appears — that's the actionable
        // datum in a WARN log line.
        ReceiptExtractionException e = new ReceiptExtractionException(500, false,
                "boom");

        String rendered = e.toString();
        assertTrue(rendered.contains("500"),
                "expected statusCode in toString: " + rendered);
        assertTrue(rendered.contains("ReceiptExtractionException"),
                "expected class name in toString: " + rendered);
    }
}