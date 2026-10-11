package com.ticketapp.bff.api.dto;

import java.math.BigDecimal;

/**
 * Wire shape of one product line on a receipt.
 *
 * <p>{@code unit} stays a {@code String} here even though the domain
 * types it maps to is {@link com.ticketapp.domain.QuantityUnit}: this
 * is the transport record, and the conversion is deliberate at the
 * edge rather than hidden in a Jackson annotation on the enum (the
 * domain module has no Jackson on its classpath by design). Reading
 * goes through {@link com.ticketapp.domain.QuantityUnit#fromLabel},
 * which folds anything unrecognised rather than rejecting the edit.
 */
public record ProductLineDto(
        String name,
        BigDecimal quantity,
        String unit,
        BigDecimal pricePerUnit,
        BigDecimal lineTotal) { }