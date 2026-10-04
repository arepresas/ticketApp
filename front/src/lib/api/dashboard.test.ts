/**
 * Tests for the dashboard HTTP client.
 *
 * The module used to serve a mock JSON payload behind a `USE_MOCK`
 * flag; it now calls `GET /api/dashboard`. These tests mock `fetch`
 * directly and assert the three things the dashboard depends on:
 * the Authorization header is sent, the JSON contract is passed
 * through untouched, and a 401 bubbles the `auth:expired` event so
 * the session host can clear the stale token.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fetchDashboard, DashboardApiError, type Dashboard } from './dashboard';

const payload: Dashboard = {
	kpis: {
		totalTickets: 42,
		openTickets: 7,
		extractedTicketsInCurrency: 30,
		totalSpent: 3187.5,
		avgTicketValue: 75.89,
		currency: 'EUR'
	},
	ticketsPerMonth: [
		{ month: '2026-04', count: 9 },
		{ month: '2026-05', count: 10 }
	],
	spendByCategory: [
		{ category: 'transport', amount: 845 },
		{ category: 'food', amount: 1120.5 },
		{ category: 'lodging', amount: 920 },
		{ category: 'other', amount: 302 }
	]
};

const jsonResponse = (body: unknown, status = 200): Response =>
	({ ok: status >= 200 && status < 300, status, json: async () => body }) as Response;

describe('fetchDashboard', () => {
	const fetchSpy = vi.fn();

	beforeEach(() => {
		fetchSpy.mockReset();
		vi.stubGlobal('fetch', fetchSpy);
	});

	afterEach(() => {
		vi.unstubAllGlobals();
	});

	it('requests the dashboard endpoint with the session token', async () => {
		fetchSpy.mockResolvedValue(jsonResponse(payload));

		await fetchDashboard('jwt-123');

		expect(fetchSpy).toHaveBeenCalledTimes(1);
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/api/dashboard');
		expect(init.method).toBe('GET');
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer jwt-123');
	});

	it('passes the payload through unchanged', async () => {
		fetchSpy.mockResolvedValue(jsonResponse(payload));

		const result = await fetchDashboard('jwt-123');

		expect(result).toEqual(payload);
		expect(result.kpis.currency).toBe('EUR');
	});

	it('throws a DashboardApiError carrying the status on a non-2xx response', async () => {
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 500,
			text: async () => 'boom'
		} as Response);

		// Both the type and the status: asserting only the type would
		// pass with an omitted or wrong status.
		const error = await fetchDashboard('jwt-123').catch((e: unknown) => e);

		expect(error).toBeInstanceOf(DashboardApiError);
		expect((error as DashboardApiError).status).toBe(500);
	});

	it('bubbles auth:expired on 401 so the session host clears the token', async () => {
		const listener = vi.fn();
		globalThis.addEventListener('auth:expired', listener);
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 401,
			text: async () => '{"message":"expired"}'
		} as Response);

		await expect(fetchDashboard('jwt-123')).rejects.toThrow(DashboardApiError);

		expect(listener).toHaveBeenCalledTimes(1);
		globalThis.removeEventListener('auth:expired', listener);
	});

	// A 403 means "authenticated but not allowed". Treating it as an
	// expired session clears a valid token, which is how a permission
	// edge turns into a logout loop. Only 401 expires the session.
	it.each([400, 403, 404, 429, 503])(
		'does not bubble auth:expired on %i',
		async (status) => {
			const listener = vi.fn();
			globalThis.addEventListener('auth:expired', listener);
			fetchSpy.mockResolvedValue({
				ok: false,
				status,
				text: async () => 'nope'
			} as Response);

			await expect(fetchDashboard('jwt-123')).rejects.toThrow(DashboardApiError);

			expect(listener).not.toHaveBeenCalled();
			globalThis.removeEventListener('auth:expired', listener);
		}
	);

	// The BFF answers RFC 7807 ProblemDetail, whose user-facing field is
	// `detail`. Reading only the legacy `message` meant every real API
	// failure fell through to the raw body.
	it('reads the user-facing detail out of an RFC 7807 ProblemDetail', async () => {
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 400,
			text: async () =>
				JSON.stringify({
					type: 'about:blank',
					title: 'Bad Request',
					status: 400,
					detail: 'merchant must not be blank'
				})
		} as Response);

		await expect(fetchDashboard('jwt-123')).rejects.toThrow('merchant must not be blank');
	});

	it('falls back to the ProblemDetail title when detail is absent', async () => {
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 409,
			text: async () => JSON.stringify({ title: 'Conflict', status: 409 })
		} as Response);

		await expect(fetchDashboard('jwt-123')).rejects.toThrow('Conflict');
	});

	it('still reads a legacy message field', async () => {
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 400,
			text: async () => JSON.stringify({ message: 'nope' })
		} as Response);

		await expect(fetchDashboard('jwt-123')).rejects.toThrow('nope');
	});

	// An intermediary's HTML error page must not become the message a
	// user reads, nor leak internal text if backend sanitisation slips.
	it('never surfaces a non-JSON body verbatim', async () => {
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 502,
			text: async () => '<html>bad gateway</html>'
		} as Response);

		const error = await fetchDashboard('jwt-123').catch((e: unknown) => e);

		expect((error as Error).message).toBe('Request failed with 502');
		expect((error as Error).message).not.toContain('html');
	});
});