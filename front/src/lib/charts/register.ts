/**
 * Centralized Chart.js registration.
 *
 * chart.js v4 dropped auto-register — every controller, element, and scale
 * must be wired explicitly. Doing it in one place keeps chart components
 * lean and avoids duplicate registration under HMR.
 *
 * Import this module once at the entry point of any feature that renders
 * charts; the side-effect-free `registerCharts()` export is also available
 * for tests or callers that want explicit control.
 */
import {
  Chart,
  LineController,
  LineElement,
  PointElement,
  LinearScale,
  CategoryScale,
  Filler,
  Tooltip,
  Legend,
  Title,
  DoughnutController,
  ArcElement
} from 'chart.js';

let registered = false;

/**
 * Register every Chart.js controller, element, and scale the dashboard
 * charts need. Idempotent — safe to call more than once. Errors thrown by
 * Chart.js when a component is already registered (notably under Vite HMR)
 * are swallowed on purpose.
 */
export const registerCharts = (): void => {
  if (registered) return;
  try {
    Chart.register(
      LineController,
      LineElement,
      PointElement,
      LinearScale,
      CategoryScale,
      Filler,
      Tooltip,
      Legend,
      Title,
      DoughnutController,
      ArcElement
    );
    registered = true;
  } catch (err) {
    // chart.js throws "X is already registered" when HMR re-evaluates this
    // module. Treat as success — the registry is already in the state we want.
    if (err instanceof Error && err.message.includes('already registered')) {
      registered = true;
      return;
    }
    throw err;
  }
};

// Side-effect: importing this module is enough to make charts renderable.
registerCharts();
