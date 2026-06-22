/**
 * HTTP client for the BFF auth endpoints.
 *
 * Pure functions — no shared state. The store (auth.svelte.ts) owns
 * the session token and passes it in; this module never reads it.
 */
import type { SessionResponse, AuthUser } from './types';

const API_BASE = '/api/auth';

export class AuthApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'AuthApiError';
  }
}

const parseError = async (res: Response): Promise<string> => {
  try {
    const body = (await res.json()) as { message?: string; error?: string };
    return body.message ?? body.error ?? `HTTP ${res.status}`;
  } catch {
    return `HTTP ${res.status}`;
  }
};

/**
 * Exchange a Google `id_token` for a BFF-issued session JWT.
 * The BFF has already verified the Google signature + audience.
 */
export const exchangeIdToken = async (idToken: string): Promise<SessionResponse> => {
  const res = await fetch(`${API_BASE}/google`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ idToken })
  });
  if (!res.ok) {
    throw new AuthApiError(await parseError(res), res.status);
  }
  return (await res.json()) as SessionResponse;
};

/** Fetch the user attached to the current session token. */
export const fetchMe = async (token: string): Promise<AuthUser> => {
  const res = await fetch(`${API_BASE}/me`, {
    headers: { authorization: `Bearer ${token}` }
  });
  if (!res.ok) {
    throw new AuthApiError(await parseError(res), res.status);
  }
  return (await res.json()) as AuthUser;
};

/** Invalidate the session server-side (best-effort) and discard locally. */
export const logout = async (token: string): Promise<void> => {
  await fetch(`${API_BASE}/logout`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` }
  });
  // 401/403 here is acceptable — we always clear local state regardless.
};