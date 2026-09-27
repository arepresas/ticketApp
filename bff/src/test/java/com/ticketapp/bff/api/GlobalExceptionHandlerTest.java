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
                new OptimisticLockException(UUID.randomUUID(), "boom"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Concurrent modification");
        assertThat(problem.getDetail()).contains("retry");
    }
}
