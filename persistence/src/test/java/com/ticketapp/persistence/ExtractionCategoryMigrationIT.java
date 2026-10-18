package com.ticketapp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketapp.domain.SpendCategory;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the V24/V25 pair: every stored category was folded onto the
 * closed set, and the column now refuses anything else.
 *
 * <p>These two claims are different and only the second one is what
 * anything depends on — "the changeset ran" is not "the column is
 * constrained" — so the schema is asserted, not just the log.
 */
class ExtractionCategoryMigrationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private long owner;

    @BeforeEach
    void cleanSlate() {
        owner = seedOwner(jdbc, "category-migration");
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    @DisplayName("the category changesets are recorded in DATABASECHANGELOG")
    void changesetsAreRecorded() {
        // Liquibase splits `--changeset ticketapp:<id>` into an author
        // column and an id column, so the filter is on the id half.
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM databasechangelog WHERE author = 'ticketapp' AND id LIKE '20261012%'", String.class);

        assertThat(ids)
                .containsExactlyInAnyOrder(
                        "20261012120000-fold-ticket-extraction-category",
                        "20261012120100-create-spend-category-type",
                        "20261012120200-category-column-to-spend-category-type",
                        "20261012120300-category-column-not-null");
    }

    @Test
    @DisplayName("the column is the spend_category type and is NOT NULL")
    void columnIsTheNativeType() {
        // A CHECK constraint would leave the column VARCHAR(64) with a
        // list of string literals beside it; the type is the point.
        assertThat(jdbc.queryForObject("""
                SELECT udt_name FROM information_schema.columns
                WHERE table_name = 'ticket_extractions' AND column_name = 'category'""", String.class)).isEqualTo("spend_category");

        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'ticket_extractions' AND column_name = 'category'""", String.class)).isEqualTo("NO");
    }

    @Test
    @DisplayName("the type carries exactly the enum's members, in its order")
    void typeCarriesTheMembersInOrder() {
        List<String> labels = jdbc.queryForList("""
                SELECT enumlabel FROM pg_enum
                JOIN pg_type ON pg_type.oid = pg_enum.enumtypid
                WHERE typname = 'spend_category'
                ORDER BY enumsortorder""", String.class);

        // Declaration order is the enum's own order, not alphabetical:
        // an ORDER BY on a native enum sorts by it, so a report gets
        // the intended order for free.
        assertThat(labels).containsExactly("food", "transport", "lodging", "pharmacy", "other");
    }

    @Test
    @DisplayName("the column rejects a value that is not a member")
    void columnRejectsForeignValues() {
        assertThatThrownBy(() -> seedExtraction("gas station")).hasMessageContaining("spend_category");
        assertThatThrownBy(() -> seedExtraction(null)).hasMessageContaining("category");
    }

    @Test
    @DisplayName("every member of the enum is accepted")
    void everyMemberIsAccepted() {
        for (SpendCategory category : SpendCategory.values()) {
            seedExtraction(category.wireName());
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_extractions", Integer.class))
                .isEqualTo(SpendCategory.values().length);
    }

    /**
     * The fold the migration applies to rows written before it. These
     * labels can no longer be inserted — that is the point — so the
     * assertion is on the mapping the runtime performs, which is the
     * same table V24 applies, and on the CHECK closing the door.
     */
    @Test
    @DisplayName("folds the labels the old prompt asked for")
    void foldsLegacyLabels() {
        // restaurant → food and fuel → transport are the two that used
        // to be silently reported as `other` by the read-side CASE.
        assertThat(SpendCategory.fromExtractionValue("restaurant")).isEqualTo(SpendCategory.FOOD);
        assertThat(SpendCategory.fromExtractionValue("fuel")).isEqualTo(SpendCategory.TRANSPORT);
        assertThat(SpendCategory.fromExtractionValue("gas station")).isEqualTo(SpendCategory.OTHER);
        assertThat(SpendCategory.fromExtractionValue(null)).isEqualTo(SpendCategory.OTHER);
    }

    private void seedExtraction(String category) {
        Long ticketId = jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, description, status, created_at, updated_at)
                VALUES (?, 't', '', 'DONE', now(), now()) RETURNING id""", Long.class, owner);
        jdbc.update("""
                INSERT INTO ticket_extractions
                    (ticket_id, merchant, purchase_date, category, products,
                     total_amount, currency, model, extracted_at)
                VALUES (?, 'M', ?, ?::spend_category, '[]'::jsonb, 1.00, 'EUR', 'test', now())""", ticketId, LocalDate.of(2026, Month.JANUARY, 1), category);
    }

    /** The write path stores the enum's name, never the model's label. */
    @Test
    @DisplayName("the repository stores the enum name it is given")
    void repositoryStoresTheMember() {
        // Guards the other direction: the column is only as trustworthy
        // as the code that writes it.
        TicketExtraction extraction = new TicketExtraction(
                0L,
                "M",
                LocalDate.of(2026, Month.JANUARY, 1),
                SpendCategory.TRANSPORT,
                List.of(),
                new BigDecimal("1.00"),
                "EUR",
                "test",
                java.time.Instant.now(),
                "{}",
                null);

        assertThat(extraction.category().storageValue()).isEqualTo("transport");
    }
}
