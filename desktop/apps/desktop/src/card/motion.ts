/**
 * Springs and easing for the card. A spring has no fixed duration: new input just changes its target, so a drag can
 * grab the card mid-flight and a release carries the finger's speed (the same idea as the phone's SpringAnimation).
 */

export class Spring {
  velocity = 0;
  target: number;
  private k: number;
  private c: number;

  /** `response`: seconds to reach the target, roughly. `dampingRatio`: 1 = no overshoot, lower = a little bounce. */
  constructor(public value: number, response = 0.4, dampingRatio = 1) {
    this.target = value;
    this.k = (2 * Math.PI / response) ** 2;
    this.c = (4 * Math.PI * dampingRatio) / response;
  }

  tune(response: number, dampingRatio: number) {
    this.k = (2 * Math.PI / response) ** 2;
    this.c = (4 * Math.PI * dampingRatio) / response;
  }

  /** Advances by `dt` seconds. Returns true while still moving. */
  step(dt: number): boolean {
    const h = 1 / 240;                       // small fixed steps keep a stiff spring stable at any frame rate
    for (let left = Math.min(dt, 0.064); left > 0; left -= h) {
      const s = Math.min(h, left);
      this.velocity += (-this.k * (this.value - this.target) - this.c * this.velocity) * s;
      this.value += this.velocity * s;
    }
    if (Math.abs(this.velocity) < 2 && Math.abs(this.value - this.target) < 0.25) {
      this.value = this.target; this.velocity = 0;
      return false;
    }
    return true;
  }
}

/** Where a flick would come to rest, like scroll deceleration (px/s in, px out). */
export const project = (velocity: number, rate = 0.998) => ((velocity / 1000) * rate) / (1 - rate);

export const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi));

/** Android's DecelerateInterpolator(f): fast start, gentle stop. */
export const decelerate = (f: number) => (x: number) => 1 - (1 - x) ** (2 * f);
/** Android's AccelerateDecelerateInterpolator. */
export const accelDecel = (x: number) => Math.cos((x + 1) * Math.PI) / 2 + 0.5;
export const easeOut = (x: number) => 1 - (1 - x) ** 3;

/** Pointer positions over the last moments, for the release velocity. */
export class Tracker {
  private pts: { t: number; x: number; y: number }[] = [];
  add(x: number, y: number, t = performance.now()) {
    this.pts.push({ t, x, y });
    while (this.pts.length > 2 && t - this.pts[0].t > 100) this.pts.shift();
  }
  /** px/s, measured over the last ~100 ms; 0 if the pointer rested before letting go. */
  velocity(now = performance.now()): { vx: number; vy: number } {
    const last = this.pts[this.pts.length - 1], first = this.pts[0];
    if (!last || !first || last === first || now - last.t > 60) return { vx: 0, vy: 0 };
    const dt = (last.t - first.t) / 1000;
    return dt > 0 ? { vx: (last.x - first.x) / dt, vy: (last.y - first.y) / dt } : { vx: 0, vy: 0 };
  }
}
