/**
 * Domain types for the auth layer.
 *
 * Kept dependency-free so they can be imported by both the Svelte
 * components and the API client without dragging GIS / fetch into types.
 */

export type AuthUser = {
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