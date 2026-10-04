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
			flushThenAct(
				async () => {
					throw new Error('network down');
				},
				act
			)
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
});