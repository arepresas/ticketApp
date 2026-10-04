import { describe, expect, it } from 'vitest';

import { formatDate, formatDateTime } from './date';

/**
 * The whole point of `date.ts` is that these render day-first in
 * English regardless of the machine locale. A test that only ran on a
 * developer's `en-GB` browser would pass even if the locale were
 * removed, so these assertions pin the *shape* of the output: `en-GB`
 * medium style is "21 Mar 2026", never "3/21/2026" and never
 * "21 mar 2026".
 *
 * The subject is built with the local-time constructor on purpose —
 * `Intl.DateTimeFormat` renders in the viewer's timezone, so a
 * `Z`-suffixed instant would assert a different hour on every machine.
 */
describe('date formatters', () => {
	const local = new Date(2026, 2, 21, 14, 7, 9);

	it('formats the date day-first with an English month', () => {
		expect(formatDate(local)).toBe('21 Mar 2026');
	});

	it('includes the time for formatDateTime', () => {
		expect(formatDateTime(local)).toBe('21 Mar 2026, 14:07');
	});

	it('never falls back to a numeric month-first format', () => {
		// The regression this module exists to prevent: an `en-US`
		// machine rendering "3/21/2026" for the same instant.
		expect(formatDate(local)).not.toMatch(/^\d{1,2}\/\d{1,2}\/\d{2,4}$/);
	});

	it('accepts an ISO string as well as a Date', () => {
		expect(formatDate(local.toISOString())).toBe('21 Mar 2026');
		expect(formatDateTime(local.toISOString())).toBe('21 Mar 2026, 14:07');
	});

	it('returns the raw string when the value cannot be parsed', () => {
		// The dashboard should degrade, not crash, on a malformed
		// timestamp from the backend.
		expect(formatDate('not-a-date')).toBe('not-a-date');
		expect(formatDateTime('not-a-date')).toBe('not-a-date');
	});
});