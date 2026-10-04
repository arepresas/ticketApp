package com.ticketapp.bff.api;

import com.ticketapp.domain.exceptions.OptimisticLockException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler}. The 409 contract
 * (lost optimistic race → re-read and retry) is pinned here; the
 * production path is exercised by concurrent PATCH ITs.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void optimisticLockMapsToConflictWithRetryGuidance() {
        var problem = handler.handleOptimisticLock(
                new OptimisticLockException(nextId(), "boom"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Concurrent modification");
        assertThat(problem.getDetail()).contains("retry");
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private static long nextId() {
        return IDS.incrementAndGet();
    }
}
