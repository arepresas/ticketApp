package com.ticketapp.persistence;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTicketRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTicketRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void cleanSlate() {
        // The Testcontainers Postgres is static — every test in this
        // class shares one DB. Without cleanup, rows seeded in one
        // test method pollute the next (the system-scope query
        // would surface stale OPEN rows from earlier runs).
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    void saveAndFindRoundTrip() {
        Ticket created = Ticket.open(OWNER, "Bug A", "details");
        repository.save(created);

        Ticket loaded = repository.findById(created.id(), OWNER).orElseThrow();

        assertThat(loaded.id()).isEqualTo(created.id());
        assertThat(loaded.title()).isEqualTo("Bug A");
        assertThat(loaded.status()).isEqualTo(Ticket.Status.OPEN);
        assertThat(loaded.ownerId()).isEqualTo(OWNER);
    }

    @Test
    void findOpenForExtractionReturnsOpenTicketsAcrossOwners() {
        // The system-scope path is used by the scheduler — it must
        // surface tickets from every owner, oldest-first, capped at
        // the limit.
        Ticket oldest = repository.save(Ticket.open(OWNER, "oldest", ""));
        Ticket middle = repository.save(Ticket.open(OTHER_OWNER, "middle", ""));
        Ticket newest = repository.save(Ticket.open(OWNER, "newest", ""));

        // Three OPEN rows from two owners. The repo's LIMIT clause
        // caps the returned set; ORDER BY created_at ASC drains FIFO.
        // We don't pin exact order here because consecutive saves can
        // produce equal-millisecond timestamps on a fast clock —
        // assert set membership and the FIFO ordering only when the
        // rows have distinct timestamps (added via the wider scan
        // below).
        List<Ticket> firstTwo = repository.findOpenForExtraction(2);
        assertThat(firstTwo).hasSize(2);
        assertThat(firstTwo).extracting(Ticket::id)
                .containsExactlyInAnyOrder(oldest.id(), middle.id());

        // Wider scan: all three rows present.
        List<Ticket> allThree = repository.findOpenForExtraction(10);
        assertThat(allThree).extracting(Ticket::id)
                .containsExactlyInAnyOrder(oldest.id(), middle.id(), newest.id());
    }

    @Test
    void findOpenForExtractionExcludesNonOpenStatuses() {
        Ticket open = repository.save(Ticket.open(OWNER, "open", ""));
        Ticket done = repository.save(Ticket.open(OWNER, "done", "")
                .withStatus(Ticket.Status.DONE));
        Ticket onError = repository.save(Ticket.open(OWNER, "err", "")
                .markError("boom"));

        List<Ticket> openOnly = repository.findOpenForExtraction(10);

        assertThat(openOnly).extracting(Ticket::id)
                .contains(open.id())
                .doesNotContain(done.id(), onError.id());
    }

    @Test
    void ownerScopedDeleteRemovesTicket() {
        Ticket t = repository.save(Ticket.open(OWNER, "to-delete", ""));

        boolean removed = repository.deleteById(t.id(), OWNER);

        assertThat(removed).isTrue();
        assertThat(repository.findById(t.id(), OWNER)).isEmpty();
    }

    @Test
    void ownerScopedDeleteRefusesWhenOwnerMismatches() {
        Ticket t = repository.save(Ticket.open(OWNER, "mine", ""));

        boolean removed = repository.deleteById(t.id(), OTHER_OWNER);

        assertThat(removed).isFalse();
        // Row still exists for the original owner.
        assertThat(repository.findById(t.id(), OWNER)).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForMissingId() {
        assertThat(repository.findById(UUID.randomUUID(), OWNER)).isEmpty();
    }

    @Test
    void findByIdReturnsEmptyWhenOwnerMismatches() {
        // Cross-tenant read: ticket exists, but the caller's owner id
        // doesn't match. Repository returns empty so the BFF answers
        // 404 — existence is not leaked.
        Ticket t = repository.save(Ticket.open(OWNER, "mine", ""));

        assertThat(repository.findById(t.id(), OTHER_OWNER)).isEmpty();
    }

    @Test
    void onErrorWithMessageRoundTripsThroughPersistence() {
        // Regression for the ON_ERROR + error_message schema change
        // (V8 migration): the JDBC repo must persist both the new
        // status and the new column, and the row mapper must read
        // them back without losing the message.
        Ticket created = repository.save(Ticket.open(OWNER, "lidl.pdf", ""));
        repository.save(created.markError("MiniMax returned 500: upstream timeout"));

        Ticket loaded = repository.findById(created.id(), OWNER).orElseThrow();

        assertThat(loaded.status()).isEqualTo(Ticket.Status.ON_ERROR);
        assertThat(loaded.errorMessage())
                .isEqualTo("MiniMax returned 500: upstream timeout");
    }

    @Test
    void withStatusToOpenClearsErrorMessage() {
        // Manual retry path: PATCH /api/tickets/{id}/status → OPEN
        // goes through Ticket.withStatus(OPEN), which must clear the
        // previously stored error_message so the dashboard no longer
        // shows a stale failure reason. Confirms the contract at the
        // JDBC boundary — the message is gone from the row, not just
        // hidden in the DTO.
        Ticket created = repository.save(Ticket.open(OWNER, "retry.pdf", "")
                .markError("previous failure"));
        Ticket retried = created.withStatus(Ticket.Status.OPEN);

        repository.save(retried);
        Ticket loaded = repository.findById(created.id(), OWNER).orElseThrow();

        assertThat(loaded.status()).isEqualTo(Ticket.Status.OPEN);
        assertThat(loaded.errorMessage()).isNull();
    }

    @Test
    void nullErrorMessagePersistsAsNull() {
        // Sanity: a freshly-created ticket has errorMessage = null
        // and that null survives the round-trip. Without this we
        // could not distinguish "never failed" from "failed with an
        // empty message" downstream.
        Ticket created = repository.save(Ticket.open(OWNER, "plain.pdf", ""));

        Ticket loaded = repository.findById(created.id(), OWNER).orElseThrow();

        assertThat(loaded.errorMessage()).isNull();
    }

    @Test
    void findByStatusInIncludesOnErrorTickets() {
        // The pending endpoint filters on OPEN + IN_PROGRESS so the
        // dashboard doesn't surface failed tickets as "pending work".
        // findByStatusIn is the underlying primitive — verify it
        // DOES include ON_ERROR when explicitly asked, so a future
        // "failed tickets" view can use it without a new query.
        Ticket failed = repository.save(Ticket.open(OWNER, "failed.pdf", "x",
                "application/pdf", "failed.pdf", new byte[]{1})
                .markError("MiniMax returned 500"));
        Ticket done = repository.save(Ticket.open(OWNER, "done.pdf", "x",
                "application/pdf", "done.pdf", new byte[]{2})
                .withStatus(Ticket.Status.DONE));

        List<Ticket> failedOnly = repository.findByStatusIn(
                Set.of(Ticket.Status.ON_ERROR), OWNER);

        assertThat(failedOnly).extracting(Ticket::id).contains(failed.id());
        assertThat(failedOnly).extracting(Ticket::id).doesNotContain(done.id());
    }

    @Test
    void findByStatusInIsOwnerScoped() {
        // The same status filter against two owners yields disjoint
        // result sets. A user only ever sees their own tickets.
        Ticket mine = repository.save(Ticket.open(OWNER, "mine", "x",
                "application/pdf", "mine.pdf", new byte[]{1}));
        Ticket theirs = repository.save(Ticket.open(OTHER_OWNER, "theirs", "x",
                "application/pdf", "theirs.pdf", new byte[]{2}));

        List<Ticket> openForMine = repository.findByStatusIn(
                Set.of(Ticket.Status.OPEN), OWNER);

        assertThat(openForMine).extracting(Ticket::id)
                .contains(mine.id())
                .doesNotContain(theirs.id());
    }

    @Test
    void findByStatusInWithEmptySetReturnsEmpty() {
        // The /pending controller relies on this for the empty-pending
        // case: passing an empty set must yield an empty list, not a
        // SELECT-without-WHERE that scans the whole table.
        repository.save(Ticket.open(OWNER, "any.pdf", "x"));

        assertThat(repository.findByStatusIn(Set.of(), OWNER)).isEmpty();
    }

    @Test
    void findByStatusInWithNullOwnerReturnsEmpty() {
        // Defensive: a null owner must not bypass the scope and
        // return all tickets — the scheduler uses
        // findOpenForExtraction for system-scope work, never this.
        repository.save(Ticket.open(OWNER, "any.pdf", "x"));

        assertThat(repository.findByStatusIn(Set.of(Ticket.Status.OPEN), null)).isEmpty();
    }
}