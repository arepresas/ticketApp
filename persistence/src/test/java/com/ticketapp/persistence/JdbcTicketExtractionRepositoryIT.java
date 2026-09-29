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
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
        seedOwner(jdbc, OWNER, "owner-it");
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

        Optional<TicketExtraction> loaded = extractions.findByTicketId(t.id(), OWNER);
        assertThat(loaded).isPresent();
        TicketExtraction got = loaded.get();
        assertThat(got.merchant()).isEqualTo("Mercadona");
        assertThat(got.purchaseDate()).isEqualTo(LocalDate.of(2026, Month.JULY, 4));
        assertThat(got.totalAmount()).isEqualByComparingTo("26.18");
        assertThat(got.currency()).isEqualTo("EUR");
        assertThat(got.model()).isEqualTo("gpt-4o-mini");
        assertThat(got.products()).hasSize(2);
        assertThat(got.products().get(0).name()).isEqualTo("Tomatoes");
        assertThat(got.products().get(0).quantity()).isEqualByComparingTo("1.200");
        assertThat(got.products().get(1).lineTotal()).isEqualByComparingTo("1.20");
    }

    @Test
    void duplicateSaveIsIgnored() {
        // Scheduler retry racing the first write: the second save
        // for the same ticket_id is a no-op, not a PK violation.
        // The pre-check in the orchestrator stays the fast path;
        // this is the safety net for the race window.
        Ticket t = tickets.save(Ticket.open(OWNER, "r.png", "r"));
        TicketExtraction ext = sample(t.id(), "Mercadona",
                LocalDate.of(2026, Month.JULY, 4), List.of());

        extractions.save(ext);
        extractions.save(ext);

        assertThat(extractions.findByTicketId(t.id(), OWNER)).isPresent();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_extractions WHERE ticket_id = ?",
                Integer.class, t.id());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void softDeleteKeepsExtractionForAudit() {
        // Soft delete flips the ticket row but never removes it, so
        // the ON DELETE CASCADE never fires through the app path and
        // the extraction stays queryable for audit. (Physical removal
        // only happens through user-erase cascades.)
        Ticket t = tickets.save(Ticket.open(OWNER, "c.png", "z"));
        extractions.save(sample(t.id(), "X", LocalDate.of(2026, Month.JANUARY, 1), List.of()));
        assertThat(extractions.findByTicketId(t.id(), OWNER)).isPresent();

        boolean removed = tickets.deleteById(t.id(), OWNER);
        assertThat(removed).isTrue();
        // The ticket disappears from every read (status DELETED is
        // filtered out) while the extraction row survives for audit.
        // The read stays owner-scoped, so "survives" never means
        // "visible to another user" — and no endpoint reaches it,
        // because every controller gates on findById first.
        assertThat(tickets.findById(t.id(), OWNER)).isEmpty();
        assertThat(extractions.findByTicketId(t.id(), OWNER)).isPresent();
    }

    @Test
    void migrationRewritesOnlyTheRenamedVendorsModelIds() {
        // Executes the shipped migration rather than a copy of its
        // SQL: the file is read from the classpath and run, so a
        // widened predicate or a renamed file fails here.
        Ticket legacy = tickets.save(Ticket.open(OWNER, "legacy", ""));
        extractions.save(withModel(legacy.id(), "minimax-M3"));
        Ticket modern = tickets.save(Ticket.open(OWNER, "modern", ""));
        extractions.save(withModel(modern.id(), "gpt-4o-mini"));

        runMigration("V21__neutralise_legacy_model_names.sql");

        assertThat(modelOf(legacy.id())).isEqualTo("unknown");
        // Other vendors' ids are real audit data, not leftovers.
        assertThat(modelOf(modern.id())).isEqualTo("gpt-4o-mini");
    }

    @Test
    void migrationKeepsTheAuditColumnNonNull() {
        // The audit trail reads this column; a NULL would blow up
        // ExtractionResponse instead of degrading.
        Ticket t = tickets.save(Ticket.open(OWNER, "any", ""));
        extractions.save(withModel(t.id(), "MiniMax-M3"));

        runMigration("V21__neutralise_legacy_model_names.sql");

        assertThat(modelOf(t.id())).isNotBlank();
    }

    private void runMigration(String file) {
        try (java.io.InputStream in = getClass().getResourceAsStream(
                "/db/changelog/changes/" + file)) {
            assertThat(in).as("migration on the classpath: %s", file).isNotNull();
            String sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            // Strip comment lines BEFORE splitting on ';'. Doing it
            // the other way round breaks on a semicolon inside a
            // comment, which the header does contain.
            String executable = sql.lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .reduce("", (a, b) -> a + b + "\n");
            for (String statement : executable.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private String modelOf(java.util.UUID ticketId) {
        return jdbc.queryForObject(
                "SELECT model FROM ticket_extractions WHERE ticket_id = ?",
                String.class, ticketId);
    }

    private static TicketExtraction withModel(java.util.UUID ticketId, String model) {
        return new TicketExtraction(ticketId, "M",
                LocalDate.of(2026, Month.JANUARY, 1), "food", List.of(),
                new BigDecimal("26.18"), "EUR", model, Instant.now(),
                "{\"choices\":[]}");
    }

    private static TicketExtraction sample(UUID id, String merchant, LocalDate date,
                                           List<ProductLine> products) {
        return new TicketExtraction(id, merchant, date, "food", products,
                new BigDecimal("26.18"), "EUR", "gpt-4o-mini", Instant.now(),
                "{\"choices\":[]}");
    }
}