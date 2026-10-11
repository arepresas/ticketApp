/**
 * Monotonic guard for the dashboard's load sequence.
 *
 * <p>Two failure modes motivate it, both cross-account data leaks:
 *
 * <ul>
 *   <li>A slow response for user A resolving after a logout and login as
 *       user B. The generation check discards it.</li>
 *   <li>A slow response that eventually answers 401 expiring the *new*
 *       session. Aborting the obsolete request removes the failure
 *       entirely — there is no response to bubble.</li>
 * </ul>
 *
 * <p><strong>Ordering is load-bearing:</strong> {@link LoadGuard.begin}
 * must be the first statement of every load, *before* the token lookup and
 * any early return. A load that finds no token still invalidates the
 * in-flight one; if it returned first, that request would still be
 * "current" and could repaint the previous user's totals over the signed-out
 * state.
 */
export type LoadHandle = {
	/** Monotonic id. Only the newest handle may commit a result. */
	generation: number;
	/** Signal wired into the request this handle represents. */
	signal: AbortSignal;
};

export type LoadGuard = {
	/**
	 * Start a load: invalidate every earlier one and abort its request.
	 *
	 * @returns the handle for the load that is now current.
	 */
	begin(): LoadHandle;
	/** True while `generation` is still the newest load. */
	isCurrent(generation: number): boolean;
	/**
	 * Cancel whatever is in flight and invalidate its handle.
	 *
	 * <p>Used on teardown, so an unmount mid-flight cannot write into a
	 * component that no longer exists.
	 */
	abort(): void;
};

export const createLoadGuard = (): LoadGuard => {
	let newest = 0;
	let inFlight: AbortController | null = null;

	return {
		begin(): LoadHandle {
			inFlight?.abort();
			inFlight = new AbortController();
			return { generation: ++newest, signal: inFlight.signal };
		},
		isCurrent: (generation: number): boolean => generation === newest,
		abort(): void {
			inFlight?.abort();
			inFlight = null;
			// Bump so a late resolution from the aborted request is stale
			// rather than current-but-ignored.
			newest++;
		}
	};
};