<script lang="ts">
  let { apiUrl = '/api/tickets' }: { apiUrl?: string } = $props();
  let tickets = $state<Ticket[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);

  type Ticket = {
    id: string;
    title: string;
    description: string;
    status: 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';
    createdAt: string;
    updatedAt: string;
  };

  async function refresh() {
    loading = true;
    error = null;
    try {
      const res = await fetch(apiUrl);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      tickets = await res.json();
    } catch (e) {
      error = e instanceof Error ? e.message : 'unknown error';
    } finally {
      loading = false;
    }
  }

  $effect(() => {
    refresh();
  });
</script>

<section class="ticket-app">
  <header>
    <h2>Tickets</h2>
    <button type="button" onclick={refresh} disabled={loading}>
      {loading ? 'Loading…' : 'Refresh'}
    </button>
  </header>

  {#if error}
    <p role="alert" class="error">Error: {error}</p>
  {/if}

  {#if !loading && tickets.length === 0}
    <p class="empty">No tickets yet.</p>
  {:else}
    <ul>
      {#each tickets as t (t.id)}
        <li>
          <strong>{t.title}</strong>
          <span class="status status-{t.status}">{t.status}</span>
          {#if t.description}<p>{t.description}</p>{/if}
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  :host { display: block; font-family: system-ui, sans-serif; }
  .ticket-app { padding: 1rem; border: 1px solid #ddd; border-radius: 8px; }
  header { display: flex; justify-content: space-between; align-items: center; }
  ul { list-style: none; padding: 0; margin: 0; }
  li { padding: 0.5rem 0; border-bottom: 1px solid #eee; }
  .status { margin-left: 0.5rem; padding: 0.1rem 0.4rem; border-radius: 4px; font-size: 0.75rem; }
  .status-OPEN { background: #ddf; }
  .status-IN_PROGRESS { background: #ffd; }
  .status-DONE { background: #dfd; }
  .status-CANCELLED { background: #fdd; }
  .error { color: #b00; }
  .empty { color: #666; font-style: italic; }
</style>
