<script lang="ts">
  /**
   * GoogleLoginButton
   *
   * Two visual modes:
   *  - Logged out → renders the official GIS button (popup flow).
   *  - Logged in  → renders the user's avatar + a "Sign out" menu.
   *
   * The GIS script is loaded once by `index.html`. We wait for
   * `window.google?.accounts?.id` to be available before initializing,
   * which makes the component resilient to script-load timing.
   *
   * Re-renders the GIS button after sign-out without a page refresh.
   * The `{#if auth.isAuthenticated}` branch swaps, the host `<div>` mounts
   * with `bind:this={buttonHost}`, the reactive effect below re-runs,
   * and we call `g.renderButton()` on the new host.
   */
  import { onMount } from 'svelte';
  import { LogOut } from '@lucide/svelte';
  import { auth, initAuth, loginWithGoogle, logout } from './store.svelte';
  import type { GoogleCredentialResponse } from './google';

  type Props = {
    /** Google OAuth client id (from `VITE_GOOGLE_CLIENT_ID`). */
    clientId: string;
  };

  const { clientId }: Props = $props();

  let buttonHost: HTMLDivElement | undefined = $state();
  let menuOpen = $state(false);

  /** Poll for the GIS global without blocking the component init. */
  const waitForGoogle = (): Promise<void> =>
    new Promise((resolve) => {
      if (typeof window !== 'undefined' && window.google?.accounts?.id) {
        resolve();
        return;
      }
      const start = Date.now();
      const tick = (): void => {
        if (window.google?.accounts?.id) {
          resolve();
          return;
        }
        if (Date.now() - start > 5000) {
          resolve();
          return;
        }
        window.setTimeout(tick, 50);
      };
      tick();
    });

  const handleCredential = (resp: GoogleCredentialResponse): void => {
    void loginWithGoogle(resp.credential);
  };

  const renderGisButton = async (): Promise<void> => {
    if (!clientId) return;
    const g = window.google?.accounts?.id;
    const host = buttonHost;
    if (!g || !host) return;
    g.initialize({
      client_id: clientId,
      callback: handleCredential,
      cancel_on_tap_outside: true,
      ux_mode: 'popup'
    });
    g.renderButton(host, {
      type: 'standard',
      theme: 'outline',
      size: 'medium',
      text: 'signin_with',
      shape: 'rectangular'
    });
  };

  onMount(() => {
    let cancelled = false;
    void (async () => {
      await initAuth();
      if (cancelled) return;
      await Promise.resolve();
      if (cancelled) return;
      await waitForGoogle();
      if (cancelled) return;
      await renderGisButton();
    })();
    return () => {
      cancelled = true;
      window.google?.accounts?.id?.disableAutoSelect();
    };
  });

  $effect(() => {
    const signedIn = auth.isAuthenticated;
    const host = buttonHost;
    if (signedIn) return;
    if (!host) return;
    void (async () => {
      await waitForGoogle();
      await renderGisButton();
    })();
  });

  const onSignOut = async (): Promise<void> => {
    menuOpen = false;
    await logout();
  };
</script>

{#if auth.isAuthenticated && auth.user}
  <div class="relative">
    <button
      type="button"
      onclick={() => (menuOpen = !menuOpen)}
      aria-haspopup="menu"
      aria-expanded={menuOpen}
      class="inline-flex items-center gap-2 rounded-md border border-border bg-background px-2 py-1 text-sm font-medium text-foreground transition-colors hover:bg-accent"
    >
      {#if auth.user.picture}
        <img
          src={auth.user.picture}
          alt=""
          class="size-6 rounded-full"
          referrerpolicy="no-referrer"
        />
      {/if}
      <span class="hidden max-w-[10rem] truncate sm:inline">{auth.user.name}</span>
    </button>

    {#if menuOpen}
      <div
        role="menu"
        class="absolute right-0 z-50 mt-1 w-48 overflow-hidden rounded-md border border-border bg-popover text-popover-foreground shadow-md"
      >
        <div class="border-b border-border px-3 py-2 text-xs text-muted-foreground">
          {auth.user.email}
        </div>
        <button
          type="button"
          role="menuitem"
          onclick={onSignOut}
          class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors hover:bg-accent"
        >
          <LogOut class="size-3.5" />
          Sign out
        </button>
      </div>
    {/if}
  </div>
{:else}
  <div bind:this={buttonHost} class="inline-flex items-center" aria-label="Sign in with Google"></div>
{/if}
