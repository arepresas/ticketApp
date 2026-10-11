/**
 * Tests for the SPA half of the `SpendCategory` enum.
 *
 * <p>The set is a restatement of a database constraint, so the two
 * halves of it are worth pinning separately: the list the editor offers
 * (which must match the column) and the fold applied to anything else
 * (which must never be a rejection — a receipt the backend could not
 * classify is still spend).
 */
import { describe, expect, it } from 'vitest';

import {
	SPEND_CATEGORIES,
	DEFAULT_SPEND_CATEGORY,
	asSpendCategory
} from './spendCategory';

describe('spendCategory', () => {
	it('offers exactly the five members the column accepts', () => {
		// Drift here means the editor offers a value the constrained
		// column rejects, or hides one the backend can send.
		expect([...SPEND_CATEGORIES]).toEqual([
			'food',
			'transport',
			'lodging',
			'pharmacy',
			'other'
		]);
	});

	it('falls back to other for anything outside the set', () => {
		expect(asSpendCategory('fuel')).toBe('other');
		expect(asSpendCategory(null)).toBe('other');
		expect(asSpendCategory(undefined)).toBe('other');
		expect(asSpendCategory(7)).toBe('other');
	});

	it('keeps a member it knows', () => {
		for (const category of SPEND_CATEGORIES) {
			expect(asSpendCategory(category)).toBe(category);
		}
	});

	it('defaults an unclassified extraction to other', () => {
		expect(DEFAULT_SPEND_CATEGORY).toBe('other');
		expect(asSpendCategory('fuel')).toBe(DEFAULT_SPEND_CATEGORY);
	});
});