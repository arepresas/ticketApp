import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/svelte';
import KpiCard from './KpiCard.svelte';

describe('KpiCard', () => {
	it('renders the label and value', () => {
		const { container } = render(KpiCard, { label: 'Total tickets', value: 42 });
		expect(container.textContent).toMatch(/total tickets/i);
		expect(container.textContent).toMatch(/42/);
	});

	it('formats numeric currency values with a euro prefix when the label implies money', () => {
		const { container } = render(KpiCard, { label: 'Total spent', value: 3187.5 });
		expect(container.textContent).toMatch(/€3,187\.50/);
	});
});