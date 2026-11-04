/**
 * The genie, ported from GenieView.kt. The card is cut into thin strips (copies of the live card, each showing its
 * own band), and every strip is mapped onto its piece of the warped mesh with a projective matrix3d. Strips nearest
 * the target are pulled in first, so the card narrows into a neck while its far side is still wide.
 * No bitmap is needed, so nothing waits for a snapshot.
 */

export type Edge = "left" | "right" | "top" | "bottom";
export interface Rect { x: number; y: number; w: number; h: number }
type P = [number, number];

/** The screen edge closest to the card, and the point on it the card funnels into. */
export function nearestEdge(r: Rect, sw: number, sh: number): { edge: Edge; tx: number; ty: number } {
  const d: [Edge, number][] = [["left", r.x], ["right", sw - r.x - r.w], ["top", r.y], ["bottom", sh - r.y - r.h]];
  const edge = d.reduce((a, b) => (b[1] < a[1] ? b : a))[0];
  const cx = Math.min(Math.max(r.x + r.w / 2, 24), sw - 24), cy = Math.min(Math.max(r.y + r.h / 2, 24), sh - 24);
  switch (edge) {
    case "left": return { edge, tx: 0, ty: cy };
    case "right": return { edge, tx: sw, ty: cy };
    case "top": return { edge, tx: cx, ty: 0 };
    case "bottom": return { edge, tx: cx, ty: sh };
  }
}

const smooth = (q: number) => q * q * (3 - 2 * q);
const clamp01 = (v: number) => Math.min(Math.max(v, 0), 1);

/** CSS matrix3d that maps the box (0,0)-(w,h) onto the quad p0 p1 p2 p3 (clockwise from top-left). */
function quad(w: number, h: number, [x0, y0]: P, [x1, y1]: P, [x2, y2]: P, [x3, y3]: P): string {
  // Unit square to quad (Heckbert), then scaled by 1/w, 1/h.
  const dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
  const dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
  let g = 0, k = 0;
  const den = dx1 * dy2 - dx2 * dy1;
  if ((Math.abs(dx3) > 1e-6 || Math.abs(dy3) > 1e-6) && Math.abs(den) > 1e-9) {
    g = (dx3 * dy2 - dx2 * dy3) / den;
    k = (dx1 * dy3 - dx3 * dy1) / den;
  }
  const a = x1 - x0 + g * x1, b = x3 - x0 + k * x3, c = x0;
  const d = y1 - y0 + g * y1, e = y3 - y0 + k * y3, f = y0;
  const m = [a / w, d / w, 0, g / w, b / h, e / h, 0, k / h, 0, 0, 1, 0, c, f, 0, 1];
  return `matrix3d(${m.map((v) => (Number.isFinite(v) ? +v.toFixed(6) : 0)).join(",")})`;
}

/** Copies of text boxes are drawn as plain text: much cheaper to lay out 28 times than real text boxes. */
function asText(copy: HTMLElement) {
  for (const el of [...copy.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>("input, textarea")]) {
    const d = document.createElement("div");
    d.className = el.className + " as-text";
    d.dir = el.dir;
    d.textContent = el.value || el.placeholder;
    if (!el.value) d.classList.add("placeholder");
    el.replaceWith(d);
  }
}

export class Genie {
  private layer: HTMLDivElement | null = null;
  private strips: HTMLDivElement[] = [];
  private n = 0;
  /** What the strips show; strips are kept after closing and reused when the card opens unchanged. */
  key = "";
  readonly across: boolean;          // strips side by side (left/right edge) or stacked (top/bottom)

  constructor(private root: HTMLElement, private r: Rect, private edge: Edge, private tx: number, private ty: number) {
    this.across = edge === "left" || edge === "right";
  }

  /** Same strips, card somewhere else (only along the same kind of edge). */
  retarget(r: Rect, edge: Edge, tx: number, ty: number) {
    Object.assign(this, { r, edge, tx, ty });
  }

  hide() { if (this.layer) this.layer.style.visibility = "hidden"; }
  show() { if (this.layer) this.layer.style.visibility = "visible"; }

  /** Makes the strips from the card as it looks now. */
  build(card: HTMLElement, n = 28) {
    this.n = n;
    const layer = document.createElement("div");
    layer.className = "genie";
    const { w, h } = this.r;
    const step = (this.across ? w : h) / n;
    // Scrolled text areas don't keep their scroll position in a copy.
    const scrolls = [...card.querySelectorAll<HTMLElement>(".scroll")].map((e) => e.scrollTop);
    const proto = card.cloneNode(true) as HTMLElement;
    proto.removeAttribute("id");
    proto.classList.add("copy");
    proto.style.visibility = "";         // the live card is hidden while the strips show; the strips decide
    asText(proto);
    for (let i = 0; i < n; i++) {
      const s = document.createElement("div");
      s.className = "strip";
      // 1px wider than its share, so neighbours overlap and no hairline gap shows between strips.
      s.style.width = `${this.across ? step + 1 : w}px`;
      s.style.height = `${this.across ? h : step + 1}px`;
      const copy = proto.cloneNode(true) as HTMLElement;
      copy.style.transform = this.across ? `translate3d(${-i * step}px,0,0)` : `translate3d(0,${-i * step}px,0)`;
      copy.style.width = `${w}px`;
      copy.style.height = `${h}px`;
      s.appendChild(copy);
      layer.appendChild(s);
      this.strips.push(s);
    }
    this.root.appendChild(layer);
    this.layer = layer;
    // Scroll positions only stick once the copies are in the page.
    for (const s of this.strips) [...s.querySelectorAll<HTMLElement>(".scroll")].forEach((e, j) => { e.scrollTop = scrolls[j] ?? 0; });
  }

  /** t = 0: the card as it is; t = 1: sucked into the edge. */
  frame(t: number) {
    if (!this.layer) return;
    const { x, y, w, h } = this.r, { tx, ty, n } = this;
    const toward = this.edge === "right" || this.edge === "bottom";
    const pts: P[][] = [];                                  // per strip boundary: [start corner, end corner]
    for (let i = 0; i <= n; i++) {
      const u = i / n;
      const near = toward ? u : 1 - u;                     // 1 = closest to the target
      const q = clamp01(t * 1.7 - (1 - near) * 0.7);
      const shrink = Math.min(smooth(q), 0.995);           // funnel toward the target's line
      const slide = q * q;                                 // then slide in toward it
      if (this.across) {
        const x0 = x + w * u, X = x0 + (tx - x0) * slide;
        pts.push([[X, y + (ty - y) * shrink], [X, y + h + (ty - y - h) * shrink]]);
      } else {
        const y0 = y + h * u, Y = y0 + (ty - y0) * slide;
        pts.push([[x + (tx - x) * shrink, Y], [x + w + (tx - x - w) * shrink, Y]]);
      }
    }
    const step = (this.across ? w : h) / n;
    for (let i = 0; i < n; i++) {
      const [a0, a1] = pts[i], [b0, b1] = pts[i + 1];
      this.strips[i].style.transform = this.across
        ? quad(step, h, a0, b0, b1, a1)
        : quad(w, step, a0, a1, b1, b0);
    }
    // Fades over the last stretch so it dissolves into the edge instead of just stopping.
    this.layer.style.opacity = String(1 - smooth(clamp01((t - 0.85) / 0.15)));
  }

  clear() {
    this.layer?.remove();
    this.layer = null;
    this.strips = [];
  }
}
