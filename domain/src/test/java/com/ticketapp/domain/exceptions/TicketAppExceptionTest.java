package com.ticketapp.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TicketAppException}. Pins the contract that
 * callers downstream (the BFF, the persistence layer) rely on: the
 * exception is unchecked, carries a stable {@code code}, and the
 * message is propagated verbatim. No HTTP status lives here — that
 * mapping belongs to the edge handler.
 */
class TicketAppExceptionTest {

    @Test
    void carriesCodeAndMessage() {
        TicketAppException e = new TicketAppException(
                "TICKET_ALREADY_EXTRACTED", "ticket already extracted");

        assertEquals("ticket already extracted", e.getMessage());
        assertEquals("TICKET_ALREADY_EXTRACTED", e.code());
    }

    @Test
    void isUnchecked() {
        assertTrue(new TicketAppException("X", "m") instanceof RuntimeException,
                "domain exceptions must not force checked handling");
    }

    @Test
    void carriesCause() {
        IllegalStateException cause = new IllegalStateException("root");
        TicketAppException e = new TicketAppException("X", "m", cause);

        assertEquals(cause, e.getCause());
    }

    @Test
    void rejectsNullCode() {
        assertThrows(NullPointerException.class,
                () -> new TicketAppException(null, "m"));
    }

    @Test
    void rejectsNullMessage() {
        assertThrows(NullPointerException.class,
                () -> new TicketAppException("X", null));
    }

    @Test
    void toStringIncludesCode() {
        TicketAppException e = new TicketAppException("OOPS", "oops");

        String rendered = e.toString();
        assertNotNull(rendered);
        assertTrue(rendered.contains("OOPS"),
                "expected toString to contain code: " + rendered);
        assertTrue(rendered.contains("TicketAppException"),
                "expected toString to contain class name: " + rendered);
    }
}
