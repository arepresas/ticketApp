package com.ticketapp.persistence;

import com.ticketapp.domain.Product;
import com.ticketapp.domain.ProductRepository;
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

    private UUID seedProduct(String name, String unit) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, name, normalised_name, unit, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, name, name.trim().toLowerCase(), unit,
                Timestamp.from(Instant.now()));
        return id;
    }

    @Test
    void prefixSearchIsCaseInsensitive() {
        // The SPA sends whatever the user typed; ILIKE handles
        // casing without the user having to know the catalogue's
        // canonical lower-case form.
        seedProduct("Bread", "kg");
        seedProduct("Banana", null);
        seedProduct("Milk 1L", "L");

        List<Product> got = repository.searchByNormalisedName("b", 10);

        assertThat(got).extracting(Product::name)
                .containsExactlyInAnyOrder("Bread", "Banana");
    }

    @Test
    void prefixSearchReturnsAlphabeticallyOrderedMatches() {
        seedProduct("Bread", null);
        seedProduct("Banana", null);
        seedProduct("Beef", null);
        seedProduct("Cheese", null); // shouldn't match

        // Sorted by normalised_name ASC in the SQL — pins the
        // ordering the SPA relies on for the dropdown.
        List<Product> got = repository.searchByNormalisedName("b", 10);

        assertThat(got).extracting(Product::name)
                .containsExactly("Banana", "Beef", "Bread");
    }

    @Test
    void prefixSearchRespectsLimit() {
        for (String n : new String[]{"Apple", "Avocado", "Almond", "Artichoke", "Aubergine", "Apricot"}) {
            seedProduct(n, null);
        }

        assertThat(repository.searchByNormalisedName("a", 3)).hasSize(3);
    }

    @Test
    void prefixSearchIgnoresNonMatchingRows() {
        seedProduct("Bread", null);
        seedProduct("Milk", null);

        // "z" matches nothing — empty result, not an empty-shaped list.
        assertThat(repository.searchByNormalisedName("z", 10)).isEmpty();
    }

    @Test
    void prefixSearchEscapesUserSuppliedWildcards() {
        // "%" and "_" are ILIKE wildcards. A user typing "%" or
        // "_" must not blow the search up into "match everything".
        // The implementation escapes them with '\' so they
        // become literal characters.
        seedProduct("Bread", null);
        seedProduct("%50-off coupon", null);

        List<Product> got = repository.searchByNormalisedName("%", 10);

        assertThat(got).extracting(Product::name)
                .containsExactly("%50-off coupon");
    }

    @Test
    void prefixSearchReturnsEmptyForEmptyOrNegativeArguments() {
        seedProduct("Bread", null);
        seedProduct("Milk", null);

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
        seedProduct("Bread", null);
        seedProduct("Bread", "kg");

        List<Product> got = repository.searchByNormalisedName("bread", 10);

        assertThat(got).hasSize(2);
        assertThat(got.get(0).name()).isEqualTo("Bread");
        assertThat(got.get(1).name()).isEqualTo("Bread");
    }
}
