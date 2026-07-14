<svelte:options customElement="new-ticket-app" />

<script lang="ts">
	/**
	 * NewTicketApp — custom element shell for the "New ticket" upload screen.
	 *
	 * Why a custom element (not a plain Svelte component)?
	 * The host page (index.html) mounts this as `<new-ticket-app>`. Shadow
	 * DOM isolates Tailwind utilities inside this subtree — we inject the
	 * compiled stylesheet via {@html} so utility classes reach the DOM.
	 * Same pattern as DashboardApp.svelte / LandingApp.svelte.
	 *
	 * Visibility:
	 * Driven by the `is-new` body class toggled from the Header link
	 * (see index.html CSS gate). This element always mounts once but is
	 * `display: none` until the class flips. Re-renders on auth flip
	 * (refuses to render when signed-out — backend is auth-gated).
	 *
	 * Multi-file upload UX (since 2026-07):
	 *  - Drag-and-drop zone + click-to-upload fallback (hidden <input>).
	 *  - Accepts several files in one batch (drop, paste, or browse);
	 *    the user can keep adding files until they hit submit. No
	 *    hard cap on the front — the per-file 10 MB size limit plus
	 *    the BFF's per-request memory budget are the effective caps.
	 *  - Per-file validation: every entry is either accepted (with
	 *    buffered bytes) or rejected (with a reason). Rejected files
	 *    still show in the list as a row with a red error chip so the
	 *    user can see which one was skipped and why.
	 *  - Per-row remove (X) button. Cancel goes back to the dashboard
	 *    via history.back() without clearing the queue.
	 *
	 * Submit:
	 * POSTs each accepted file to the BFF sequentially — one
	 * {@code createTicket} call per file (the user asked for a
	 * front-only change; the BFF keeps its single-file contract).
	 * The per-row status flips idle → uploading → done / error as
	 * the loop progresses, so the user sees a live progress strip
	 * even though the underlying requests are serial. On full
	 * success the screen exits; on partial failure it stays so the
	 * user can re-submit or remove the failed entries.
	 */
	import { onDestroy } from 'svelte';
	import { Upload, FileText, X, CheckCircle2, Loader2, Image as ImageIcon } from '@lucide/svelte';

	import tailwindCss from '../../app.css?inline';
	import { auth } from '../auth/store.svelte';
	import { createTicket, TicketApiError } from '../api/tickets';
	import { humanSize, MAX_BYTES, validateFiles } from './validation';
	import { navigateBack } from '../navigation';

	const SESSION_STORAGE_KEY = 'ticketapp.session';

	function readSessionToken(): string | null {
		const g = globalThis as { window?: { sessionStorage?: Storage } };
		const s = g.window?.sessionStorage;
		if (!s) return null;
		try {
			return s.getItem(SESSION_STORAGE_KEY);
		} catch {
			return null;
		}
	}

	/**
	 * One row in the file list. `bytes` is only populated for
	 * accepted rows — rejected rows keep the original `File` so the
	 * row can still display the name and size, and the entry
	 * carries a human-readable `error` instead.
	 */
	type Preview = {
		id: string;
		name: string;
		size: number;
		type: string;
		kind: 'image' | 'pdf';
		// Image previews need a blob URL so the <img> can render the
		// thumbnail; PDFs don't (just an icon). Kept on the entry so
		// the per-row remove handler knows what to revoke.
		url: string | null;
		// Buffer is empty for rejected rows.
		bytes: Uint8Array;
		// 'idle' = accepted, waiting to upload. 'uploading' = in-flight
		// POST. 'done' = ticket created. 'error' = local rejection
		// OR failed POST. The submit button is disabled while any
		// row is 'uploading'.
		status: 'idle' | 'uploading' | 'done' | 'error';
		// Server-assigned id, populated on success.
		createdId?: string;
		// Local-rejection reason or upload error message.
		error?: string;
	};

	let dragOver = $state(false);
	let fileInput: HTMLInputElement = $state(null!);
	let previews = $state<Preview[]>([]);
	// Aggregate-level error (session-expired, etc.) — separate from
	// the per-row errors that live on each Preview entry.
	let aggregateError = $state<string | null>(null);
	let submitting = $state(false);
	let toastVisible = $state(false);
	let toastMessage = $state('Ticket created');

	// Derived flags the submit button + label depend on. The submit
	// button is disabled while a row is mid-upload OR the list is
	// empty OR the only entries are pre-rejected (so the user must
	// either remove them or replace them with valid files first).
	const anyUploading = $derived(previews.some((p) => p.status === 'uploading'));
	const anyUploadable = $derived(previews.some((p) => p.status === 'idle'));
	const submitDisabled = $derived(
		previews.length === 0 || submitting || anyUploading || !anyUploadable
	);
	const submitLabel = $derived.by((): string => {
		if (submitting) return 'Sending…';
		const pending = previews.filter((p) => p.status === 'idle').length;
		if (pending === 0) return 'Create ticket';
		if (pending === 1) return 'Create ticket';
		return `Create ${pending} tickets`;
	});

	function revokeUrl(p: Preview): void {
		if (p.kind === 'image' && p.url) {
			URL.revokeObjectURL(p.url);
		}
	}

	function clearOne(id: string): void {
		const idx = previews.findIndex((p) => p.id === id);
		if (idx < 0) return;
		revokeUrl(previews[idx]);
		previews = previews.filter((p) => p.id !== id);
		aggregateError = null;
	}

	function clearAll(): void {
		for (const p of previews) revokeUrl(p);
		previews = [];
		aggregateError = null;
	}

	// Belt-and-braces: revoke any stragglers if the component is
	// torn down before the user has submitted. Mirrors the
	// single-file revoke path in the previous revision.
	onDestroy(() => {
		for (const p of previews) revokeUrl(p);
	});

	/**
	 * Add every file in the input to the list. Accepted rows go in
	 * with status='idle'; rejected rows go in too, with status='error'
	 * and a human-readable reason — the user sees the offending
	 * filename in the list and can remove it before submitting.
	 *
	 * The async work (file.arrayBuffer()) is fire-and-forget at the
	 * call site so the click / drop handler doesn't block on disk
	 * I/O. Multiple consecutive selections (drop → drop → drop)
	 * each trigger their own pass; the rows accumulate.
	 */
	async function ingestFiles(files: FileList | null | undefined): Promise<void> {
		if (!files || files.length === 0) return;
		const list = Array.from(files);
		const decisions = await validateFiles(list);
		const additions: Preview[] = decisions.map((d) => {
			const id = (globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`)
				.toString();
			if (d.status === 'accepted') {
				const isImage = d.file.type.startsWith('image/');
				return {
					id,
					name: d.file.name,
					size: d.file.size,
					type: d.file.type,
					kind: isImage ? 'image' : 'pdf',
					url: isImage ? URL.createObjectURL(d.file) : null,
					bytes: d.bytes,
					status: 'idle'
				};
			}
			// Rejected: still a row, but with a red error chip and no
			// bytes. `bytes` stays empty so the submit loop skips it.
			return {
				id,
				name: d.file.name,
				size: d.file.size,
				type: d.file.type,
				kind: 'pdf',
				url: null,
				bytes: new Uint8Array(0),
				status: 'error',
				error: d.reason
			};
		});
		previews = [...previews, ...additions];
		aggregateError = null;
	}

	function onFiles(files: FileList | null | undefined): void {
		void ingestFiles(files);
	}

	function onDrop(e: DragEvent): void {
		e.preventDefault();
		dragOver = false;
		onFiles(e.dataTransfer?.files);
	}

	function onDragOver(e: DragEvent): void {
		e.preventDefault();
		dragOver = true;
	}

	function onDragLeave(): void {
		dragOver = false;
	}

	function onPickClick(): void {
		// Clear the input value before re-clicking so picking the
		// same file twice in a row still triggers an onchange event
		// (the browser short-circuits otherwise, which would leave a
		// user unable to retry a failed upload by re-selecting the
		// same file).
		if (fileInput) fileInput.value = '';
		fileInput?.click();
	}

	/**
	 * Leave the upload screen. Goes through the browser's history so
	 * the back / forward buttons stay consistent — re-entering the
	 * screen via the header link pushes a new entry. The popstate
	 * listener in `lib/navigation.ts` removes the `is-new` body
	 * class when the URL is no longer `#new`.
	 */
	function exit(): void {
		navigateBack();
	}

	async function onSubmit(e: SubmitEvent): Promise<void> {
		e.preventDefault();
		if (submitDisabled) return;

		const token = readSessionToken();
		if (!token) {
			aggregateError = 'Session expired. Sign in again to upload a ticket.';
			return;
		}

		submitting = true;
		aggregateError = null;
		// Snapshot the entries to upload so a concurrent ingest
		// (user drops a new file mid-upload) doesn't shift the
		// list while we're iterating. Only rows that are 'idle'
		// and have buffered bytes are eligible.
		const targets = previews.filter((p) => p.status === 'idle' && p.bytes.byteLength > 0);
		for (const target of targets) {
			target.status = 'uploading';
			try {
				// Copy the bytes into a fresh ArrayBuffer so the File
				// constructor accepts them — Uint8Array.buffer is
				// typed as ArrayBufferLike which doesn't satisfy
				// File's BlobPart constraint in TS 5.
				const ab = new ArrayBuffer(target.bytes.byteLength);
				new Uint8Array(ab).set(target.bytes);
				const file = new File([ab], target.name, { type: target.type });
				const created = await createTicket(token, file);
				target.status = 'done';
				target.createdId = created.id;
			} catch (err: unknown) {
				target.status = 'error';
				target.error =
					err instanceof TicketApiError
						? `Upload failed (${err.status}): ${err.message}`
						: `Upload failed: ${err instanceof Error ? err.message : 'unknown error'}`;
			}
		}

		const total = targets.length;
		const ok = targets.filter((p) => p.status === 'done').length;
		if (total === 0) {
			// Nothing eligible (only rejected rows were in the list).
			submitting = false;
			return;
		}
		if (ok === total) {
			toastMessage = total === 1 ? 'Ticket created' : `${total} tickets created`;
		} else {
			toastMessage = `${ok} of ${total} tickets created`;
		}
		toastVisible = true;
		setTimeout(() => {
			toastVisible = false;
			submitting = false;
			if (ok === total) {
				// Full success — drop the list and bounce to the
				// dashboard. The dashboard's tickets list re-fetches
				// via the popstate → applyRoute → dashboard:refresh
				// path that's now wired.
				clearAll();
				exit();
			} else {
				// Partial failure — stay on the screen so the user
				// can remove the failed rows or replace them. The
				// 'done' rows are harmless leftovers (already
				// persisted server-side); the user can dismiss them
				// individually.
			}
		}, 2000);
	}
</script>

<svelte:element this={'style'}>{@html tailwindCss}</svelte:element>

{#if auth.isAuthenticated}
	<div
		class="min-h-screen bg-background text-foreground font-sans antialiased"
		data-testid="new-ticket-screen"
	>
		<main class="mx-auto flex w-full max-w-2xl flex-col gap-6 px-4 py-10 sm:px-6">
			<header class="flex items-start justify-between gap-4">
				<div>
					<h1 class="text-2xl font-bold tracking-tight sm:text-3xl">New ticket</h1>
					<p class="mt-1 text-sm text-muted-foreground">
						Upload one or more receipts — photos or PDFs. Each file becomes a separate
						ticket.
					</p>
				</div>
			</header>

			<form onsubmit={onSubmit} class="flex flex-col gap-4" novalidate>
				<button
					type="button"
					onclick={onPickClick}
					ondrop={onDrop}
					ondragover={onDragOver}
					ondragleave={onDragLeave}
					class={[
						'flex min-h-56 w-full flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-6 text-center transition-colors',
						dragOver
							? 'border-primary bg-primary/5 text-foreground'
							: 'border-border bg-muted/30 text-muted-foreground hover:bg-muted/50'
					].join(' ')}
					data-testid="dropzone"
					aria-label="Drop files here or click to browse"
				>
					<Upload class="size-8" aria-hidden="true" />
					<p class="text-sm font-medium">
						{previews.length === 0
							? 'Drop files here or click to browse'
							: `${previews.length} file${previews.length === 1 ? '' : 's'} queued · drop or browse to add more`}
					</p>
					<p class="text-xs">Images (PNG, JPG, HEIC) or PDF · max {humanSize(MAX_BYTES)} per file</p>
				</button>

				<input
					bind:this={fileInput}
					type="file"
					accept="image/*,application/pdf"
					multiple
					class="sr-only"
					onchange={(e) => onFiles((e.currentTarget as HTMLInputElement).files)}
					data-testid="file-input"
				/>

				{#if aggregateError}
					<p
						role="alert"
						data-testid="error"
						class="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive"
					>
						{aggregateError}
					</p>
				{/if}

				{#if previews.length > 0}
					<ul class="flex flex-col gap-2" data-testid="preview-list">
						{#each previews as p (p.id)}
							<li
								class="flex items-center gap-3 rounded-lg border border-border bg-card p-3"
								data-testid="preview-row"
								data-status={p.status}
							>
								{#if p.kind === 'image' && p.url}
									<img
										src={p.url}
										alt=""
										class="size-12 shrink-0 rounded-md object-cover"
									/>
									<ImageIcon class="sr-only" />
								{:else}
									<div
										class="flex size-12 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground"
										aria-hidden="true"
									>
										<FileText class="size-6" />
									</div>
								{/if}
								<div class="min-w-0 flex-1">
									<p class="truncate text-sm font-medium" data-testid="preview-name">{p.name}</p>
									<p class="text-xs text-muted-foreground">
										{humanSize(p.size)}
										{#if p.status === 'uploading'}
											· <Loader2 class="inline size-3 align-text-bottom" aria-hidden="true" /> Uploading…
										{:else if p.status === 'done'}
											· <CheckCircle2 class="inline size-3 align-text-bottom text-emerald-600" aria-hidden="true" /> Created
										{/if}
									</p>
									{#if p.error}
										<p
											class="mt-0.5 text-xs text-destructive"
											data-testid="preview-error"
										>
											{p.error}
										</p>
									{/if}
								</div>
								<button
									type="button"
									onclick={() => clearOne(p.id)}
									aria-label={`Remove ${p.name}`}
									disabled={p.status === 'uploading'}
									class="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
								>
									<X class="size-4" />
								</button>
							</li>
						{/each}
					</ul>
				{/if}

				<div class="flex items-center justify-end gap-2">
					{#if previews.length > 1}
						<button
							type="button"
							onclick={clearAll}
							class="inline-flex h-9 items-center justify-center rounded-md border border-input bg-background px-4 text-sm font-medium hover:bg-accent"
						>
							Clear all
						</button>
					{/if}
					<button
						type="button"
						onclick={exit}
						class="inline-flex h-9 items-center justify-center rounded-md border border-input bg-background px-4 text-sm font-medium hover:bg-accent"
					>
						Cancel
					</button>
					<button
						type="submit"
						disabled={submitDisabled}
						data-testid="submit"
						class="inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow hover:bg-primary/90 disabled:opacity-50"
					>
						{submitLabel}
					</button>
				</div>
			</form>
		</main>

		{#if toastVisible}
			<div
				role="status"
				data-testid="toast"
				class="fixed bottom-6 left-1/2 -translate-x-1/2 rounded-full bg-foreground px-4 py-2 text-sm font-medium text-background shadow-lg"
			>
				{toastMessage}
			</div>
		{/if}
	</div>
{/if}
