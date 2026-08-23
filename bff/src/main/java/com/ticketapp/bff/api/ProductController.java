package com.ticketapp.bff.api;

import com.ticketapp.bff.auth.AuthenticatedUser;
import com.ticketapp.bff.security.CurrentUser;
import com.ticketapp.domain.Product;
import com.ticketapp.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST surface for the product catalogue.
 *
 * <p>Used by the ticket-detail screen to power the line-editor
 * autocomplete and the in-BDD / new-product hint icon. Products
 * are global (the {@link ProductRepository} doesn't carry an
 * owner id), but the endpoint stays behind the auth filter so the
 * catalogue is never visible to anonymous traffic — same posture
 * as {@link ShopController}.
 */
@RestController
@RequestMapping("/api/products")
@Slf4j
@RequiredArgsConstructor
public class ProductController {

    /**
     * Hard cap on the autocomplete payload. The SPA sends
     * {@code limit=10} by default; this guards against a buggy
     * client asking for the entire catalogue.
     */
    static final int MAX_LIMIT = 25;

    static final int DEFAULT_LIMIT = 10;

    private final ProductRepository products;

    /**
     * Prefix search against {@code normalised_name}. Returns up to
     * {@code limit} matches ordered alphabetically. Empty
     * {@code name} returns an empty list — the SPA decides whether
     * to show the full catalogue on its own.
     *
     * @param name   case-insensitive prefix; trimmed before lookup
     *               so trailing whitespace doesn't widen the match
     * @param limit  optional, defaults to {@value #DEFAULT_LIMIT},
     *               clamped to {@link #MAX_LIMIT}
     */
    @GetMapping("/search")
    public List<ProductSearchResponse> search(
            @RequestParam("name") String name,
            @RequestParam(value = "limit", required = false) Integer limit) {
        AuthenticatedUser user = CurrentUser.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = clampLimit(limit);
        return products.searchByNormalisedName(trimmed, effectiveLimit).stream()
                .map(ProductSearchResponse::of)
                .toList();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Wire shape for the autocomplete payload. Trims the columns
     * to just what the SPA renders: id (used as the datalist
     * option value's identity), name (rendered label), and unit
     * (rendered in parentheses next to the label so two products
     * with the same display name but different units stay
     * distinguishable). The {@code normalisedName} is intentionally
     * not exposed — it's a detail of the match logic, not a UI
     * concern.
     */
    public record ProductSearchResponse(
            java.util.UUID id,
            String name,
            String unit,
            String label) {

        static ProductSearchResponse of(Product p) {
            // Rendered label = "Name (unit)" when a unit exists, bare
            // name otherwise. The datalist's <option value="..."/>
            // uses the label, so the input picks the full label
            // string on selection — not ideal for downstream
            // matching. The SPA extracts the canonical name from
            // the picked id when wiring up the line.
            String label = (p.unit() == null || p.unit().isBlank())
                    ? p.name()
                    : p.name() + " (" + p.unit() + ")";
            return new ProductSearchResponse(p.id(), p.name(), p.unit(), label);
        }
    }
}
