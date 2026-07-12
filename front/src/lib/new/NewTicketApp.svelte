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
	 * Upload UX:
	 *  - Drag-and-drop zone + click-to-upload fallback (hidden <input>).
	 *  - Accepted MIME types: image/* and application/pdf. Anything else
	 *    shows an inline error and does not progress.
	 *  - Max size: 10 MB. Larger files are rejected with an inline error.
	 *  - Preview: <img> for images, an icon for PDFs.
	 *
	 * Submit:
	 * POSTs the file to the BFF (`POST /api/tickets` with a multipart
	 * payload — see `../api/tickets.ts`). On success a toast confirms
	 * the action and the user is bounced back to the dashboard by
	 * removing the `is-new` body class. On failure the preview stays
	 * in place so the user can retry without re-selecting the file.
	 */
	import tailwindCss from '../../app.css?inline';

	import { Upload, FileText, X, Image as ImageIcon } from '@lucide/svelte';

	import { auth } from '../auth/store.svelte';
	import { createTicket, TicketApiError } from '../api/tickets';
	import { MAX_BYTES, fileSizeError, humanSize, isAcceptedFile } from './validation';
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

	type Preview =
		| { kind: 'image'; url: string; name: string; size: number; bytes: Uint8Array; type: string }
		| { kind: 'pdf'; name: string; size: number; bytes: Uint8Array; type: string }
		| null;

	let dragOver = $state(false);
	let fileInput: HTMLInputElement = $state(null!);
	let preview = $state<Preview>(null);
	let errorMessage = $state<string | null>(null);
	let submitting = $state(false);
	let toastVisible = $state(false);
	let toastMessage = $state('Ticket created');

	function setError(msg: string): void {
		errorMessage = msg;
		preview = null;
	}

	function clearPreview(): void {
		if (preview?.kind === 'image') {
			URL.revokeObjectURL(preview.url);
		}
		preview = null;
		errorMessage = null;
	}

	async function acceptFile(file: File): Promise<void> {
		if (!isAcceptedFile(file)) {
			setError('Only images and PDFs are accepted.');
			return;
		}
		const tooBig = fileSizeError(file);
		if (tooBig) {
			setError(tooBig);
			return;
		}
		// Buffer the file once so the upload can re-stream the bytes
		// without re-reading from disk. PDF previews still need a URL
		// but a blob URL works for any type — we just don't render it.
		let bytes: Uint8Array;
		try {
			bytes = new Uint8Array(await file.arrayBuffer());
		} catch {
			setError('Could not read the file. Try again.');
			return;
		}
		errorMessage = null;
		if (preview?.kind === 'image') URL.revokeObjectURL(preview.url);
		const base = { name: file.name, size: file.size, bytes, type: file.type };
		if (file.type === 'application/pdf') {
			preview = { ...base, kind: 'pdf' };
		} else {
			preview = { ...base, kind: 'image', url: URL.createObjectURL(file) };
		}
	}

	function onFiles(files: FileList | null | undefined): void {
		const file = files?.[0];
		if (!file) return;
		void acceptFile(file);
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
		fileInput?.click();
	}

	function onCancel(): void {
		clearPreview();
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
		if (!preview || submitting) return;

		const token = readSessionToken();
		if (!token) {
			errorMessage = 'Session expired. Sign in again to upload a ticket.';
			return;
		}

		submitting = true;
		errorMessage = null;
		// POST the file to the BFF. On success: toast and bounce back to
		// the dashboard. On failure: keep the preview so the user can
		// retry without re-selecting the file.
		try {
			// Copy the bytes into a fresh ArrayBuffer so the File constructor
// accepts them — Uint8Array.buffer is typed as ArrayBufferLike which
// doesn't satisfy File's BlobPart constraint in TS 5.
const ab = new ArrayBuffer(preview.bytes.byteLength);
	new Uint8Array(ab).set(preview.bytes);
	const file = new File([ab], preview.name, { type: preview.type });
			const created = await createTicket(token, file);
			toastMessage = `Ticket ${created.id.slice(0, 8)} created`;
			toastVisible = true;
			setTimeout(() => {
				toastVisible = false;
				submitting = false;
				clearPreview();
				exit();
			}, 1500);
		} catch (err: unknown) {
			submitting = false;
			if (err instanceof TicketApiError) {
				errorMessage = `Upload failed (${err.status}): ${err.message}`;
			} else {
				errorMessage = `Upload failed: ${err instanceof Error ? err.message : 'unknown error'}`;
			}
		}
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
						Upload a receipt — a photo or a PDF. We'll handle the rest.
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
					aria-label="Drop a file here or click to browse"
				>
					<Upload class="size-8" aria-hidden="true" />
					<p class="text-sm font-medium">
						{preview ? 'Replace file' : 'Drop a file here or click to browse'}
					</p>
					<p class="text-xs">Images (PNG, JPG, HEIC) or PDF · max {humanSize(MAX_BYTES)}</p>
				</button>

				<input
					bind:this={fileInput}
					type="file"
					accept="image/*,application/pdf"
					class="sr-only"
					onchange={(e) => onFiles((e.currentTarget as HTMLInputElement).files)}
					data-testid="file-input"
				/>

				{#if errorMessage}
					<p
						role="alert"
						data-testid="error"
						class="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive"
					>
						{errorMessage}
					</p>
				{/if}

				{#if preview}
					<div
						class="flex items-center gap-3 rounded-lg border border-border bg-card p-3"
						data-testid="preview"
					>
						{#if preview.kind === 'image'}
							<img
								src={preview.url}
								alt={preview.name}
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
							<p class="truncate text-sm font-medium" data-testid="preview-name">{preview.name}</p>
							<p class="text-xs text-muted-foreground">{humanSize(preview.size)}</p>
						</div>
						<button
							type="button"
							onclick={onCancel}
							aria-label="Remove file"
							class="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
						>
							<X class="size-4" />
						</button>
					</div>
				{/if}

				<div class="flex items-center justify-end gap-2">
					<button
						type="button"
						onclick={exit}
						class="inline-flex h-9 items-center justify-center rounded-md border border-input bg-background px-4 text-sm font-medium hover:bg-accent"
					>
						Cancel
					</button>
					<button
						type="submit"
						disabled={!preview || submitting}
						data-testid="submit"
						class="inline-flex h-9 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground shadow hover:bg-primary/90 disabled:opacity-50"
					>
						{submitting ? 'Sending…' : 'Create ticket'}
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