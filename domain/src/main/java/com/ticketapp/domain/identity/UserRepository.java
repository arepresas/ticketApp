package com.ticketapp.domain.identity;

import java.util.Optional;


public interface UserRepository {

    /** Find by Google `sub` claim (stable across email changes). */
    Optional<AuthenticatedUser> findByGoogleSub(String googleSub);

    Optional<AuthenticatedUser> findById(long id);

    /**
     * Upsert by google_sub. On insert, a new id is generated and {@code createdAt}
     * is set to now. On update, only {@code lastLoginAt} (and mutable fields) are refreshed.
     */
    AuthenticatedUser upsertFromGoogle(String googleSub,
                                       String email,
                                       String name,
                                       String pictureUrl);
}