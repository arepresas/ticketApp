import { afterEach, describe, expect, it, vi } from 'vitest';

import { ProductApiError, searchProducts, type ProductSummary } from './products';

function mockResponse(body: unknown, init: { status?: number; ok?: boolean } = {}): Response {
	const status = init.status ?? (init.ok === false ? 500 : 200);
	const ok = init.ok ?? status < 400;
	return new Response(JSON.stringify(body), {
		status,
		headers: { 'content-type': 'application/json' }
	});
}

describe('searchProducts', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('passes name + Bearer token in the request', async () => {
		const expected: ProductSummary[] = [
			{ id: 'p-1', name: 'Bread', unit: null, label: 'Bread' }
		];
		const fetchSpy = vi
			.spyOn(globalThis, 'fetch')
			.mockResolvedValue(mockResponse(expected));

		const got = await searchProducts('jwt-token', 'Bread');

		expect(got).toEqual(expected);
		expect(fetchSpy).toHaveBeenCalledTimes(1);
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/api/products/search?name=Bread');
		expect(init.method).toBe('GET');
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer jwt-token');
		expect((init.headers as Record<string, string>).accept).toBe('application/json');
	});

	it('forwards the optional limit query param', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockResponse([]));

		await searchProducts('tok', 'Be', 5);

		const [url] = (vi.mocked(globalThis.fetch).mock.calls[0] ?? []) as [string, RequestInit];
		expect(url).toBe('/api/products/search?name=Be&limit=5');
	});

	it('trims nothing on the client — BFF handles whitespace', async () => {
		// The SPA passes the typed text verbatim; the BFF trims and
		// returns an empty list for blank input. The client must not
		// silently filter results on its side — empty responses must
		// round-trip through unchanged so the SPA can distinguish
		// "no match" from "still loading".
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockResponse([]));

		const got = await searchProducts('tok', '   ');

		expect(got).toEqual([]);
	});

	it('throws ProductApiError on 4xx with parsed message', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'authentication required' }, { status: 403 })
		);

		await expect(searchProducts('tok', 'B')).rejects.toThrowError(ProductApiError);
		await expect(searchProducts('tok', 'B')).rejects.toMatchObject({
			name: 'ProductApiError',
			status: 403
		});
	});

	it('fires auth:expired on 401', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'expired' }, { status: 401 })
		);

		let fired = false;
		const handler = (): void => {
			fired = true;
		};
		window.addEventListener('auth:expired', handler);
		try {
			await expect(searchProducts('tok', 'B')).rejects.toThrowError(ProductApiError);
		} finally {
			window.removeEventListener('auth:expired', handler);
		}
		expect(fired).toBe(true);
	});

	it('throws ProductApiError on 5xx', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'database unavailable' }, { status: 503 })
		);

		await expect(searchProducts('tok', 'B')).rejects.toThrowError(ProductApiError);
	});
});
