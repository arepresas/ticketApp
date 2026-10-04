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
 * Fire `auth:expired` on 401 only.
 *
 * A 403 means "authenticated but not allowed", which is a permission
 * answer, not an expired session. Dispatching on it would clear a valid
 * token and bounce the user to the login screen on any authorisation
 * edge — a logout loop with no way back in. Only the BFF's 401 means the
 * session is gone.
 *
 * Note this deliberately differs from `api/tickets.ts`, which still
 * treats 403 as expiry; that is a bug there, not a convention to copy.
 */
function bubbleAuthExpired(res: Response): void {
  if (res.status === 401) {
    globalThis.dispatchEvent(new CustomEvent('auth:expired'));
  }
}

/**
 * Extract a human-readable message from an error body.
 *
 * <p>The BFF answers errors as RFC 7807 `ProblemDetail`, whose
 * user-facing field is {@code detail} — {@code message} is a legacy
 * Spring shape and is usually absent. Reading only {@code message} meant
 * every real API failure fell through to the raw body, dumping the whole
 * JSON (and whatever internal text it carried) into the UI.
 *
 * <p>Falls back to a status-based message rather than echoing the raw
 * body: an HTML error page from an intermediary is not a message worth
 * showing, and a regression in backend sanitisation should not turn into
 * a UI that renders internal error text.
 */
async function parseError(res: Response): Promise<string> {
  const body = await res.text().catch(() => '');
  if (body) {
    try {
      const parsed: unknown = JSON.parse(body);
      if (parsed && typeof parsed === 'object') {
        const problem = parsed as { detail?: unknown; title?: unknown; message?: unknown };
        for (const field of [problem.detail, problem.title, problem.message]) {
          if (typeof field === 'string' && field) return field;
        }
      }
    } catch {
      /* not JSON — fall through to the generic message */
    }
  }
  return `Request failed with ${res.status}`;
}