package com.ticketapp.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = { "com.ticketapp.bff", "com.ticketapp.infrastructure", "com.ticketapp.domain" })
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
