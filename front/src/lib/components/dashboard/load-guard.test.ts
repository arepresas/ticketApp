/**
 * Tests for the dashboard load guard.
 *
 * Pure logic — no rendering, no network. What matters is the ordering
 * contract documented on the module: a load that never issues a request
 * (no token) still has to invalidate the one in flight, and an obsolete
 * request has to be cancelled rather than left to answer 401 later.
 */
import { describe, expect, it } from 'vitest';

import { createLoadGuard } from './load-guard';

describe('createLoadGuard', () => {
  it('hands out strictly increasing generations', () => {
    const guard = createLoadGuard();

    const first = guard.begin();
    const second = guard.begin();

    expect(second.generation).toBeGreaterThan(first.generation);
  });

  it('treats only the newest generation as current', () => {
    const guard = createLoadGuard();

    const first = guard.begin();
    expect(guard.isCurrent(first.generation)).toBe(true);

    const second = guard.begin();
    expect(guard.isCurrent(first.generation)).toBe(false);
    expect(guard.isCurrent(second.generation)).toBe(true);
  });

  // The regression this module exists for: a load that finds no token
  // returns early without fetching anything, and used to leave the
  // previous request "current" — so a late response repainted the
  // previous user's totals over the signed-out state.
  it('a load that issues no request still invalidates the one in flight', () => {
    const guard = createLoadGuard();

    const inFlight = guard.begin();
    // `begin()` first, then the caller discovers there is no token and
    // returns without ever awaiting anything.
    const signedOut = guard.begin();

    expect(guard.isCurrent(inFlight.generation)).toBe(false);
    expect(guard.isCurrent(signedOut.generation)).toBe(true);
  });

  it('aborts the superseded request so its 401 can never expire a new session', () => {
    const guard = createLoadGuard();

    const first = guard.begin();
    expect(first.signal.aborted).toBe(false);

    guard.begin();

    expect(first.signal.aborted).toBe(true);
  });

  it('aborts cancels the in-flight request and invalidates its handle', () => {
    const guard = createLoadGuard();

    const handle = guard.begin();
    guard.abort();

    expect(handle.signal.aborted).toBe(true);
    expect(guard.isCurrent(handle.generation)).toBe(false);
  });

  it('does not abort the request it just started', () => {
    const guard = createLoadGuard();

    const handle = guard.begin();

    expect(handle.signal.aborted).toBe(false);
  });
});
