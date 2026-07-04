/**
 * Header component tests.
 *
 * The header lives in the light DOM (mounted by `index.ts`), so it can be
 * tested directly with `@testing-library/svelte` without a custom-element
 * registry. We mock the auth store to swap between public and authenticated
 * modes — that's the only branching the component does.
 */
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/svelte';

import type { AuthUser } from '../../auth/types';
import Stub from './__stubs__/Stub.svelte';

type AuthSnapshot = {
	user: AuthUser | null;
	status: 'idle' | 'loading' | 'authenticated' | 'error';
	isAuthenticated: boolean;
	error: string | null;
};

// Mutable snapshot swapped per test. `vi.hoisted` so the mock factory can
// close over the same instance — the factory runs before imports resolve.
const { snapshot } = vi.hoisted(() => ({
	snapshot: {
		user: null,
		status: 'idle',
		isAuthenticated: false,
		error: null
	} as AuthSnapshot
}));

vi.mock('../../auth/store.svelte.ts', () => ({
	auth: {
		get user() {
			return snapshot.user;
		},
		get status() {
			return snapshot.status;
		},
		get isAuthenticated() {
			return snapshot.isAuthenticated;
		},
		get error() {
			return snapshot.error;
		}
	},
	initAuth: vi.fn().mockResolvedValue(undefined),
	loginWithGoogle: vi.fn(),
	logout: vi.fn()
}));

// Replace the real components with the Stub. Stub receives an `id` prop and
// renders `<div data-testid={id} />`, which the tests use to assert the
// children were composed.
vi.mock('../../auth/GoogleLoginButton.svelte', () => ({
	default: Stub
}));
vi.mock('../../landing/ThemeToggle.svelte', () => ({
	default: Stub
}));

// Import AFTER the mocks so the Svelte compiler picks them up.
import Header from './Header.svelte';

// Desktop nav is the first `<nav>` in the document (the second is the
// mobile drawer, hidden behind a media query). Pull labels from there only.
const desktopLabels = (container: HTMLElement): string[] => {
	const desktopNav = container.querySelector('nav[aria-label="Primary"]');
	if (!desktopNav) return [];
	return Array.from(desktopNav.querySelectorAll('a')).map(
		(a) => a.textContent?.trim() ?? ''
	);
};

describe('Header', () => {
	// The Header reads `VITE_GOOGLE_CLIENT_ID` from `import.meta.env` and
	// only mounts the GoogleLoginButton wrapper when it's truthy. CI may
	// run without that env var defined, so the test must force a value
	// to keep the assertions deterministic. `vi.stubEnv` (Vitest ≥ 1.x)
	// does exactly this and is auto-restored by `afterAll` via
	// `vi.unstubAllEnvs`.
	beforeEach(() => {
		vi.stubEnv('VITE_GOOGLE_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
		cleanup();
		snapshot.user = null;
		snapshot.status = 'idle';
		snapshot.isAuthenticated = false;
		snapshot.error = null;
	});

	afterAll(() => {
		vi.unstubAllEnvs();
	});

	it('renders the marketing links when the user is signed out', () => {
		const { container } = render(Header);

		expect(desktopLabels(container)).toEqual([
			'Features',
			'How it works',
			'Pricing',
			'FAQ'
		]);
	});

	it('renders the app links when the user is authenticated', () => {
		snapshot.user = {
			id: 'u-1',
			email: 'ada@example.com',
			name: 'Ada Lovelace'
		};
		snapshot.isAuthenticated = true;
		snapshot.status = 'authenticated';

		const { container } = render(Header);

		expect(desktopLabels(container)).toEqual(['Pending tickets', 'New', 'Dashboard']);
	});

	it('wires the "New" link to the upload-screen hash', () => {
		// The "New" entry doesn't navigate to an in-page anchor like the
		// other app links — it opens the <new-ticket-app> shell by setting
		// the `is-new` body class. Pinning the href keeps that contract
		// visible from the test surface.
		snapshot.user = {
			id: 'u-1',
			email: 'ada@example.com',
			name: 'Ada Lovelace'
		};
		snapshot.isAuthenticated = true;
		snapshot.status = 'authenticated';

		const { container } = render(Header);

		const desktopNav = container.querySelector('nav[aria-label="Primary"]');
		const newLink = desktopNav?.querySelector<HTMLAnchorElement>('a[href="#new"]');
		expect(newLink?.textContent?.trim()).toBe('New');
	});

	it('includes the theme toggle and login button', () => {
		const { container } = render(Header);

		// Stub components render an element with data-testid matching the
		// `id` prop they receive.
		expect(container.querySelector('[data-testid="theme-toggle"]')).toBeTruthy();
		expect(container.querySelector('[data-testid="google-login-button"]')).toBeTruthy();
	});

	it('keeps the GoogleLoginButton mounted when authenticated (so the avatar menu survives the auth flip)', () => {
		// Regression guard for a previous bug: the Header used to gate the
		// GoogleLoginButton on `!auth.isAuthenticated`, which unmounted the
		// component (and its avatar + sign-out menu) the instant the user
		// signed in. The fix always mounts it; the child branches internally.
		snapshot.user = {
			id: 'u-1',
			email: 'ada@example.com',
			name: 'Ada Lovelace'
		};
		snapshot.isAuthenticated = true;
		snapshot.status = 'authenticated';

		const { container } = render(Header);

		expect(container.querySelector('[data-testid="google-login-button"]')).toBeTruthy();
	});

	it('does not render the GitHub link when the user is authenticated', () => {
		// GitHub is a marketing affordance — keep the authenticated header focused.
		snapshot.user = {
			id: 'u-1',
			email: 'ada@example.com',
			name: 'Ada Lovelace'
		};
		snapshot.isAuthenticated = true;

		const { container } = render(Header);

		const github = container.querySelector('a[aria-label="GitHub"]');
		expect(github).toBeNull();
	});
});
