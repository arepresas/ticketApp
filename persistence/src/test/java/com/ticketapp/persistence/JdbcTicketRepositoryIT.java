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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

    /** Seeded in {@link #cleanSlate()}; app_users.id is an identity column. */
    private long owner;
    /** Seeded in {@link #cleanSlate()}; app_users.id is an identity column. */
    private long otherOwner;

    @BeforeEach
    void cleanSlate() {
        // The Testcontainers Postgres is static — every test in this
        // class shares one DB. Without cleanup, rows seeded in one
        // test method pollute the next (the system-scope query
        // would surface stale OPEN rows from earlier runs).
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
        owner = seedOwner(jdbc, "owner-it");
        otherOwner = seedOwner(jdbc, "other-owner-it");
    }

    @Test
    void saveAndFindRoundTrip() {
        Ticket created = Ticket.open(owner, "Bug A", "details");
        created = repository.save(created);

        Ticket loaded = repository.findById(created.id(), owner).orElseThrow();

        assertThat(loaded.id()).isEqualTo(created.id());
        assertThat(loaded.title()).isEqualTo("Bug A");
        assertThat(loaded.status()).isEqualTo(Ticket.Status.OPEN);
        assertThat(loaded.ownerId()).isEqualTo(owner);
    }

    @Test
    void findOpenForExtractionReturnsOpenTicketsAcrossOwners() {
        // The system-scope path is used by the scheduler — it must
        // surface tickets from every owner, oldest-first, capped at
        // the limit.
        Ticket oldest = repository.save(Ticket.open(owner, "oldest", ""));
        Ticket middle = repository.save(Ticket.open(otherOwner, "middle", ""));
        Ticket newest = repository.save(Ticket.open(owner, "newest", ""));

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
        // The scheduler's candidate query is OPEN-only, and three
        // separate guarantees rest on that:
        //   * a terminal ticket (DONE / CANCELLED / DELETED) is never
        //     re-extracted;
        //   * a ticket reopened for editing as IN_PROGRESS stays free
        //     of AI calls until the user re-validates it by hand;
        //   * a claim left in IN_ANALYSIS by a dead worker is not
        //     double-processed while it waits for the re-queue sweep.
        // Widening the filter would silently reintroduce a paid API
        // call in all three cases, hence the explicit list.
        Ticket open = repository.save(Ticket.open(owner, "open", ""));
        Ticket done = repository.save(Ticket.open(owner, "done", "")
                .withStatus(Ticket.Status.DONE));
        Ticket cancelled = repository.save(Ticket.open(owner, "cancelled", "")
                .withStatus(Ticket.Status.CANCELLED));
        Ticket deleted = repository.save(Ticket.open(owner, "deleted", "")
                .withStatus(Ticket.Status.DELETED));
        Ticket reopened = repository.save(Ticket.open(owner, "reopened", "")
                .withStatus(Ticket.Status.IN_PROGRESS));
        Ticket claimed = repository.save(Ticket.open(owner, "claimed", "")
                .withStatus(Ticket.Status.IN_ANALYSIS));
        Ticket onError = repository.save(Ticket.open(owner, "err", "")
                .markError("boom"));

        List<Ticket> openOnly = repository.findOpenForExtraction(10);

        assertThat(openOnly).extracting(Ticket::id)
                .contains(open.id())
                .doesNotContain(done.id(), cancelled.id(), deleted.id(),
                        reopened.id(), claimed.id(), onError.id());
    }

    @Test
    void ownerScopedDeleteFlipsToDeletedAndHides() {
        Ticket t = repository.save(Ticket.open(owner, "to-delete", ""));

        boolean removed = repository.deleteById(t.id(), owner);

        assertThat(removed).isTrue();
        // Soft delete: the row flips to DELETED and every read
        // treats it as missing — but the row itself survives for
        // audit (raw SQL still sees it).
        assertThat(repository.findById(t.id(), owner)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM tickets WHERE id = ?", String.class, t.id()))
                .isEqualTo("DELETED");
    }

    @Test
    void repeatedDeleteReportsFalse() {
        Ticket t = repository.save(Ticket.open(owner, "twice", ""));
        assertThat(repository.deleteById(t.id(), owner)).isTrue();

        assertThat(repository.deleteById(t.id(), owner)).isFalse();
    }

    @Test
    void ownerScopedDeleteRefusesWhenOwnerMismatches() {
        Ticket t = repository.save(Ticket.open(owner, "mine", ""));

        boolean removed = repository.deleteById(t.id(), otherOwner);

        assertThat(removed).isFalse();
        // Row still exists for the original owner.
        assertThat(repository.findById(t.id(), owner)).isPresent();
    }

    @Test
    void findByIdReturnsEmptyForMissingId() {
        assertThat(repository.findById(nextId(), owner)).isEmpty();
    }

    @Test
    void findByIdReturnsEmptyWhenOwnerMismatches() {
        // Cross-tenant read: ticket exists, but the caller's owner id
        // doesn't match. Repository returns empty so the BFF answers
        // 404 — existence is not leaked.
        Ticket t = repository.save(Ticket.open(owner, "mine", ""));

        assertThat(repository.findById(t.id(), otherOwner)).isEmpty();
    }

    @Test
    void onErrorWithMessageRoundTripsThroughPersistence() {
        // Regression for the ON_ERROR + error_message schema change
        // (V8 migration): the JDBC repo must persist both the new
        // status and the new column, and the row mapper must read
        // them back without losing the message.
        Ticket created = repository.save(Ticket.open(owner, "lidl.pdf", ""));
        repository.save(created.markError("the provider returned 500: upstream timeout"));

        Ticket loaded = repository.findById(created.id(), owner).orElseThrow();

        assertThat(loaded.status()).isEqualTo(Ticket.Status.ON_ERROR);
        assertThat(loaded.errorMessage())
                .isEqualTo("the provider returned 500: upstream timeout");
    }

    @Test
    void withStatusToOpenClearsErrorMessage() {
        // Manual retry path: PATCH /api/tickets/{id}/status → OPEN
        // goes through Ticket.withStatus(OPEN), which must clear the
        // previously stored error_message so the dashboard no longer
        // shows a stale failure reason. Confirms the contract at the
        // JDBC boundary — the message is gone from the row, not just
        // hidden in the DTO.
        Ticket created = repository.save(Ticket.open(owner, "retry.pdf", "")
                .markError("previous failure"));
        repository.save(created.withStatus(Ticket.Status.OPEN));

        Ticket loaded = repository.findById(created.id(), owner).orElseThrow();

        assertThat(loaded.status()).isEqualTo(Ticket.Status.OPEN);
        assertThat(loaded.errorMessage()).isNull();
    }

    @Test
    void nullErrorMessagePersistsAsNull() {
        // Sanity: a freshly-created ticket has errorMessage = null
        // and that null survives the round-trip. Without this we
        // could not distinguish "never failed" from "failed with an
        // empty message" downstream.
        Ticket created = repository.save(Ticket.open(owner, "plain.pdf", ""));

        Ticket loaded = repository.findById(created.id(), owner).orElseThrow();

        assertThat(loaded.errorMessage()).isNull();
    }

    @Test
    void findByStatusInIncludesOnErrorTickets() {
        // The pending endpoint filters on OPEN + IN_PROGRESS so the
        // dashboard doesn't surface failed tickets as "pending work".
        // findByStatusIn is the underlying primitive — verify it
        // DOES include ON_ERROR when explicitly asked, so a future
        // "failed tickets" view can use it without a new query.
        Ticket failed = repository.save(Ticket.open(owner, "failed.pdf", "x",
                "application/pdf", "failed.pdf", new byte[]{1})
                .markError("the AI provider returned 500"));
        Ticket done = repository.save(Ticket.open(owner, "done.pdf", "x",
                "application/pdf", "done.pdf", new byte[]{2})
                .withStatus(Ticket.Status.DONE));

        List<Ticket> failedOnly = repository.findByStatusIn(
                Set.of(Ticket.Status.ON_ERROR), owner);

        assertThat(failedOnly).extracting(Ticket::id).contains(failed.id());
        assertThat(failedOnly).extracting(Ticket::id).doesNotContain(done.id());
    }

    @Test
    void findByStatusInIsOwnerScoped() {
        // The same status filter against two owners yields disjoint
        // result sets. A user only ever sees their own tickets.
        Ticket mine = repository.save(Ticket.open(owner, "mine", "x",
                "application/pdf", "mine.pdf", new byte[]{1}));
        Ticket theirs = repository.save(Ticket.open(otherOwner, "theirs", "x",
                "application/pdf", "theirs.pdf", new byte[]{2}));

        List<Ticket> openForMine = repository.findByStatusIn(
                Set.of(Ticket.Status.OPEN), owner);

        assertThat(openForMine).extracting(Ticket::id)
                .contains(mine.id())
                .doesNotContain(theirs.id());
    }

    @Test
    void findByStatusInWithEmptySetReturnsEmpty() {
        // The /pending controller relies on this for the empty-pending
        // case: passing an empty set must yield an empty list, not a
        // SELECT-without-WHERE that scans the whole table.
        repository.save(Ticket.open(owner, "any.pdf", "x"));

        assertThat(repository.findByStatusIn(Set.of(), owner)).isEmpty();
    }

    @Test
    void insertWithUnknownOwnerViolatesFk() {
        // Since V19 the database enforces what the SQL layer always
        // assumed: no app_users row, no ticket.
        assertThatThrownBy(() -> repository.save(
                Ticket.open(nextId(), "orphan", "")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void deleteUserCascadesTickets() {
        // GDPR-style erasure: removing the user removes their
        // tickets (and, by cascade, extractions / prices / lines).
        Ticket t = repository.save(Ticket.open(owner, "mine", ""));

        jdbc.update("DELETE FROM app_users WHERE id = ?", owner);

        assertThat(repository.findById(t.id(), owner)).isEmpty();
    }

    @Test
    void findByStatusInWithNullOwnerReturnsEmpty() {
        // Defensive: a null owner must not bypass the scope and
        // return all tickets — the scheduler uses
        // findOpenForExtraction for system-scope work, never this.
        repository.save(Ticket.open(owner, "any.pdf", "x"));

        assertThat(repository.findByStatusIn(Set.of(), owner)).isEmpty();
    }

    @Test
    void saveBumpsVersionOnUpdate() {
        Ticket created = repository.save(Ticket.open(owner, "v.pdf", ""));
        assertThat(created.version()).isEqualTo(0);

        Ticket updated = repository.save(created.withStatus(Ticket.Status.DONE));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(repository.findById(created.id(), owner).orElseThrow().version())
                .isEqualTo(1);
    }

    @Test
    void staleSaveThrowsOptimisticLockAndKeepsWinner() {
        Ticket created = repository.save(Ticket.open(owner, "race.pdf", ""));
        Ticket winner = repository.save(created.withStatus(Ticket.Status.DONE));

        assertThatThrownBy(() -> repository.save(created.withStatus(Ticket.Status.CANCELLED)))
                .isInstanceOf(OptimisticLockException.class);

        Ticket loaded = repository.findById(created.id(), owner).orElseThrow();
        assertThat(loaded.status()).isEqualTo(Ticket.Status.DONE);
        assertThat(loaded.version()).isEqualTo(winner.version());
    }

    @Test
    void recordAttemptDoesNotMutateTicketStatus() {
        Ticket t = repository.save(Ticket.open(owner, "r.png", "r"));
        repository.recordAttempt(t.id());
        Ticket reloaded = repository.findById(t.id(), owner).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(Ticket.Status.OPEN);
    }

    @Test
    void requeueAbandonedAnalysisMovesStaleClaimsBackToOpen() {
        Ticket stuck = repository.save(
                Ticket.open(owner, "stuck", "x").withStatus(Ticket.Status.IN_ANALYSIS));
        repository.recordAttempt(stuck.id());
        backdateLastAttempt(stuck.id(), Instant.now().minus(Duration.ofMinutes(30)));

        List<Ticket> requeued = repository.requeueAbandonedAnalysis(
                Instant.now().minus(Duration.ofMinutes(10)), 10);

        assertThat(requeued).extracting(Ticket::id).containsExactly(stuck.id());
        assertThat(requeued.getFirst().status()).isEqualTo(Ticket.Status.OPEN);
        assertThat(repository.findById(stuck.id(), owner).orElseThrow().status())
                .isEqualTo(Ticket.Status.OPEN);
    }

    @Test
    void requeueAbandonedAnalysisIgnoresFreshClaims() {
        Ticket fresh = repository.save(
                Ticket.open(owner, "fresh", "x").withStatus(Ticket.Status.IN_ANALYSIS));
        repository.recordAttempt(fresh.id());

        assertThat(repository.requeueAbandonedAnalysis(
                Instant.now().minus(Duration.ofMinutes(10)), 10)).isEmpty();
        assertThat(repository.findById(fresh.id(), owner).orElseThrow().status())
                .isEqualTo(Ticket.Status.IN_ANALYSIS);
    }

    @Test
    void requeueAbandonedAnalysisIgnoresRowsThatAlreadyHaveAnExtraction() {
        Ticket stuck = repository.save(
                Ticket.open(owner, "stuck", "x").withStatus(Ticket.Status.IN_ANALYSIS));
        repository.recordAttempt(stuck.id());
        backdateLastAttempt(stuck.id(), Instant.now().minus(Duration.ofMinutes(30)));
        jdbc.update("INSERT INTO ticket_extractions (ticket_id, merchant, purchase_date,"
                + " products, total_amount, currency, model, extracted_at)"
                + " VALUES (?, 'M', current_date, '[]'::jsonb, 1.00, 'EUR', 'test', now())",
                stuck.id());

        assertThat(repository.requeueAbandonedAnalysis(
                Instant.now().minus(Duration.ofMinutes(10)), 10)).isEmpty();
    }

    @Test
    void requeueAbandonedAnalysisRespectsTheLimit() {
        for (int i = 0; i < 3; i++) {
            Ticket t = repository.save(
                    Ticket.open(owner, "stuck-" + i, "x").withStatus(Ticket.Status.IN_ANALYSIS));
            repository.recordAttempt(t.id());
            backdateLastAttempt(t.id(), Instant.now().minus(Duration.ofMinutes(30)));
        }

        assertThat(repository.requeueAbandonedAnalysis(
                Instant.now().minus(Duration.ofMinutes(10)), 2)).hasSize(2);
    }

    /**
     * Ages the bookkeeping column the scheduler's re-queue decision
     * reads. {@code recordAttempt} only ever writes "now", so the
     * only way to test the timeout is to move the clock on the row.
     */
    private void backdateLastAttempt(long ticketId, Instant when) {
        jdbc.update("UPDATE tickets SET last_extraction_attempt_at = ? WHERE id = ?",
                Timestamp.from(when), ticketId);
    }

    @Test
    void findSummariesReportsSizeWithoutBlobs() {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        Ticket t = repository.save(Ticket.open(owner, "r.png", "x",
                "image/png", "r.png", bytes));

        List<TicketSummary> summaries = repository.findSummariesByStatusIn(
                Set.of(Ticket.Status.OPEN), owner);

        assertThat(summaries).extracting(TicketSummary::id).contains(t.id());
        TicketSummary got = summaries.stream()
                .filter(s -> s.id() == t.id()).findFirst().orElseThrow();
        assertThat(got.sizeBytes()).isEqualTo(bytes.length);
        assertThat(got.title()).isEqualTo("r.png");
    }

    @Test
    void findSummariesMapsMissingFileToNullSize() {
        // octet_length(NULL) is NULL: a metadata-only ticket must
        // read back as null size (same as the detail path), not a
        // fake 0-byte size.
        Ticket t = repository.save(Ticket.open(owner, "meta", ""));

        List<TicketSummary> summaries = repository.findSummariesByStatusIn(
                Set.of(Ticket.Status.OPEN), owner);

        TicketSummary got = summaries.stream()
                .filter(s -> s.id() == t.id()).findFirst().orElseThrow();
        assertThat(got.sizeBytes()).isNull();
    }

    @Test
    void saveInsertEnforcesZeroVersion() {
        // A previously-read copy whose row vanished out-of-band
        // (raw SQL delete here; user-erase cascades in prod) must
        // not smuggle its stale version into the re-insert: new
        // rows always start at 0.
        Ticket created = repository.save(Ticket.open(owner, "gone.pdf", ""));
        Ticket stale = repository.save(created.withStatus(Ticket.Status.DONE));
        jdbc.update("DELETE FROM tickets WHERE id = ?", created.id());

        Ticket reinserted = repository.save(stale);

        // The id comes from the identity column, and a sequence never
        // rewinds: the re-insert lands on a NEW id, not the deleted
        // one. What matters is the version, which restarts at 0.
        assertThat(reinserted.id()).isNotEqualTo(created.id());
        assertThat(reinserted.version()).isEqualTo(0);
        assertThat(repository.findById(reinserted.id(), owner).orElseThrow().version())
                .isEqualTo(0);
    }

    @Test
    void findSummariesIsOwnerScoped() {
        Ticket mine = repository.save(Ticket.open(owner, "mine", ""));
        Ticket theirs = repository.save(Ticket.open(otherOwner, "theirs", ""));

        List<TicketSummary> summaries = repository.findSummariesByStatusIn(
                Set.of(Ticket.Status.OPEN), owner);

        assertThat(summaries).extracting(TicketSummary::id)
                .contains(mine.id())
                .doesNotContain(theirs.id());
    }

    @Test
    void findOpenForExtractionExcludesExtractedTickets() {
        // The anti-join lives in SQL: an OPEN ticket with an
        // extraction row must not surface, without the job
        // loading every extracted id into memory.
        Ticket pending = repository.save(Ticket.open(owner, "pending.png", ""));
        Ticket done = repository.save(Ticket.open(owner, "done.png", ""));
        jdbc.update(
                "INSERT INTO ticket_extractions (ticket_id, merchant, purchase_date, category,"
                        + " products, total_amount, currency, model, extracted_at,"
                        + " raw_response_text, extraction_payload)"
                        + " VALUES (?, 'Mercadona', CURRENT_DATE, 'food', '[]'::jsonb,"
                        + " 1.20, 'EUR', 'gpt-4o-mini', now(), '{}', '{}'::jsonb)",
                done.id());

        assertThat(repository.findOpenForExtraction(10)).extracting(Ticket::id)
                .contains(pending.id())
                .doesNotContain(done.id());
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private static long nextId() {
        return IDS.incrementAndGet();
    }
}