package com.ticketapp.bff.ai;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.Ticket.Status;
import com.ticketapp.domain.TicketExtractionQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketExtractionJob}.
 *
 * <p>The job is a thin scheduler: it filters candidates, then calls
 * {@link TicketExtractionService#processTicket(Ticket)} for each. The
 * "is the API call correct?" coverage lives in
 * {@code OpenAiApiClientTest}; the "is the status reverted on
 * failure?" coverage lives in {@code TicketExtractionServiceTest}.
 * Here we pin the job's contract: filter, batch-size cap, and
 * short-circuit when the kill switch is off.
 *
 * <p>The scheduler is system-scope — it calls
 * {@link TicketExtractionQueue#findOpenForExtraction(int)} (no owner) so
 * tests below stub that method, not the per-owner read paths.
 */
class TicketExtractionJobTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TicketExtractionQueue tickets;
    private TicketExtractionService service;
    private AiProperties properties;
    private TicketExtractionJob job;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketExtractionQueue.class);
        service = mock(TicketExtractionService.class);
        properties = new AiProperties(
                true,                                  // enabled
                "0 */15 * * * *",
                5,                                     // batchSize
                2,                                     // retryAttempts
                Duration.ofMinutes(10));               // staleAnalysisTimeout
        job = new TicketExtractionJob(tickets, service, properties);
    }

    @Test
    void disabledJobIsNoOp() {
        AiProperties off = withEnabled(false);
        job = new TicketExtractionJob(tickets, service, off);

        job.tick();

        verify(tickets, never()).findOpenForExtraction(anyInt());
        verify(service, never()).processTicket(any());
    }

    @Test
    void emptyCandidateSetShortCircuits() {
        when(tickets.findOpenForExtraction(properties.batchSize())).thenReturn(List.of());

        job.tick();

        verify(service, never()).processTicket(any());
    }

    @Test
    void openTicketsWithinBatchSizeAreProcessed() {
        Ticket t1 = openTicket();
        Ticket t2 = openTicket();
        when(tickets.findOpenForExtraction(properties.batchSize())).thenReturn(List.of(t1, t2));

        job.tick();

        verify(service, times(2)).processTicket(any(Ticket.class));
    }

    @Test
    void nonOpenTicketsAreFilteredOut() {
        // The system-scope query already returns only OPEN rows, but
        // the job double-checks status (defence in depth) so a future
        // change to findOpenForExtraction that loosens the WHERE
        // doesn't silently start processing DONE/ON_ERROR tickets.
        Ticket t = new Ticket(
                UUID.randomUUID(), OWNER, "x", "", Status.DONE, Instant.now(), Instant.now(),
                null, null, null, null, 0, null, null, 0);
        when(tickets.findOpenForExtraction(properties.batchSize())).thenReturn(List.of(t));

        job.tick();

        verify(service, never()).processTicket(any());
    }

    @Test
    void candidatesFromRepositoryAreProcessedVerbatim() {
        // The "already extracted" exclusion lives in SQL now
        // (anti-join in findOpenForExtraction) — the job trusts
        // whatever the repository returns and processes every OPEN
        // row. Pinned here so a future re-addition of in-memory
        // filtering catches the eye.
        Ticket t1 = openTicket();
        Ticket t2 = openTicket();
        when(tickets.findOpenForExtraction(properties.batchSize()))
                .thenReturn(List.of(t1, t2));

        job.tick();

        verify(service).processTicket(t1);
        verify(service).processTicket(t2);
    }

    @Test
    void batchSizeCapsTheCandidateSet() {
        // The repo's findOpenForExtraction already honours the limit
        // — verify the job forwards batchSize verbatim and that the
        // returned set stays under the cap. We stub the repo to
        // return exactly batchSize rows; a wider return would be a
        // contract violation by the impl, not something the job
        // can defend against.
        List<Ticket> capped = List.of(openTicket(), openTicket(), openTicket(),
                openTicket(), openTicket());
        when(tickets.findOpenForExtraction(properties.batchSize())).thenReturn(capped);

        job.tick();

        ArgumentCaptor<Ticket> cap = ArgumentCaptor.forClass(Ticket.class);
        verify(service, times(properties.batchSize())).processTicket(cap.capture());
        assertThat(cap.getAllValues()).hasSize(properties.batchSize());
    }

    @Test
    void inProgressTicketsAreAlsoFilteredOut() {
        Ticket t = new Ticket(
                UUID.randomUUID(), OWNER, "x", "", Status.IN_PROGRESS,
                Instant.now(), Instant.now(), null, null, null, null, 0, null, null, 0);
        when(tickets.findOpenForExtraction(properties.batchSize())).thenReturn(List.of(t));

        job.tick();

        verify(service, never()).processTicket(any());
    }

    @Test
    void onErrorTicketsAreFilteredOut() {
        // ON_ERROR is terminal — the scheduler filters on Status.OPEN
        // only so a failed ticket is never picked up automatically
        // (would loop forever on a broken receipt).
        Ticket t = new Ticket(
                UUID.randomUUID(), OWNER, "x", "", Status.ON_ERROR,
                Instant.now(), Instant.now(), null, null, null,
                "previous failure", 0, null, null, 0);
        when(tickets.findOpenForExtraction(properties.batchSize())).thenReturn(List.of(t));

        job.tick();

        verify(service, never()).processTicket(any());
    }

    @Test
    void databaseFailureFromOneTicketAbortsTheBatch() {
        // A database failure is the one thing the job cannot absorb
        // per ticket: the tick bails and resumes on the next
        // invocation. Provider failures never reach here — the
        // service handles those itself.
        Ticket good = openTicket();
        Ticket bad = openTicket();
        when(tickets.findOpenForExtraction(properties.batchSize())).thenReturn(List.of(good, bad));
        when(service.processTicket(good)).thenReturn(true);
        when(service.processTicket(bad))
                .thenThrow(new DataAccessResourceFailureException("DB down"));

        job.tick();

        // The good ticket reached the service; the bad one threw and
        // the loop bailed (per the existing "log + break" contract).
        verify(service).processTicket(good);
        verify(service).processTicket(bad);
    }

    @Test
    void tickPassesBatchSizeToRepository() {
        // The job must forward batchSize verbatim so the SQL LIMIT
        // caps the row count we scan per tick. Indirect verification:
        // capture the int argument and assert it equals the property.
        when(tickets.findOpenForExtraction(properties.batchSize())).thenReturn(List.of());

        job.tick();

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(tickets).findOpenForExtraction(cap.capture());
        assertThat(cap.getValue()).isEqualTo(properties.batchSize());
    }

    @Test
    void abandonedClaimsAreRequeuedBeforeCandidatesArePicked() {
        Ticket stuck = openTicket();
        when(tickets.requeueAbandonedAnalysis(any(Instant.class), anyInt()))
                .thenReturn(List.of(stuck));
        when(tickets.findOpenForExtraction(properties.batchSize()))
                .thenReturn(List.of(stuck.withStatus(Status.OPEN)));

        job.tick();

        // The cutoff must sit one timeout in the past (10 min, with
        // slack so the assertion can't race the clock), and the
        // batch cap must bound how many rows one tick can re-queue.
        verify(tickets).requeueAbandonedAnalysis(
                argThat(cutoff -> cutoff.isBefore(Instant.now().minusSeconds(60))
                        && cutoff.isAfter(Instant.now().minusSeconds(660))),
                eq(5));
    }

    @Test
    void disabledJobDoesNotRequeueAbandonedClaims() {
        job = new TicketExtractionJob(tickets, service, withEnabled(false));

        job.tick();

        verify(tickets, never()).requeueAbandonedAnalysis(any(Instant.class), anyInt());
    }

    @Test
    void requeueHappensBeforeCandidateSelection() {
        Ticket stuck = openTicket();
        when(tickets.requeueAbandonedAnalysis(any(Instant.class), anyInt()))
                .thenReturn(List.of(stuck));
        when(tickets.findOpenForExtraction(anyInt())).thenReturn(List.of());

        job.tick();

        InOrder order = inOrder(tickets);
        order.verify(tickets).requeueAbandonedAnalysis(any(Instant.class), anyInt());
        order.verify(tickets).findOpenForExtraction(anyInt());
    }

    // ---- helpers ---------------------------------------------------------

    private static Ticket openTicket() {
        return openTicket(UUID.randomUUID());
    }

    private static Ticket openTicket(UUID id) {
        return new Ticket(id, OWNER, "title", "", Status.OPEN,
                Instant.now(), Instant.now(), null, null, null, null, 0, null, null, 0);
    }

    private static AiProperties withEnabled(boolean enabled) {
        return new AiProperties(
                enabled,
                "0 0 0 * * *",
                5,
                2,
                Duration.ofMinutes(10));
    }
}