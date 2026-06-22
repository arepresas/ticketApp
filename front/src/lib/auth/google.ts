/**
 * Typed contract for the `google.accounts.id` global injected by the
 * Google Identity Services script. Centralised so tests can mock a single
 * object instead of monkey-patching the global each time.
 */
import type { AuthUser } from './types';

export type GoogleCredentialResponse = {
  credential: string; // JWT (id_token)
  select_by?: string;
};

export type GoogleAccountsId = {
  initialize: (config: {
    client_id: string;
    callback: (response: GoogleCredentialResponse) => void;
    auto_select?: boolean;
    cancel_on_tap_outside?: boolean;
    ux_mode?: 'popup' | 'redirect';
  }) => void;
  renderButton: (
    parent: HTMLElement,
    options: {
      type?: 'standard' | 'icon';
      theme?: 'outline' | 'filled_blue' | 'filled_black';
      size?: 'large' | 'medium' | 'small';
      text?: 'signin_with' | 'signup_with' | 'continue_with' | 'signin';
      shape?: 'rectangular' | 'pill' | 'circle' | 'square';
      width?: number;
    }
  ) => void;
  prompt: () => void;
  disableAutoSelect: () => void;
};

declare global {
  interface Window {
    google?: {
      accounts: {
        id: GoogleAccountsId;
      };
    };
  }
}

/** Payload extracted from a Google id_token (verified server-side). */
export type GoogleIdTokenPayload = {
  sub: string;
  email: string;
  email_verified: boolean;
  name: string;
  picture?: string;
  aud: string;
  iss: string;
  exp: number;
};

/** Map a verified Google payload to our domain user. */
export const toAuthUser = (p: GoogleIdTokenPayload): AuthUser => ({
  id: p.sub,
  email: p.email,
  name: p.name,
  picture: p.picture
});