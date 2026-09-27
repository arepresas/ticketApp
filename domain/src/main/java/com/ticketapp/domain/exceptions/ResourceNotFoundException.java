package com.ticketapp.domain.exceptions;

import java.util.UUID;

/**
 * A row the caller addressed by id (or by owner-scoped lookup) is
 * not there.
 *
 * <p>Separate from the "you may not see this" case: a not-found is a
 * genuine absence, and the HTTP edge is free to answer 404 for it.
 * Ports that must not leak existence should not throw this at all —
 * they return an empty {@code Optional} so "not yours" and "not
 * there" are the same answer.
 *
 * <p>Existed mainly so adapters stop signalling absence with
 * {@link IllegalStateException}, which told callers nothing they could
 * branch on without guessing the adapter's intent.
 */
public class ResourceNotFoundException extends TicketAppException {

    /** Stable code for logs and API responses. */
    public static final String CODE = "RESOURCE_NOT_FOUND";

    private final UUID resourceId;

    public ResourceNotFoundException(String resource, UUID resourceId) {
        super(CODE, resource + " " + resourceId + " does not exist");
        this.resourceId = resourceId;
    }

    public UUID resourceId() {
        return resourceId;
    }
}
