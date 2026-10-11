package com.ticketapp.persistence;

import com.ticketapp.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies V27: {@code tickets.status} is the native {@code ticket_status}
 * enum and the CHECK that used to stand in for one is gone.
 *
 * <p>The interesting assertion is the last one. Leaving the CHECK in
 * place alongside the type would mean two lists of seven values to keep
 * in step — and a status added to the type but forgotten in the CHECK
 * would fail every write with a constraint error instead of a compile
 * error, which is the failure mode this change exists to remove.
 */
class TicketStatusTypeMigrationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long owner;

    @BeforeEach
    void cleanSlate() {
        owner = seedOwner(jdbc, "status-type-" + java.util.UUID.randomUUID());
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    @DisplayName("the status changesets are recorded in DATABASECHANGELOG")
    void changesetsAreRecorded() {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM databasechangelog WHERE author = 'ticketapp' AND id LIKE '2026101313%'",
                String.class);

        assertThat(ids).containsExactlyInAnyOrder(
                "20261013130000-create-ticket-status-type",
                "20261013130100-ticket-status-column-type",
                "20261013130200-drop-tickets-status-check");
    }

    @Test
    @DisplayName("the column is the ticket_status type")
    void columnIsTheNativeType() {
        assertThat(jdbc.queryForObject("""
                SELECT udt_name FROM information_schema.columns
                WHERE table_name = 'tickets' AND column_name = 'status'""",
                String.class))
                .isEqualTo("ticket_status");
    }

    @Test
    @DisplayName("the type carries the seven statuses in the domain's declaration order")
    void typeCarriesTheStatusesInOrder() {
        List<String> labels = jdbc.queryForList("""
                SELECT enumlabel FROM pg_enum
                JOIN pg_type ON pg_type.oid = pg_enum.enumtypid
                WHERE typname = 'ticket_status'
                ORDER BY enumsortorder""", String.class);

        // Not alphabetical: an ORDER BY on the column follows the
        // declaration, so it matches com.ticketapp.domain.Ticket.Status
        // rather than an accident of the alphabet.
        assertThat(labels).containsExactly("OPEN", "IN_ANALYSIS", "IN_PROGRESS",
                "ON_ERROR", "DONE", "CANCELLED", "DELETED");
    }

    @Test
    @DisplayName("the CHECK constraint is gone")
    void checkConstraintIsDropped() {
        Integer remaining = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'tickets'::regclass
                  AND conname = 'tickets_status_check'""",
                Integer.class);

        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("every status is accepted and a foreign one is not")
    void everyStatusIsAccepted() {
        for (com.ticketapp.domain.Ticket.Status status
                : com.ticketapp.domain.Ticket.Status.values()) {
            seedTicket(status.name());
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class))
                .isEqualTo(com.ticketapp.domain.Ticket.Status.values().length);

        assertThatThrownBy(() -> seedTicket("ARCHIVED"))
                .hasMessageContaining("ticket_status");
    }

    private void seedTicket(String status) {
        jdbc.update("""
                INSERT INTO tickets (owner_id, title, description, status, created_at, updated_at)
                VALUES (?, 't', '', ?::ticket_status, now(), now())""", owner, status);
    }
}