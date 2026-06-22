import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor, fireEvent } from '@testing-library/svelte';
import GoogleLoginButton from './GoogleLoginButton.svelte';
import { auth, logout as storeLogout } from './store.svelte';
import type { GoogleAccountsId } from './google';

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
});