package com.ticketapp.persistence;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.TicketSummary;
import com.ticketapp.domain.exceptions.OptimisticLockException;
import com.ticketapp.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        seedOwner(jdbc, OWNER, "owner-it");
        seedOwner(jdbc, OTHER_OWNER, "other-owner-it");
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
    void ownerScopedDeleteFlipsToDeletedAndHides() {
        Ticket t = repository.save(Ticket.open(OWNER, "to-delete", ""));

        boolean removed = repository.deleteById(t.id(), OWNER);

        assertThat(removed).isTrue();
        // Soft delete: the row flips to DELETED and every read
        // treats it as missing — but the row itself survives for
        // audit (raw SQL still sees it).
        assertThat(repository.findById(t.id(), OWNER)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM tickets WHERE id = ?", String.class, t.id()))
                .isEqualTo("DELETED");
    }

    @Test
    void repeatedDeleteReportsFalse() {
        Ticket t = repository.save(Ticket.open(OWNER, "twice", ""));
        assertThat(repository.deleteById(t.id(), OWNER)).isTrue();

        assertThat(repository.deleteById(t.id(), OWNER)).isFalse();
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
    void insertWithUnknownOwnerViolatesFk() {
        // Since V19 the database enforces what the SQL layer always
        // assumed: no app_users row, no ticket.
        assertThatThrownBy(() -> repository.save(
                Ticket.open(UUID.randomUUID(), "orphan", "")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void deleteUserCascadesTickets() {
        // GDPR-style erasure: removing the user removes their
        // tickets (and, by cascade, extractions / prices / lines).
        Ticket t = repository.save(Ticket.open(OWNER, "mine", ""));

        jdbc.update("DELETE FROM app_users WHERE id = ?", OWNER);

        assertThat(repository.findById(t.id(), OWNER)).isEmpty();
    }

    @Test
    void findByStatusInWithNullOwnerReturnsEmpty() {
        // Defensive: a null owner must not bypass the scope and
        // return all tickets — the scheduler uses
        // findOpenForExtraction for system-scope work, never this.
        repository.save(Ticket.open(OWNER, "any.pdf", "x"));

        assertThat(repository.findByStatusIn(Set.of(Ticket.Status.OPEN), null)).isEmpty();
    }

    @Test
    void saveBumpsVersionOnUpdate() {
        Ticket created = repository.save(Ticket.open(OWNER, "v.pdf", ""));
        assertThat(created.version()).isEqualTo(0);

        Ticket updated = repository.save(created.withStatus(Ticket.Status.DONE));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(repository.findById(created.id(), OWNER).orElseThrow().version())
                .isEqualTo(1);
    }

    @Test
    void staleSaveThrowsOptimisticLockAndKeepsWinner() {
        Ticket created = repository.save(Ticket.open(OWNER, "race.pdf", ""));
        Ticket winner = repository.save(created.withStatus(Ticket.Status.DONE));

        assertThatThrownBy(() -> repository.save(created.withStatus(Ticket.Status.CANCELLED)))
                .isInstanceOf(OptimisticLockException.class);

        Ticket loaded = repository.findById(created.id(), OWNER).orElseThrow();
        assertThat(loaded.status()).isEqualTo(Ticket.Status.DONE);
        assertThat(loaded.version()).isEqualTo(winner.version());
    }

    @Test
    void recordAttemptDoesNotMutateTicketStatus() {
        Ticket t = repository.save(Ticket.open(OWNER, "r.png", "r"));
        repository.recordAttempt(t.id());
        Ticket reloaded = repository.findById(t.id(), OWNER).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(Ticket.Status.OPEN);
    }

    @Test
    void findSummariesReportsSizeWithoutBlobs() {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        Ticket t = repository.save(Ticket.open(OWNER, "r.png", "x",
                "image/png", "r.png", bytes));

        List<TicketSummary> summaries = repository.findSummariesByStatusIn(
                Set.of(Ticket.Status.OPEN), OWNER);

        assertThat(summaries).extracting(TicketSummary::id).contains(t.id());
        TicketSummary got = summaries.stream()
                .filter(s -> s.id().equals(t.id())).findFirst().orElseThrow();
        assertThat(got.sizeBytes()).isEqualTo(bytes.length);
        assertThat(got.title()).isEqualTo("r.png");
    }

    @Test
    void findSummariesMapsMissingFileToNullSize() {
        // octet_length(NULL) is NULL: a metadata-only ticket must
        // read back as null size (same as the detail path), not a
        // fake 0-byte size.
        Ticket t = repository.save(Ticket.open(OWNER, "meta", ""));

        List<TicketSummary> summaries = repository.findSummariesByStatusIn(
                Set.of(Ticket.Status.OPEN), OWNER);

        TicketSummary got = summaries.stream()
                .filter(s -> s.id().equals(t.id())).findFirst().orElseThrow();
        assertThat(got.sizeBytes()).isNull();
    }

    @Test
    void saveInsertEnforcesZeroVersion() {
        // A previously-read copy whose row vanished out-of-band
        // (raw SQL delete here; user-erase cascades in prod) must
        // not smuggle its stale version into the re-insert: new
        // rows always start at 0.
        Ticket created = repository.save(Ticket.open(OWNER, "gone.pdf", ""));
        Ticket stale = repository.save(created.withStatus(Ticket.Status.DONE));
        jdbc.update("DELETE FROM tickets WHERE id = ?", created.id());

        Ticket reinserted = repository.save(stale);

        assertThat(reinserted.version()).isEqualTo(0);
        assertThat(repository.findById(created.id(), OWNER).orElseThrow().version())
                .isEqualTo(0);
    }

    @Test
    void findSummariesIsOwnerScoped() {
        Ticket mine = repository.save(Ticket.open(OWNER, "mine", ""));
        Ticket theirs = repository.save(Ticket.open(OTHER_OWNER, "theirs", ""));

        List<TicketSummary> summaries = repository.findSummariesByStatusIn(
                Set.of(Ticket.Status.OPEN), OWNER);

        assertThat(summaries).extracting(TicketSummary::id)
                .contains(mine.id())
                .doesNotContain(theirs.id());
    }

    @Test
    void findOpenForExtractionExcludesExtractedTickets() {
        // The anti-join lives in SQL: an OPEN ticket with an
        // extraction row must not surface, without the job
        // loading every extracted id into memory.
        Ticket pending = repository.save(Ticket.open(OWNER, "pending.png", ""));
        Ticket done = repository.save(Ticket.open(OWNER, "done.png", ""));
        jdbc.update(
                "INSERT INTO ticket_extractions (ticket_id, merchant, purchase_date, category,"
                        + " products, total_amount, currency, model, extracted_at,"
                        + " raw_response_text, extraction_payload)"
                        + " VALUES (?, 'Mercadona', CURRENT_DATE, 'food', '[]'::jsonb,"
                        + " 1.20, 'EUR', 'MiniMax-M3', now(), '{}', '{}'::jsonb)",
                done.id());

        assertThat(repository.findOpenForExtraction(10)).extracting(Ticket::id)
                .contains(pending.id())
                .doesNotContain(done.id());
    }
}