package com.ticketapp.persistence;

import com.ticketapp.domain.TicketCatalogueRepository.TicketCatalogue;
import com.ticketapp.support.AbstractPostgresIntegrationTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract of {@link TicketCatalogueRepository} against a real
 * Postgres.
 *
 * <p>What is worth pinning here and nowhere else: the view is
 * tenant-scoped in SQL (a wrong owner gets the same empty answer as
 * a missing ticket), and the four "no catalogue yet" cases the port
 * folds together all read as empty.
 *
 * <p>Note there is deliberately no test for a line whose product or
 * price master row is gone: the FKs are RESTRICT, so that state is
 * unreachable. The adapter's LEFT JOIN and null projection are
 * defence in depth for a future migration that relaxes them.
 */
class JdbcTicketCatalogueRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTicketCatalogueRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    /** Seeded in {@link #cleanSlate()}; app_users.id is an identity column. */
    private long owner;
    /** Seeded in {@link #cleanSlate()}; app_users.id is an identity column. */
    private long other;

    @BeforeEach
    void cleanSlate() {
        // Delete order follows the foreign keys: lines reference
        // products and prices, prices reference products.
        jdbc.update("DELETE FROM line_tickets");
        jdbc.update("DELETE FROM prices");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM ticket_extractions");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM shops WHERE normalised_name LIKE 'it-%'");
        seedOwner();
    }

    private void seedOwner() {
        owner = seedOwner(jdbc, "owner-it");
        other = seedOwner(jdbc, "other-owner-it");
    }

    /** A shop, a product, a price and one line wired to a ticket. */
    private long seedNormalisedTicket(long owner) {
        long shopId = jdbc.queryForObject("""
                INSERT INTO shops (name, normalised_name, created_at)
                VALUES ('it-shop', 'it-shop', now()) RETURNING id""", Long.class);
        long productId = jdbc.queryForObject("""
                INSERT INTO products (name, normalised_name, created_at)
                VALUES ('it-bread', 'it-bread', now()) RETURNING id""", Long.class);
        long ticketId = jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, status, created_at, updated_at)
                VALUES (?, 't', 'DONE', now(), now()) RETURNING id""", Long.class, owner);
        jdbc.update("UPDATE tickets SET shop_id = ? WHERE id = ?", shopId, ticketId);
        long priceId = jdbc.queryForObject("""
                INSERT INTO prices (product_id, ticket_id, amount, created_at, updated_at)
                VALUES (?, ?, 2.50, now(), now()) RETURNING id""", Long.class, productId, ticketId);
        jdbc.update("""
                INSERT INTO line_tickets (id, ticket_id, product_id, price_id,
                                         quantity, line_total, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 2.50, now(), now())
                """, nextId(), ticketId, productId, priceId);
        return ticketId;
    }

    @Test
    void returnsShopAndLinesInReceiptOrder() {
        long ticketId = seedNormalisedTicket(owner);

        TicketCatalogue got = repository.findByTicketId(ticketId, owner).orElseThrow();

        assertThat(got.shop().normalisedName()).isEqualTo("it-shop");
        assertThat(got.lines()).hasSize(1);
        assertThat(got.lines().getFirst().productName()).isEqualTo("it-bread");
        assertThat(got.lines().getFirst().pricePerUnit()).isEqualByComparingTo("2.50");
    }

    @Test
    void anotherOwnerSeesNothing() {
        long ticketId = seedNormalisedTicket(owner);

        // Not an exception, not someone else's data: the same empty
        // answer as a ticket that does not exist.
        assertThat(repository.findByTicketId(ticketId, other)).isEmpty();
    }

    @Test
    void unknownTicketIsEmpty() {
        assertThat(repository.findByTicketId(nextId(), owner)).isEmpty();
    }

    @Test
    void softDeletedTicketIsInvisible() {
        // The soft-delete sink must not be distinguishable from a
        // missing ticket: this port replaced a controller that used
        // to pre-read the ticket, so the status filter has to live
        // here or a deleted ticket keeps serving its catalogue.
        long ticketId = seedNormalisedTicket(owner);
        jdbc.update("UPDATE tickets SET status = 'DELETED' WHERE id = ?", ticketId);

        assertThat(repository.findByTicketId(ticketId, owner)).isEmpty();
    }

    @Test
    void ticketWithoutShopIsEmpty() {
        long ticketId = jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, status, created_at, updated_at)
                VALUES (?, 't', 'OPEN', now(), now()) RETURNING id""", Long.class, owner);

        assertThat(repository.findByTicketId(ticketId, owner)).isEmpty();
    }

    @Test
    void ticketWithoutLinesIsEmpty() {
        long shopId = jdbc.queryForObject("""
                INSERT INTO shops (name, normalised_name, created_at)
                VALUES ('it-shop', 'it-shop', now()) RETURNING id""", Long.class);
        long ticketId = jdbc.queryForObject("""
                INSERT INTO tickets (owner_id, title, status, shop_id,
                                     created_at, updated_at)
                VALUES (?, 't', 'DONE', ?, now(), now()) RETURNING id""", Long.class, owner, shopId);

        assertThat(repository.findByTicketId(ticketId, owner)).isEmpty();
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private static long nextId() {
        return IDS.incrementAndGet();
    }
}
