/** Zoom steps, as in a browser. */
const STEPS = [0.5, 0.67, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2];

export const MIN_ZOOM = STEPS[0], MAX_ZOOM = STEPS[STEPS.length - 1];

/** The next step in or out from `z` (dir 0 = back to 100%). */
export function stepZoom(z: number, dir: 1 | -1 | 0): number {
  if (dir === 0) return 1;
  if (dir > 0) return STEPS.find((s) => s > z + 0.001) ?? MAX_ZOOM;
  return [...STEPS].reverse().find((s) => s < z - 0.001) ?? MIN_ZOOM;
}

/** Ctrl with = / + / - / 0 (top row or keypad): which way to zoom, or null for any other key. */
export function zoomKey(e: KeyboardEvent): 1 | -1 | 0 | null {
  if (!(e.ctrlKey || e.metaKey) || e.altKey) return null;
  if (e.key === "=" || e.key === "+" || e.code === "NumpadAdd") return 1;
  if (e.key === "-" || e.key === "_" || e.code === "NumpadSubtract") return -1;
  if (e.key === "0" || e.code === "Numpad0") return 0;
  return null;
}

/**
 * Ctrl+wheel: one step per notch. Touchpads send many small deltas, so they add up to a notch's worth first.
 * Returns the direction, or null while it is still adding up (or the wheel event isn't a zoom).
 */
let wheelSum = 0, wheelAt = 0;
export function zoomWheel(e: WheelEvent): 1 | -1 | null {
  if (!(e.ctrlKey || e.metaKey)) return null;
  e.preventDefault();
  const now = performance.now();
  if (now - wheelAt > 400) wheelSum = 0;
  wheelAt = now;
  wheelSum += e.deltaMode === 1 ? e.deltaY * 33 : e.deltaY;
  if (Math.abs(wheelSum) < 50) return null;
  const dir = wheelSum < 0 ? 1 : -1;
  wheelSum = 0;
  return dir;
}

/** A small "110%" that shows for a moment where the zoom changed. */
export function zoomBadge(host: HTMLElement, z: number) {
  let el = host.querySelector<HTMLElement>(":scope > .zoom-badge");
  if (!el) {
    el = document.createElement("div");
    el.className = "zoom-badge";
    if (host === document.body) el.style.position = "fixed";
    el.setAttribute("role", "status");
    host.appendChild(el);
  }
  el.textContent = `${Math.round(z * 100)}%`;
  el.classList.remove("out");
  void el.offsetWidth;
  el.classList.add("on");
  clearTimeout(Number(el.dataset.t));
  el.dataset.t = String(setTimeout(() => el!.classList.replace("on", "out"), 900));
}
