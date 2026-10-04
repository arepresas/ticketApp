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

		await expect(fetchDashboard('jwt-123')).rejects.toThrow(DashboardApiError);
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

	it('does not bubble auth:expired on a non-auth failure', async () => {
		const listener = vi.fn();
		globalThis.addEventListener('auth:expired', listener);
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 503,
			text: async () => 'unavailable'
		} as Response);

		await expect(fetchDashboard('jwt-123')).rejects.toThrow(DashboardApiError);

		expect(listener).not.toHaveBeenCalled();
		globalThis.removeEventListener('auth:expired', listener);
	});

	it('surfaces the server message when the error body is JSON', async () => {
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 400,
			text: async () => JSON.stringify({ message: 'nope' })
		} as Response);

		await expect(fetchDashboard('jwt-123')).rejects.toThrow('nope');
	});

	it('falls back to the raw body when the error is not JSON', async () => {
		fetchSpy.mockResolvedValue({
			ok: false,
			status: 502,
			text: async () => '<html>bad gateway</html>'
		} as Response);

		await expect(fetchDashboard('jwt-123')).rejects.toThrow('<html>bad gateway</html>');
	});
});