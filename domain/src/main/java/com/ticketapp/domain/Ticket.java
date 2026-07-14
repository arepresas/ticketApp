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
        UUID ownerId,
        String title,
        String description,
        Status status,
        Instant createdAt,
        Instant updatedAt,
        String contentType,
        String fileName,
        byte[] fileData,
        String errorMessage,
        int attempts
) {

    public Ticket {
        if (id == null) throw new NullPointerException("id");
        if (ownerId == null) throw new NullPointerException("ownerId");
        if (title == null) throw new NullPointerException("title");
        if (description == null) description = "";
        if (status == null) throw new NullPointerException("status");
        if (createdAt == null) throw new NullPointerException("createdAt");
        if (updatedAt == null) throw new NullPointerException("updatedAt");
        if (errorMessage != null && errorMessage.isBlank()) errorMessage = null;
        if (attempts < 0) throw new IllegalArgumentException("attempts must be >= 0");
    }

    /**
     * Build a ticket without an attached file. Kept for backward
     * compatibility with callers (tests, fixtures) that don't upload.
     */
    public static Ticket open(UUID ownerId, String title, String description) {
        return open(ownerId, title, description, null, null, null);
    }

    /**
     * Build a ticket with an attached file. The {@code title} argument
     * is preserved as-is — callers decide whether to use the upload's
     * original filename or a user-typed title.
     */
    public static Ticket open(UUID ownerId,
                              String title,
                              String description,
                              String contentType,
                              String fileName,
                              byte[] fileData) {
        Instant now = Instant.now();
        return new Ticket(UUID.randomUUID(), ownerId, title, description, Status.OPEN,
                now, now, contentType, fileName, fileData, null, 0);
    }

    /**
     * Transition to a new status. When the target status is anything
     * other than {@link Status#ON_ERROR}, any previously stored error
     * message is cleared — this is how a manual retry (PATCH
     * {@code /api/tickets/{id}/status} → {@code OPEN}) wipes the
     * failure reason so the dashboard no longer shows a stale error.
     * The orchestrator never sets {@code ON_ERROR} via this method;
     * it calls {@link #markError(String)} instead.
     */
    public Ticket withStatus(Status newStatus) {
        String cleared = (newStatus == Status.ON_ERROR) ? errorMessage : null;
        return new Ticket(id, ownerId, title, description, newStatus, createdAt, Instant.now(),
                contentType, fileName, fileData, cleared, attempts);
    }

    /**
     * User-driven title edit (detail screen "Edit" affordance).
     * Bumps {@code updatedAt} so the UI can show "just edited" via
     * the existing sort/order logic. The new title must be
     * non-blank — the BFF controller enforces this on the way in,
     * and the invariant here guarantees we never persist a ticket
     * with a {@code null}/empty title even if a future caller skips
     * validation.
     */
    public Ticket withTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return new Ticket(id, ownerId, newTitle, description, status, createdAt, Instant.now(),
                contentType, fileName, fileData, errorMessage, attempts);
    }

    /**
     * User-driven description edit. {@code null} is normalised to
     * the canonical empty string (matches the record's compact
     * constructor) so the wire shape never carries a {@code null}
     * description even when the user cleared the field.
     */
    public Ticket withDescription(String newDescription) {
        String sanitized = newDescription == null ? "" : newDescription;
        return new Ticket(id, ownerId, title, sanitized, status, createdAt, Instant.now(),
                contentType, fileName, fileData, errorMessage, attempts);
    }

    /**
     * Mark the ticket as failed. Sets status to {@link Status#ON_ERROR}
     * and stores the supplied message so the dashboard and operators
     * can see why the scheduled extraction did not succeed. ON_ERROR
     * is terminal from the scheduler's POV — the next cron tick
     * filters on {@code Status.OPEN} only, so a failed ticket is not
     * retried automatically. A user-initiated PATCH (to {@code OPEN}
     * or {@code CANCELLED}) clears the message via
     * {@link #withStatus(Status)}.
     *
     * @param message human-readable failure reason. Callers are
     *                expected to truncate before calling (the
     *                orchestrator caps at 2000 chars) — this method
     *                does not enforce a bound.
     */
    public Ticket markError(String message) {
        return new Ticket(id, ownerId, title, description, Status.ON_ERROR,
                createdAt, Instant.now(),
                contentType, fileName, fileData, message, attempts);
    }

    /**
     * Bump the extraction-attempt counter. Called by the orchestrator
     * immediately before each {@code receiptExtractor.extract(...)}
     * call, so success and failure paths both increment. Bumps
     * {@code updatedAt} so the dashboard's "last attempt" sort
     * (currently driven by {@code last_extraction_attempt_at} on the
     * SQL side) stays consistent with the in-domain field.
     *
     * <p>Not called by manual PATCH status flips: the counter is the
     * AI pipeline's "I tried" tally, not a "the user clicked retry"
     * tally — those have different meanings and conflating them
     * would make the dashboard number harder to reason about.
     */
    public Ticket incrementAttempts() {
        return new Ticket(id, ownerId, title, description, status, createdAt, Instant.now(),
                contentType, fileName, fileData, errorMessage, attempts + 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Ticket other)) return false;
        return java.util.Objects.equals(id, other.id)
                && java.util.Objects.equals(ownerId, other.ownerId)
                && java.util.Objects.equals(title, other.title)
                && java.util.Objects.equals(description, other.description)
                && status == other.status
                && java.util.Objects.equals(createdAt, other.createdAt)
                && java.util.Objects.equals(updatedAt, other.updatedAt)
                && java.util.Objects.equals(contentType, other.contentType)
                && java.util.Objects.equals(fileName, other.fileName)
                && Arrays.equals(fileData, other.fileData)
                && java.util.Objects.equals(errorMessage, other.errorMessage)
                && attempts == other.attempts;
    }

    @Override
    public int hashCode() {
        int h = java.util.Objects.hash(id, ownerId, title, description, status,
                createdAt, updatedAt, contentType, fileName, errorMessage, attempts);
        return 31 * h + Arrays.hashCode(fileData);
    }

    @Override
    public String toString() {
        return "Ticket[id=" + id + ", ownerId=" + ownerId + ", title=" + title
                + ", description=" + description + ", status=" + status
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", contentType=" + contentType + ", fileName=" + fileName
                + ", fileData=" + Arrays.toString(fileData)
                + ", errorMessage=" + errorMessage + ", attempts=" + attempts + "]";
    }

    /**
     * Lifecycle states. {@link #ON_ERROR} is the terminal failure
     * state for the scheduled extraction pipeline: a ticket reaches
     * it when the AI provider call throws (non-2xx, parse failure,
     * empty reply, etc.) and is not retried automatically. Manual
     * intervention via PATCH {@code /api/tickets/{id}/status} moves
     * it back to {@link #OPEN} (clears the error message) or
     * {@link #CANCELLED}.
     */
    public enum Status { OPEN, IN_PROGRESS, ON_ERROR, DONE, CANCELLED }
}