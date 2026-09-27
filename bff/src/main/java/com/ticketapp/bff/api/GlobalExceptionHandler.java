package com.ticketapp.bff.api;

import com.ticketapp.domain.exceptions.OptimisticLockException;
import com.ticketapp.domain.exceptions.TicketAppException;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures to RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Single place for the translation so controllers stay free of
 * per-endpoint error mapping. Every domain failure carries a stable
 * {@link TicketAppException#code() code} and lands here through the
 * one {@link TicketAppException} branch, so a caller (or the SPA)
 * can switch on {@code code} without knowing the exception class.
 * {@code ResponseStatusException} thrown by controllers is already
 * rendered as {@code ProblemDetail} by Spring Boot itself and needs
 * no branch here. Unexpected bugs intentionally have no branch —
 * they must surface as 500s, not as curated client errors.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Lost optimistic-locking race: the caller wrote against a stale
     * version because another writer (user PATCH, scheduler tick)
     * committed first. The client should re-read and retry the
     * action against the fresh state.
     */
    @ExceptionHandler(OptimisticLockException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockException e) {
        log.info("Optimistic lock conflict for ticket {}: {}", e.ticketId(), e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Concurrent modification");
        problem.setDetail("The ticket changed while your request was in flight. "
                + "Re-read it and retry.");
        problem.setProperty("code", e.code());
        return problem;
    }

    /**
     * Every other domain failure. The status is deliberately 422:
     * these are well-formed requests the domain refused, and the
     * machine-readable {@code code} is what the caller switches on.
     * The human message stays server-side (it is operator-oriented);
     * {@code detail} carries the code instead of the internals.
     */
    @ExceptionHandler(TicketAppException.class)
    public ProblemDetail handleDomainFailure(TicketAppException e) {
        log.warn("Domain failure code={} type={}: {}",
                e.code(), e.getClass().getSimpleName(), e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Request refused by the domain");
        problem.setProperty("code", e.code());
        return problem;
    }
}
