/**
 * Dashboard data service.
 *
 * Pure module — no shared state, no DOM access, no console.
 * Currently returns imported mock JSON; swap `USE_MOCK` to `false` and
 * replace the body of `fetchDashboard()` with a `fetch('/api/dashboard')`
 * call once the BFF endpoint lands. Types stay identical either way.
 */
import mockData from '../mocks/dashboard.json';

/**
 * Toggle to switch the service from local mock JSON to the real BFF.
 *
 * To wire the live endpoint:
 *   1. Flip `USE_MOCK` to `false`.
 *   2. Replace the body of `fetchDashboard()` with a `fetch('/api/dashboard')`
 *      call (mirror the `AuthApiError` pattern from `auth/api.ts`).
 *   3. Keep the exported types unchanged — consumers are insulated.
 */
export const USE_MOCK = true;

// ---------------------------------------------------------------------------
// Domain types — keep dependency-free so they can be imported by components,
// tests, and any future real client without dragging runtime code in.
// ---------------------------------------------------------------------------

export type TicketStatus = 'open' | 'closed';

export type TicketCategory = 'transport' | 'food' | 'lodging' | 'other';

export type Kpi = {
  totalTickets: number;
  openTickets: number;
  totalSpentEur: number;
  avgTicketEur: number;
};

export type TicketsPerMonth = {
  /** ISO year-month, e.g. "2026-05". */
  month: string;
  count: number;
};

export type SpendByCategory = {
  category: TicketCategory;
  amountEur: number;
};

export type RecentTicket = {
  id: string;
  title: string;
  category: TicketCategory;
  /** ISO date, YYYY-MM-DD. */
  date: string;
  amountEur: number;
  status: TicketStatus;
};

export type Dashboard = {
  kpis: Kpi;
  ticketsPerMonth: TicketsPerMonth[];
  spendByCategory: SpendByCategory[];
  recentTickets: RecentTicket[];
};

// ---------------------------------------------------------------------------
// Module-level delay — exported setter so tests can override it without
// monkey-patching globals (vitest-friendly). Default 300ms per the bundle.
// ---------------------------------------------------------------------------

let mockDelayMs = 300;

/**
 * Test hook: override the simulated network delay (ms). Pass `0` to disable.
 * No-op when `USE_MOCK` is false, so production code is unaffected.
 */
export const __setMockDelay = (ms: number): void => {
  mockDelayMs = ms;
};

const sleep = (ms: number): Promise<void> =>
  new Promise((resolve) => {
    setTimeout(resolve, ms);
  });

// Narrow the imported JSON to the contract type. Vite ships the JSON as a
// typed const, but the cast keeps us honest if the file drifts.
const loadMock = (): Dashboard => mockData as Dashboard;

/**
 * Fetch the dashboard payload. Currently serves the mock JSON after a
 * short artificial delay so the UI exercises its loading states.
 */
export const fetchDashboard = async (): Promise<Dashboard> => {
  if (USE_MOCK) {
    await sleep(mockDelayMs);
    return loadMock();
  }
  // Placeholder for the real call — intentionally not implemented so a
  // stale flip to `false` fails loudly instead of silently returning mock.
  throw new Error('fetchDashboard: live BFF not yet wired');
};