package com.ticketapp.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketTest {

    @Test
    void openCreatesTicketWithOpenStatus() {
        Ticket t = Ticket.open("Bug", "Login broken");
        assertNotNull(t.id());
        assertEquals("Bug", t.title());
        assertEquals("Login broken", t.description());
        assertEquals(Ticket.Status.OPEN, t.status());
    }

    @Test
    void withStatusUpdatesStatusAndPreservesIdentity() {
        Ticket t = Ticket.open("Bug", "x");
        Ticket moved = t.withStatus(Ticket.Status.IN_PROGRESS);

        assertEquals(Ticket.Status.IN_PROGRESS, moved.status());
        assertEquals(t.id(), moved.id());
        assertEquals(t.title(), moved.title());
        assertEquals(t.description(), moved.description());
        assertEquals(t.createdAt(), moved.createdAt());
        assertNotNull(moved.updatedAt());
        assertTrue(!moved.updatedAt().isBefore(t.updatedAt()),
                "updatedAt must not move backwards");
    }

    @Test
    void statusEnumHasExpectedValues() {
        assertEquals(4, List.of(Ticket.Status.values()).size());
    }
}
