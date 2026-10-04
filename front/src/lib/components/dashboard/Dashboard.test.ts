/**
 * Dashboard component tests.
 *
 * The two chart sub-components depend on chart.js + canvas, which is fragile
 * in jsdom. We swap them with trivial stubs so the tests focus on Dashboard's
 * own contract: loading state, KPI labels, table headers, retry behaviour,
 * and the no-rerun-on-user-change guarantee.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/svelte';

import type { AuthUser } from '../../auth/types';

// `vi.hoisted` runs before `vi.mock` factories — needed because the mock
// factory below closes over `fetchSpy` but the spy itself is declared later.
const { fetchSpy } = vi.hoisted(() => ({
	fetchSpy: vi.fn(async (_token: string): Promise<unknown> => DASHBOARD_PAYLOAD)
}));

// Shape mirrors the BFF's DashboardResponse. Kept inline (rather than
// importing the deleted mock JSON) so the test fails loudly if the wire
// contract drifts.
const DASHBOARD_PAYLOAD = {
	kpis: {
		totalTickets: 42,
		openTickets: 7,
		extractedTickets: 30,
		totalSpent: 3187.5,
		avgTicketValue: 75.89,
		currency: 'EUR'
	},
	ticketsPerMonth: [
		{ month: '2026-03', count: 8 },
		{ month: '2026-04', count: 9 },
		{ month: '2026-05', count: 10 }
	],
	spendByCategory: [
		{ category: 'transport', amount: 845 },
		{ category: 'food', amount: 1120.5 },
		{ category: 'lodging', amount: 920 },
		{ category: 'other', amount: 302 }
	]
} as const;

// Chart components: stub them so jsdom doesn't need a real canvas. Each stub
// exposes a data-testid so tests can assert presence without coupling to the
// chart.js implementation.
vi.mock('./TicketsPerMonthChart.svelte', () => ({
	default: () => ({ $$render: () => '<div data-testid="chart-line" />' })
}));
vi.mock('./SpendByCategoryChart.svelte', () => ({
	default: () => ({ $$render: () => '<div data-testid="chart-doughnut" />' })
}));

// Wrap the real fetchDashboard with our counted spy so we can assert call
// counts without depending on the network. importActual preserves the real
// module's other exports (the error class and types).
vi.mock('../../api/dashboard', async () => {
	const actual = await vi.importActual<typeof import('../../api/dashboard')>(
		'../../api/dashboard'
	);
	return {
		...actual,
		fetchDashboard: (...args: Parameters<typeof actual.fetchDashboard>) =>
			fetchSpy(...args)
	};
});

// The all-tickets table now self-fetches via listAllTickets. Stub it with
// a deterministic payload so the Dashboard test can still assert the
// table renders on the dashboard. The real listAllTickets behaviour
// (auth, retries, error state) is covered in RecentTicketsTable.test.ts.
const { listAllTicketsStub } = vi.hoisted(() => ({
	listAllTicketsStub: vi.fn<(..._args: unknown[]) => Promise<unknown[]>>()
}));
listAllTicketsStub.mockResolvedValue([
		{
			id: 810,
			title: 'Mercadona weekly',
			description: '',
			status: 'OPEN',
			createdAt: '2026-03-12T10:00:00Z',
			updatedAt: '2026-03-12T10:00:00Z',
			contentType: 'image/png',
			fileName: 'mercadona.png',
			sizeBytes: 12345,
			errorMessage: null,
			attempts: 1
		}
	]);
vi.mock('../../api/tickets', async () => {
	const actual = await vi.importActual<typeof import('../../api/tickets')>(
		'../../api/tickets'
	);
	return {
		...actual,
		listAllTickets: listAllTicketsStub
	};
});

// Now safe to import — the spy wrapper is already in the mocked module.
import Dashboard from './Dashboard.svelte';

const user: AuthUser = {
	id: 'sub-1',
	email: 'ada@example.com',
	name: 'Ada Lovelace',
	picture: 'https://example.com/ada.png'
};

describe('Dashboard', () => {
	beforeEach(() => {
		fetchSpy.mockReset();
		fetchSpy.mockResolvedValue(DASHBOARD_PAYLOAD);
		listAllTicketsStub.mockClear();
		// Seed sessionStorage with a fake JWT so the self-fetching
		// table component can resolve its token lookup. The mock above
		// returns the stub payload regardless of the token value.
		try {
			window.sessionStorage.setItem('ticketapp.session', 'stub.jwt.token');
		} catch {
			/* sessionStorage may be disabled — the table will show its
			   empty state and the headers test below still passes */
		}
	});

	afterEach(() => {
		cleanup();
		vi.restoreAllMocks();
		try {
			window.sessionStorage.clear();
		} catch {
			/* sessionStorage may be disabled — ignore */
		}
	});

	it('renders the loading skeleton initially while the fetch is pending', async () => {
		// Hold the promise open so the loading branch stays on screen.
		fetchSpy.mockImplementation(() => new Promise(() => {}));

		const { container } = render(Dashboard, { user });

		// The busy region + the sr-only "Loading…" text confirm the
		// loading branch is on screen BEFORE the promise resolves.
		expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
		expect(container.textContent).toMatch(/Loading…/);
	});

	it('renders the four KPI labels once the dashboard resolves', async () => {
		const { getByText } = render(Dashboard, { user });

		await waitFor(() => {
			expect(getByText('Total tickets')).toBeTruthy();
			expect(getByText('Open tickets')).toBeTruthy();
			expect(getByText('Total spent')).toBeTruthy();
			expect(getByText('Avg ticket value')).toBeTruthy();
		});

		// The hint on the open-tickets card proves the hint prop wired
		// through AND that the data resolved.
		expect(getByText('7 open')).toBeTruthy();
	});

	// Regression guard. KpiCard used to decide money-vs-count by
	// sniffing the label with /spent|ticket|value/i, so the two count
	// cards rendered as "€42.00" and "€7.00". Counts must stay counts.
	it('renders count cards without a currency symbol and money cards with one', async () => {
		const { container } = render(Dashboard, { user });

		await waitFor(() => {
			expect(container.textContent).toContain('Total spent');
		});

		const text = container.textContent ?? '';
		expect(text).toContain('42');
		expect(text).toContain('7');
		// Money cards carry the € symbol; the counts must not.
		expect(text).toContain('€3,187.50');
		expect(text).toContain('€75.89');
		expect(text).not.toContain('€42');
		expect(text).not.toContain('€7.00');
	});

	it('shows an error instead of firing a request when there is no session', async () => {
		try {
			window.sessionStorage.clear();
		} catch {
			/* sessionStorage may be disabled */
		}

		const { getByText } = render(Dashboard, { user });

		await waitFor(() => {
			expect(getByText('Not signed in.')).toBeTruthy();
		});
		// No token means no request: the endpoint would answer 401 and
		// trip the auth:expired handler for a user never logged in.
		expect(fetchSpy).not.toHaveBeenCalled();
	});

	it('renders the all-tickets table headers and a row from the mocked fetch', async () => {
		const { container } = render(Dashboard, { user });

		await waitFor(() => {
			const headers = Array.from(container.querySelectorAll('thead th')).map(
				(th) => th.textContent?.trim() ?? ''
			);
			expect(headers).toEqual([
				'Ticket',
				'File',
				'Size',
				'Created',
				'Status',
				'Attempts',
				'Error',
				'Actions'
			]);
		});

		// The stub row's title is rendered (proves the table actually
		// fetched via the mocked listAllTickets and rendered the
		// payload end-to-end).
		await waitFor(() => {
			expect(container.textContent).toContain('Mercadona weekly');
		});
	});

	it('exposes the greeting + email in the header', async () => {
		const { getByText } = render(Dashboard, { user });

		// Greeting uses the first name only — split on first space, then trim.
		expect(getByText('Hi, Ada')).toBeTruthy();
		expect(getByText('ada@example.com')).toBeTruthy();
	});

	it('falls back to the full name when there is no space to split on', async () => {
		const single: AuthUser = { id: 'sub-2', email: 'cher@example.com', name: 'Cher' };
		const { getByText } = render(Dashboard, { user: single });
		expect(getByText('Hi, Cher')).toBeTruthy();
	});

	it('only triggers a single fetch on mount (user prop changes do not re-fetch)', async () => {
		const { rerender } = render(Dashboard, { user });

		await waitFor(() => {
			expect(fetchSpy).toHaveBeenCalledTimes(1);
		});

		// Pass a NEW user object reference — the worst case for our `untrack`
		// guard. If we forgot to `untrack`, the effect would re-run here.
		rerender({ user: { ...user, email: 'ada+new@example.com' } });

		// Give any rogue effect tick a chance to (incorrectly) re-fire.
		await new Promise((r) => setTimeout(r, 20));

		expect(fetchSpy).toHaveBeenCalledTimes(1);
	});

	it('calls fetchDashboard again when the Retry button is clicked after an error', async () => {
		// First call rejects, subsequent calls succeed.
		fetchSpy.mockRejectedValueOnce(new Error('boom'));

		const { getByText } = render(Dashboard, { user });

		// Error banner appears with the Retry button.
		await waitFor(() => {
			expect(getByText('Failed to load dashboard')).toBeTruthy();
		});

		expect(fetchSpy).toHaveBeenCalledTimes(1);

		// Click retry → second call → resolves → KPI labels appear.
		await getByText('Retry').click();

		await waitFor(() => {
			expect(getByText('Total tickets')).toBeTruthy();
		});

		expect(fetchSpy).toHaveBeenCalledTimes(2);
	});

	// Regression guard for the header-as-refresh wiring. The greeting
	// header in Dashboard.svelte delegates a click to the inner
	// RecentTicketsTable's `load()` via a `registerLoad` callback prop.
	// If the prop wiring breaks (stale closure, missing $state, etc.)
	// clicking the header does nothing — silent UX regression. The
	// tickets refetch is the only observable signal we can pin here.
	it('re-fetches tickets when the greeting header is clicked', async () => {
		const { container } = render(Dashboard, { user });

		// Wait for the initial mount fetch (Dashboard + RecentTicketsTable
		// both fire on mount, so we expect at least one listAllTickets call).
		await waitFor(() => {
			expect(listAllTicketsStub).toHaveBeenCalledTimes(1);
		});
		const callsAfterMount = listAllTicketsStub.mock.calls.length;

		const header = container.querySelector('[data-testid="dashboard-header"]');
		expect(header).not.toBeNull();
		header?.dispatchEvent(new MouseEvent('click', { bubbles: true }));

		await waitFor(() => {
			expect(listAllTicketsStub.mock.calls.length).toBeGreaterThan(callsAfterMount);
		});
		expect(listAllTicketsStub).toHaveBeenCalledTimes(callsAfterMount + 1);
	});
});