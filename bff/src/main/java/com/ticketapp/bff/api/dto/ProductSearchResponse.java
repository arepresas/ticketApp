package com.ticketapp.bff.api.dto;

import com.ticketapp.domain.Product;



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
        long id,
        String name,
        String unit,
        String label) {

    public static ProductSearchResponse of(Product p) {
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
