package com.ticketapp.bff;

import com.ticketapp.bff.ai.AiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // ADR 0006 — enables the @Scheduled TicketExtractionJob.
@EnableConfigurationProperties(AiProperties.class)
// Scans the BFF module, the domain module, and the persistence
// module so the @Repository beans (TicketRepository,
// TicketExtractionRepository, JdbcTicketExtractionRepository) are
// registered. The AI module (minimax-ai today) is wired through
// Spring Boot autoconfiguration — see ADR 0007.
@ComponentScan(basePackages = {
        "com.ticketapp.bff",
        "com.ticketapp.domain",
        "com.ticketapp.persistence"
})
public class BffApplication {
    static {
        // google-auth-library emits a benign warning when no application
        // default credentials are configured (we don't need them for
        // id_token verification — Google's public JWKs are anonymous).
        java.util.logging.Logger.getLogger("com.google.auth")
                .setLevel(java.util.logging.Level.OFF);
    }
    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
