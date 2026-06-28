<svelte:options customElement="landing-app" />

<script lang="ts">
	// Vite `?inline` inlines the compiled CSS as a string into the JS bundle.
	// We inject it via {@html} inside a <svelte:element this="style"> so it
	// lands as a real <style> element inside the shadow root. This is the
	// only way Tailwind utilities reach elements rendered inside the custom
	// element's shadow DOM (light-DOM stylesheets don't cross the boundary).
	import tailwindCss from '../../app.css?inline';

	import Hero from './Hero.svelte';
	import Categories from './Categories.svelte';
	import Features from './Features.svelte';
	import HowItWorks from './HowItWorks.svelte';
	import Pricing from './Pricing.svelte';
	import Faq from './Faq.svelte';
	import Cta from './Cta.svelte';
</script>

<svelte:element this={'style'}>{@html tailwindCss}</svelte:element>

<div class="flex min-h-screen flex-col bg-background font-sans text-foreground antialiased">
	<main id="main" class="flex-1">
		<Hero />
		<Categories />
		<Features />
		<HowItWorks />
		<Pricing />
		<Faq />
		<Cta />
	</main>
</div>
