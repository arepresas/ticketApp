package com.ticketapp.bff.api.dto;

import com.ticketapp.domain.Ticket;

/** Wire shape for {@code PATCH /api/tickets/{id}/status}. */
public record ChangeStatusRequest(Ticket.Status status) { }
