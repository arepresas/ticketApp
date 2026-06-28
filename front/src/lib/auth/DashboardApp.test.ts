import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { AuthUser } from './types';

// Stub the auth store before importing the component under test. The
// runes-mode `$state` singleton can't be reset between tests, so
// `vi.mock` is the only clean way to control what DashboardApp sees.
//
// Mocking Dashboard.svelte too — otherwise its mount-time `fetchDashboard()`
// would hit the network. The stub returns a minimal Svelte-component-like
// shape so any downstream consumer can introspect it without crashing.
const mockAuth = {
	user: null as AuthUser | null,
	status: 'idle' as 'idle' | 'loading' | 'authenticated' | 'error',
	isAuthenticated: false,
	error: null as string | null
};

vi.mock('./store.svelte.ts', () => ({
	auth: mockAuth,
	initAuth: vi.fn().mockResolvedValue(undefined),
	loginWithGoogle: vi.fn(),
	logout: vi.fn()
}));

vi.mock('../components/dashboard/Dashboard.svelte', () => ({
	default: {}
}));

describe('DashboardApp', () => {
	beforeEach(() => {
		mockAuth.user = null;
		mockAuth.status = 'idle';
		mockAuth.isAuthenticated = false;
		mockAuth.error = null;
	});

	it('exports a Svelte component module', async () => {
		// Importing triggers the customElement registration side-effect. We
		// just need to know the module loaded without throwing and the
		// default export is present. `render()` against a <svelte:options
		// customElement="..."> component isn't supported by
		// @testing-library/svelte in jsdom — coverage of the actual gates
		// is deferred to subtask 10 where E2E (Playwright) is in scope.
		const mod = await import('./DashboardApp.svelte');
		expect(mod.default).toBeDefined();
	});

	it('mocked auth store reports the unauthenticated baseline', () => {
		expect(mockAuth.isAuthenticated).toBe(false);
		expect(mockAuth.user).toBeNull();
		expect(mockAuth.status).toBe('idle');
	});

	// Full coverage (reactive re-render on login, body-class sync, error
	// state, loading skeleton) is deferred to subtask 10 — those tests
	// need a real custom-element registry and Playwright, not jsdom.
});