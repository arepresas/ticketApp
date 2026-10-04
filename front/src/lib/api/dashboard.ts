/**
 * HTTP client for the BFF dashboard aggregate — the four KPI cards and
 * both charts.
 *
 * Replaces the mock JSON this module used to ship. The wire contract
 * mirrors `DashboardController.DashboardResponse`; see
 * `bff/.../api/dto/DashboardResponse.java`.
 *
 * 401 handling: every protected call funnels through
 * {@link bubbleAuthExpired} before throwing, matching
 * `api/tickets.ts`, so an expired session fires the `auth:expired` DOM
 * event that `auth/host.ts` listens for.
 */
const API_BASE = '/api/dashboard';

export class DashboardApiError extends Error {
	constructor(
		message: string,
		readonly status: number
	) {
		super(message);
		this.name = 'DashboardApiError';
	}
}

/** The four categories the donut knows about, in wire (lowercase) form. */
export type SpendCategory = 'transport' | 'food' | 'lodging' | 'other';

export type Kpi = {
  /** Tickets the user can still see a record of (excludes deleted/cancelled). */
  totalTickets: number;
  /** Tickets not yet in a terminal state. */
  openTickets: number;
  /**
   * Tickets the AI read *in {@link currency}* — the average is taken
   * over these, not over `totalTickets`. Deliberately named so the gap
   * against `totalTickets` (an owner holding receipts in another
   * currency) reads as intentional rather than as a bug.
   */
  extractedTicketsInCurrency: number;
  /**
   * Sum over {@link extractedTickets}, expressed in {@link currency}.
   * Not suffixed `Eur` on purpose: the BFF reports one currency and
   * names it, so the UI must not assume euros.
   */
  totalSpent: number;
  avgTicketValue: number;
  /** ISO 4217 code the money figures above are denominated in. */
  currency: string;
};

export type TicketsPerMonth = {
  /**
   * ISO year-month, e.g. "2026-05". This is the receipt's purchase
   * month, not its upload date, and the series is always gap-free.
   */
  month: string;
  count: number;
};

export type SpendByCategory = {
  category: SpendCategory;
  amount: number;
};

export type Dashboard = {
  kpis: Kpi;
  ticketsPerMonth: TicketsPerMonth[];
  spendByCategory: SpendByCategory[];
};

/**
 * Fetch the dashboard payload.
 *
 * @param token BFF session JWT. The endpoint returns 401 without one —
 *              callers must hold a live session before rendering.
 */
export const fetchDashboard = async (token: string): Promise<Dashboard> => {
  const res = await fetch(API_BASE, {
    method: 'GET',
    headers: { authorization: `Bearer ${token}`, accept: 'application/json' }
  });
  if (!res.ok) {
    bubbleAuthExpired(res);
    throw new DashboardApiError(await parseError(res), res.status);
  }
  return (await res.json()) as Dashboard;
};

/**
 * Fire `auth:expired` on 401/403 so the session host clears the stale
 * token. Identical to the helper in `api/tickets.ts`; duplicated rather
 * than exported from there because each API module is meant to stay
 * independently importable.
 */
function bubbleAuthExpired(res: Response): void {
  if (res.status === 401 || res.status === 403) {
    globalThis.dispatchEvent(new CustomEvent('auth:expired'));
  }
}

async function parseError(res: Response): Promise<string> {
  const text = await res.text().catch(() => '');
  if (!text) return `Request failed with ${res.status}`;
  try {
    const parsed: unknown = JSON.parse(text);
    if (parsed && typeof parsed === 'object' && 'message' in parsed) {
      const message = (parsed as { message?: unknown }).message;
      if (typeof message === 'string' && message) return message;
    }
  } catch {
    /* not JSON — fall through to the raw text */
  }
  return text;
}