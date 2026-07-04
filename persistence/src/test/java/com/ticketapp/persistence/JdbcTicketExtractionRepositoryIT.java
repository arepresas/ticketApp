package com.ticketapp.persistence;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.support.AbstractPostgresIntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT coverage for {@link JdbcTicketExtractionRepository}.
 *
 * <p>Verifies the JSONB round-trip (the schema is novel for this
 * project) and the cascade delete behaviour on the tickets FK.
 *
 * <p>Uses the shared {@link AbstractPostgresIntegrationTest} so the
 * Postgres container is reused across the suite — see
 * testing.md §Pyramid.
 */
class JdbcTicketExtractionRepositoryIT extends AbstractPostgresIntegrationTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    TicketRepository tickets;

    @Autowired
    TicketExtractionRepository extractions;

    @Autowired
    JdbcTicketExtractionRepository jdbcExtractions;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    void saveAndFindRoundTripsProductsJsonb() {
        Ticket t = tickets.save(Ticket.open(OWNER, "r.png", "receipt"));
        TicketExtraction ext = sample(t.id(), "Mercadona", LocalDate.of(2026, Month.JULY, 4),
                List.of(
                        new ProductLine("Tomatoes", new BigDecimal("1.200"), "kg",
                                new BigDecimal("2.50"), new BigDecimal("3.00")),
                        new ProductLine("Bread", new BigDecimal("1"), "unit",
                                new BigDecimal("1.20"), new BigDecimal("1.20"))));

        extractions.save(ext);

        Optional<TicketExtraction> loaded = extractions.findByTicketId(t.id());
        assertThat(loaded).isPresent();
        TicketExtraction got = loaded.get();
        assertThat(got.merchant()).isEqualTo("Mercadona");
        assertThat(got.purchaseDate()).isEqualTo(LocalDate.of(2026, Month.JULY, 4));
        assertThat(got.totalAmount()).isEqualByComparingTo("26.18");
        assertThat(got.currency()).isEqualTo("EUR");
        assertThat(got.model()).isEqualTo("MiniMax-M3");
        assertThat(got.products()).hasSize(2);
        assertThat(got.products().get(0).name()).isEqualTo("Tomatoes");
        assertThat(got.products().get(0).quantity()).isEqualByComparingTo("1.200");
        assertThat(got.products().get(1).lineTotal()).isEqualByComparingTo("1.20");
    }

    @Test
    void recordAttemptDoesNotMutateTicketStatus() {
        UUID ownerId = OWNER;
        Ticket t = tickets.save(Ticket.open(ownerId, "r.png", "r"));
        jdbcExtractions.recordAttempt(t.id());
        Ticket reloaded = tickets.findById(t.id(), ownerId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(Ticket.Status.OPEN);
    }

    @Test
    void findExtractedTicketIdsReturnsAllPersistedIds() {
        Ticket t1 = tickets.save(Ticket.open(OWNER, "a.png", "x"));
        Ticket t2 = tickets.save(Ticket.open(OWNER, "b.png", "y"));
        Ticket t3 = tickets.save(Ticket.open(OWNER, "c.png", "z"));
        extractions.save(sample(t1.id(), "A", LocalDate.of(2026, Month.JANUARY, 1), List.of()));
        extractions.save(sample(t2.id(), "B", LocalDate.of(2026, Month.FEBRUARY, 2), List.of()));
        // t3 has no extraction

        List<UUID> ids = extractions.findExtractedTicketIds();
        assertThat(ids).containsExactlyInAnyOrder(t1.id(), t2.id()).doesNotContain(t3.id());
    }

    @Test
    void deleteTicketCascadesExtraction() {
        Ticket t = tickets.save(Ticket.open(OWNER, "c.png", "z"));
        extractions.save(sample(t.id(), "X", LocalDate.of(2026, Month.JANUARY, 1), List.of()));
        assertThat(extractions.findByTicketId(t.id())).isPresent();

        boolean removed = tickets.deleteById(t.id(), OWNER);
        assertThat(removed).isTrue();
        assertThat(extractions.findByTicketId(t.id())).isEmpty();
    }

    private static TicketExtraction sample(UUID id, String merchant, LocalDate date,
                                           List<ProductLine> products) {
        return new TicketExtraction(id, merchant, date, "food", products,
                new BigDecimal("26.18"), "EUR", "MiniMax-M3", Instant.now(),
                "{\"choices\":[]}");
    }
}