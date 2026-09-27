package com.ticketapp.bff.api.dto;

import com.ticketapp.domain.Ticket;

/**
 * Wire shape for {@code PATCH /api/tickets/{id}}. Both fields
 * are optional — the controller only updates the fields the
 * caller actually sent. {@code title} is validated non-blank
 * by {@link Ticket#withTitle(String)}; {@code description} is
 * normalised to {@code ""} by {@link Ticket#withDescription(String)}
 * when {@code null}.
 */
public record UpdateTicketRequest(String title, String description) { }
