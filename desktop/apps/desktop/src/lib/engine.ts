/**
 * The notes engine. It lives in the card's window, which is always there (hidden when closed), so the card opens
 * on its note without asking anyone. The notes window is a client: it gets the notes in messages and sends its
 * edits back here, so only one place ever writes the notes file.
 */
import { emitTo, listen } from "@tauri-apps/api/event";
import type { Note } from "@scratchy/core";
import { LocalStore } from "./localStore";

export const store = new LocalStore();

/** Everything a client needs to draw the notes. */
const snapshot = () => ({ notes: store.all() });
const push = () => { emitTo("main", "notes", snapshot()).catch(() => {}); };

export async function startEngine() {
  await store.load();
  store.onChange(push);
  await listen("notes-hello", push);
  await listen<{ note: Note }>("notes-save", ({ payload }) => { store.save(payload.note); });
  await listen<{ id: string }>("notes-remove", ({ payload }) => { store.remove(payload.id); });
  await listen<{ note: Note }>("notes-restore", ({ payload }) => { store.restore(payload.note); });
  push();          // a notes window that asked before the engine was ready gets its notes now
}
