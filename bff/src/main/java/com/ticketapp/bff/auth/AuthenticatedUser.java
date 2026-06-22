package com.ticketapp.bff.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain-level representation of an authenticated user.
 *
 * Auth concerns live in the BFF (not the core domain) on purpose: the core
 * is currently ticket-focused and does not need to know how users sign in.
 */
public record AuthenticatedUser(
        UUID id,
        String googleSub,
        String email,
        String name,
        String pictureUrl,
        Instant createdAt,
        Instant lastLoginAt
) { }