/**
 * Domain types for the auth layer.
 *
 * Kept dependency-free so they can be imported by both the Svelte
 * components and the API client without dragging GIS / fetch into types.
 */

export type AuthUser = {
  /**
   * Opaque, and deliberately NOT `app_users.id`: on the client this is
   * Google's `sub` from the decoded id_token. Nothing in the SPA keys
   * off it — ownership is enforced by the BFF from the session JWT.
   */
  id: string;
  email: string;
  name: string;
  picture?: string;
};

export type AuthState = {
  user: AuthUser | null;
  status: 'idle' | 'loading' | 'authenticated' | 'error';
  error: string | null;
};

/**
 * Shape returned by the BFF after a successful `POST /api/auth/google`.
 * `token` is the BFF-issued session JWT (NOT the Google id_token).
 */
export type SessionResponse = {
  token: string;
  user: AuthUser;
};