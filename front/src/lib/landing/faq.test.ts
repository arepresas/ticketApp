import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/svelte';
import Faq from './Faq.svelte';

describe('Faq', () => {
	it('renders 5 details elements', () => {
		const { container } = render(Faq);
		expect(container.querySelectorAll('details').length).toBe(5);
	});

	it('opens the first question by default', () => {
		const { container } = render(Faq);
		expect(container.querySelectorAll('details')[0].hasAttribute('open')).toBe(true);
	});
});
