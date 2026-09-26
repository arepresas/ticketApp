package com.ticketapp.domain.exceptions;

import java.util.Objects;

/**
 * Base type for domain failures with a stable machine-readable code.
 *
 * <p>Unchecked: domain errors cross module boundaries toward a single
 * HTTP-edge handler (future {@code ProblemDetail} advice) instead of
 * being declared at every layer. The {@code code} (e.g.
 * {@code TICKET_NOT_FOUND}) is what logs and API responses switch
 * on; the message stays human-readable and operator-oriented.
 *
 * <p>Deliberately HTTP-agnostic: status mapping lives at the edge,
 * not in the domain. No Lombok by project rule: the domain module
 * depends only on {@code java.*}.
 */
public class TicketAppException extends RuntimeException {

    private final String code;

    public TicketAppException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public TicketAppException(String code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "[code=" + code + ", message=" + getMessage() + "]";
    }
}
