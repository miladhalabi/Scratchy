/**
 * The app's settings (the phone keeps these in SharedPreferences), in settings.json. Both windows read them;
 * a change is written once and announced, so the other window picks it up at once.
 * The card's own options (opens on, animation, where it was left) stay in card.json, owned by the card.
 */
import { emit, listen } from "@tauri-apps/api/event";
import { files } from "./files";

export type ShotMode = "ask" | "auto" | "off";
export interface AppSettings {
  /** "system" follows the desktop's accent color; otherwise one of the phone's fixed colors. */
  color: string;
  shotMode: ShotMode;
  /** Seconds a new screenshot keeps waiting to be attached. */
  shotTtl: number;
  /** Where screenshots land; "" = the usual place for this desktop. */
  shotDir: string;
  suggestCategories: boolean;
  textModel: string;
  imageModel: string;
  startAtLogin: boolean;
  /** The notes window's zoom (1 = 100%). The card has its own, for its text, in card.json. */
  zoom: number;
  /** The live relay ("https://host"); "" = the default one. */
  relay: string;
}

export const DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash-1017";
/** The relay everyone uses, unless they set their own in Settings → Sync → Advanced (same as the phone's). */
export const DEFAULT_RELAY = "https://scratchy-sync.halabimilad.workers.dev";
const defaults: AppSettings = {
  color: "system", shotMode: "ask", shotTtl: 60, shotDir: "", suggestCategories: true,
  textModel: "", imageModel: "", startAtLogin: true, zoom: 1, relay: "",
};

let current: AppSettings = { ...defaults };
const listeners = new Set<(s: AppSettings) => void>();
let started: Promise<void> | null = null;

function start() {
  started ??= (async () => {
    const t = await files.readText("settings.json").catch(() => null);
    try { current = { ...defaults, ...(t ? JSON.parse(t) : {}) }; } catch { /* keep the defaults */ }
    await listen<AppSettings>("settings", ({ payload }) => { current = { ...defaults, ...payload }; listeners.forEach((f) => f(current)); });
  })();
  return started;
}

export async function loadSettings(): Promise<AppSettings> { await start(); return current; }
export const settings = () => current;

/** Calls `fn` now and after every change, in this window or the other one. */
export async function watchSettings(fn: (s: AppSettings) => void) {
  await start();
  listeners.add(fn);
  fn(current);
  return () => listeners.delete(fn);
}

export async function saveSettings(patch: Partial<AppSettings>) {
  await start();
  current = { ...current, ...patch };
  await files.writeText("settings.json", JSON.stringify(current));
  await emit("settings", current);                        // every window, this one included
}

export const textModel = () => current.textModel.trim() || DEFAULT_MODEL;
export const imageModel = () => current.imageModel.trim() || textModel();
export const relayUrl = () => current.relay.trim() || DEFAULT_RELAY;

/** "https://host" (a relay sits at the root of its address), or null when the text isn't one. */
export function cleanRelay(text: string): string | null {
  const t = text.trim().replace(/\/+$/, "");
  if (!t.startsWith("https://")) return null;
  const host = t.slice("https://".length);
  return host && !/[/\s?#]/.test(host) ? `https://${host}` : null;
}

/** Resolves when `url` answers like a Scratchy relay (its WebSocket address opens), else rejects with why. */
export function checkRelay(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${url.replace(/^https/, "wss")}/v1/${"0".repeat(64)}/ws`);
    const timer = setTimeout(() => { ws.close(); reject(new Error("It didn't answer.")); }, 8000);
    ws.onopen = () => { clearTimeout(timer); ws.close(); resolve(); };
    ws.onerror = () => { clearTimeout(timer); reject(new Error("That address isn't a Scratchy relay, or it can't be reached.")); };
  });
}
