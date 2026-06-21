package com.ticketapp.infrastructure.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring slice for infrastructure-layer tests:
 * auto-configures JDBC + Liquibase + component-scans the persistence package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.ticketapp.infrastructure.persistence")
public class InfrastructureTestSlice {
}
