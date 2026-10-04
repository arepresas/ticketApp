package com.ticketapp.domain.exceptions;



/**
 * Thrown when a guarded write loses a read-modify-write race: the
 * row's version moved underneath the caller between read and save.
 *
 * <p>Unchecked so the persistence layer can signal the conflict
 * without polluting the port signature; callers translate it to a
 * retry, a skip, or (at the HTTP edge) a 409. Carries the ticket id
 * for log lines — never the row bytes.
 */
public class OptimisticLockException extends TicketAppException {

    private final long ticketId;

    /** Stable code callers and logs switch on. */
    public static final String ERROR_CODE = "TICKET_MODIFIED_CONCURRENTLY";

    public OptimisticLockException(long ticketId, String message) {
        super(ERROR_CODE, message);
        this.ticketId = ticketId;
    }

    public long ticketId() {
        return ticketId;
    }
}
