import './app.css';
import { mount } from 'svelte';

import TicketApp from './lib/TicketApp.svelte';
import LandingApp from './lib/landing/LandingApp.svelte';
import DashboardApp from './lib/auth/DashboardApp.svelte';
import Header from './lib/components/layout/Header.svelte';
// Eager side-effect import: mounts the EffectHost that toggles
// `document.body.classList` based on `auth.isAuthenticated`. Must run
// before either <landing-app> or <dashboard-app> renders so the CSS
// gate is in effect on first paint.
import './lib/auth/host';

// Mount the global app shell header. Lives in the light DOM so the existing
// Tailwind stylesheet applies directly — no shadow-DOM stylesheet injection
// needed. Target element is supplied by index.html.
const headerTarget = document.getElementById('app-header-mount');
if (headerTarget) {
	mount(Header, { target: headerTarget });
}

export { TicketApp, LandingApp, DashboardApp };
