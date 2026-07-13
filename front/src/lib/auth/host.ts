/**
 * Host-side auth gate + history bridge.
 *
 * Side-effect module imported eagerly from front/src/index.ts. On load:
 *  1. Calls `initAuth()` to bootstrap the store from sessionStorage.
 *  2. Mounts `<EffectHost>` (a headless component) at the document body
 *     so its `$effect` can run outside any visible component tree. The
 *     effect toggles `document.body.classList` with `is-authenticated`,
 *     which the index.html stylesheet reads to show/hide the
 *     `<landing-app>` vs `<dashboard-app>` siblings.
 *  3. Calls `setupHistoryBridge()` from `../navigation.ts` so the
 *     popstate listener is installed before any user-triggered
 *     history.pushState. The bridge syncs body classes from the URL
 *     hash on every back / forward event.
 *
 * Why a host module instead of running the effect inside DashboardApp?
 * The landing page MUST hide the instant auth flips to true, even before
 * the dashboard has mounted. Driving the toggle from a sibling element
 * keeps DashboardApp purely concerned with rendering its own subtree.
 *
 * Teardown: returns a `dispose()` function that unmounts the effect host.
 * Vite/HMR will call into it via `import.meta.hot?.dispose()` so we
 * don't leak listeners during dev reloads.
 */
import { mount, unmount } from 'svelte';

import { clearSession, initAuth } from './store.svelte';
import { setupHistoryBridge, navigate } from '../navigation';
import EffectHost from './EffectHost.svelte';

const target = document.createElement('div');
target.setAttribute('aria-hidden', 'true');
target.style.display = 'none';
document.body.appendChild(target);

const instance = mount(EffectHost, { target });

// Bootstrap the store. `initAuth` is idempotent (dedupes via a module-
// scoped promise), so calling it again from host.ts is safe even if
// DashboardApp also calls it on its own mount.
void initAuth();

// Hash-router side effect: install the popstate listener and sync
// the body class to whatever hash the page loaded with.
setupHistoryBridge();

/**
 * Session-expired bridge. Every protected BFF call funnels through
 * a 401 check in `lib/api/tickets.ts`, which fires the
 * {@code auth:expired} DOM event when the BFF rejects the token.
 * Here we react: drop the local session (so the auth listener in
 * `EffectHost` removes `is-authenticated` and the CSS gate reveals
 * the landing) and force a clean URL push so the user doesn't stay
 * on the now-broken detail / pending / new screen.
 *
 * `navigate({kind: 'dashboard'})` is a deliberate no-op for the
 * signed-out user: the dashboard overlay hides behind
 * `body.is-authenticated`, so the body class flips back to "no
 * overlay active" and the landing page shows. The user re-logs in
 * from there.
 */
window.addEventListener('auth:expired', () => {
	clearSession();
	try {
		navigate({ kind: 'dashboard' });
	} catch {
		// Outside a browser (unit tests with no real router) —
		// best-effort; clearing the session alone is the meaningful
		// half of the contract.
	}
});

export const dispose = (): void => {
	unmount(instance);
	target.remove();
};

if (import.meta.hot) {
	import.meta.hot.dispose(dispose);
}