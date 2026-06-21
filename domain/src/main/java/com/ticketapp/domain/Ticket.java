package com.ticketapp.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain entity. No framework annotations, no persistence concerns.
 */
public record Ticket(
        UUID id,
        String title,
        String description,
        Status status,
        Instant createdAt,
        Instant updatedAt
) {

    public Ticket {
        if (id == null) throw new NullPointerException("id");
        if (title == null) throw new NullPointerException("title");
        if (description == null) description = "";
        if (status == null) throw new NullPointerException("status");
        if (createdAt == null) throw new NullPointerException("createdAt");
        if (updatedAt == null) throw new NullPointerException("updatedAt");
    }

    public static Ticket open(String title, String description) {
        Instant now = Instant.now();
        return new Ticket(UUID.randomUUID(), title, description, Status.OPEN, now, now);
    }

    public Ticket withStatus(Status newStatus) {
        return new Ticket(id, title, description, newStatus, createdAt, Instant.now());
    }

    public enum Status { OPEN, IN_PROGRESS, DONE, CANCELLED }
}
