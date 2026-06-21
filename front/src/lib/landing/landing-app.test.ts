import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/svelte';
import LandingApp from './LandingApp.svelte';

describe('LandingApp', () => {
	it('renders main, header and footer landmarks', () => {
		const { container } = render(LandingApp);
		expect(container.querySelector('main#main')).toBeTruthy();
		expect(container.querySelector('header')).toBeTruthy();
		expect(container.querySelector('footer')).toBeTruthy();
	});

	it('renders key section landmarks', () => {
		const { container } = render(LandingApp);
		for (const id of ['#hero', '#features', '#how-it-works', '#pricing', '#faq', '#cta']) {
			expect(container.querySelector(id), 'missing ' + id).toBeTruthy();
		}
	});
});
