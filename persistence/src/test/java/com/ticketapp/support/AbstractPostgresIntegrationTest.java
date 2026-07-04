package com.ticketapp.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for infrastructure-layer integration tests.
 * <p>
 * Spins up a single Postgres container per JVM, shared across tests via
 * {@link SharedPostgres}. Spring Boot 4 auto-binds the container to
 * {@code spring.datasource.*} via {@link ServiceConnection}.
 *
 * <p>Originally this base declared a {@code @Container static} field.
 * JUnit Jupiter's TestcontainersExtension treats that as
 * {@code AutoCloseable} and stops it in the {@code afterAll} of the
 * first IT class to finish, which left subsequent ITs in the same
 * Maven run with a dead DataSource URL. Routing through
 * {@link SharedPostgres} keeps the container alive for the entire JVM
 * lifetime; {@code ryuk} reaps it on shutdown.
 */
@SpringBootTest(classes = PersistenceTestSlice.class)
@ActiveProfiles("it")
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            SharedPostgres.get();
}
