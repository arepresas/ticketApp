package com.ticketapp.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton holder for the shared Testcontainers Postgres instance used
 * by every infrastructure IT in this module.
 *
 * <p>Why a singleton (and not {@code @Container static} on the abstract
 * test base)? JUnit Jupiter's {@code TestcontainersExtension} treats
 * any {@code @Container} field as {@code AutoCloseable} and stops it
 * in the {@code afterAll} of the class that owns it. With multiple
 * IT classes extending the same abstract base, the field is shared
 * by class identity but the extension still fires per-class — the
 * first IT to finish tears the container down and the second IT
 * boots a Spring context that finds a dead DataSource URL.
 *
 * <p>Wrapping the container in a holder that the test base class
 * consults sidesteps the extension entirely: the container starts
 * lazily on first use, lives until the JVM exits, and is never
 * stopped by the JUnit lifecycle. {@code ryuk} reaps it on JVM
 * shutdown, which is the desired behaviour.
 *
 * <p>Trade-off: tests can't be run in parallel (the holder would
 * hand the same container to two contexts). We don't run them in
 * parallel, and the failsafe plugin is configured with
 * {@code forkCount=1} for the same reason.
 */
public final class SharedPostgres {

    private static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    .withDatabaseName("ticketapp")
                    .withUsername("ticketapp")
                    .withPassword("ticketapp")
                    // Postgres 18 stores data under a major-version subdir.
                    // PGDATA below the tmpfs mount avoids the
                    // "unused mount/volume" error.
                    .withEnv("PGDATA", "/var/lib/postgresql/data/pgdata");

    static {
        INSTANCE.start();
    }

    private SharedPostgres() {}

    /** The single shared container. Starts on class load. */
    public static PostgreSQLContainer<?> get() {
        return INSTANCE;
    }
}