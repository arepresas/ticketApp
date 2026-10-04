/**
 * Date / time formatters.
 *
 * Three things matter:
 *
 * 1. The UI copy is English, so the locale must be English too —
 *    formatting with `es-ES` would render "21 mar 2026" (Spanish
 *    month abbreviations) inside an English screen.
 *
 * 2. The region is Spain, which writes dates day-first. We use
 *    `en-GB` rather than `en-US` precisely because it is English
 *    *and* day-first: "21 Mar 2026", never "3/21/2026". The browser
 *    default (i.e. `undefined` in `Intl.DateTimeFormat`) is whatever
 *    the OS ships with — a fresh VM or a machine set to `en-US`
 *    produces month-first, which is the bug this file fixes.
 *
 * 3. This covers *display* of timestamps the backend sends as ISO-8601
 *    (`createdAt`, `updatedAt`, …). It does NOT affect
 *    `<input type="date">`: browsers render those from the browser's
 *    own locale and ignore both this module and the page's `lang`.
 *    See the `purchaseDate` field in `TicketDetailApp.svelte` — that
 *    one is only fixable browser-side (Chrome display language
 *    `en-GB`).
 */

const LOCALE = 'en-GB';

const dateFmt = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium' });
const dtFmt = new Intl.DateTimeFormat(LOCALE, {
	dateStyle: 'medium',
	timeStyle: 'short'
});

function toDate(value: Date | string | number): Date {
	if (value instanceof Date) return value;
	return new Date(value);
}

/**
 * Render a timestamp day-first, e.g. "21 Mar 2026".
 * Accepts an ISO-8601 string, a {@link Date}, or a numeric epoch
 * millis value. Falls back to the raw input on parse failure — the
 * dashboard should degrade, not crash, on a malformed timestamp
 * from the backend.
 */
export function formatDate(value: Date | string | number): string {
	try {
		return dateFmt.format(toDate(value));
	} catch {
		return typeof value === 'string' ? value : '';
	}
}

/**
 * Render a timestamp day-first with the time, e.g. "21 Mar 2026, 14:07".
 */
export function formatDateTime(value: Date | string | number): string {
	try {
		return dtFmt.format(toDate(value));
	} catch {
		return typeof value === 'string' ? value : '';
	}
}