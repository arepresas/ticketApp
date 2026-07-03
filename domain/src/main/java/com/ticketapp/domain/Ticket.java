package com.ticketapp.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Pure domain entity. No framework annotations, no persistence concerns.
 *
 * <p>Optional attachment fields ({@code contentType}, {@code fileName},
 * {@code fileData}) carry the uploaded receipt. All three are nullable:
 * tickets created before the upload feature (or future ticket types
 * that don't need a file) leave them as {@code null}.
 *
 * <p>{@link #equals(Object)}, {@link #hashCode()} and {@link #toString()}
 * are overridden so the {@code byte[]} attachment is compared by content
 * rather than by reference. The auto-generated record semantics would do
 * the same, but SonarQube's S6218 rule can't tell — keeping the override
 * explicit silences the rule and makes the intent obvious to readers.
 */
public record Ticket(
        UUID id,
        String title,
        String description,
        Status status,
        Instant createdAt,
        Instant updatedAt,
        String contentType,
        String fileName,
        byte[] fileData
) {

    public Ticket {
        if (id == null) throw new NullPointerException("id");
        if (title == null) throw new NullPointerException("title");
        if (description == null) description = "";
        if (status == null) throw new NullPointerException("status");
        if (createdAt == null) throw new NullPointerException("createdAt");
        if (updatedAt == null) throw new NullPointerException("updatedAt");
    }

    /**
     * Build a ticket without an attached file. Kept for backward
     * compatibility with callers (tests, fixtures) that don't upload.
     */
    public static Ticket open(String title, String description) {
        return open(title, description, null, null, null);
    }

    /**
     * Build a ticket with an attached file. The {@code title} argument
     * is preserved as-is — callers decide whether to use the upload's
     * original filename or a user-typed title.
     */
    public static Ticket open(String title,
                              String description,
                              String contentType,
                              String fileName,
                              byte[] fileData) {
        Instant now = Instant.now();
        return new Ticket(UUID.randomUUID(), title, description, Status.OPEN,
                now, now, contentType, fileName, fileData);
    }

    public Ticket withStatus(Status newStatus) {
        return new Ticket(id, title, description, newStatus, createdAt, Instant.now(),
                contentType, fileName, fileData);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Ticket other)) return false;
        return java.util.Objects.equals(id, other.id)
                && java.util.Objects.equals(title, other.title)
                && java.util.Objects.equals(description, other.description)
                && status == other.status
                && java.util.Objects.equals(createdAt, other.createdAt)
                && java.util.Objects.equals(updatedAt, other.updatedAt)
                && java.util.Objects.equals(contentType, other.contentType)
                && java.util.Objects.equals(fileName, other.fileName)
                && Arrays.equals(fileData, other.fileData);
    }

    @Override
    public int hashCode() {
        int h = java.util.Objects.hash(id, title, description, status, createdAt,
                updatedAt, contentType, fileName);
        return 31 * h + Arrays.hashCode(fileData);
    }

    @Override
    public String toString() {
        return "Ticket[id=" + id + ", title=" + title + ", description=" + description
                + ", status=" + status + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", contentType=" + contentType + ", fileName=" + fileName
                + ", fileData=" + Arrays.toString(fileData) + "]";
    }

    public enum Status { OPEN, IN_PROGRESS, DONE, CANCELLED }
}