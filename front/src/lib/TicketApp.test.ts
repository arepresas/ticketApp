import { describe, expect, it } from 'vitest';
import { mount, unmount } from 'svelte';
import TicketApp from './TicketApp.svelte';

describe('TicketApp', () => {
  it('renders without crashing', () => {
    const target = document.createElement('div');
    document.body.appendChild(target);

    fetchMockOnce([]);
    const comp = mount(TicketApp, { target, props: { apiUrl: '/api/tickets' } });

    expect(target.querySelector('section.ticket-app')).toBeTruthy();
    unmount(comp);
  });

  function fetchMockOnce(_body: unknown) {
    globalThis.fetch = (() =>
      Promise.resolve(new Response('[]', { status: 200 }))) as unknown as typeof fetch;
  }
});
