/**
 * The unit a product line's quantity is counted in — the SPA half of
 * the BFF's `QuantityUnit` enum.
 *
 * <p>Mirrors the wire names (`'kg' | 'unit'`), not the enum constants,
 * because that is what travels in JSON. A receipt line whose unit the
 * catalogue does not model reads as `unit`: the BFF folds `L`,
 * `pack` and an absent unit into it rather than rejecting the edit.
 *
 * <p>The list is short and grows with the catalogue, so the editor
 * offers exactly these two rather than a free-text field: a unit the
 * backend cannot store would silently change meaning on the next
 * normalisation.
 */
export const QUANTITY_UNITS = ['kg', 'unit'] as const;

export type QuantityUnit = (typeof QUANTITY_UNITS)[number];

/** What a new line starts as — a piece, the common case. */
export const DEFAULT_QUANTITY_UNIT: QuantityUnit = 'unit';

/**
 * Narrow an unknown value (a wire payload, a stored edit) to a unit.
 *
 * <p>Falls back to {@link DEFAULT_QUANTITY_UNIT} for anything the
 * backend can send but this build does not know — the same fold the
 * BFF applies, so a value added server-side reads as a piece here
 * rather than blanking the editor.
 */
export const asQuantityUnit = (value: unknown): QuantityUnit =>
  QUANTITY_UNITS.includes(value as QuantityUnit) ? (value as QuantityUnit) : DEFAULT_QUANTITY_UNIT;
