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
        int attempts,
        UUID shopId,
        String ocrText
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
        // shopId is nullable: most tickets have no shop row until the
        // normaliser runs on the DONE transition. A non-null value
        // must reference a real shops row — the FK constraint
        // (V13) enforces that at the SQL layer.
        // ocrText is the verbatim transcription of the uploaded image
        // (or PDF page) produced at upload time by the OCR port
        // (see domain.ai.DocumentTextExtractor). Nullable because the
        // upload may have happened before the OCR step landed, the
        // provider may have declined to transcribe (blank image), or
        // the upload was a metadata-only ticket (no bytes). The
        // BFF normalises blank → null so callers never have to test
        // both.
        if (ocrText != null && ocrText.isBlank()) ocrText = null;
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
                now, now, contentType, fileName, fileData, null, 0, null, null);
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
                contentType, fileName, fileData, cleared, attempts, shopId, ocrText);
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
                contentType, fileName, fileData, errorMessage, attempts, shopId, ocrText);
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
                contentType, fileName, fileData, errorMessage, attempts, shopId, ocrText);
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
                contentType, fileName, fileData, message, attempts, shopId, ocrText);
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
                contentType, fileName, fileData, errorMessage, attempts + 1, shopId, ocrText);
    }

    /**
     * Anchor the shop the ticket was bought from. Called by the
     * normaliser during the {@code DONE} transition — the merchant
     * string the AI extracted resolves (or creates) a single
     * {@code shops} row, and the resulting id is stamped onto the
     * ticket so every line_ticket row can derive its shop by
     * joining back through the ticket instead of carrying a
     * redundant FK of its own. Bumps {@code updatedAt} so the
     * dashboard's sort picks up the change.
     */
    public Ticket withShopId(UUID newShopId) {
        return new Ticket(id, ownerId, title, description, status, createdAt, Instant.now(),
                contentType, fileName, fileData, errorMessage, attempts, newShopId, ocrText);
    }

    /**
     * Stamp the verbatim OCR transcription produced at upload time.
     * Called by the BFF's upload flow (see
     * {@code DocumentTextExtractionSyncService}) once the
     * {@link com.ticketapp.domain.ai.DocumentTextExtractor} returns.
     * Passing {@code null} (or a blank string, which the compact
     * constructor normalises to {@code null}) clears any previously
     * stored transcription — the upload flow does this on the
     * no-text outcome so the column reflects "OCR said: empty".
     *
     * <p>Does NOT bump {@code updatedAt}: the OCR text is part of
     * the upload payload, not a user-driven edit, so the dashboard
     * sort would mislead the operator by surfacing "just updated"
     * for every image that happens to come back without text.
     */
    public Ticket withOcrText(String newOcrText) {
        return new Ticket(id, ownerId, title, description, status, createdAt, updatedAt,
                contentType, fileName, fileData, errorMessage, attempts, shopId, newOcrText);
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
                && attempts == other.attempts
                && java.util.Objects.equals(shopId, other.shopId)
                && java.util.Objects.equals(ocrText, other.ocrText);
    }

    @Override
    public int hashCode() {
        int h = java.util.Objects.hash(id, ownerId, title, description, status,
                createdAt, updatedAt, contentType, fileName, errorMessage, attempts,
                shopId, ocrText);
        return 31 * h + Arrays.hashCode(fileData);
    }

    @Override
    public String toString() {
        return "Ticket[id=" + id + ", ownerId=" + ownerId + ", title=" + title
                + ", description=" + description + ", status=" + status
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", contentType=" + contentType + ", fileName=" + fileName
                + ", fileData=" + Arrays.toString(fileData)
                + ", errorMessage=" + errorMessage + ", attempts=" + attempts
                + ", shopId=" + shopId + ", ocrText=" + ocrText + "]";
    }

    /**
     * Lifecycle states.
     *
     * <p>The happy path runs:
     * {@link #OPEN} → (scheduler picks) → {@link #IN_ANALYSIS} → (AI
     * finishes) → {@link #IN_PROGRESS} → (user marks) → {@link #DONE}.
     *
     * <ul>
     *   <li>{@link #OPEN} — user-uploaded, waiting for the scheduler
     *       to pick it up. Also the manual-retry target: a PATCH
     *       {@code /api/tickets/{id}/status} → {@code OPEN} re-enqueues
     *       an {@code ON_ERROR} ticket for the next tick.</li>
     *   <li>{@link #IN_ANALYSIS} — the scheduler pre-set this state
     *       before invoking {@code processTicket}; the orchestrator
     *       holds it while the AI provider call is in flight. UI
     *       badge colour distinguishes it from
     *       {@link #IN_PROGRESS} so the user can see "the AI is
     *       working on it" vs "the AI is done, awaiting your
     *       validation".</li>
     *   <li>{@link #IN_PROGRESS} — the AI finished (extraction row
     *       written). The ticket is now waiting for the user to
     *       validate via PATCH {@code /api/tickets/{id}/status} →
     *       {@code DONE} or {@code CANCELLED}.</li>
     *   <li>{@link #ON_ERROR} — terminal failure of the AI pipeline.
     *       The next cron tick filters on {@link #OPEN} only so a
     *       failed ticket is not retried automatically. Manual
     *       intervention (Reset / Mark as OPEN) clears the error and
     *       re-enqueues.</li>
     *   <li>{@link #DONE} — user-validated terminal state. The detail
     *       screen goes read-only.</li>
     *   <li>{@link #CANCELLED} — user-dismissed terminal state.</li>
     * </ul>
     */
    public enum Status {
        OPEN, IN_ANALYSIS, IN_PROGRESS, ON_ERROR, DONE, CANCELLED
    }
}