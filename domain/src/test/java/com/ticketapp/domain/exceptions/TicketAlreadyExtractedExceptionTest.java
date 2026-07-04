package com.ticketapp.domain.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TicketAlreadyExtractedException}. The
 * subclass must carry the same value/httpCode contract as its
 * parent — downstream code branches on
 * {@link TicketAppException#getValue()} for RFC 7807 mapping, and
 * a "duplicate" error code is the precondition for that branch.
 */
class TicketAlreadyExtractedExceptionTest {

    @Test
    void carriesValueAndHttpCodeForConflictMapping() {
        TicketAlreadyExtractedException e = new TicketAlreadyExtractedException(
                "already extracted", "TICKET_ALREADY_EXTRACTED", 409);

        assertEquals("already extracted", e.getMessage());
        assertEquals("TICKET_ALREADY_EXTRACTED", e.getValue());
        assertEquals(409, e.getHttpCode());
    }

    @Test
    void isAlsoATicketAppException() {
        // Downstream code catches the parent type — verify the
        // subclass is reachable through that path (matters for any
        // future @ExceptionHandler that picks the right ProblemDetail
        // based on the parent class).
        TicketAlreadyExtractedException e = new TicketAlreadyExtractedException(
                "m", "X", 409);

        assertTrue(e instanceof TicketAppException,
                "subclass must be reachable via the parent type");
    }
}