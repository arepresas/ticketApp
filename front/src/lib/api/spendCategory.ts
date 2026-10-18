/**
 * The closed set of spending categories, as it travels on the wire.
 *
 * <p>Mirrors the BFF's `SpendCategory` enum by its wire names, not its
 * constants: the column is constrained to these five values, so this is
 * a restatement of a contract rather than a local guess. Anything the
 * backend folds to `other` arrives here as `'other'`.
 *
 * <p>Lives in its own module because three different surfaces need it —
 * the extraction payload, the edit form and the dashboard donut — and
 * they must not drift apart the way two hand-written unions would.
 */
export const SPEND_CATEGORIES = ['food', 'transport', 'lodging', 'pharmacy', 'other'] as const;

export type SpendCategory = (typeof SPEND_CATEGORIES)[number];

/** What a new extraction starts as, and what the user can always pick. */
export const DEFAULT_SPEND_CATEGORY: SpendCategory = 'other';

/**
 * Narrow an unknown value (a wire payload, a legacy row read through
 * an older backend) onto the set.
 *
 * <p>Folds rather than rejects, mirroring what the BFF does: a label
 * it could not classify is still spend, and dropping it would make the
 * donut disagree with the total beside it.
 */
export const asSpendCategory = (value: unknown): SpendCategory =>
  SPEND_CATEGORIES.includes(value as SpendCategory)
    ? (value as SpendCategory)
    : DEFAULT_SPEND_CATEGORY;
