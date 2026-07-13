package com.ticketapp.bff.api;

import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.ai.ReceiptExtraction;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionRequest;
import com.ticketapp.domain.ai.ReceiptExtractionResult;
import com.ticketapp.domain.ai.ReceiptExtractor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable {@code @TestConfiguration} that swaps the real
 * {@link ReceiptExtractor} (which calls MiniMax over HTTP) for an
 * in-process fake. The IT suite doesn't have network access to
 * {@code api.minimax.chat}, and even when it did, hitting a paid API
 * during a routine {@code mvn verify} would burn tokens.
 *
 * <p>The fake applies a caller-supplied {@link Function} to the
 * receipt bytes so each IT can decide what the stubbed extraction
 * returns (success, empty products, exception). The default returns
 * a minimal successful extraction so tests that just need an
 * extraction row without caring about its shape can use the
 * default-behaviour factory.
 *
 * <p>Marked {@code @Primary} so Spring picks this bean over the
 * production one wired by {@code MinimaxAiAutoConfiguration}.
 */
@TestConfiguration
public class TestReceiptExtractorConfig {

    /**
     * Custom functional interface so callers can write a lambda
     * that throws {@link ReceiptExtractionException} without the
     * {@code throws} clause dance {@code java.util.function.Function}
     * forces.
     */
    @FunctionalInterface
    public interface StubbedExtractor {
        ReceiptExtractionResult apply(ReceiptExtractionRequest request)
                throws ReceiptExtractionException;
    }

    /**
     * Records every call so tests can assert "the controller
     * triggered an extraction" without having to mock Mockito in an
     * IT (the IT uses real Spring + real JdbcTemplate, not mocks).
     */
    private static final List<ReceiptExtractionRequest> CALLS =
            new ArrayList<>();

    /** Last outcome returned, useful for assertions on retries. */
    private static final AtomicInteger SUCCESS_COUNT = new AtomicInteger();
    private static final AtomicInteger FAILURE_COUNT = new AtomicInteger();

    public static void reset() {
        CALLS.clear();
        SUCCESS_COUNT.set(0);
        FAILURE_COUNT.set(0);
    }

    public static List<ReceiptExtractionRequest> calls() {
        return List.copyOf(CALLS);
    }

    public static int successCount() {
        return SUCCESS_COUNT.get();
    }

    public static int failureCount() {
        return FAILURE_COUNT.get();
    }

    /**
     * Default bean: every call returns a minimal successful
     * extraction with a single line and EUR 1.00 total. Tests that
     * need a specific response shape (or failure) should call
     * {@link #stubbed(Function)} from a setup hook and override the
     * bean via {@code @MockBean} or a per-test
     * {@code @Import(TestReceiptExtractorConfig.WithStub.class)}.
     */
    @Bean
    @Primary
    public ReceiptExtractor testReceiptExtractor() {
        return stubbed(req -> minimalSuccess());
    }

    /**
     * Build a {@link ReceiptExtractor} that applies the given
     * function to each request. The function may throw
     * {@link ReceiptExtractionException} to simulate an AI failure.
     */
    public static ReceiptExtractor stubbed(StubbedExtractor fn) {
        return new ReceiptExtractor() {
            @Override
            public ReceiptExtraction extract(ReceiptExtractionRequest request)
                    throws ReceiptExtractionException {
                CALLS.add(request);
                ReceiptExtractionResult result = fn.apply(request);
                SUCCESS_COUNT.incrementAndGet();
                return new ReceiptExtraction(result, "{\"stubbed\":true}", "test-stub-model");
            }
        };
    }

    /** Counterpart that always throws — useful for failure-path ITs. */
    public static ReceiptExtractor alwaysFailing(String message) {
        return stubbed(req -> {
            throw new ReceiptExtractionException(500, message);
        });
    }

    private static ReceiptExtractionResult minimalSuccess() {
        return new ReceiptExtractionResult(
                "Mercadona",
                LocalDate.of(2026, 7, 4),
                "food",
                List.of(new ProductLine(
                        "Bread",
                        new BigDecimal("1"),
                        null,
                        new BigDecimal("1.00"),
                        new BigDecimal("1.00"))),
                new BigDecimal("1.00"),
                "EUR");
    }
}