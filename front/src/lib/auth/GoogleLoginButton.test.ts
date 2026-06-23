import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor, fireEvent, act } from '@testing-library/svelte';
import GoogleLoginButton from './GoogleLoginButton.svelte';
import { auth, logout as storeLogout, loginWithGoogle } from './store.svelte';
import type { GoogleAccountsId } from './google';
import type { AuthUser } from './types';

const renderGoogleMock = vi.fn();
const initializeGoogleMock = vi.fn();
const disableAutoSelectMock = vi.fn();

const installGoogleMock = (): void => {
  const idMock: Pick<GoogleAccountsId, 'initialize' | 'renderButton' | 'disableAutoSelect'> = {
    initialize: initializeGoogleMock,
    renderButton: renderGoogleMock,
    disableAutoSelect: disableAutoSelectMock
  };
  (window as unknown as { google: { accounts: { id: typeof idMock } } }).google = {
    accounts: { id: idMock as unknown as GoogleAccountsId }
  };
};

describe('GoogleLoginButton', () => {
  beforeEach(() => {
    renderGoogleMock.mockClear();
    initializeGoogleMock.mockClear();
    disableAutoSelectMock.mockClear();
    installGoogleMock();
    // Always start each test logged out + clear storage.
    window.sessionStorage.clear();
    // mock the logout endpoint so the store can call it without throwing
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 200 }));
  });

  afterEach(async () => {
    await storeLogout();
    vi.restoreAllMocks();
  });

  it('renders a host element when logged out', async () => {
    const { container } = render(GoogleLoginButton, { clientId: 'test-client' });
    await waitFor(() => {
      expect(initializeGoogleMock).toHaveBeenCalledWith(
        expect.objectContaining({ client_id: 'test-client' })
      );
    });
    expect(container.querySelector('[aria-label="Sign in with Google"]')).toBeTruthy();
  });

  it('does not crash when GIS is not loaded', () => {
    delete (window as { google?: unknown }).google;
    const { container } = render(GoogleLoginButton, { clientId: 'test-client' });
    // The button host still renders; the GIS layer simply skips init.
    expect(container.querySelector('[aria-label="Sign in with Google"]')).toBeTruthy();
  });

  it('does not initialise GIS when no clientId is provided', async () => {
    render(GoogleLoginButton, { clientId: '' });
    await waitFor(() => {
      expect(initializeGoogleMock).not.toHaveBeenCalled();
    });
  });

  it('exposes auth state via the store', () => {
    expect(auth.isAuthenticated).toBe(false);
    expect(auth.user).toBeNull();
  });

  it('re-renders the GIS button after the user signs out (no full page refresh)', async () => {
    render(GoogleLoginButton, { clientId: 'test-client' });
    // Wait for the initial mount to settle (onMount + first effect run).
    await waitFor(() => {
      expect(initializeGoogleMock).toHaveBeenCalled();
    });

    // Seed the store with a logged-in user. The store action does an async
    // fetch which we mock; the test only needs the resulting `state.user`
    // mutation to flip auth.isAuthenticated to true.
    const fakeUser: AuthUser = {
      id: 'google-sub-1',
      email: 'alice@example.com',
      name: 'Alice',
      picture: 'https://example.com/a.png'
    };
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ token: 'fake-bff-jwt', user: fakeUser }), {
        status: 200,
        headers: { 'content-type': 'application/json' }
      })
    );
    await act(async () => {
      await loginWithGoogle('any.google.id.token');
    });
    expect(auth.isAuthenticated).toBe(true);

    // Clear the GIS mocks so we can count re-init calls.
    renderGoogleMock.mockClear();
    initializeGoogleMock.mockClear();

    // Trigger logout through the same code path the avatar menu uses.
    await act(async () => {
      await storeLogout();
    });
    expect(auth.isAuthenticated).toBe(false);

    // After auth flips back to logged-out, the host div mounts and the
    // reactive effect should call renderButton() again — without a full
    // page refresh. The effect's renderGisButton is fire-and-forget, so
    // waitFor is the right polling primitive.
    await waitFor(
      () => {
        expect(renderGoogleMock).toHaveBeenCalled();
      },
      { timeout: 1000 }
    );
  });
});