package com.ticketapp.bff.api.dto;

/**
 * Sparse PATCH body for {@code PATCH /api/shops/{id}}. A
 * {@code null} field is "leave alone" — not "clear". An absent JSON
 * key (omitted property) deserialises to {@code null}, so the wire
 * form {@code {"city":"Madrid"}} updates only {@code city} and
 * leaves the other six contact fields untouched. No field is
 * required; an empty body is rejected at the controller level (it
 * would be a no-op and a confusing one).
 */
public record UpdateShopRequest(
        String addressLine,
        String postalCode,
        String city,
        String country,
        String phone,
        String taxId,
        String website
) { }
