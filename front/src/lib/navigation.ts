/**
 * Hash-based router + history bridge.
 *
 * The single-page app has five overlay screens (landing, dashboard,
 * new, pending, detail) — all sibling `<custom-element>`s mounted
 * once in `index.html`, toggled by body classes (`is-pending`,
 * `is-new`, `is-detail`). This module is the single source of
 * truth for the body-class ↔ URL mapping.
 *
 * Why a shared module instead of letting each component manage its
 * own history?
 *  - Browser back / forward must work across screens. Setting
 *    `window.location.hash` does add a history entry, but screen
 *    transitions between overlays (pending → detail → back) need
 *    a single listener to keep the body class in sync with the
 *    URL. Spreading that logic across three components leaks
 *    routing concerns into the UI shells.
 *  - The X (close) buttons are gone (the user wants browser
 *    back/forward to be the only way out). `navigateBack()` is a
 *    one-liner replacement; the alternative — each shell calling
 *    its own `history.replaceState` — was the original bug.
 *
 * Hash format:
 *   (empty)         → dashboard (when authenticated) / landing
 *   #pending        → pending tickets list
 *   #new            → new-ticket upload
 *   #ticket/<uuid>  → per-ticket detail
 *
 * `setupHistoryBridge()` is idempotent and must be called once on
 * app boot (from `host.ts`). It installs the popstate listener and
 * syncs the body class to whatever hash the page loaded with
 * (e.g. the user refreshes while inside `#pending` → still shows
 * pending).
 */

export type Route =
	| { kind: 'dashboard' }
	| { kind: 'pending' }
	| { kind: 'new' }
	| { kind: 'detail'; ticketId: string };

const TICKET_HASH_RE = /^#?ticket\/([0-9a-fA-F-]{36})$/;

function readHash(): string {
	return window.location.hash;
}

/**
 * Map the current URL hash to a {@link Route}. Unknown / empty
 * hashes fall through to `{kind: 'dashboard'}` — the default
 * authenticated view.
 */
export function parseRoute(): Route {
	const h = readHash();
	if (h === '' || h === '#' || h === '#dashboard') return { kind: 'dashboard' };
	if (h === '#pending') return { kind: 'pending' };
	if (h === '#new') return { kind: 'new' };
	const m = TICKET_HASH_RE.exec(h);
	if (m) return { kind: 'detail', ticketId: m[1] };
	return { kind: 'dashboard' };
}

function bodyClassFor(route: Route): string | null {
	switch (route.kind) {
		case 'dashboard':
			return null;
		case 'pending':
			return 'is-pending';
		case 'new':
			return 'is-new';
		case 'detail':
			return 'is-detail';
	}
}

function hashFor(route: Route): string {
	switch (route.kind) {
		case 'dashboard':
			return '';
		case 'pending':
			return '#pending';
		case 'new':
			return '#new';
		case 'detail':
			return `#ticket/${route.ticketId}`;
	}
}

/**
 * Sync the body class + dispatch the per-screen open event for the
 * given route. Pure side-effect function — no history mutation,
 * called both from {@link navigate} (forward) and from the popstate
 * listener (back/forward).
 */
export function applyRoute(route: Route): void {
	document.body.classList.remove('is-pending', 'is-new', 'is-detail');
	const cls = bodyClassFor(route);
	if (cls) document.body.classList.add(cls);
	switch (route.kind) {
		case 'dashboard':
			// Self-fetching screens need a signal to re-pull their
			// data when the dashboard becomes the active route,
			// regardless of how the user got there — forward
			// navigation, same-route re-click, or back/forward.
			// Centralising the dispatch here (instead of in
			// `navigate`) means the popstate path also fires it,
			// which is the only way the dashboard refreshes after
			// a `history.back()` from the new-ticket screen or
			// any other screen that back-navigates to the dashboard.
			// The listener in RecentTicketsTable skips the event
			// while the initial mount fetch is in flight, so the
			// boot-time applyRoute call (no listener yet) is
			// harmless.
			window.dispatchEvent(new CustomEvent('dashboard:refresh'));
			break;
		case 'pending':
			window.dispatchEvent(new CustomEvent('pending:open'));
			break;
		case 'new':
			break;
		case 'detail':
			window.dispatchEvent(
				new CustomEvent('detail:open', { detail: { id: route.ticketId } })
			);
			break;
	}
}

/**
 * Push a new history entry for `target` and apply the matching
 * body class + open event. Skips the push when the URL already
 * represents `target` so re-clicking the same screen doesn't stack
 * identical entries. The per-route `dashboard:refresh` /
 * `pending:open` / `detail:open` events fire from {@link applyRoute}
 * so both the forward (pushState) and back/forward (popstate)
 * paths produce the same effect.
 */
export function navigate(target: Route): void {
	const targetHash = hashFor(target);
	const sameRoute = readHash() === targetHash;
	if (!sameRoute) {
		const url = targetHash === '' ? window.location.pathname + window.location.search : targetHash;
		history.pushState({ route: target }, '', url);
	}
	applyRoute(target);
}

/** Equivalent to the browser's back button. */
export function navigateBack(): void {
	history.back();
}

let installed = false;

/**
 * Idempotent boot. Installs the popstate listener that re-applies
 * the body class on browser back / forward, plus a one-shot sync
 * to the URL hash the page loaded with.
 *
 * Side-effect-only — call once from `host.ts`.
 */
export function setupHistoryBridge(): void {
	if (installed) return;
	installed = true;
	window.addEventListener('popstate', () => {
		applyRoute(parseRoute());
	});
	applyRoute(parseRoute());
}
