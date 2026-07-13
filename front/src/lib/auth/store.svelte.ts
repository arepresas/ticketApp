/**
 * Auth store — Svelte 5 runes (`$state` + module-scoped singleton).
 *
 * Exposes a single reactive `auth` object. Components read `auth.user`
 * directly; mutating actions (`loginWithGoogle`, `logout`) are async
 * methods on the same module.
 *
 * Token persistence:
 * - sessionStorage (NOT localStorage): clears when the tab closes,
 *   so a stale BFF JWT cannot be replayed days later.
 */
import { exchangeIdToken, fetchMe, logout as apiLogout } from './api';
import type { AuthState, AuthUser } from './types';

const TOKEN_KEY = 'ticketapp.session';

/**
 * Read `sessionStorage` from whatever host is running us — browser,
 * jsdom, happy-dom, or Node. `globalThis` is the only universal anchor;
 * `window` is not a valid identifier outside browser globals.
 */
const sessionStorage = (): Storage | null => {
	const g = (globalThis as { window?: { sessionStorage?: Storage } }).window;
	return g?.sessionStorage ?? null;
};

const readToken = (): string | null => {
  const s = sessionStorage();
  if (!s) return null;
  try {
    return s.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
};

const writeToken = (token: string | null): void => {
  const s = sessionStorage();
  if (!s) return;
  try {
    if (token === null) s.removeItem(TOKEN_KEY);
    else s.setItem(TOKEN_KEY, token);
  } catch {
    /* sessionStorage may be disabled — auth still works for the tab */
  }
};

const state: AuthState = $state({
  user: null,
  status: 'idle',
  error: null
});

/** Read-only view consumed by components. */
export const auth = {
  get user(): AuthUser | null {
    return state.user;
  },
  get status(): AuthState['status'] {
    return state.status;
  },
  get error(): string | null {
    return state.error;
  },
  get isAuthenticated(): boolean {
    return state.user !== null;
  }
};

/**
 * Bootstraps the store from any persisted token. Safe to call multiple
 * times — concurrent calls collapse on the in-flight promise.
 */
let bootstrapPromise: Promise<void> | null = null;
export const initAuth = (): Promise<void> => {
  bootstrapPromise ??= doInit();
  return bootstrapPromise;
};

const doInit = async (): Promise<void> => {
  if (!sessionStorage()) return;
  const token = readToken();
  if (!token) return;
  state.status = 'loading';
  try {
    state.user = await fetchMe(token);
    state.status = 'authenticated';
  } catch {
    // Token couldn't be validated (likely 401). Drop it and let
    // the user re-authenticate. The auth listener in host.ts will
    // pick up isAuthenticated === false and hide the dashboard
    // overlays via the CSS gate.
    clearSession();
  }
};

/**
 * Drop the local session without round-tripping the BFF. Used when
 * an API client observes a 401 response — the token is already
 * invalid on the server, so calling `/api/auth/logout` would just
 * bounce back 401. The navigation half of "where does the user go?"
 * lives in `host.ts` next to the rest of the auth listener wiring,
 * so the API client can stay side-effect-free w.r.t. routing.
 *
 * Idempotent — calling twice is a no-op. Safe to call from any
 * auth-aware code path.
 */
export const clearSession = (): void => {
  writeToken(null);
  state.user = null;
  state.status = 'idle';
  state.error = null;
};

/**
 * Exchange a Google `id_token` for a BFF session, then persist + refresh.
 * Call this from the GIS callback.
 */
export const loginWithGoogle = async (idToken: string): Promise<void> => {
  state.status = 'loading';
  state.error = null;
  try {
    const session = await exchangeIdToken(idToken);
    writeToken(session.token);
    state.user = session.user;
    state.status = 'authenticated';
  } catch (e) {
    state.error = e instanceof Error ? e.message : 'unknown error';
    state.user = null;
    state.status = 'error';
  }
};

/** Sign out and clear local + server session. */
export const logout = async (): Promise<void> => {
  const token = readToken();
  if (token) {
    try {
      await apiLogout(token);
    } catch {
      /* ignore — we always clear locally */
    }
  }
  writeToken(null);
  state.user = null;
  state.status = 'idle';
  state.error = null;
};

// Dev-only debug surface. Exposes the auth store on `window.__auth` so the
// browser console (or Playwright) can flip state without a real Google
// round-trip. Tree-shaken in production by Vite's `import.meta.env.DEV`
// dead-code elimination — the assignment is replaced by an empty statement
// when the build runs with `mode !== 'development'`.
if (import.meta.env.DEV) {
  (globalThis as unknown as { __auth?: typeof auth }).__auth = auth;
  (
    globalThis as unknown as {
      __loginAs?: (u: { id: string; email: string; name: string; picture?: string }) => void;
    }
  ).__loginAs = (u) => {
    state.user = u;
    state.status = 'authenticated';
    state.error = null;
  };
  (
    globalThis as unknown as { __logout?: () => void }
  ).__logout = () => {
    state.user = null;
    state.status = 'idle';
    state.error = null;
  };
}