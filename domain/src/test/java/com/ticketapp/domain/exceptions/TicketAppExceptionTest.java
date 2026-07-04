package com.ticketapp.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TicketAppException}. Pins the contract that
 * callers downstream (the BFF, the persistence layer) rely on: the
 * exception carries a stable {@code value} (error code) and an HTTP
 * status, and the message is propagated verbatim.
 */
class TicketAppExceptionTest {

    @Test
    void carriesMessageValueAndHttpCode() {
        TicketAppException e = new TicketAppException(
                "ticket already extracted", "TICKET_ALREADY_EXTRACTED", 409);

        assertEquals("ticket already extracted", e.getMessage());
        assertEquals("TICKET_ALREADY_EXTRACTED", e.getValue());
        assertEquals(409, e.getHttpCode());
    }

    @Test
    void rejectsNullMessage() {
        assertThrows(NullPointerException.class,
                () -> new TicketAppException(null, "X", 400));
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class,
                () -> new TicketAppException("m", null, 400));
    }

    @Test
    void rejectsNullHttpCode() {
        assertThrows(NullPointerException.class,
                () -> new TicketAppException("m", "X", null));
    }

    @Test
    void toStringIncludesValueAndHttpCode() {
        // Lombok's default @ToString on an Exception subclass renders
        // the subclass fields but skips the inherited Throwable.message
        // (and our @EqualsAndHashCode(callSuper = false) follows the
        // same pattern). Verify the subclass-specific fields appear —
        // they're what an operator greps a WARN log line for.
        TicketAppException e = new TicketAppException("oops", "OOPS", 500);

        String rendered = e.toString();
        assertNotNull(rendered);
        assertTrue(rendered.contains("OOPS"),
                "expected toString to contain value: " + rendered);
        assertTrue(rendered.contains("500"),
                "expected toString to contain httpCode: " + rendered);
    }
}