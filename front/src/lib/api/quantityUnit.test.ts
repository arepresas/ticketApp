/**
 * Tests for the SPA half of the `QuantityUnit` enum.
 *
 * <p>The interesting case is the fallback: the BFF folds every label
 * it does not model (`L`, `pack`, a null) into `unit`, so this build
 * has to read those as a piece rather than blanking the editor or
 * leaving a `<select>` with no matching option.
 */
import { describe, expect, it } from 'vitest';

import {
	QUANTITY_UNITS,
	DEFAULT_QUANTITY_UNIT,
	asQuantityUnit
} from './quantityUnit';

describe('quantityUnit', () => {
	it('offers exactly the units the backend stores', () => {
		// A free-text field here would accept a label the BFF folds on
		// save, so the user would see one unit and get another.
		expect([...QUANTITY_UNITS]).toEqual(['kg', 'unit']);
	});

	it('starts a new line as a piece', () => {
		expect(DEFAULT_QUANTITY_UNIT).toBe('unit');
	});

	it('keeps a unit it knows', () => {
		expect(asQuantityUnit('kg')).toBe('kg');
		expect(asQuantityUnit('unit')).toBe('unit');
	});

	it('folds anything else to the default', () => {
		// 'L' and null are what a receipt of the previous schema, or a
		// backend that grew a unit this build does not know, sends.
		expect(asQuantityUnit('L')).toBe('unit');
		expect(asQuantityUnit(null)).toBe('unit');
		expect(asQuantityUnit(undefined)).toBe('unit');
		expect(asQuantityUnit('')).toBe('unit');
		expect(asQuantityUnit(42)).toBe('unit');
	});
});