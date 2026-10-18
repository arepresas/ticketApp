import { describe, expect, it, vi } from 'vitest';

import { flushThenAct } from './flushThenAct';

/**
 * Regression cover for data loss: a ticket marked DONE used to discard
 * unsaved edits, because the status change fired while they were still
 * only in component state and then closed the view.
 */
describe('flushThenAct', () => {
  it('runs the transition when there is nothing to flush', async () => {
    const act = vi.fn(async () => {});

    await expect(flushThenAct(null, act)).resolves.toBe(true);

    expect(act).toHaveBeenCalledTimes(1);
  });

  it('flushes before running the transition', async () => {
    // Order matters, so record it rather than asserting call counts.
    const order: string[] = [];
    const flush = vi.fn(async () => {
      order.push('flush');
      return true;
    });
    const act = vi.fn(async () => {
      order.push('act');
    });

    await flushThenAct(flush, act);

    expect(order).toEqual(['flush', 'act']);
  });

  it('flushes and then transitions, awaiting both', async () => {
    const flush = vi.fn(async () => true);
    const act = vi.fn(async () => {});

    await expect(flushThenAct(flush, act)).resolves.toBe(true);

    expect(flush).toHaveBeenCalledTimes(1);
    expect(act).toHaveBeenCalledTimes(1);
  });

  it('skips the transition when the flush reports failure', async () => {
    // The important half: a failed save must not leave a DONE
    // ticket carrying pre-edit data.
    const flush = vi.fn(async () => false);
    const act = vi.fn(async () => {});

    await expect(flushThenAct(flush, act)).resolves.toBe(false);

    expect(act).not.toHaveBeenCalled();
  });

  it('skips the transition when the flush rejects', async () => {
    const act = vi.fn(async () => {});

    await expect(
      flushThenAct(async () => {
        throw new Error('network down');
      }, act)
    ).rejects.toThrow('network down');

    expect(act).not.toHaveBeenCalled();
  });

  it('propagates a failure from the transition itself', async () => {
    await expect(
      flushThenAct(null, async () => {
        throw new Error('status 409');
      })
    ).rejects.toThrow('status 409');
  });

  it('waits for the flush to resolve before starting the transition', async () => {
    // Deferred rather than a timer: the ordering assertion is
    // exact, so there is nothing for a sleep to paper over. This
    // is the "mark as done keeps what the user typed" case — the
    // status PATCH must not be in flight while the edits are
    // still unsaved.
    let releaseFlush!: (ok: boolean) => void;
    const flushSettled = new Promise<boolean>((resolve) => {
      releaseFlush = resolve;
    });
    const order: string[] = [];
    const act = vi.fn(async () => {
      order.push('status');
    });

    const running = flushThenAct(async () => {
      order.push('save');
      return flushSettled;
    }, act);

    await Promise.resolve();
    expect(order).toEqual(['save']);
    expect(act).not.toHaveBeenCalled();

    releaseFlush(true);
    await expect(running).resolves.toBe(true);
    expect(order).toEqual(['save', 'status']);
  });

  it('leaves the transition untouched when the flush settles false', async () => {
    // The half-applied state this exists to prevent: a DONE ticket
    // still carrying the pre-edit data.
    let releaseFlush!: (ok: boolean) => void;
    const flushSettled = new Promise<boolean>((resolve) => {
      releaseFlush = resolve;
    });
    const act = vi.fn(async () => undefined);

    const running = flushThenAct(() => flushSettled, act);
    releaseFlush(false);

    await expect(running).resolves.toBe(false);
    expect(act).not.toHaveBeenCalled();
  });

  it('reports success when the transition fails after a successful flush', async () => {
    // The edits are durable at that point; only the transition
    // failed, so the caller shows the transition's error while the
    // save stays done and the view stays open.
    const flush = vi.fn(async () => true);
    const act = vi.fn(async () => {
      throw new Error('status 409');
    });

    await expect(flushThenAct(flush, act)).rejects.toThrow('status 409');

    expect(flush).toHaveBeenCalledTimes(1);
    expect(act).toHaveBeenCalledTimes(1);
  });
});
