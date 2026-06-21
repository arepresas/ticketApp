package com.ticketapp.infrastructure.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for infrastructure-layer integration tests.
 * <p>
 * Spins up a single Postgres container per JVM, shared across tests. Spring Boot 4
 * auto-binds it to {@code spring.datasource.*} via {@link ServiceConnection}.
 */
@SpringBootTest(classes = InfrastructureTestSlice.class)
@Testcontainers
@ActiveProfiles("it")
public abstract class AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.4-alpine")
                    .withDatabaseName("ticketapp")
                    .withUsername("ticketapp")
                    .withPassword("ticketapp")
                    // Postgres 18 stores data under a major-version subdir.
                    // PGDATA below the tmpfs mount avoids the "unused mount/volume" error.
                    .withEnv("PGDATA", "/var/lib/postgresql/data/pgdata");
}
