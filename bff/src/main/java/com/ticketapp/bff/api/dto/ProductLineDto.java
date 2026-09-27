package com.ticketapp.bff.api.dto;

import java.math.BigDecimal;

public record ProductLineDto(
        String name,
        BigDecimal quantity,
        String unit,
        BigDecimal pricePerUnit,
        BigDecimal lineTotal) { }
