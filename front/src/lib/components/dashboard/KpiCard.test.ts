import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/svelte';

import KpiCard from './KpiCard.svelte';

/**
 * `format` is explicit rather than inferred from the label. These tests
 * pin both halves of that contract, because the bug it replaced was
 * silent: a label sniffing `/spent|ticket|value/i` rendered the two
 * *count* cards ("Total tickets", "Open tickets") with a € prefix.
 */
describe('KpiCard', () => {
	it('renders the label and value', () => {
		const { container } = render(KpiCard, { label: 'Total tickets', value: 42 });
		expect(container.textContent).toMatch(/total tickets/i);
		expect(container.textContent).toMatch(/42/);
	});

	it('defaults to count so a bare value never gains a currency symbol', () => {
		const { container } = render(KpiCard, { label: 'Total tickets', value: 42 });
		expect(container.textContent).not.toContain('€');
	});

	it('formats counts with thousands separators and no decimals', () => {
		const { container } = render(KpiCard, { label: 'Total tickets', value: 1234, format: 'count' });
		expect(container.textContent).toContain('1,234');
		expect(container.textContent).not.toContain('€');
	});

	it('formats money with two decimals and the currency symbol', () => {
		const { container } = render(KpiCard, {
			label: 'Total spent',
			value: 3187.5,
			format: 'money',
			currency: 'EUR'
		});
		expect(container.textContent).toContain('€3,187.50');
	});

	it('uses the symbol of the currency it was given, not a hardcoded euro', () => {
		const { container } = render(KpiCard, {
			label: 'Total spent',
			value: 10,
			format: 'money',
			currency: 'GBP'
		});
		expect(container.textContent).toContain('£10.00');
		expect(container.textContent).not.toContain('€');
	});

	it('falls back to the currency code when there is no known symbol', () => {
		// A wrong symbol is worse than a slightly verbose one.
		const { container } = render(KpiCard, {
			label: 'Total spent',
			value: 10,
			format: 'money',
			currency: 'CHF'
		});
		expect(container.textContent).toContain('CHF 10.00');
	});

	it('renders string values verbatim regardless of format', () => {
		const { container } = render(KpiCard, {
			label: 'Status',
			value: 'n/a',
			format: 'money',
			currency: 'EUR'
		});
		expect(container.textContent).toContain('n/a');
	});

	it('renders the hint when provided', () => {
		const { container } = render(KpiCard, {
			label: 'Open tickets',
			value: 7,
			format: 'count',
			hint: '7 open'
		});
		expect(container.textContent).toContain('7 open');
	});
});