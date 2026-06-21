import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/svelte';
import Hero from './Hero.svelte';

describe('Hero', () => {
	it('renders the headline', () => {
		const { container } = render(Hero);
		expect(container.querySelector('h1')?.textContent).toMatch(/every receipt/i);
	});

	it('renders the mockup receipt', () => {
		const { container } = render(Hero);
		expect(container.textContent).toMatch(/Mercadona/);
		expect(container.textContent).toMatch(/25.28/);
	});
});
