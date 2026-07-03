import { afterEach, describe, expect, it, vi } from 'vitest';

import { createTicket, TicketApiError, type CreatedTicket } from '../api/tickets';

const sampleCreated: CreatedTicket = {
	id: '8a3d4f12-7c0e-4f6a-9d2b-1e8c4f12abcd',
	title: 'receipt.pdf',
	description: 'Lunch at Mercadona',
	status: 'OPEN',
	createdAt: '2026-07-03T17:00:00Z',
	updatedAt: '2026-07-03T17:00:00Z',
	contentType: 'application/pdf',
	fileName: 'receipt.pdf',
	sizeBytes: 2048
};

function mockResponse(body: unknown, init: { status?: number; ok?: boolean } = {}): Response {
	const status = init.status ?? (init.ok === false ? 500 : 201);
	const ok = init.ok ?? status < 400;
	return new Response(JSON.stringify(body), {
		status,
		headers: { 'content-type': 'application/json' }
	});
}

describe('createTicket', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('POSTs multipart with file + Bearer token and returns the wire DTO', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockResponse(sampleCreated, { status: 201 }));

		const file = new File(['%PDF-1.4 hello'], 'receipt.pdf', { type: 'application/pdf' });
		const created = await createTicket('test.jwt.token', file, 'Lunch at Mercadona');

		expect(created).toEqual(sampleCreated);
		expect(fetchSpy).toHaveBeenCalledTimes(1);

		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/api/tickets');
		expect(init.method).toBe('POST');
		// Multipart boundary is added by the browser; the client must NOT
		// set Content-Type itself or the boundary is stripped.
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer test.jwt.token');
		expect((init.headers as Record<string, string>)['content-type']).toBeUndefined();
		expect(init.body).toBeInstanceOf(FormData);

		const fd = init.body as FormData;
		expect(fd.get('file')).toBe(file);
		expect(fd.get('description')).toBe('Lunch at Mercadona');
	});

	it('omits the description field when none is supplied', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockResponse(sampleCreated, { status: 201 }));

		const file = new File(['x'], 'a.png', { type: 'image/png' });
		await createTicket('tok', file);

		expect(fetchSpy).toHaveBeenCalledTimes(1);
		const init = fetchSpy.mock.calls[0]?.[1] as RequestInit | undefined;
		expect(init).toBeDefined();
		const fd = init!.body as FormData;
		expect(fd.has('description')).toBe(false);
		expect(fd.get('file')).toBe(file);
	});

	it('throws TicketApiError on non-2xx responses', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'file too large' }, { status: 400, ok: false })
		);

		const file = new File(['x'], 'big.pdf', { type: 'application/pdf' });
		await expect(createTicket('tok', file)).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 400,
			message: 'file too large'
		});
	});

	it('falls back to "HTTP {status}" when the error body has no message field', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response('not json', { status: 502, headers: { 'content-type': 'text/plain' } })
		);

		const file = new File(['x'], 'a.png', { type: 'image/png' });
		await expect(createTicket('tok', file)).rejects.toBeInstanceOf(TicketApiError);
		await expect(createTicket('tok', file)).rejects.toThrow('HTTP 502');
	});
});