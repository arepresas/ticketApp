package com.ticketapp.bff.security;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;

/**
 * Spring Security configuration for the BFF.
 *
 * <p>Replaces the previous custom {@code SessionFilter} +
 * {@code SessionTokenService} pair with the standard OAuth2 resource
 * server stack. Wire contract is unchanged: clients still send
 * {@code Authorization: Bearer <jwt>} and receive 401 on missing /
 * invalid tokens. The JWT codec is HS256 with the same
 * {@code bff.jwt.secret} (≥ 32 chars) the old custom impl used, so
 * tokens minted before this migration validate unchanged as long as
 * their claims match.
 *
 * <h2>Two filter chains</h2>
 *
 * <p>Spring Security 6's {@code oauth2ResourceServer().jwt()} installs
 * a {@code BearerTokenAuthenticationFilter} on every request. That
 * filter rejects requests that fall inside the chain's matcher with
 * 401 even on {@code permitAll()} paths — a known quirk when a
 * single chain mixes public login with the resource server. The fix
 * is two chains, ordered by specificity:
 * <ol>
 *   <li>{@code publicChain} matches {@code /api/auth/google} +
 *       {@code /actuator/health} and permits all. No resource
 *       server — the bearer filter never runs here.</li>
 *   <li>{@code apiChain} matches {@code /api/**} (everything else)
 *       and runs the resource server. {@code
 *       anyRequest().authenticated()} is the gate.</li>
 * </ol>
 *
 * <h2>CSRF</h2>
 * Stateless Bearer API, no cookies — CSRF is disabled. Spring
 * Security's default CSRF protection is for cookie-based session
 * auth; with {@code Authorization: Bearer <jwt>} an attacker cannot
 * ride a victim's browser to forge a request.
 *
 * <h2>Validation chain</h2>
 * <ol>
 *   <li>Nimbus signature + exp (default).</li>
 *   <li>{@link SessionExistsValidator} — checks {@code auth_sessions}
 *       so a revoked or deleted session is rejected even before its
 *       JWT expiry.</li>
 *   <li>{@link JwtToAuthenticatedUserConverter} — runs after both, so
 *       it can rely on the claims being well-formed and the session
 *       being live. Builds an {@code AbstractAuthenticationToken}
 *       whose principal is the resolved {@link
 *       com.ticketapp.bff.auth.AuthenticatedUser}.</li>
 * </ol>
 */
@Configuration
@Slf4j
public class SecurityConfig {

    private final byte[] secretBytes;

    public SecurityConfig(@Value("${bff.jwt.secret}") String secret) {
        if (secret == null || secret.length() < 32) {
            // Mirrors the old SessionTokenService guard — fail fast at
            // boot rather than signing tokens with a weak key.
            throw new IllegalStateException(
                    "bff.jwt.secret must be at least 32 chars (256 bits) for HS256");
        }
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        log.info("SecurityConfig initialised: HS256 resource server, secret={} chars",
                secret.length());
    }

    /**
     * Public chain — no resource server, no auth required. Higher
     * precedence (lower {@link Order}) than {@link #apiChain} so a
     * request to {@code /api/auth/google} never reaches the resource
     * server filter (which would 401 it).
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/auth/google", "/actuator/health")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Protected chain — resource server, every request requires a
     * valid Bearer JWT. Matches every other {@code /api/**} path
     * via the {@link /api/**} request matcher.
     */
    @Bean
    SecurityFilterChain apiChain(HttpSecurity http,
                                 JwtDecoder jwtDecoder,
                                 JwtToAuthenticatedUserConverter jwtConverter) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtConverter)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Verifies incoming Bearer tokens. The secret is the same
     * {@code bff.jwt.secret} the login endpoint signs with, so a
     * token minted by {@link JwtEncoder} round-trips through this
     * decoder. The custom {@link SessionExistsValidator} is
     * appended to Nimbus' default validators (signature + exp).
     */
    @Bean
    JwtDecoder jwtDecoder(SessionExistsValidator sessionValidator) {
        SecretKeySpec key = new SecretKeySpec(secretBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                org.springframework.security.oauth2.jwt.JwtValidators.createDefault(),
                sessionValidator));
        return decoder;
    }

    /**
     * Mints new Bearer tokens for the login endpoint. Symmetric
     * HMAC — same key as the decoder — so signing and verification
     * agree without needing a key resolver.
     */
    @Bean
    JwtEncoder jwtEncoder() {
        SecretKeySpec key = new SecretKeySpec(secretBytes, "HmacSHA256");
        JWKSource<SecurityContext> jwks = new ImmutableSecret<>(key);
        return new NimbusJwtEncoder(jwks);
    }
}