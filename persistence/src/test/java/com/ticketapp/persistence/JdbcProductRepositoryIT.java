package com.ticketapp.persistence;

import com.ticketapp.domain.Product;
import com.ticketapp.domain.ProductRepository;
import com.ticketapp.domain.QuantityUnit;
import com.ticketapp.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT for {@link JdbcProductRepository#searchByNormalisedName(String, int)}.
 * Pins the exact-prefix ILIKE behaviour the SPA's autocomplete
 * depends on: case-insensitivity, limit honoured, ordering, and
 * edge cases (empty input / negative limit / lookalike wildcards).
 *
 * <p>Runs against the shared {@code AbstractPostgresIntegrationTest}
 * Postgres container (Testcontainers under the hood).
 */
class JdbcProductRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcProductRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        // products are referenced by line_tickets/prices via FK;
        // truncate those first so product cleanup is unblocked.
        jdbc.update("DELETE FROM line_tickets");
        jdbc.update("DELETE FROM prices");
        jdbc.update("DELETE FROM products");
    }

    /**
     * Seeds a raw catalogue row, bypassing the repository.
     *
     * <p>The {@code unit} argument is written straight to the column,
     * so a fixture states which member a product is filed under rather
     * than letting the repository decide. The cast is the column's: it
     * is a native {@code quantity_unit}, so a text literal has to be
     * told what it is becoming.
     */
    private long seedProduct(String name, String unit) {
        return jdbc.queryForObject("""
                INSERT INTO products (name, normalised_name, unit, created_at)
                VALUES (?, ?, ?::quantity_unit, ?) RETURNING id""", Long.class, name, name.trim().toLowerCase(), unit,
                Timestamp.from(Instant.now()));
    }

    @Test
    void prefixSearchIsCaseInsensitive() {
        // The SPA sends whatever the user typed; ILIKE handles
        // casing without the user having to know the catalogue's
        // canonical lower-case form.
        seedProduct("Bread", "kg");
        seedProduct("Banana", "unit");
        seedProduct("Milk 1L", "unit");

        List<Product> got = repository.searchByNormalisedName("b", 10);

        assertThat(got).extracting(Product::name)
                .containsExactlyInAnyOrder("Bread", "Banana");
    }

    @Test
    void prefixSearchReturnsAlphabeticallyOrderedMatches() {
        seedProduct("Bread", "unit");
        seedProduct("Banana", "unit");
        seedProduct("Beef", "unit");
        seedProduct("Cheese", "unit"); // shouldn't match

        // Sorted by normalised_name ASC in the SQL — pins the
        // ordering the SPA relies on for the dropdown.
        List<Product> got = repository.searchByNormalisedName("b", 10);

        assertThat(got).extracting(Product::name)
                .containsExactly("Banana", "Beef", "Bread");
    }

    @Test
    void prefixSearchRespectsLimit() {
        for (String n : new String[]{"Apple", "Avocado", "Almond", "Artichoke", "Aubergine", "Apricot"}) {
            seedProduct(n, "unit");
        }

        assertThat(repository.searchByNormalisedName("a", 3)).hasSize(3);
    }

    @Test
    void prefixSearchIgnoresNonMatchingRows() {
        seedProduct("Bread", "unit");
        seedProduct("Milk", "unit");

        // "z" matches nothing — empty result, not an empty-shaped list.
        assertThat(repository.searchByNormalisedName("z", 10)).isEmpty();
    }

    @Test
    void prefixSearchEscapesUserSuppliedWildcards() {
        // "%" and "_" are ILIKE wildcards. A user typing "%" or
        // "_" must not blow the search up into "match everything".
        // The implementation escapes them with '\' so they
        // become literal characters.
        seedProduct("Bread", "unit");
        seedProduct("%50-off coupon", "unit");

        List<Product> got = repository.searchByNormalisedName("%", 10);

        assertThat(got).extracting(Product::name)
                .containsExactly("%50-off coupon");
    }

    @Test
    void prefixSearchReturnsEmptyForEmptyOrNegativeArguments() {
        seedProduct("Bread", "unit");
        seedProduct("Milk", "unit");

        // Empty / null / negative must not throw and must not blow
        // up into "match every row in the catalogue".
        assertThat(repository.searchByNormalisedName("", 10)).isEmpty();
        assertThat(repository.searchByNormalisedName(null, 10)).isEmpty();
        assertThat(repository.searchByNormalisedName("b", 0)).isEmpty();
        assertThat(repository.searchByNormalisedName("b", -1)).isEmpty();
    }

    @Test
    void prefixSearchDistinguishesProductsByUnit() {
        // Two products share the same name but differ on unit
        // ("Bread" with NULL vs "Bread" with "kg"). The ILIKE
        // search is by name only — both rows surface. Callers
        // that need exact-match pick do a follow-up with
        // findByNormalisedName(name, unit).
        seedProduct("Bread", "unit");
        seedProduct("Bread", "kg");

        List<Product> got = repository.searchByNormalisedName("bread", 10);

        assertThat(got).hasSize(2);
        assertThat(got.get(0).name()).isEqualTo("Bread");
        assertThat(got.get(1).name()).isEqualTo("Bread");
    }

    @Test
    void saveReturnsTheStoredRowSoTheForeignKeyStaysValid() {
        // The port contract: save returns the row as actually
        // stored. On a match-key conflict the database keeps the
        // ORIGINAL id, so returning the argument's freshly minted
        // UUID would hand the caller a dangling id to write into
        // line_tickets.product_id.
        Product first = repository.save(
                new Product(nextId(), "Bread", "bread", QuantityUnit.KG, Instant.now()));

        Product second = repository.save(
                new Product(nextId(), "bread", "bread", QuantityUnit.KG, Instant.now()));

        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void saveDistinguishesTheSameNameWithADifferentUnit() {
        Product byPiece = repository.save(
                new Product(nextId(), "Bread", "bread", QuantityUnit.UNIT, Instant.now()));
        Product byWeight = repository.save(
                new Product(nextId(), "Bread", "bread", QuantityUnit.KG, Instant.now()));

        assertThat(byWeight.id()).isNotEqualTo(byPiece.id());
    }

    @Test
    void saveReturnsTheStoredRowForAUnitProduct() {
        // The conflict target uses COALESCE(unit, ''), so the
        // re-read must apply the same normalisation or it would miss
        // the row it just wrote and fall back to the argument.
        Product first = repository.save(
                new Product(nextId(), "Banana", "banana", QuantityUnit.UNIT, Instant.now()));

        Product second = repository.save(
                new Product(nextId(), "banana", "banana", QuantityUnit.UNIT, Instant.now()));

        assertThat(second.id()).isEqualTo(first.id());
    }

    // "No unit" is stored under its own name, not as SQL NULL. The
    // column is NOT NULL since V23 and V22 backfilled the rows that
    // used to spell it NULL — a NULL here would be a row the schema
    // rejects rather than a product state the code models.
    @Test
    void aUnitProductIsStoredUnderItsOwnName() {
        long id = repository.save(
                new Product(nextId(), "Rice", "rice", QuantityUnit.UNIT, Instant.now())).id();

        String stored = jdbc.queryForObject(
                "SELECT unit FROM products WHERE id = ?", String.class, id);

        assertThat(stored).isEqualTo("unit");
        assertThat(repository.findByNormalisedName("rice", QuantityUnit.UNIT))
                .get()
                .extracting(Product::id)
                .isEqualTo(id);
    }

    // A pre-enum row carrying a label the enum no longer holds still
// loads — folding is why the column cannot make the catalogue
    // The two spellings of "no unit" that coexisted while the column
    // was NULLable — a row filed under no unit and one under the
    // literal "unit" — are now the same row. With a native type there
    // is only one way to say "a piece", so the second save resolves to
    // the first instead of leaving a duplicate row behind.
    @Test
    void savingTheSameNameTwiceUnderUnitResolvesToOneRow() {
        long first = repository.save(
                new Product(nextId(), "Milk 1L", "milk 1l", QuantityUnit.UNIT, Instant.now())).id();
        long second = repository.save(
                new Product(nextId(), "Milk 1L", "milk 1l", QuantityUnit.UNIT, Instant.now())).id();

        assertThat(second).isEqualTo(first);
        assertThat(repository.findByNormalisedName("milk 1l", QuantityUnit.UNIT))
                .get()
                .extracting(Product::id)
                .isEqualTo(first);
    }

    @Test
    void aWeightRowIsNotMatchedByAUnitLookup() {
        long kgId = seedProduct("Flour", "kg");

        assertThat(repository.findByNormalisedName("flour", QuantityUnit.UNIT)).isEmpty();
        assertThat(repository.findByNormalisedName("flour", QuantityUnit.KG))
                .get()
                .extracting(Product::id)
                .isEqualTo(kgId);
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private static long nextId() {
        return IDS.incrementAndGet();
    }
}
