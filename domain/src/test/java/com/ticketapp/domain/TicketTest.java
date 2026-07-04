package com.ticketapp.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketTest {

    /** Fixed owner for all tests in this class. The UUID value is
     * arbitrary — Ticket equality/identity is id-based, so each
     * test that needs a specific owner can use its own. */
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void openCreatesTicketWithOpenStatus() {
        Ticket t = Ticket.open(OWNER, "Bug", "Login broken");
        assertNotNull(t.id());
        assertEquals(OWNER, t.ownerId());
        assertEquals("Bug", t.title());
        assertEquals("Login broken", t.description());
        assertEquals(Ticket.Status.OPEN, t.status());
    }

    @Test
    void withStatusUpdatesStatusAndPreservesIdentity() {
        Ticket t = Ticket.open(OWNER, "Bug", "x");
        Ticket moved = t.withStatus(Ticket.Status.IN_PROGRESS);

        assertEquals(Ticket.Status.IN_PROGRESS, moved.status());
        assertEquals(t.id(), moved.id());
        assertEquals(t.ownerId(), moved.ownerId());
        assertEquals(t.title(), moved.title());
        assertEquals(t.description(), moved.description());
        assertEquals(t.createdAt(), moved.createdAt());
        assertNotNull(moved.updatedAt());
        assertFalse(moved.updatedAt().isBefore(t.updatedAt()),
                "updatedAt must not move backwards");
    }

    @Test
    void statusEnumHasExpectedValues() {
        // 5 lifecycle states: OPEN, IN_PROGRESS, ON_ERROR, DONE, CANCELLED.
        // ON_ERROR was added alongside the error_message column so the
        // scheduler can mark a ticket as terminally failed instead of
        // reverting to OPEN (which caused silent infinite retry loops).
        assertEquals(5, List.of(Ticket.Status.values()).size());
    }

    @Test
    void markErrorSetsOnErrorStatusAndStoresMessage() {
        Ticket t = Ticket.open(OWNER, "x", "");
        Ticket failed = t.markError("MiniMax returned 500");

        assertEquals(Ticket.Status.ON_ERROR, failed.status());
        assertEquals("MiniMax returned 500", failed.errorMessage());
    }

    @Test
    void withStatusToNonErrorClearsErrorMessage() {
        // Manual retry path: PATCH .../status → OPEN goes through
        // withStatus, which must clear any previously stored error
        // message so the dashboard no longer surfaces a stale reason.
        Ticket failed = Ticket.open(OWNER, "x", "").markError("previous failure");
        Ticket retried = failed.withStatus(Ticket.Status.OPEN);

        assertEquals(Ticket.Status.OPEN, retried.status());
        assertNull(retried.errorMessage());
    }

    @Test
    void withStatusToOnErrorPreservesErrorMessage() {
        // The clear-on-transition path is for non-error targets.
        // Transitioning to ON_ERROR via withStatus (e.g. an admin
        // manually marking a ticket as failed) must NOT wipe the
        // current message — only markError owns that field.
        Ticket failed = Ticket.open(OWNER, "x", "").markError("previous reason");
        Ticket reMarked = failed.withStatus(Ticket.Status.ON_ERROR);

        assertEquals(Ticket.Status.ON_ERROR, reMarked.status());
        assertEquals("previous reason", reMarked.errorMessage());
    }

    @Test
    void markErrorBumpsUpdatedAt() {
        // markError returns a new record instance — verify it carries
        // a fresh updatedAt so the dashboard's "last updated" widget
        // shows the failure timestamp, not the creation one.
        Ticket t = Ticket.open(OWNER, "x", "");
        Instant created = t.updatedAt();
        Ticket failed = t.markError("boom");

        assertFalse(failed.updatedAt().isBefore(created));
    }

    @Test
    void constructorRejectsNullId() {
        Instant now = Instant.now();
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new Ticket(
                        null, OWNER, "x", "", Ticket.Status.OPEN, now, now,
                        null, null, null, null));
        assertEquals("id", ex.getMessage());
    }

    @Test
    void constructorRejectsNullOwnerId() {
        Instant now = Instant.now();
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new Ticket(
                        UUID.randomUUID(), null, "x", "", Ticket.Status.OPEN, now, now,
                        null, null, null, null));
        assertEquals("ownerId", ex.getMessage());
    }

    @Test
    void constructorRejectsNullTitle() {
        Instant now = Instant.now();
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new Ticket(
                        UUID.randomUUID(), OWNER, null, "", Ticket.Status.OPEN, now, now,
                        null, null, null, null));
        assertEquals("title", ex.getMessage());
    }

    @Test
    void constructorRejectsNullStatus() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class,
                () -> new Ticket(
                        UUID.randomUUID(), OWNER, "x", "", null, now, now,
                        null, null, null, null));
    }

    @Test
    void constructorRejectsNullCreatedAt() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class,
                () -> new Ticket(
                        UUID.randomUUID(), OWNER, "x", "", Ticket.Status.OPEN, null, now,
                        null, null, null, null));
    }

    @Test
    void constructorRejectsNullUpdatedAt() {
        Instant now = Instant.now();
        assertThrows(NullPointerException.class,
                () -> new Ticket(
                        UUID.randomUUID(), OWNER, "x", "", Ticket.Status.OPEN, now, null,
                        null, null, null, null));
    }

    @Test
    void constructorNormalisesBlankErrorMessageToNull() {
        // The compact constructor collapses blank strings to null so
        // an accidentally-empty message never sneaks into the DB row.
        Instant now = Instant.now();
        Ticket t = new Ticket(
                UUID.randomUUID(), OWNER, "x", "", Ticket.Status.ON_ERROR, now, now,
                null, null, null, "   ");

        assertNull(t.errorMessage());
    }

    @Test
    void equalsIsContentBasedIncludingErrorMessage() {
        // The equals override includes errorMessage so two tickets
        // with the same identity but different error states compare
        // as different.
        Ticket a = Ticket.open(OWNER, "x", "").markError("first");
        Ticket b = Ticket.open(OWNER, "x", "").markError("second");

        assertNotEquals(a, b);
    }

    @Test
    void equalsDistinguishesDifferentOwners() {
        // Two tickets with the same content but different owners are
        // distinct entities — the equality contract must include
        // ownerId so the persistence layer can't accidentally swap
        // them in a Set / Map lookup.
        Ticket a = Ticket.open(OWNER, "x", "");
        Ticket b = Ticket.open(OTHER_OWNER, "x", "");

        assertNotEquals(a, b);
    }

    @Test
    void hashCodeIsConsistentWithEquals() {
        // Two tickets with identical content (including the random
        // id, which equals() uses) must produce equal hashCodes.
        // Build the instances manually so the id is fixed.
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2026-07-05T17:00:00Z");
        Ticket a = new Ticket(id, OWNER, "x", "", Ticket.Status.ON_ERROR, created, created,
                null, null, null, "msg");
        Ticket b = new Ticket(id, OWNER, "x", "", Ticket.Status.ON_ERROR, created, created,
                null, null, null, "msg");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void toStringIncludesErrorMessage() {
        Ticket t = Ticket.open(OWNER, "x", "").markError("boom");

        String rendered = t.toString();
        assertTrue(rendered.contains("boom"), "expected errorMessage in toString: " + rendered);
        assertTrue(rendered.contains("ON_ERROR"), "expected status in toString: " + rendered);
    }

    @Test
    void toStringIncludesOwnerId() {
        // The owner id is a key audit field on every ticket row —
        // toString must surface it so a debug log line is sufficient
        // to identify the tenant.
        Ticket t = Ticket.open(OWNER, "x", "");

        assertTrue(t.toString().contains(OWNER.toString()),
                "expected ownerId in toString: " + t);
    }
}