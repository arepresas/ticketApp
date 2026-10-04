package com.ticketapp.bff.ai;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.Ticket.Status;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.ai.ReceiptExtraction;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionRequest;
import com.ticketapp.domain.ai.ReceiptExtractionResult;
import com.ticketapp.domain.ai.ReceiptExtractor;
import com.ticketapp.domain.exceptions.OptimisticLockException;
import com.ticketapp.persistence.JdbcTicketRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketExtractionService}.
 *
 * <p>After ADR 0007 the orchestrator depends only on the
 * {@link ReceiptExtractor} port — provider-specific concerns
 * (PDF/image routing, response parsing, raw-reply capture) live in
 * the provider module. Tests here pin the orchestrator contract:
 * <ul>
 *   <li>On success: ticket → IN_PROGRESS, extraction row inserted.</li>
 *   <li>On {@link ReceiptExtractionException}: ticket → IN_PROGRESS
 *       then marked ON_ERROR with the failure reason attached.</li>
 *   <li>On any other unexpected exception: same ON_ERROR marking.</li>
 *   <li>Long error messages are truncated to
 *       {@link TicketExtractionService#ERROR_MESSAGE_MAX_CHARS} so a
 *       runaway raw reply cannot bloat the row.</li>
 *   <li>Already extracted: skip without touching the ticket.</li>
 *   <li>The request handed to the port carries the ticket's bytes
 *       and content type unchanged.</li>
 *   <li>The persisted {@link TicketExtraction} merges the port's
 *       result with the ticket id, current time, and the raw
 *       reply returned by the port.</li>
 * </ul>
 *
 * <p>The orchestrator operates as the ticket's owner — there is no
 * separate user session for the cron tick — so the lookup that
 * refreshes the entity before marking ON_ERROR uses the ticket's own
 * ownerId rather than a system scope. The {@code markError} path is
 * tested by stubbing the owner-scoped {@code findById}.
 *
 * <p>Provider-specific tests (PDF routing, response parsing,
 * {@code <think>} stripping) live in
 * {@code openai-ai/src/test/.../OpenAiReceiptExtractorTest}.
 */
class TicketExtractionServiceTest {

    private static final String MODEL = "gpt-4o-mini";
    private static final long OWNER = 4369L;

    private TicketRepository tickets;
    private TicketExtractionRepository extractions;
    private JdbcTicketRepository jdbcTickets;
    private ReceiptExtractor receiptExtractor;
    private TicketExtractionService service;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        extractions = mock(TicketExtractionRepository.class);
        jdbcTickets = mock(JdbcTicketRepository.class);
        receiptExtractor = mock(ReceiptExtractor.class);
        // Real TransactionTemplate over a no-op transaction manager:
        // the callbacks must actually run (the saves happen inside
        // them), but there is no DB to commit to in a unit test.
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new TicketExtractionService(
                tickets, extractions, jdbcTickets, receiptExtractor,
                new TransactionTemplate(tm), new AiProperties(false, "0 0 0 1 1 ?", 5, 2, Duration.ofMinutes(10)),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static Ticket sampleTicket(long id) {
        return new Ticket(id, OWNER, "r.png", "", Status.OPEN,
                Instant.now(), Instant.now(),
                "image/png", "r.png", new byte[]{1, 2, 3}, null, 0, null, null, 0);
    }

    private static Ticket sampleTicket(long id, byte[] bytes) {
        return new Ticket(id, OWNER, "r.png", "", Status.OPEN,
                Instant.now(), Instant.now(),
                "image/png", "r.png", bytes, null, 0, null, null, 0);
    }

    @Test
    void successPathPersistsExtractionAndFlipsInAnalysisToInProgress() throws Exception {
        // The orchestrator's two-save dance: IN_ANALYSIS at the
        // start (the "AI is being called" state) and IN_PROGRESS on
        // success (the "AI is done, awaiting your validation"
        // state). The dashboard badge colour flips from sky to
        // amber when the second save lands.
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "Mercadona",
                                LocalDate.of(2026, Month.JULY, 4),
                                "food",
                                List.of(new ProductLine(
                                        "Bread",
                                        new BigDecimal("1"),
                                        "unit",
                                        new BigDecimal("1.20"),
                                        new BigDecimal("1.20"))),
                                new BigDecimal("1.20"),
                                "EUR"),
                        "{\"merchant\":\"Mercadona\"}",
                        MODEL));

        boolean processed = service.processTicket(open);

        assertThat(processed).isTrue();
        // The first save is the IN_ANALYSIS pre-set (with the
        // attempts counter bumped); the second save is the
        // IN_PROGRESS flip on success.
        verify(tickets, atLeast(2)).save(any(Ticket.class));
        verify(tickets).save(argThat(t -> t.status() == Status.IN_ANALYSIS && t.attempts() == 1));
        verify(tickets).save(argThat(t -> t.status() == Status.IN_PROGRESS));
        verify(jdbcTickets).recordAttempt(id);
        ArgumentCaptor<TicketExtraction> cap = ArgumentCaptor.forClass(TicketExtraction.class);
        verify(extractions).save(cap.capture());
        TicketExtraction saved = cap.getValue();
        assertThat(saved.ticketId()).isEqualTo(id);
        assertThat(saved.merchant()).isEqualTo("Mercadona");
        assertThat(saved.currency()).isEqualTo("EUR");
        assertThat(saved.model()).isEqualTo(MODEL);
        assertThat(saved.rawResponse()).isEqualTo("{\"merchant\":\"Mercadona\"}");
        // No revert to OPEN on the success path.
        verify(tickets, never()).save(argThat(t -> t.status() == Status.OPEN && t.id() == id));
    }

    @Test
    void inAnalysisSaveIsCommittedBeforeTheProviderCall() throws Exception {
        // Regression pin for the transaction-segmentation fix: the
        // IN_ANALYSIS save must happen BEFORE receiptExtractor.extract()
        // is invoked, in a transaction segment that has already
        // committed by the time the provider call starts. With the old
        // single-@Transactional design both saves shared one
        // transaction, so during the whole AI round-trip other readers
        // still saw OPEN — the dashboard badge never showed "In
        // analysis" while the AI worked.
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "X", LocalDate.of(2026, Month.JANUARY, 1), "other",
                                List.of(), BigDecimal.ONE, "EUR"),
                        "{}", MODEL));

        service.processTicket(open);

        InOrder order = inOrder(tickets, receiptExtractor);
        order.verify(tickets).save(argThat(t -> t.status() == Status.IN_ANALYSIS));
        order.verify(receiptExtractor).extract(any());
    }

    @Test
    void extractorFailureMarksTicketOnErrorWithMessage() throws Exception {
        // The orchestrator refreshes the ticket via owner-scoped
        // findById(id, ownerId) before writing ON_ERROR so a
        // concurrent delete can't resurrect a stale copy.
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(500, false,
                        "provider 500 at https://internal.acme/v1",
                        "the AI provider is failing on its side (status 500)"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        // Two saves: first IN_ANALYSIS at the start of the call, then
        // ON_ERROR after the failure. The transition goes
        // IN_ANALYSIS → ON_ERROR directly (no IN_PROGRESS in
        // between — there's nothing to validate when the AI
        // never produced a structured extraction). Neither
        // transitions back to OPEN — the failure is terminal from
        // the scheduler's POV.
        verify(tickets, times(2)).save(any(Ticket.class));
        verify(tickets).save(argThat(t -> t.status() == Status.IN_ANALYSIS));
        verify(tickets).save(argThat(t ->
                t.status() == Status.ON_ERROR
                        && t.errorMessage() != null
                        && t.errorMessage().contains("500")
                        && t.errorMessage().contains("failing on its side")
                        // The provider's own text stays out of the row.
                        && !t.errorMessage().contains("internal.acme")));
        verify(extractions, never()).save(any());
    }

    @Test
    void onlyTheSafeMessageReachesTheTicketRow() throws Exception {
        // The diagnostic may contain anything the provider returned:
        // an endpoint, a model id, a key, a multi-KB body. It must
        // reach the log and nothing else. The row is what the
        // dashboard renders and what any later API call returns.
        // The sentinel value uses a deliberately credential-free shape
        // (no `sk-`/`sk-live-` prefix, no `Bearer`, no base64-looking
        // blob) so secret scanners do not classify it as a real key.
        String hostile = "provider 401 for https://internal.acme/v1"
                + " model=acme-secret-v2 key=AAbbCCddEEffGGhh-1234"
                + System.lineSeparator() + "{\"note\":\"x\"}".repeat(200);
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenThrow(
                new ReceiptExtractionException(401, false, hostile,
                        "the AI provider rejected the request (check the configured key)"));

        service.processTicket(open);

        // The marker 'note' is unescaped inside the JSON body
        // (`{"note":"x"}`) so a real doesNotContain assertion catches
        // it. The previous version searched for the literal
        // backslashed-quote pair, which never appears in the runtime
        // string and so passed trivially.
        verify(tickets).save(argThat(t -> {
            String persisted = t.errorMessage();
            if (persisted == null || !t.status().equals(Status.ON_ERROR)) {
                return false;
            }
            assertThat(persisted)
                    .as("persisted error message")
                    .doesNotContain("internal.acme")
                    .doesNotContain("acme-secret-v2")
                    .doesNotContain("AAbbCCddEEffGGhh-1234")
                    .doesNotContain("note")
                    .contains("check the configured key");
            return true;
        }));
    }

    @Test
    void providerDiagnosticNeverReachesTheWarnLog() throws Exception {
        // Capture the WARN log so we can assert the diagnostic (which
        // may carry an endpoint, a key, or a response body) is NOT
        // emitted at WARN. The orchestrator already caps the row to
        // safeMessage() + status; the log must follow the same rule,
        // otherwise a future bug that bypasses `markError` would
        // still leak via stdout/stderr.
        ch.qos.logback.classic.Logger svc =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(TicketExtractionService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> sink =
                new ch.qos.logback.core.read.ListAppender<>();
        sink.start();
        svc.addAppender(sink);
        try {
            String hostile = "provider 500 for https://internal.acme/v1"
                    + " key=AAbbCCddEEffGGhh-1234"
                    + " body=" + "{\"note\":\"x\"}".repeat(200);
            long id = nextId();
            Ticket open = sampleTicket(id);
            when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
            when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
            when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(receiptExtractor.extract(any())).thenThrow(
                    new ReceiptExtractionException(500, false, hostile,
                            "the AI provider is unavailable"));

            service.processTicket(open);

            String joined = sink.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(joined)
                    .as("WARN log line")
                    .doesNotContain("internal.acme")
                    .doesNotContain("AAbbCCddEEffGGhh-1234")
                    .doesNotContain("note");
        } finally {
            svc.detachAppender(sink);
            sink.stop();
        }
    }

    @Test
    void errorMessageIsBoundedByTheSafeMessageRegardlessOfDiagnosticSize() throws Exception {
        // The error_message column is TEXT, but the row must stay
        // bounded: a multi-KB raw provider diagnostic (think a <think>
        // dump) cannot bloat it. Truncation used to clamp the row
        // directly; now safeMessage() is the only text persisted, so
        // the size is fixed by construction.
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        String huge = "x".repeat(TicketExtractionService.ERROR_MESSAGE_MAX_CHARS + 500);
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(502, false,
                huge));

        service.processTicket(open);

        verify(tickets).save(argThat(t -> {
            String msg = t.errorMessage();
            return t.status() == Status.ON_ERROR
                    && msg != null
                    && msg.length() <= TicketExtractionService.ERROR_MESSAGE_MAX_CHARS
                    && msg.contains(ReceiptExtractionException.GENERIC_SAFE_MESSAGE);
        }));
    }

    @Test
    void alreadyExtractedTicketsAreSkipped() throws Exception {
        long id = nextId();
        Ticket open = sampleTicket(id, new byte[]{1});
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.of(
                new TicketExtraction(id, "X", LocalDate.now(), null,
                        List.of(), BigDecimal.ZERO, "EUR", MODEL,
                        Instant.now(), "{}")));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verify(tickets, never()).save(any());
        verifyNoExtractorCall();
    }

    @Test
    void ticketBytesAndContentTypeAreForwardedToThePort() throws Exception {
        long id = nextId();
        byte[] bytes = new byte[]{1, 2, 3, 4};
        Ticket png = new Ticket(id, OWNER, "r.png", "", Status.OPEN,
                Instant.now(), Instant.now(),
                "image/png", "r.png", bytes, null, 0, null, null, 0);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(png));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "X", LocalDate.of(2026, Month.JANUARY, 1), "other",
                                List.of(), BigDecimal.ONE, "EUR"),
                        "{}", MODEL));

        service.processTicket(png);

        ArgumentCaptor<ReceiptExtractionRequest> cap =
                ArgumentCaptor.forClass(ReceiptExtractionRequest.class);
        verify(receiptExtractor).extract(cap.capture());
        ReceiptExtractionRequest sent = cap.getValue();
        assertThat(sent.content()).isEqualTo(bytes);
        assertThat(sent.contentType()).isEqualTo("image/png");
    }

    @Test
    void saveFailureMarksTicketOnError() throws Exception {
        // The previous contract reverted the ticket to OPEN when the
        // extraction-row insert failed. With the new contract the
        // ticket lands in ON_ERROR so the scheduler does not pick it
        // up on the next tick and loop on the same broken write.
        long id = nextId();
        Ticket open = sampleTicket(id, new byte[]{1});
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "X", LocalDate.of(2026, Month.JANUARY, 1), "other",
                                List.of(), BigDecimal.ONE, "EUR"),
                        "{}", MODEL));
        when(extractions.save(any())).thenThrow(
                new DataIntegrityViolationException("DB boom"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verify(tickets).save(argThat(t -> t.status() == Status.ON_ERROR
                && t.errorMessage() != null
                && t.errorMessage().contains("DB boom")));
    }

    @Test
    void markErrorNoopWhenTicketVanishes() throws Exception {
        // The refresh lookup returns empty (concurrent delete) — the
        // orchestrator must not throw, the cron tick just skips the
        // missing ticket and resumes on the next one.
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.empty()); // race: gone
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(500, false,
                        "boom"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        // Only the initial IN_ANALYSIS save — the ON_ERROR save was
        // skipped because the refresh returned empty.
        verify(tickets, times(1)).save(any(Ticket.class));
    }

    @Test
    void attemptsCounterIsIncrementedOnEveryProcessTicketCall() throws Exception {
        // The dashboard surfaces a per-ticket "attempts" counter so the
        // user can see how many AI tries a stuck extraction has burned.
        // The orchestrator must bump it on every call — both success
        // and failure — so the number reflects reality regardless of
        // outcome. Counter starts at 0 on the fixture, so the
        // persisted save should carry attempts == 1 on the first
        // (IN_ANALYSIS) save.
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "Mercadona",
                                LocalDate.of(2026, Month.JULY, 4),
                                "food",
                                List.of(),
                                new BigDecimal("1.20"),
                                "EUR"),
                        "{}",
                        MODEL));

        service.processTicket(open);

        // First save is the IN_ANALYSIS pre-set with attempts bumped to 1.
        verify(tickets).save(argThat(t ->
                t.status() == Status.IN_ANALYSIS && t.attempts() == 1));
    }

    @Test
    void attemptsCounterPersistsAcrossRetriesForSameTicket() throws Exception {
        // After a processTicket call the persisted counter must be
        // reflected on the ON_ERROR save too. The orchestrator calls
        // markError which re-reads via owner-scoped findById before
        // writing ON_ERROR — in production that re-read lands on the
        // DB row persisted by the IN_ANALYSIS save, so the bumped
        // counter survives the failure path. The mock has to simulate
        // that round-trip: the saved ticket becomes the next findById
        // answer, otherwise the mock returns the original (attempts=0)
        // and the test would assert a value that only exists in
        // production.
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        // Mutable holder so the save() answer can publish the bumped
        // ticket that the subsequent markError findById() must return.
        java.util.concurrent.atomic.AtomicReference<Ticket> stored = new java.util.concurrent.atomic.AtomicReference<>(open);
        when(tickets.save(any())).thenAnswer(inv -> {
            Ticket saved = inv.getArgument(0);
            stored.set(saved);
            return saved;
        });
        when(tickets.findById(id, OWNER)).thenAnswer(inv -> Optional.of(stored.get()));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(500, false,
                "boom"));

        service.processTicket(open);

        // Two saves: IN_ANALYSIS (attempts==1) then ON_ERROR
        // (attempts==1, errorMessage set). The counter does NOT
        // increment again on the failure path — incrementAttempts()
        // is called once per processTicket invocation.
        verify(tickets, times(2)).save(any(Ticket.class));
        verify(tickets).save(argThat(t ->
                t.status() == Status.IN_ANALYSIS && t.attempts() == 1));
        verify(tickets).save(argThat(t ->
                t.status() == Status.ON_ERROR && t.attempts() == 1));
    }

    @Test
    void transientFailureIsRetriedThenSucceeds() throws Exception {
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(503, true,
                        "overloaded"))
                .thenReturn(new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "Mercadona", LocalDate.of(2026, Month.JULY, 4), "food",
                                List.of(), new BigDecimal("1.20"), "EUR"),
                        "{}", MODEL));

        boolean processed = service.processTicket(open);

        assertThat(processed).isTrue();
        verify(receiptExtractor, times(2)).extract(any());
        verify(tickets).save(argThat(t -> t.status() == Status.IN_PROGRESS));
    }

    @Test
    void clientErrorFailsFastWithoutRetry() throws Exception {
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(400, false,
                "bad request"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verify(receiptExtractor, times(1)).extract(any());
        verify(tickets).save(argThat(t -> t.status() == Status.ON_ERROR));
    }

    @Test
    void persistentTransientFailureGivesUpAfterConfiguredAttempts() throws Exception {
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(500, true,
                        "boom"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verify(receiptExtractor, times(2)).extract(any());
        verify(tickets).save(argThat(t -> t.status() == Status.ON_ERROR));
    }

    @Test
    void lockConflictBeforeProviderCallSkipsWithoutClobbering() throws Exception {
        // Another writer won the race before segment 1 committed.
        // The service must not call the provider and must not mark
        // error — the winner owns the row.
        long id = nextId();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.save(any())).thenThrow(new OptimisticLockException(id, "boom"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verifyNoExtractorCall();
        verify(tickets, never()).save(argThat(t -> t.status() == Status.ON_ERROR));
    }

    @Test
    void segment2ConflictWithMovedOnTicketSkipsSilently() throws Exception {
        // The row moved on while the provider call was in flight
        // (user validated/cancelled meanwhile). The recovery re-read
        // sees a non-IN_ANALYSIS status and leaves the winner alone.
        long id = nextId();
        Ticket open = sampleTicket(id);
        Ticket done = sampleTicket(id).withStatus(Status.DONE);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.save(any()))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new OptimisticLockException(id, "boom"));
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(done));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "Mercadona", LocalDate.of(2026, Month.JULY, 4), "food",
                                List.of(), new BigDecimal("1.20"), "EUR"),
                        "{}", MODEL));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verify(tickets, never()).save(argThat(t -> t.status() == Status.ON_ERROR));
    }

    @Test
    void segment2ConflictWithStaleAnalysisMarksError() throws Exception {
        // The row is still IN_ANALYSIS after the lost race (crashed
        // or concurrent run that never came back). Recovery lands it
        // on ON_ERROR with a visible message instead of leaving it
        // stuck where the OPEN-only cron never looks.
        long id = nextId();
        Ticket open = sampleTicket(id);
        Ticket staleAnalysis = sampleTicket(id).withStatus(Status.IN_ANALYSIS);
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());
        when(tickets.save(any()))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new OptimisticLockException(id, "boom"))
                .thenAnswer(inv -> inv.getArgument(0));
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(staleAnalysis));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "Mercadona", LocalDate.of(2026, Month.JULY, 4), "food",
                                List.of(), new BigDecimal("1.20"), "EUR"),
                        "{}", MODEL));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verify(tickets).save(argThat(t -> t.status() == Status.ON_ERROR
                && t.errorMessage() != null
                && t.errorMessage().contains("optimistic race")));
    }

    private void verifyNoExtractorCall() throws Exception {
        verify(receiptExtractor, never()).extract(any());
    }

    /** Sequential stand-in for the former UUID test ids. */
    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong(1L);

    private static long nextId() {
        return IDS.incrementAndGet();
    }
}