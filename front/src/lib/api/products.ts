/**
 * HTTP client for the BFF product catalogue endpoints.
 *
 * Used by the ticket-detail screen to power the line-editor
 * autocomplete + the "in DB" hint icon. Mirrors the typed-error +
 * 401-bubbling conventions in `auth/api.ts` and `tickets.ts`.
 */

export class ProductApiError extends Error {
	constructor(
		message: string,
		readonly status: number
	) {
		super(message);
		this.name = 'ProductApiError';
	}
}

/**
 * One row of the autocomplete payload. The BFF strips the catalogue
 * match key (`normalisedName`) from the wire shape — `name` + `unit`
 * are enough for the SPA to render the dropdown option and to
 * recompute the match status when the user picks an entry.
 */
export type ProductSummary = {
	id: string;
	/** Display name as printed on past receipts. */
	name: string;
	/** Unit label ("kg", "L", "unit", ...). Null when the line had no unit. */
	unit: string | null;
	/** Pre-rendered display label: "Name (unit)" or just "Name". */
	label: string;
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
 * Search the catalogue by name prefix (case-insensitive). Used by
 * the ticket-detail line-editor — a debounced fetch keeps the
 * autocomplete responsive while the user types.
 *
 * @param token  BFF session JWT.
 * @param name   prefix (already trimmed by the caller).
 * @param limit  optional, defaults to the BFF's default of 10.
 * @returns up to `limit` matching products, ordered alphabetically.
 *          Empty array when the prefix is blank or nothing matches.
 * @throws ProductApiError on 4xx/5xx.
 */
export const searchProducts = async (
	token: string,
	name: string,
	limit?: number
): Promise<ProductSummary[]> => {
	const qs = new URLSearchParams();
	qs.set('name', name);
	if (limit !== undefined) qs.set('limit', String(limit));

	const res = await fetch(`/api/products/search?${qs.toString()}`, {
		method: 'GET',
		headers: { authorization: `Bearer ${token}`, accept: 'application/json' }
	});
	if (res.status === 401) {
		// Mirror tickets.ts: surface a 401 to the auth host so the
		// dashboard can clear the session and route back to landing.
		if (typeof window !== 'undefined') {
			window.dispatchEvent(new CustomEvent('auth:expired'));
		}
		throw new ProductApiError(await parseError(res), res.status);
	}
	if (!res.ok) {
		throw new ProductApiError(await parseError(res), res.status);
	}
	return (await res.json()) as ProductSummary[];
};
