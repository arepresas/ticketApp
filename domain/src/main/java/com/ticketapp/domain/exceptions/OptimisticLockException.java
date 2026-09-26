package com.ticketapp.domain.exceptions;

import java.util.UUID;

/**
 * Thrown when a guarded write loses a read-modify-write race: the
 * row's version moved underneath the caller between read and save.
 *
 * <p>Unchecked so the persistence layer can signal the conflict
 * without polluting the port signature; callers translate it to a
 * retry, a skip, or (at the HTTP edge) a 409. Carries the ticket id
 * for log lines — never the row bytes.
 */
public class OptimisticLockException extends RuntimeException {

    private final UUID ticketId;

    public OptimisticLockException(UUID ticketId, String message) {
        super(message);
        this.ticketId = ticketId;
    }

    public UUID ticketId() {
        return ticketId;
    }
}
