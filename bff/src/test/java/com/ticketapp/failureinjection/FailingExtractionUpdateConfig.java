package com.ticketapp.failureinjection;

import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtraction.EditableFields;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.persistence.JdbcTicketExtractionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * Failure injection for the transaction tests: an extraction repository
 * that refuses the column-scoped update.
 *
 * <p>Placed outside {@code com.ticketapp.bff} on purpose. That package
 * tree is component-scanned by {@code BffApplication} for the
 * application context, and a nested {@code @TestConfiguration} in a
 * test under {@code com.ticketapp.bff.api} is picked up by that scan —
 * which would install the failing bean into every other test's shared
 * context. Here it is only ever active through an explicit
 * {@code @Import}, so the injection cannot leak.
 */
@Configuration
public class FailingExtractionUpdateConfig {

    /**
     * Registers a {@code @Primary} extraction repository that throws on
     * {@code updateEditableFields}, but only after asserting the
     * metadata write already landed in the same transaction. The
     * assertion is what makes the injected failure prove the ordering
     * the service documents instead of passing for the wrong reason.
     */
    @Bean
    @Primary
    TicketExtractionRepository failingExtractionRepository(
            JdbcTicketExtractionRepository delegate, JdbcTemplate jdbc) {
        return new DelegatingExtractionRepository(delegate) {
            @Override
            public boolean updateEditableFields(long ticketId, long ownerId,
                                                EditableFields fields) {
                String currentTitle = jdbc.queryForObject(
                        "SELECT title FROM tickets WHERE id = ?", String.class, ticketId);
                if (!"Weekly shop".equals(currentTitle)) {
                    throw new AssertionError(
                            "metadata write must land before the extraction update; saw: "
                                    + currentTitle);
                }
                throw new IllegalStateException("simulated extraction write failure");
            }
        };
    }

    /** Delegates everything; the override above is the only behaviour. */
    abstract static class DelegatingExtractionRepository
            implements TicketExtractionRepository {

        private final TicketExtractionRepository delegate;

        private DelegatingExtractionRepository(TicketExtractionRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<TicketExtraction> findByTicketId(long ticketId, long ownerId) {
            return delegate.findByTicketId(ticketId, ownerId);
        }

        @Override
        public TicketExtraction save(TicketExtraction extraction) {
            return delegate.save(extraction);
        }

        @Override
        public TicketExtraction replace(TicketExtraction extraction) {
            return delegate.replace(extraction);
        }

        @Override
        public boolean updateEditableFields(long ticketId, long ownerId,
                                            EditableFields fields) {
            return delegate.updateEditableFields(ticketId, ownerId, fields);
        }
    }
}