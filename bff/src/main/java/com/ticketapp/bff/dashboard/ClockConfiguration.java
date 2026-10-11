package com.ticketapp.bff.dashboard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the single {@link Clock} the application reads time through.
 *
 * <p>Exists so that anything time-dependent can be handed a fixed clock
 * in a test instead of calling {@code Instant.now()} and hoping the
 * assertion did not land on a boundary — the dashboard's reporting month
 * is the case that matters, where a rollover between the production code
 * and the test would make the expected month wrong.
 */
@Configuration
public class ClockConfiguration {

    /**
     * System UTC. Components that need a business-specific zone
     * (currently only {@link DashboardService#REPORTING_ZONE}) convert
     * it with {@link Clock#withZone}, rather than each declaring its
     * own bean and Spring becoming ambiguous.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}