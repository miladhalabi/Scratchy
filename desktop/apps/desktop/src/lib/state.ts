import { writable } from "svelte/store";
import { type Note, fixedColors, newNote } from "@scratchy/core";
import { LocalStore } from "./localStore";

export const local = new LocalStore();
export const notes = writable<Note[]>([]);
export const ready = writable(false);

export async function init() {
  await local.load();
  notes.set(local.all());
  local.onChange(() => notes.set(local.all()));
  ready.set(true);
}

export const createNote = () => newNote(crypto.randomUUID());

/** Sets the color variables on the page from the chosen accent and the system dark/light mode. */
export function applyTheme(name = "blue") {
  const dark = matchMedia("(prefers-color-scheme: dark)").matches;
  const c = fixedColors(name, dark);
  const s = document.documentElement.style;
  s.setProperty("--accent", c.accent);
  s.setProperty("--on-accent", c.onAccent);
  s.setProperty("--accent-container", c.accentContainer);
  s.setProperty("--bg", c.background);
  s.setProperty("--surface", c.surface);
  s.setProperty("--floating", c.floating);
  s.setProperty("--text", c.text);
  s.setProperty("--secondary", c.secondary);
  s.setProperty("--hairline", c.hairline);
  s.setProperty("--track", c.track);
  s.setProperty("--danger", c.danger);
  s.setProperty("--success", c.success);
  document.documentElement.dataset.mode = dark ? "dark" : "light";
}
