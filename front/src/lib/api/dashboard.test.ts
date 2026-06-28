import { afterEach, describe, expect, it } from 'vitest';
import {
	USE_MOCK,
	__setMockDelay,
	fetchDashboard,
	type Dashboard,
	type SpendByCategory,
	type TicketsPerMonth
} from './dashboard';

// Default mock delay the service ships with. Re-applied after every test via
// `afterEach` so a `__setMockDelay(0)` call cannot leak across the suite.
const DEFAULT_MOCK_DELAY_MS = 300;

describe('lib/api/dashboard', () => {
	afterEach(() => {
		// Reset to the documented default so neighbouring tests see a clean
		// 300ms baseline regardless of what the previous test set.
		__setMockDelay(DEFAULT_MOCK_DELAY_MS);
	});

	describe('USE_MOCK flag', () => {
		it('is exported and currently true', () => {
			expect(USE_MOCK).toBe(true);
		});
	});

	describe('fetchDashboard delay', () => {
		it('waits roughly the default 300ms before resolving', async () => {
			// Make sure we start from the default — paranoia for test ordering.
			__setMockDelay(DEFAULT_MOCK_DELAY_MS);

			const start = performance.now();
			await fetchDashboard();
			const elapsed = performance.now() - start;

			// Lower bound guards against a regression that drops the delay
			// entirely (e.g. someone wires the real fetch and the promise
			// resolves in <1ms). Upper bound leaves room for CI jitter.
			expect(elapsed).toBeGreaterThanOrEqual(250);
			expect(elapsed).toBeLessThan(500);
		});

		it('honours __setMockDelay(0) and resolves without blocking', async () => {
			__setMockDelay(0);

			// Snapshot the identity immediately. If the implementation ever
			// returns the bare value (not a Promise), this test will catch it
			// — `await` would still work, but `Promise.resolve(value).then`
			// style code in the consumer would break.
			const promise = fetchDashboard();
			expect(promise).toBeInstanceOf(Promise);

			const result = await promise;
			expect(result).toBeDefined();
		});
	});

	describe('fetchDashboard payload shape', () => {
		it('resolves with the documented Dashboard shape', async () => {
			__setMockDelay(0);

			const result = await fetchDashboard();

			// Collection sizes — these are the contract the UI depends on for
			// KPI cards, the 6-month chart and the category breakdown.
			expect(result.ticketsPerMonth).toHaveLength(6);
			expect(result.spendByCategory).toHaveLength(4);
			expect(result.recentTickets).toHaveLength(5);

			// One nested-field confidence check. If the JSON drifts, this
			// fails loudly rather than silently rendering `undefined`.
			expect(result.kpis.totalTickets).toBe(42);
			expect(result.kpis.totalSpentEur).toBe(3187.5);
		});

		it('exposes internally consistent aggregates (catches JSON drift)', async () => {
			__setMockDelay(0);

			const result: Dashboard = await fetchDashboard();

			// spendByCategory.amountEur must sum to kpis.totalSpentEur.
			// Floating-point comparison with a tiny epsilon — `toBe` would
			// fail on e.g. 0.1 + 0.2 rounding.
			const spendTotal = result.spendByCategory.reduce(
				(sum: number, row: SpendByCategory) => sum + row.amountEur,
				0
			);
			expect(Math.abs(spendTotal - result.kpis.totalSpentEur)).toBeLessThan(0.01);

			// ticketsPerMonth.count must sum to kpis.totalTickets.
			// Counts are integers, so a round-trip through Math.round is safe
			// and signals intent: we're comparing an integer aggregate, not a
			// continuous one.
			const ticketsTotal = result.ticketsPerMonth.reduce(
				(sum: number, row: TicketsPerMonth) => sum + row.count,
				0
			);
			expect(Math.round(ticketsTotal)).toBe(result.kpis.totalTickets);
		});
	});
});
