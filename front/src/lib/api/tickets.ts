/**
 * HTTP client for the BFF ticket endpoints.
 *
 * Pure module — no shared state, no DOM access, no console. The store
 * (auth.svelte.ts) owns the session token; callers pass it in. This
 * module never reads sessionStorage itself.
 *
 * Mirrors the `auth/api.ts` conventions: typed errors with `{status}`,
 * shared `parseError` helper for non-2xx responses. The wire contract
 * matches `TicketController.TicketResponse` on the BFF side — bytes are
 * deliberately omitted.
 */
const API_BASE = '/api/tickets';

export class TicketApiError extends Error {
	constructor(
		message: string,
		readonly status: number
	) {
		super(message);
		this.name = 'TicketApiError';
	}
}

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';

export type CreatedTicket = {
	id: string;
	title: string;
	description: string;
	status: TicketStatus;
	createdAt: string;
	updatedAt: string;
	contentType: string | null;
	fileName: string | null;
	sizeBytes: number | null;
};

const parseError = async (res: Response): Promise<string> => {
	try {
		const body = (await res.json()) as { message?: string; error?: string };
		return body.message ?? body.error ?? `HTTP ${res.status}`;
	} catch {
		return `HTTP ${res.status}`;
	}
};

/**
 * Upload a new ticket to the BFF. Sends a multipart payload with the
 * `file` part (the actual receipt) plus optional `description`. The
 * server picks `title` from the filename when the caller doesn't supply
 * one.
 *
 * Returns the created ticket metadata. The file bytes never come back
 * over the wire — they're persisted server-side and can be fetched
 * separately if/when a download endpoint lands.
 *
 * @param token BFF session JWT. Required: the endpoint refuses 401
 *              without a valid Bearer token.
 * @param file  The receipt (image or PDF). Validated client-side for
 *              type and size; the server re-validates as defense in
 *              depth.
 * @param description Optional free-form note. Defaults to empty.
 */
export const createTicket = async (
	token: string,
	file: File,
	description?: string
): Promise<CreatedTicket> => {
	const fd = new FormData();
	fd.append('file', file);
	if (description && description.trim().length > 0) {
		fd.append('description', description.trim());
	}

	const res = await fetch(API_BASE, {
		method: 'POST',
		headers: { authorization: `Bearer ${token}` },
		// No Content-Type header — the browser must set it with the
		// multipart boundary itself. Setting it manually would strip the
		// boundary and break the upload.
		body: fd
	});
	if (!res.ok) {
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as CreatedTicket;
};