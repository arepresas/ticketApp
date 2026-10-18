package com.ticketapp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketapp.domain.Product;
import com.ticketapp.domain.QuantityUnit;
import com.ticketapp.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies V23, which made {@code products.unit} {@code NOT NULL}, and
 * V26, which gave the column a type.
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
    @DisplayName("the NOT NULL changeset is recorded in DATABASECHANGELOG")
    void changesetsAreRecorded() {
        // Liquibase splits `--changeset ticketapp:<id>` into an author
        // column and an id column, so the filter is on the id half.
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM databasechangelog WHERE author = 'ticketapp' AND id LIKE '20261011%'", String.class);

        assertThat(ids).containsExactly("20261011120100-products-unit-not-null");
    }

    @Test
    @DisplayName("products.unit rejects NULL")
    void unitColumnRejectsNull() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO products (name, normalised_name, unit, created_at)
                VALUES ('Rice', 'rice', NULL, now())""")).hasMessageContaining("unit");
    }

    @Test
    @DisplayName("the column is NOT NULL in the catalogue")
    void unitColumnIsNotNull() {
        String nullable = jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'products' AND column_name = 'unit'""", String.class);

        assertThat(nullable).isEqualTo("NO");
    }

    @Test
    @DisplayName("the repository writes a unit and reads the same one back")
    void repositoryRoundTripsAUnit() {
        Product piece = repository.save(new Product(0L, "Rice", "rice", QuantityUnit.UNIT, Instant.now()));
        Product weight = repository.save(new Product(0L, "Flour", "flour", QuantityUnit.KG, Instant.now()));

        assertThat(piece.unit()).isEqualTo(QuantityUnit.UNIT);
        assertThat(weight.unit()).isEqualTo(QuantityUnit.KG);
        assertThat(jdbc.queryForObject("SELECT unit FROM products WHERE id = ?", String.class, piece.id()))
                .isEqualTo("unit");
    }

    /**
     * A label from before the enum can no longer be stored. This used to
     * be the tolerant read — the column said "L", the domain said UNIT —
     * and the native type is what retires it: an unrecognised label is
     * now rejected at the database instead of quietly folded on every
     * read. The write boundary (QuantityUnit.fromLabel) is where the
     * fold now happens, once, on the way in.
     */
    @Test
    @DisplayName("a pre-enum free-text label can no longer be stored")
    void legacyLabelIsRejectedByTheType() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO products (name, normalised_name, unit, created_at)
                VALUES ('Milk 1L', 'milk 1l', 'L', now())""")).hasMessageContaining("quantity_unit");
    }

    @Test
    @DisplayName("the column is the quantity_unit type")
    void unitColumnIsTheNativeType() {
        // A VARCHAR with a CHECK beside it would still report
        // character varying here; the type is the point.
        assertThat(jdbc.queryForObject("""
                SELECT udt_name FROM information_schema.columns
                WHERE table_name = 'products' AND column_name = 'unit'""", String.class)).isEqualTo("quantity_unit");
    }
}
