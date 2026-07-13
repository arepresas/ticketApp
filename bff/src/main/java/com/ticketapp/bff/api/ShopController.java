package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthenticatedUser;
import com.ticketapp.bff.security.CurrentUser;
import com.ticketapp.domain.Shop;
import com.ticketapp.domain.ShopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * REST surface for the shop master catalogue.
 *
 * <p>Shops are global — they aren't scoped per user the way tickets
 * are. The first ticket mentioning "Mercadona" creates the row; any
 * authenticated user can subsequently enrich its contact info (a
 * later ticket's extraction may add the address, a user may add the
 * phone by hand, etc.). This is deliberate: the catalogue is a
 * shared resource and there's no meaningful per-user version of it.
 *
 * <p>Cross-tenant edits are still safe because contact info is
 * descriptive, not authorisation-bearing — a tenant boundary on the
 * shops table would force every analytics query (which spans
 * tenants) to choose one and lose the others.
 *
 * <p>The PATCH endpoint accepts a sparse body: only fields present
 * (non-null) are applied. A field set to {@code null} keeps its
 * existing value. There is no way to clear a field via this endpoint
 * (no field is sent as empty string by design); a future
 * {@code DELETE} field affordance, if ever needed, would go through
 * a separate dedicated endpoint so the simple "set the phone" call
 * doesn't have to distinguish missing vs clearing.
 */
@RestController
@RequestMapping("/api/shops")
@Slf4j
@RequiredArgsConstructor
public class ShopController {

    private final ShopRepository shops;

    /**
     * Fetch the master row for a shop id. Returns 404 when the id
     * is unknown — never 403, because shop existence itself is not
     * a tenant boundary.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ShopResponse> get(@PathVariable UUID id) {
        // Touch the security context so an unauthenticated caller
        // gets a 401 from the resource-server filter rather than a
        // 404 from this method. Belt-and-braces; the filter should
        // already have rejected the request.
        AuthenticatedUser user = CurrentUser.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return shops.findById(id)
                .map(shop -> ResponseEntity.ok(ShopResponse.of(shop)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Partial update of a shop's contact info. The body is sparse:
     * omitted fields keep their existing values. All seven contact
     * fields are optional and accept arbitrary text — no per-country
     * tax-id format validation, no URL parsing, no phone
     * normalisation. The user typed it, we trust it.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ShopResponse> patch(@PathVariable UUID id,
                                              @RequestBody(required = false) UpdateShopRequest body) {
        AuthenticatedUser user = CurrentUser.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (countApplied(body) == 0) {
            // Empty body (either null fields throughout, or the
            // caller sent {}) is a no-op — refuse it so the UI
            // can't accidentally round-trip a "save" with nothing
            // to save. The 400 is friendlier than a 200 that did
            // nothing.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "request body must include at least one contact field to update");
        }
        if (body.country() != null && body.country().length() != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "country must be an ISO 3166-1 alpha-2 code (2 letters)");
        }
        return shops.findById(id)
                .map(existing -> {
                    Shop patched = existing.withContact(
                            body.addressLine(),
                            body.postalCode(),
                            body.city(),
                            body.country(),
                            body.phone(),
                            body.taxId(),
                            body.website());
                    Shop saved = shops.save(patched);
                    log.info("User {} patched shop {} ({} contact fields updated)",
                            user.id(), saved.id(), countApplied(body));
                    return ResponseEntity.ok(ShopResponse.of(saved));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static int countApplied(UpdateShopRequest body) {
        int n = 0;
        if (body.addressLine() != null) n++;
        if (body.postalCode()  != null) n++;
        if (body.city()        != null) n++;
        if (body.country()     != null) n++;
        if (body.phone()       != null) n++;
        if (body.taxId()       != null) n++;
        if (body.website()     != null) n++;
        return n;
    }

    /**
     * Sparse PATCH body. A {@code null} field is "leave alone" — not
     * "clear". An absent JSON key (omitted property) deserialises to
     * {@code null}, so the wire form {@code {"city":"Madrid"}} updates
     * only {@code city} and leaves the other six contact fields
     * untouched. No field is required; an empty body is rejected at
     * the controller level (it would be a no-op and a confusing one).
     */
    public record UpdateShopRequest(
            String addressLine,
            String postalCode,
            String city,
            String country,
            String phone,
            String taxId,
            String website
    ) {}

    /**
     * Full read shape — every column from {@link Shop}. The frontend
     * can render {@code null} fields as "—" rather than " ".
     */
    public record ShopResponse(
            UUID id,
            String name,
            String normalisedName,
            String addressLine,
            String postalCode,
            String city,
            String country,
            String phone,
            String taxId,
            String website,
            Instant createdAt
    ) {
        public static ShopResponse of(Shop shop) {
            return new ShopResponse(
                    shop.id(),
                    shop.name(),
                    shop.normalisedName(),
                    shop.addressLine(),
                    shop.postalCode(),
                    shop.city(),
                    shop.country(),
                    shop.phone(),
                    shop.taxId(),
                    shop.website(),
                    shop.createdAt());
        }
    }
}