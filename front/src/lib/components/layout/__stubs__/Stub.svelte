<script lang="ts">
	/**
	 * Test stub used by Header.test.ts to replace real child components.
	 *
	 * Renders an empty `<div>`. The Header test asserts on `data-testid`
	 * attributes placed on the WRAPPING divs in Header.svelte, so the stub
	 * itself only needs to render harmlessly without crashing on whatever
	 * props the parent passes (`clientId`, no props, etc.).
	 *
	 * Accepts an arbitrary `clientId` (matching `GoogleLoginButton`) and an
	 * arbitrary `id` (matching `ThemeToggle`) so destructuring satisfies
	 * Svelte's `custom_element_props_identifier` check.
	 */
	let { clientId = '', id = '' }: { clientId?: string; id?: string } = $props();
</script>

<div></div>
