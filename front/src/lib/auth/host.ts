/**
 * Host-side auth gate.
 *
 * Side-effect module imported eagerly from front/src/index.ts. On load:
 *  1. Calls `initAuth()` to bootstrap the store from sessionStorage.
 *  2. Mounts `<EffectHost>` (a headless component) at the document body
 *     so its `$effect` can run outside any visible component tree. The
 *     effect toggles `document.body.classList` with `is-authenticated`,
 *     which the index.html stylesheet reads to show/hide the
 *     `<landing-app>` vs `<dashboard-app>` siblings.
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

import { initAuth } from './store.svelte';
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

export const dispose = (): void => {
	unmount(instance);
	target.remove();
};

if (import.meta.hot) {
	import.meta.hot.dispose(dispose);
}