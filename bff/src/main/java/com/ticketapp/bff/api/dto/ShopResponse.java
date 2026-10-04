package com.ticketapp.bff.api.dto;

import com.ticketapp.domain.Shop;

import java.time.Instant;


/**
 * Full read shape for shops — every column from {@link Shop}. The
 * frontend can render {@code null} fields as "—" rather than " ".
 */
public record ShopResponse(
        long id,
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
