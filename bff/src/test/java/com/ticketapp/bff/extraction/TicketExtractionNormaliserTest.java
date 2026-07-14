package com.ticketapp.bff.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.LineTicketRepository;
import com.ticketapp.domain.PriceRepository;
import com.ticketapp.domain.ProductRepository;
import com.ticketapp.domain.Shop;
import com.ticketapp.domain.ShopRepository;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.Ticket.Status;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketExtractionNormaliser} focused on the
 * shop resolution path — the part that lifts contact info from
 * {@code extraction_payload} and threads it into the
 * {@link Shop} master row.
 *
 * <p>No Spring context, no DB. Repositories are mocked so each test
 * pins exactly what the normaliser wrote, not the wider round-trip.
 * The full ticket→lines round-trip is covered by
 * {@link com.ticketapp.bff.api.TicketControllerIT#markAsDoneReusesExistingShopMasterAcrossTickets()}
 * — these tests are the surgical ones for the new code path.
 */
class TicketExtractionNormaliserTest {

    private TicketRepository tickets;
    private TicketExtractionRepository extractions;
    private ShopRepository shops;
    private ProductRepository products;
    private PriceRepository prices;
    private LineTicketRepository lineTickets;
    private ObjectMapper objectMapper;
    private TicketExtractionNormaliser normaliser;

    @BeforeEach
    void setup() {
        tickets = mock(TicketRepository.class);
        extractions = mock(TicketExtractionRepository.class);
        shops = mock(ShopRepository.class);
        products = mock(ProductRepository.class);
        prices = mock(PriceRepository.class);
        lineTickets = mock(LineTicketRepository.class);
        objectMapper = new ObjectMapper();
        normaliser = new TicketExtractionNormaliser(
                tickets, extractions, shops, products, prices, lineTickets, objectMapper);

        // Default mocks: products/prices/lines/shops/tickets return the
        // input as saved (mirrors the round-trip used by the
        // existing IT tests). Individual tests override what's
        // relevant — the existing-shop test stubs shops.save to
        // never be called.
        when(shops.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(products.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(prices.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lineTickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * Build a minimal {@link Ticket} fixture for the normaliseOnDone
     * call. The V13 refactor moved shop_id onto the ticket, so the
     * normaliser now takes the full Ticket (it stamps the resolved
     * shop id back onto it) — the test fixture matches.
     */
    private static Ticket sampleTicket(UUID id) {
        return new Ticket(
                id,
                UUID.randomUUID(),
                "Mercadona receipt",
                "",
                Status.OPEN,
                Instant.parse("2026-07-04T09:00:00Z"),
                Instant.parse("2026-07-04T09:00:00Z"),
                "image/png",
                "mercadona.png",
                new byte[]{1, 2, 3},
                null,
                0,
                null);
    }

    private TicketExtraction extraction(UUID ticketId, String merchant,
                                        String payload, List<ProductLine> lines) {
        return new TicketExtraction(
                ticketId,
                merchant,
                LocalDate.of(2026, Month.JULY, 4),
                "food",
                lines,
                new BigDecimal("3.50"),
                "EUR",
                "MiniMax-M3",
                Instant.parse("2026-07-04T10:00:00Z"),
                "{\"merchant\":\"" + merchant + "\"}",
                payload);
    }

    private ProductLine sampleLine() {
        return new ProductLine("Bread", new BigDecimal("1"), null,
                new BigDecimal("3.50"), new BigDecimal("3.50"));
    }

    @Test
    void firstTimeShopCreationUsesNullContactWhenPayloadAbsent() {
        // extraction_payload is null (today's MiniMax prompt doesn't
        // ask for shop fields). The shop row is created with every
        // contact field null — the user fills them via PATCH later.
        UUID ticketId = UUID.randomUUID();
        when(extractions.findByTicketId(ticketId))
                .thenReturn(Optional.of(extraction(ticketId, "Mercadona", null,
                        List.of(sampleLine()))));
        when(shops.findByNormalisedName("mercadona")).thenReturn(Optional.empty());

        normaliser.normaliseOnDone(sampleTicket(ticketId));

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shops).save(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("Mercadona");
        assertThat(saved.getValue().normalisedName()).isEqualTo("mercadona");
        assertThat(saved.getValue().addressLine()).isNull();
        assertThat(saved.getValue().postalCode()).isNull();
        assertThat(saved.getValue().city()).isNull();
        assertThat(saved.getValue().country()).isNull();
        assertThat(saved.getValue().phone()).isNull();
        assertThat(saved.getValue().taxId()).isNull();
        assertThat(saved.getValue().website()).isNull();
    }

    @Test
    void shopContactLiftedFromExtractionPayload() {
        // The LLM emits a top-level "shop" object with the
        // merchant's contact info, separate from the "merchant"
        // string (which stays the dedup key). The normaliser
        // threads those fields into the shops master row on first
        // encounter; the UPSERT's COALESCE preserves any richer
        // info a later manual PATCH wrote.
        UUID ticketId = UUID.randomUUID();
        String payload = """
                {
                  "merchant": "Mercadona",
                  "shop": {
                    "address": "Calle Mayor 1",
                    "postal_code": "28013",
                    "city": "Madrid",
                    "country": "ES",
                    "phone": "+34 911 22 33 44",
                    "tax_id": "A12345678",
                    "website": "https://mercadona.es"
                  }
                }
                """;
        when(extractions.findByTicketId(ticketId))
                .thenReturn(Optional.of(extraction(ticketId, "Mercadona", payload,
                        List.of(sampleLine()))));
        when(shops.findByNormalisedName("mercadona")).thenReturn(Optional.empty());

        normaliser.normaliseOnDone(sampleTicket(ticketId));

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shops).save(saved.capture());
        Shop shop = saved.getValue();
        assertThat(shop.addressLine()).isEqualTo("Calle Mayor 1");
        assertThat(shop.postalCode()).isEqualTo("28013");
        assertThat(shop.city()).isEqualTo("Madrid");
        assertThat(shop.country()).isEqualTo("ES");
        assertThat(shop.phone()).isEqualTo("+34 911 22 33 44");
        assertThat(shop.taxId()).isEqualTo("A12345678");
        assertThat(shop.website()).isEqualTo("https://mercadona.es");
    }

    @Test
    void shopContactReadsShopObjectNotMerchant() {
        // Defensive: a future schema that puts address inside
        // "merchant" (instead of "shop") must not be silently
        // read — the normaliser looks at "shop" only. This
        // protects against silent regressions if the prompt is
        // refactored without a coordinated test update.
        UUID ticketId = UUID.randomUUID();
        String payload = """
                {
                  "merchant": {
                    "address": "wrong place"
                  }
                }
                """;
        when(extractions.findByTicketId(ticketId))
                .thenReturn(Optional.of(extraction(ticketId, "Lidl", payload,
                        List.of(sampleLine()))));
        when(shops.findByNormalisedName("lidl")).thenReturn(Optional.empty());

        normaliser.normaliseOnDone(sampleTicket(ticketId));

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shops).save(saved.capture());
        assertThat(saved.getValue().addressLine()).isNull();
    }

    @Test
    void partialPayloadLeavesMissingFieldsNull() {
        // Provider emits only some fields. The absent ones stay
        // null and the upsert's COALESCE preserves any value a
        // later manual PATCH wrote.
        UUID ticketId = UUID.randomUUID();
        String payload = """
                {"shop": {"city": "Madrid", "country": "ES"}}
                """;
        when(extractions.findByTicketId(ticketId))
                .thenReturn(Optional.of(extraction(ticketId, "Dia", payload,
                        List.of(sampleLine()))));
        when(shops.findByNormalisedName("dia")).thenReturn(Optional.empty());

        normaliser.normaliseOnDone(sampleTicket(ticketId));

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shops).save(saved.capture());
        Shop shop = saved.getValue();
        assertThat(shop.city()).isEqualTo("Madrid");
        assertThat(shop.country()).isEqualTo("ES");
        assertThat(shop.addressLine()).isNull();
        assertThat(shop.postalCode()).isNull();
        assertThat(shop.phone()).isNull();
        assertThat(shop.taxId()).isNull();
        assertThat(shop.website()).isNull();
    }

    @Test
    void shopObjectMissingLeavesAllContactFieldsNull() {
        // Payload has top-level fields but no "shop" object —
        // the parser is defensive and returns all-null contact
        // info, leaving the rest of the normalisation (lines) to
        // proceed normally.
        UUID ticketId = UUID.randomUUID();
        String payload = """
                {"purchase_date": "2026-07-04", "total_amount": 3.50}
                """;
        when(extractions.findByTicketId(ticketId))
                .thenReturn(Optional.of(extraction(ticketId, "Lidl", payload,
                        List.of(sampleLine()))));
        when(shops.findByNormalisedName("lidl")).thenReturn(Optional.empty());

        normaliser.normaliseOnDone(sampleTicket(ticketId));

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shops).save(saved.capture());
        assertThat(saved.getValue().addressLine()).isNull();
        assertThat(saved.getValue().city()).isNull();
        assertThat(saved.getValue().country()).isNull();
    }

    @Test
    void malformedPayloadDegradesGracefully() {
        // A broken extraction_payload (e.g. a future model
        // revision emits invalid JSON) must not abort the
        // normalisation — the lines are the important part.
        // The shop row is still created, just without contact info.
        UUID ticketId = UUID.randomUUID();
        when(extractions.findByTicketId(ticketId))
                .thenReturn(Optional.of(extraction(ticketId, "Consum",
                        "{not valid json", List.of(sampleLine()))));
        when(shops.findByNormalisedName("consum")).thenReturn(Optional.empty());

        normaliser.normaliseOnDone(sampleTicket(ticketId));

        ArgumentCaptor<Shop> saved = ArgumentCaptor.forClass(Shop.class);
        verify(shops).save(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("Consum");
        assertThat(saved.getValue().addressLine()).isNull();
    }

    @Test
    void existingShopIsReusedNotRecreated() {
        // Re-normalising the same merchant should hit
        // findByNormalisedName, NOT call save again. The contact
        // info on the existing row is preserved by the COALESCE
        // in the upsert; the normaliser doesn't need to pass it
        // through.
        UUID ticketId = UUID.randomUUID();
        Shop existing = new Shop(UUID.randomUUID(), "Mercadona", "mercadona",
                "Calle Mayor 1", "28013", "Madrid", "ES",
                "+34 911 22 33 44", "A12345678", "https://mercadona.es",
                Instant.parse("2026-01-01T00:00:00Z"));
        when(extractions.findByTicketId(ticketId))
                .thenReturn(Optional.of(extraction(ticketId, "Mercadona", null,
                        List.of(sampleLine()))));
        when(shops.findByNormalisedName("mercadona")).thenReturn(Optional.of(existing));

        normaliser.normaliseOnDone(sampleTicket(ticketId));

        verify(shops, never()).save(any());
        // V13 refactor: the shop id is now stamped onto the ticket
        // (not the line). Verify the ticket save captured the
        // resolved shop id and that the line was saved without a
        // shopId (the field no longer exists).
        ArgumentCaptor<Ticket> anchored = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets).save(anchored.capture());
        assertThat(anchored.getValue().shopId()).isEqualTo(existing.id());
        ArgumentCaptor<com.ticketapp.domain.LineTicket> line =
                ArgumentCaptor.forClass(com.ticketapp.domain.LineTicket.class);
        verify(lineTickets).save(line.capture());
        assertThat(line.getValue().ticketId()).isEqualTo(ticketId);
    }

    @Test
    void firstTimeShopIsStampedOntoTheTicket() {
        // The V13 refactor moved the shop id from line_tickets to
        // tickets. When the normaliser creates a new shop row, it
        // must persist the resolved id onto the ticket itself so
        // the controller's catalogue() read can join through the
        // ticket rather than through the (now shopless) line row.
        UUID ticketId = UUID.randomUUID();
        Shop created = new Shop(UUID.randomUUID(), "Consum", "consum",
                null, null, null, null, null, null, null,
                Instant.parse("2026-07-04T10:00:00Z"));
        when(extractions.findByTicketId(ticketId))
                .thenReturn(Optional.of(extraction(ticketId, "Consum", null,
                        List.of(sampleLine()))));
        when(shops.findByNormalisedName("consum")).thenReturn(Optional.empty());
        when(shops.save(any())).thenReturn(created);

        normaliser.normaliseOnDone(sampleTicket(ticketId));

        // The new shop was created once.
        ArgumentCaptor<Shop> savedShop = ArgumentCaptor.forClass(Shop.class);
        verify(shops).save(savedShop.capture());
        // The ticket was updated with the new shop id.
        ArgumentCaptor<Ticket> savedTicket = ArgumentCaptor.forClass(Ticket.class);
        verify(tickets).save(savedTicket.capture());
        assertThat(savedTicket.getValue().shopId()).isEqualTo(created.id());
    }
}