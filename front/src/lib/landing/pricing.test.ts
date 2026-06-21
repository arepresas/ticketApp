import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/svelte';
import Pricing from './Pricing.svelte';

describe('Pricing', () => {
	it('renders 3 tiers', () => {
		const { container } = render(Pricing);
		expect(container.querySelectorAll('a[href="#"]').length).toBeGreaterThanOrEqual(3);
	});

	it('highlights the Pro tier', () => {
		const { container } = render(Pricing);
		expect(container.textContent).toContain('Most popular');
	});
});
