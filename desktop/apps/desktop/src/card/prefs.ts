import type { CardPrefs } from "@scratchy/core";
import { files } from "../lib/files";

export type Anim = "genie" | "slide" | "none";

/** The card's own settings and where it was left, in card.json next to the notes. */
export interface CardState extends CardPrefs {
  x?: number;
  y?: number;
  w: number;
  h: number;
  pinned: boolean;
  anim: Anim;
  /** Genie speed, 50–200 %. */
  speed: number;
}

const defaults: CardState = { mode: "recent", w: 400, h: 460, pinned: false, anim: "genie", speed: 100 };

export async function loadCard(): Promise<CardState> {
  const t = await files.readText("card.json").catch(() => null);
  try { return { ...defaults, ...(t ? JSON.parse(t) : {}) }; } catch { return { ...defaults }; }
}

let pending: ReturnType<typeof setTimeout> | undefined;
/** Saved a moment later, so a drag or a stream of typing writes once. */
export function saveCard(s: CardState) {
  clearTimeout(pending);
  pending = setTimeout(() => files.writeText("card.json", JSON.stringify(s)).catch(() => {}), 250);
}
