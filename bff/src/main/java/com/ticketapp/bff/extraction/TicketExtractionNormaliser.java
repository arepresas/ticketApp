package com.ticketapp.bff.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.LineTicket;
import com.ticketapp.domain.LineTicketRepository;
import com.ticketapp.domain.Price;
import com.ticketapp.domain.PriceRepository;
import com.ticketapp.domain.Product;
import com.ticketapp.domain.ProductRepository;
import com.ticketapp.domain.Shop;
import com.ticketapp.domain.ShopRepository;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Snapshots the structured payload of a validated ticket into the
 * normalised catalogue. Triggered when a ticket transitions to
 * {@code DONE}; idempotent on re-validation so calling it twice is
 * safe.
 *
 * <p>The snapshot fans out into four tables so downstream analytics
 * can join without parsing the JSONB column on
 * {@code ticket_extractions.products}:
 * <ol>
 *   <li>{@code shops} — one row per unique merchant (matched by
 *       normalised name). Address / phone / tax id / website are
 *       lifted from {@code extraction_payload.merchant.*} when the
 *       provider emits them.</li>
 *   <li>{@code products} — one row per unique {@code (name, unit)}
 *       tuple (matched by normalised name + unit).</li>
 *   <li>{@code prices} — one row per unique
 *       {@code (product, ticket, amount)} tuple. A product can
 *       carry multiple amounts across tickets; the same ticket
 *       can carry multiple amounts for the same product if the
 *       receipt genuinely shows a discount variant.</li>
 *   <li>{@code line_tickets} — one row per
 *       {@code (ticket, product)} pair, with a {@code line_total}
 *       captured as the AI extracted it (may be negative for
 *       discount/credit rows).</li>
 * </ol>
 *
 * <p>Transactions: the whole snapshot runs under a single
 * {@code @Transactional} so a partial apply (mid-line failure)
 * leaves no orphans in the catalogue. Cancellation does NOT call
 * this service — historical prices stay intact for analytics, even
 * when a ticket is reverted to {@code CANCELLED}.
 *
 * <p>Tickets with no extraction row (status {@code ON_ERROR}, or
 * still pending AI) are silently skipped: normalising lines that
 * don't exist would be guessing, and the user's manual edits on
 * the detail screen (which talk to {@code ticket_extractions} only)
 * aren't expected to feed the catalogue.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TicketExtractionNormaliser {

    private final TicketRepository tickets;
    private final TicketExtractionRepository extractions;
    private final ShopRepository shops;
    private final ProductRepository products;
    private final PriceRepository prices;
    private final LineTicketRepository lineTickets;
    private final ObjectMapper objectMapper;

    @Transactional
    public void normaliseOnDone(Ticket ticket) {
        UUID ticketId = ticket.id();
        TicketExtraction extraction = extractions.findByTicketId(ticketId).orElse(null);
        if (extraction == null) {
            log.debug("Skip normalisation: no extraction row for ticket {}", ticketId);
            return;
        }
        if (extraction.products() == null || extraction.products().isEmpty()) {
            log.debug("Skip normalisation: empty products for ticket {}", ticketId);
            return;
        }

        // Step 1 — shop. Anchor once for the whole ticket so we
        // don't repeat the lookup per line. Address / phone / tax
        // id / website are lifted from extraction_payload if the
        // provider emitted them; null fields are passed through and
        // the UPSERT's COALESCE preserves any value already on the
        // row from a previous ticket or a manual PATCH.
        //
        // The resolved shop id is stamped onto the ticket itself
        // (V13 refactor) — every line_tickets row derives its shop
        // by joining back through the ticket. The save is inside
        // this transaction, so a later line failure rolls the
        // shop_id back too (the controller's @Transactional
        // boundary is the unit of consistency).
        ShopContact contact = readShopContact(extraction.extractionPayload());
        Shop shop = resolveShop(extraction.merchant(), contact, Instant.now()).value();
        tickets.save(ticket.withShopId(shop.id()));

        int persistedProducts = 0;
        int persistedPrices = 0;
        int persistedLines = 0;

        // Step 2 — per line. Product → Price → LineTicket. Each
        // step is idempotent: re-validation reuses existing ids.
        //
        // Each line captures its own {@code Instant.now()} so the
        // {@code created_at} column reflects insertion order. A
        // single captured {@code now} reused across all lines would
        // truncate to the same microsecond on Postgres and make the
        // catalogue's ORDER BY created_at non-deterministic — the
        // dashboard would then show line items in random UUID
        // order instead of receipt order.
        for (var line : extraction.products()) {
            Instant lineNow = Instant.now();
            Resolved<Product> product = resolveProduct(
                    line.name(), line.unit(), lineNow);
            if (product.created()) persistedProducts++;

            Resolved<Price> price = resolvePrice(
                    product.value().id(), ticketId, line.pricePerUnit(), lineNow);
            if (price.created()) persistedPrices++;

            LineTicket lineTicket = lineTickets.save(new LineTicket(
                    UUID.randomUUID(),
                    ticketId,
                    product.value().id(),
                    price.value().id(),
                    line.quantity(),
                    line.lineTotal(),
                    lineNow,
                    lineNow));
            persistedLines++;
            log.debug("Linked line_ticket {} (ticket={}, product={}, price={})",
                    lineTicket.id(), ticketId, product.value().id(), price.value().id());
        }

        log.info("Normalised ticket {} (shop={}): +{} product(s), +{} price(s), +{} line(s)",
                ticketId, shop.id(), persistedProducts, persistedPrices, persistedLines);
    }

    private Resolved<Shop> resolveShop(String merchantName, ShopContact contact, Instant now) {
        if (merchantName == null || merchantName.isBlank()) {
            // Defensive: extraction validation upstream would have
            // 400'd on this. If somehow we got here, skip the shop
            // anchor rather than create an empty row.
            throw new IllegalStateException(
                    "Cannot normalise ticket without a merchant name");
        }
        String normalised = Shop.normalisedNameOf(merchantName);
        return resolve(shops.findByNormalisedName(normalised), () -> shops.save(new Shop(
                        UUID.randomUUID(),
                        merchantName,
                        normalised,
                        contact.addressLine(),
                        contact.postalCode(),
                        contact.city(),
                        contact.country(),
                        contact.phone(),
                        contact.taxId(),
                        contact.website(),
                        now)));
    }

    /**
     * Get-or-create in one place: the port's {@code save} returns the
     * row as actually stored (the upsert keeps the original id on
     * conflict), so the caller never has to re-read it, and we know
     * here — not by comparing timestamps afterwards — whether this
     * call created the row or reused it.
     */
    private <T> Resolved<T> resolve(Optional<T> existing, Supplier<T> create) {
        return existing.<Resolved<T>>map(value -> new Resolved<>(value, false))
                .orElseGet(() -> new Resolved<>(create.get(), true));
    }

    /** A resolved catalogue row plus whether this call created it. */
    private record Resolved<T>(T value, boolean created) { }

    /**
     * Lift shop contact info from the {@code extraction_payload}
     * JSONB blob. The {@code shop} object in the prompt schema is
     * optional — older payloads (pre-prompt-update) won't have it
     * and return {@code all-null}, leaving the user to fill contact
     * info via {@code PATCH /api/shops/{id}}. Newer payloads carry
     * whatever the model could read from the receipt header/footer.
     *
     * <p>{@code merchant} stays a top-level string (the store name as
     * printed) — the contact fields live under {@code shop} rather
     * than nesting inside {@code merchant}, so the existing parser
     * keeps treating {@code merchant} as the dedup key while the new
     * fields live in their own object. Malformed JSON is logged and
     * treated as all-null rather than aborting the whole
     * normalisation; the lines are the important part.
     */
    private ShopContact readShopContact(String extractionPayload) {
        if (extractionPayload == null || extractionPayload.isBlank()) {
            return ShopContact.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(extractionPayload);
            JsonNode shop = root.get("shop");
            if (shop == null || !shop.isObject()) {
                return ShopContact.EMPTY;
            }
            return new ShopContact(
                    textOrNull(shop.get("address")),
                    textOrNull(shop.get("postal_code")),
                    textOrNull(shop.get("city")),
                    textOrNull(shop.get("country")),
                    textOrNull(shop.get("phone")),
                    textOrNull(shop.get("tax_id")),
                    textOrNull(shop.get("website")));
        } catch (JsonProcessingException e) {
            log.warn("Skipping shop contact extraction: malformed extraction_payload",
                    e);
            return ShopContact.EMPTY;
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String value = n.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * Best-effort contact info lifted from the extraction payload.
     * Every field nullable; an absent or unparseable payload yields
     * {@link #EMPTY}.
     */
    private record ShopContact(
            String addressLine,
            String postalCode,
            String city,
            String country,
            String phone,
            String taxId,
            String website) {
        static final ShopContact EMPTY = new ShopContact(null, null, null, null, null, null, null);
    }

    private Resolved<Product> resolveProduct(String name, String unit, Instant now) {
        String normalised = Product.normalisedNameOf(name);
        return resolve(products.findByNormalisedName(normalised, unit), () -> products.save(new Product(
                UUID.randomUUID(),
                name,
                normalised,
                unit,
                now)));
    }

    private Resolved<Price> resolvePrice(UUID productId, UUID ticketId, BigDecimal amount, Instant now) {
        return resolve(prices.findByProductAndTicket(productId, ticketId, amount),
                () -> prices.save(new Price(
                        UUID.randomUUID(),
                        productId,
                        ticketId,
                        amount,
                        now,
                        now)));
    }
}