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
        // Rendered label = "Name (kg)" for a weighed product, bare name
        // otherwise. UNIT is not spelled out: "(unit)" on every row
        // would be noise, and "Milk" already reads as a piece. The
        // datalist's <option value="..."/> uses the label, so the input
        // picks the full label string on selection — not ideal for
        // downstream matching. The SPA extracts the canonical name from
        // the picked id when wiring up the line.
        String label = p.unit() == com.ticketapp.domain.QuantityUnit.KG
                ? p.name() + " (" + p.unit().wireName() + ")"
                : p.name();
        return new ProductSearchResponse(p.id(), p.name(), p.unit().wireName(), label);
    }
}
