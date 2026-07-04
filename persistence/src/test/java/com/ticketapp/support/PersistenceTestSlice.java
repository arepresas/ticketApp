package com.ticketapp.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring slice for persistence-layer integration tests
 * (ADR 0007). Boots JDBC + Liquibase and component-scans the
 * {@code com.ticketapp.persistence} package. Anything outside that
 * package (AI, BFF controllers, ...) is invisible to the test.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.ticketapp.persistence")
public class PersistenceTestSlice {
}