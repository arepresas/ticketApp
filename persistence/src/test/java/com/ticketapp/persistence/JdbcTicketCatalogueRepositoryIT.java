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

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
        for (UUID id : List.of(OWNER, OTHER)) {
            seedOwner(jdbc, id, "owner-" + id);
        }
    }

    /** A shop, a product, a price and one line wired to a ticket. */
    private UUID seedNormalisedTicket(UUID owner) {
        UUID shopId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shops (id, name, normalised_name, created_at)
                VALUES (?, 'it-shop', 'it-shop', now())
                """, shopId);
        UUID productId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, name, normalised_name, created_at)
                VALUES (?, 'it-bread', 'it-bread', now())
                """, productId);
        UUID ticketId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tickets (id, owner_id, title, status, created_at, updated_at)
                VALUES (?, ?, 't', 'DONE', now(), now())
                """, ticketId, owner);
        jdbc.update("UPDATE tickets SET shop_id = ? WHERE id = ?", shopId, ticketId);
        UUID priceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prices (id, product_id, ticket_id, amount, created_at, updated_at)
                VALUES (?, ?, ?, 2.50, now(), now())
                """, priceId, productId, ticketId);
        jdbc.update("""
                INSERT INTO line_tickets (id, ticket_id, product_id, price_id,
                                         quantity, line_total, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 2.50, now(), now())
                """, UUID.randomUUID(), ticketId, productId, priceId);
        return ticketId;
    }

    @Test
    void returnsShopAndLinesInReceiptOrder() {
        UUID ticketId = seedNormalisedTicket(OWNER);

        TicketCatalogue got = repository.findByTicketId(ticketId, OWNER).orElseThrow();

        assertThat(got.shop().normalisedName()).isEqualTo("it-shop");
        assertThat(got.lines()).hasSize(1);
        assertThat(got.lines().getFirst().productName()).isEqualTo("it-bread");
        assertThat(got.lines().getFirst().pricePerUnit()).isEqualByComparingTo("2.50");
    }

    @Test
    void anotherOwnerSeesNothing() {
        UUID ticketId = seedNormalisedTicket(OWNER);

        // Not an exception, not someone else's data: the same empty
        // answer as a ticket that does not exist.
        assertThat(repository.findByTicketId(ticketId, OTHER)).isEmpty();
    }

    @Test
    void unknownTicketIsEmpty() {
        assertThat(repository.findByTicketId(UUID.randomUUID(), OWNER)).isEmpty();
    }

    @Test
    void softDeletedTicketIsInvisible() {
        // The soft-delete sink must not be distinguishable from a
        // missing ticket: this port replaced a controller that used
        // to pre-read the ticket, so the status filter has to live
        // here or a deleted ticket keeps serving its catalogue.
        UUID ticketId = seedNormalisedTicket(OWNER);
        jdbc.update("UPDATE tickets SET status = 'DELETED' WHERE id = ?", ticketId);

        assertThat(repository.findByTicketId(ticketId, OWNER)).isEmpty();
    }

    @Test
    void ticketWithoutShopIsEmpty() {
        UUID ticketId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tickets (id, owner_id, title, status, created_at, updated_at)
                VALUES (?, ?, 't', 'OPEN', now(), now())
                """, ticketId, OWNER);

        assertThat(repository.findByTicketId(ticketId, OWNER)).isEmpty();
    }

    @Test
    void ticketWithoutLinesIsEmpty() {
        UUID shopId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shops (id, name, normalised_name, created_at)
                VALUES (?, 'it-shop', 'it-shop', now())
                """, shopId);
        UUID ticketId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tickets (id, owner_id, title, status, shop_id,
                                     created_at, updated_at)
                VALUES (?, ?, 't', 'DONE', ?, now(), now())
                """, ticketId, OWNER, shopId);

        assertThat(repository.findByTicketId(ticketId, OWNER)).isEmpty();
    }
}
