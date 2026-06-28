import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/svelte';
import RecentTicketsTable from './RecentTicketsTable.svelte';
import type { RecentTicket } from '../../api/dashboard';

const sample: RecentTicket[] = [
  {
    id: 't-001',
    title: 'Mercadona weekly',
    category: 'food',
    date: '2026-03-12',
    amountEur: 25.28,
    status: 'closed'
  }
];

describe('RecentTicketsTable', () => {
  it('renders the expected column headers', () => {
    const { container } = render(RecentTicketsTable, { tickets: sample });
    const headers = Array.from(container.querySelectorAll('thead th')).map(
      (th) => th.textContent?.trim() ?? ''
    );
    expect(headers).toEqual(['Title', 'Category', 'Date', 'Amount', 'Status']);
  });

  it('renders one row per ticket with formatted amount', () => {
    const { container } = render(RecentTicketsTable, { tickets: sample });
    const rows = container.querySelectorAll('tbody tr');
    expect(rows).toHaveLength(1);
    expect(rows[0]?.textContent).toContain('Mercadona weekly');
    // Intl.NumberFormat with EUR renders either "€25.28" or "25,28 €" depending
    // on runtime locale — assert the digits + currency symbol instead of the
    // exact layout.
    expect(rows[0]?.textContent).toMatch(/25[.,]28/);
    expect(rows[0]?.textContent).toContain('€');
  });

  it('renders the empty state when there are no tickets', () => {
    const { container } = render(RecentTicketsTable, { tickets: [] });
    const row = container.querySelector('tbody tr');
    expect(row?.textContent?.trim()).toBe('No recent tickets.');
    // svelte applies `colspan` as the `colSpan` DOM property (HTML camelCase)
    // rather than the lowercase attribute in some paths — read the property
    // to stay implementation-agnostic. Either path yields 5 here.
    const cell = row?.querySelector('td');
    expect(cell?.colSpan).toBe(5);
  });
});