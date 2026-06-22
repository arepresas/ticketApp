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

const sessionStorage = (): Storage | null => {
  // globalThis keeps this working in jsdom, happy-dom, and node — we
  // never reach for `window` directly because it's not a valid identifier
  // outside browser globals (S7764).
  const g: { window?: { sessionStorage?: Storage } } | undefined =
    (globalThis as unknown as { window?: { sessionStorage?: Storage } }).window;
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
    writeToken(null);
    state.user = null;
    state.status = 'idle';
  }
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