/**
 * Ordering rule for "finish what the user typed, then do the thing that
 * closes the window".
 *
 * <p>Marking a ticket DONE (or CANCELLED) has to persist pending edits
 * first: that action closes the detail view, so anything unsaved at
 * that moment is unreachable rather than merely pending. The Save
 * button and "Mark as done" are peers in the UI and users reasonably
 * expect the second to keep what they just typed in the first.
 *
 * <p>If the flush fails we abort. Flipping the status anyway would
 * leave a ticket marked DONE that still carries the pre-edit data —
 * the half-applied state this exists to prevent. The caller surfaces
 * the error and leaves the user on the ticket.
 *
 * <p>This is a free function rather than an inline two-liner so the
 * ordering can be unit-tested. {@code TicketDetailApp} is a shadow-DOM
 * custom element, so jsdom cannot reach its buttons; the repo has no
 * Playwright harness yet.
 *
 * @param flush persists pending edits; `null` when there is nothing to
 *              flush. Resolves `true` when the edits landed, `false`
 *              when they did not.
 * @param act   the transition to perform. Only runs if `flush` succeeded.
 * @returns `true` when the transition ran, `false` when it was skipped.
 */
export async function flushThenAct(
	flush: (() => Promise<boolean>) | null,
	act: () => Promise<void>
): Promise<boolean> {
	if (flush && !(await flush())) {
		return false;
	}
	await act();
	return true;
}