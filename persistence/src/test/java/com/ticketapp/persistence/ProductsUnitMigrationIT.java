package com.ticketapp.persistence;

import com.ticketapp.domain.Product;
import com.ticketapp.domain.QuantityUnit;
import com.ticketapp.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the V22/V23 pair that made {@code products.unit}
 * {@code NOT NULL}: the backfill ran, the constraint holds, and the
 * repository agrees with both.
 *
 * <p>database.md asks for one {@code *IT} per release asserting the
 * changelog is up to date. These tests assert the schema rather than
 * the log alone, because "the changeset is recorded" and "the column
 * rejects a NULL" are different claims and only the second one is the
 * behaviour anything depends on.
 */
class ProductsUnitMigrationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcProductRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM line_tickets");
        jdbc.update("DELETE FROM prices");
        jdbc.update("DELETE FROM products");
    }

    @Test
    @DisplayName("the unit changesets are recorded in DATABASECHANGELOG")
    void changesetsAreRecorded() {
        // Liquibase splits `--changeset ticketapp:<id>` into an author
        // column and an id column, so the filter is on the id half.
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM databasechangelog WHERE author = 'ticketapp' AND id LIKE '20261011%'",
                String.class);

        assertThat(ids).containsExactlyInAnyOrder(
                "20261011120000-backfill-products-unit",
                "20261011120100-products-unit-not-null");
    }

    @Test
    @DisplayName("products.unit rejects NULL")
    void unitColumnRejectsNull() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO products (name, normalised_name, unit, created_at)
                VALUES ('Rice', 'rice', NULL, now())"""))
                .hasMessageContaining("unit");
    }

    @Test
    @DisplayName("the column is NOT NULL in the catalogue")
    void unitColumnIsNotNull() {
        String nullable = jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'products' AND column_name = 'unit'""",
                String.class);

        assertThat(nullable).isEqualTo("NO");
    }

    @Test
    @DisplayName("the repository writes a unit and reads the same one back")
    void repositoryRoundTripsAUnit() {
        Product piece = repository.save(
                new Product(0L, "Rice", "rice", QuantityUnit.UNIT, Instant.now()));
        Product weight = repository.save(
                new Product(0L, "Flour", "flour", QuantityUnit.KG, Instant.now()));

        assertThat(piece.unit()).isEqualTo(QuantityUnit.UNIT);
        assertThat(weight.unit()).isEqualTo(QuantityUnit.KG);
        assertThat(jdbc.queryForObject(
                "SELECT unit FROM products WHERE id = ?", String.class, piece.id()))
                .isEqualTo("unit");
    }

    /**
     * A label from before the enum still loads. The constraint says
     * "not null", not "one of these two" — constraining the values is
     * the follow-up that has to merge the folded rows first, and until
     * then a read must not be the thing that breaks.
     */
    @Test
    @DisplayName("a pre-enum free-text label still loads as UNIT")
    void legacyLabelStillLoads() {
        Long id = jdbc.queryForObject("""
                INSERT INTO products (name, normalised_name, unit, created_at)
                VALUES ('Milk 1L', 'milk 1l', 'L', now()) RETURNING id""", Long.class);

        Product loaded = repository.findAllByIds(List.of(id)).get(id);

        assertThat(loaded.unit()).isEqualTo(QuantityUnit.UNIT);
    }
}