package com.ticketapp.failureinjection;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.TicketSummary;
import com.ticketapp.persistence.JdbcTicketRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Failure injection for the transaction tests: a ticket repository that
 * plays a concurrent writer.
 *
 * <p>On {@code findById} it delegates the read, then bumps the row
 * version before the caller can write it back. A service without an
 * optimistic guard would then happily overwrite the bumped row with its
 * stale snapshot; the version-guarded JDBC repository turns the race
 * into a 409 instead. Only the interleaving is simulated — every other
 * operation delegates untouched.
 *
 * <p>Lives outside {@code com.ticketapp.bff} so this {@code @Primary}
 * bean cannot be picked up by the application context's component scan
 * of that package tree and leak into unrelated tests; it is active
 * only via an explicit {@code @Import}. See
 * {@link FailingExtractionUpdateConfig} for the same rationale.
 */
@Configuration
public class ConcurrentWriterTicketRepositoryConfig {

    @Bean
    @Primary
    TicketRepository concurrentWriterTicketRepository(
            JdbcTicketRepository delegate, JdbcTemplate jdbc) {
        return new DelegatingTicketRepository(delegate) {
            @Override
            public Optional<Ticket> findById(long id, long ownerId) {
                Optional<Ticket> found = delegate.findById(id, ownerId);
                // Simulate another writer committing between the
                // service's read and its write-back.
                found.ifPresent(t -> jdbc.update(
                        "UPDATE tickets SET version = version + 1 WHERE id = ?", id));
                return found;
            }
        };
    }

    /** Delegates everything; the override above is the only behaviour. */
    abstract static class DelegatingTicketRepository implements TicketRepository {

        private final TicketRepository delegate;

        private DelegatingTicketRepository(TicketRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Ticket> findById(long id, long ownerId) {
            return delegate.findById(id, ownerId);
        }

        @Override
        public Ticket save(Ticket ticket) {
            return delegate.save(ticket);
        }

        @Override
        public boolean deleteById(long id, long ownerId) {
            return delegate.deleteById(id, ownerId);
        }

        @Override
        public List<Ticket> findByStatusIn(Set<Ticket.Status> statuses, long ownerId) {
            return delegate.findByStatusIn(statuses, ownerId);
        }

        @Override
        public List<TicketSummary> findSummariesByStatusIn(Set<Ticket.Status> statuses,
                                                            long ownerId) {
            return delegate.findSummariesByStatusIn(statuses, ownerId);
        }
    }
}