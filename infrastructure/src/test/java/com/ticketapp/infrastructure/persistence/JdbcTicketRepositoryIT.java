package com.ticketapp.infrastructure.persistence;

import com.ticketapp.domain.Ticket;
import com.ticketapp.infrastructure.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTicketRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTicketRepository repository;

    @Test
    void saveAndFindRoundTrip() {
        Ticket created = Ticket.open("Bug A", "details");
        repository.save(created);

        Ticket loaded = repository.findById(created.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(created.id());
        assertThat(loaded.title()).isEqualTo("Bug A");
        assertThat(loaded.status()).isEqualTo(Ticket.Status.OPEN);
    }

    @Test
    void findAllReturnsInsertedTickets() {
        Ticket a = repository.save(Ticket.open("A", ""));
        Ticket b = repository.save(Ticket.open("B", ""));

        List<Ticket> all = repository.findAll();

        assertThat(all).extracting(Ticket::id).contains(a.id(), b.id());
    }

    @Test
    void deleteByIdRemovesTicket() {
        Ticket t = repository.save(Ticket.open("to-delete", ""));
        repository.deleteById(t.id());

        assertThat(repository.findById(t.id())).isEmpty();
    }

    @Test
    void findByIdReturnsEmptyForMissingId() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }
}
