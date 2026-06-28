<script lang="ts">
  /**
   * RecentTicketsTable
   *
   * Pure presentational table of the user's most recent tickets.
   * No fetching, no side effects — the parent passes already-loaded data.
   *
   * Columns: Title · Category · Date · Amount · Status.
   * Empty state: single full-width row "No recent tickets.".
   *
   * Date formatting uses `Intl.DateTimeFormat` so the displayed string
   * matches the user's locale. Amount uses `Intl.NumberFormat` with EUR.
   */
  import type { RecentTicket, TicketCategory, TicketStatus } from '../../api/dashboard';
  import { cn } from '../../utils';

  type Props = {
    tickets: RecentTicket[];
  };

  const { tickets }: Props = $props();

  // Locale-aware formatters — constructed once, reused on every render.
  // `undefined` locale follows the runtime default; safe in jsdom + browser.
  const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
  const amountFmt = new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: 'EUR'
  });

  // Category → pill class map. 4 keys, exhaustive over `TicketCategory`.
  const categoryPillClass: Record<TicketCategory, string> = {
    transport:
      'bg-sky-100 text-sky-700 ring-1 ring-inset ring-sky-200 dark:bg-sky-500/15 dark:text-sky-300 dark:ring-sky-500/30',
    food: 'bg-amber-100 text-amber-800 ring-1 ring-inset ring-amber-200 dark:bg-amber-500/15 dark:text-amber-300 dark:ring-amber-500/30',
    lodging:
      'bg-violet-100 text-violet-700 ring-1 ring-inset ring-violet-200 dark:bg-violet-500/15 dark:text-violet-300 dark:ring-violet-500/30',
    other:
      'bg-zinc-100 text-zinc-700 ring-1 ring-inset ring-zinc-200 dark:bg-zinc-500/15 dark:text-zinc-300 dark:ring-zinc-500/30'
  };

  // Status → badge class map. green=closed, amber=open (matches task spec).
  const statusBadgeClass: Record<TicketStatus, string> = {
    open: 'bg-amber-100 text-amber-800 ring-1 ring-inset ring-amber-200 dark:bg-amber-500/15 dark:text-amber-300 dark:ring-amber-500/30',
    closed:
      'bg-emerald-100 text-emerald-700 ring-1 ring-inset ring-emerald-200 dark:bg-emerald-500/15 dark:text-emerald-300 dark:ring-emerald-500/30'
  };

  // Display labels — capitalize category/status for readability.
  const categoryLabel: Record<TicketCategory, string> = {
    transport: 'Transport',
    food: 'Food',
    lodging: 'Lodging',
    other: 'Other'
  };

  // Helpers — wrap Intl formatters and accept the string fields from the API.
  // We trust the API contract here: invalid dates fall back to the raw string
  // so we never render "Invalid Date".
  const formatDate = (iso: string): string => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : dateFmt.format(d);
  };

  const formatAmount = (eur: number): string => amountFmt.format(eur);
</script>

<div class="overflow-x-auto rounded-lg border border-border">
  <table class="w-full text-sm">
    <thead class="border-b border-border bg-muted/50 text-xs uppercase tracking-wider text-muted-foreground">
      <tr>
        <th scope="col" class="px-4 py-3 text-left font-medium">Title</th>
        <th scope="col" class="px-4 py-3 text-left font-medium">Category</th>
        <th scope="col" class="px-4 py-3 text-left font-medium">Date</th>
        <th scope="col" class="px-4 py-3 text-right font-medium">Amount</th>
        <th scope="col" class="px-4 py-3 text-left font-medium">Status</th>
      </tr>
    </thead>
    <tbody class="divide-y divide-border">
      {#if tickets.length === 0}
        <tr>
          <td colspan={5} class="px-4 py-8 text-center text-sm text-muted-foreground">
            No recent tickets.
          </td>
        </tr>
      {:else}
        {#each tickets as t (t.id)}
          <tr class="transition-colors hover:bg-muted/40">
            <td class="px-4 py-3 font-medium text-foreground">{t.title}</td>
            <td class="px-4 py-3">
              <span
                class={cn(
                  'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
                  categoryPillClass[t.category]
                )}
              >
                {categoryLabel[t.category]}
              </span>
            </td>
            <td class="px-4 py-3 whitespace-nowrap text-muted-foreground">
              {formatDate(t.date)}
            </td>
            <td class="px-4 py-3 text-right font-mono tabular-nums text-foreground">
              {formatAmount(t.amountEur)}
            </td>
            <td class="px-4 py-3">
              <span
                class={cn(
                  'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium capitalize',
                  statusBadgeClass[t.status]
                )}
              >
                {t.status}
              </span>
            </td>
          </tr>
        {/each}
      {/if}
    </tbody>
  </table>
</div>