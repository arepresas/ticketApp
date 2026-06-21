package com.ticketapp.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port defined by domain. Infrastructure implements it.
 */
public interface TicketRepository {
    Optional<Ticket> findById(UUID id);
    List<Ticket> findAll();
    Ticket save(Ticket ticket);
    void deleteById(UUID id);
}
