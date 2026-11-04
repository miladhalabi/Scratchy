/** The notes window's side of the engine (see engine.ts): a live list, and edits sent to the card's window. */
import { emitTo, listen } from "@tauri-apps/api/event";
import type { Note } from "@scratchy/core";

export const notes = {
  /** Calls `fn` with every note now and after every change. */
  async watch(fn: (notes: Note[]) => void) {
    const off = await listen<{ notes: Note[] }>("notes", ({ payload }) => fn(payload.notes));
    await emitTo("card", "notes-hello");
    return off;
  },
  save: (note: Note) => emitTo("card", "notes-save", { note }),
  remove: (id: string) => emitTo("card", "notes-remove", { id }),
  restore: (note: Note) => emitTo("card", "notes-restore", { note }),
};
