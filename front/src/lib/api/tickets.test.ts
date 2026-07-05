import { afterEach, describe, expect, it, vi } from 'vitest';

import {
	createTicket,
	getTicket,
	getTicketExtraction,
	getTicketFile,
	listPendingTickets,
	TicketApiError,
	updateTicketStatus,
	type CreatedTicket,
	type TicketExtraction
} from '../api/tickets';

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

const sampleExtraction: TicketExtraction = {
	ticketId: sampleCreated.id,
	merchant: 'Mercadona',
	purchaseDate: '2026-07-03',
	category: 'food',
	products: [
		{
			name: 'Bread',
			quantity: 1,
			unit: 'unit',
			pricePerUnit: 1.2,
			lineTotal: 1.2
		}
	],
	totalAmount: 1.2,
	currency: 'EUR',
	model: 'MiniMax-M3',
	extractedAt: '2026-07-03T17:05:00Z'
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

describe('listPendingTickets', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('GETs /api/tickets/pending and returns the wire array', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse([sampleCreated])
		);
		const list = await listPendingTickets('tok');
		expect(list).toEqual([sampleCreated]);
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/api/tickets/pending');
		expect(init.method).toBe('GET');
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer tok');
	});

	it('throws TicketApiError on non-2xx', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'unauthorized' }, { status: 401, ok: false })
		);
		await expect(listPendingTickets('bad')).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 401
		});
	});
});

describe('getTicket', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('GETs /api/tickets/{id} and returns the ticket', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse(sampleCreated)
		);
		const got = await getTicket('tok', sampleCreated.id);
		expect(got).toEqual(sampleCreated);
		const [url] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}`);
	});

	it('surfaces 404 as TicketApiError (caller decides UI)', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'not found' }, { status: 404, ok: false })
		);
		await expect(getTicket('tok', 'nope')).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 404
		});
	});
});

describe('getTicketExtraction', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('GETs /api/tickets/{id}/extraction and returns the structured payload', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse(sampleExtraction)
		);
		const ex = await getTicketExtraction('tok', sampleCreated.id);
		expect(ex).toEqual(sampleExtraction);
		const [url] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}/extraction`);
	});

	it('returns null on 404 (no extraction yet, cross-tenant, or missing ticket)', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response('not found', { status: 404 })
		);
		// All three cases (no row, cross-tenant, missing ticket) share
		// the same 404 response — the API treats them as one. The
		// caller renders an empty state for any of them.
		const ex = await getTicketExtraction('tok', sampleCreated.id);
		expect(ex).toBeNull();
	});

	it('throws TicketApiError on non-404 failures', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'server error' }, { status: 500, ok: false })
		);
		await expect(getTicketExtraction('tok', sampleCreated.id)).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 500
		});
	});
});

describe('getTicketFile', () => {
	afterEach(() => {
		vi.restoreAllMocks();
		// Some tests call URL.createObjectURL which jsdom doesn't
		// implement by default — patch a stub so the assertion path
		// works. `vi.unstubAllMocks` doesn't restore this so we do it
		// manually.
		(globalThis.URL as { createObjectURL?: unknown }).createObjectURL = undefined;
	});

	it('GETs /api/tickets/{id}/file and returns an object URL + content type', async () => {
		(globalThis.URL as { createObjectURL?: unknown }).createObjectURL = vi.fn(
			() => 'blob:http://localhost/fake-id'
		);
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response(new Uint8Array([1, 2, 3, 4]), {
				status: 200,
				headers: { 'content-type': 'image/png' }
			})
		);

		const file = await getTicketFile('tok', sampleCreated.id);

		expect(file.url).toBe('blob:http://localhost/fake-id');
		expect(file.contentType).toBe('image/png');
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}/file`);
		expect(init.method).toBe('GET');
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer tok');
	});

	it('throws TicketApiError on 404', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response('not found', { status: 404 })
		);
		await expect(getTicketFile('tok', sampleCreated.id)).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 404
		});
	});
});

describe('updateTicketStatus', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('PATCHes /api/tickets/{id}/status and returns the updated ticket', async () => {
		const updated: CreatedTicket = { ...sampleCreated, status: 'DONE' };
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse(updated)
		);

		const got = await updateTicketStatus('tok', sampleCreated.id, 'DONE');

		expect(got).toEqual(updated);
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}/status`);
		expect(init.method).toBe('PATCH');
		const headers = init.headers as Record<string, string>;
		expect(headers.authorization).toBe('Bearer tok');
		expect(headers['content-type']).toBe('application/json');
		expect(JSON.parse(init.body as string)).toEqual({ status: 'DONE' });
	});

	it('throws TicketApiError on 404', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'not found' }, { status: 404, ok: false })
		);
		await expect(updateTicketStatus('tok', 'nope', 'DONE')).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 404
		});
	});
});