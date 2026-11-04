/** The notes window's side of the engine (see engine.ts): a live list, and edits sent to the card's window. */
import { emitTo, listen } from "@tauri-apps/api/event";
import type { Note } from "@scratchy/core";
import type { Snapshot } from "./engine";

let next = 0;
const waiting = new Map<number, { ok: (v: unknown) => void; fail: (e: Error) => void }>();
void listen<{ id: number; ok: boolean; value?: unknown; error?: string }>("engine-reply", ({ payload: r }) => {
  const w = waiting.get(r.id);
  if (!w) return;
  waiting.delete(r.id);
  if (r.ok) w.ok(r.value); else w.fail(new Error(r.error));
});

/** Asks the engine to do something and waits for the answer (sign-in can take minutes). */
function call<T = void>(op: string, ...args: string[]): Promise<T> {
  const id = ++next;
  return new Promise<T>((ok, fail) => {
    waiting.set(id, { ok: ok as (v: unknown) => void, fail });
    emitTo("card", "engine-call", { id, op, args }).catch((e) => { waiting.delete(id); fail(e); });
  });
}

export const notes = {
  /** Calls `fn` with everything now and after every change. */
  async watch(fn: (s: Snapshot) => void) {
    const off = await listen<Snapshot>("notes", ({ payload }) => fn(payload));
    await emitTo("card", "notes-hello");
    return off;
  },
  onNotice: (fn: (text: string) => void) => listen<{ text: string }>("notes-notice", ({ payload }) => fn(payload.text)),
  save: (note: Note) => emitTo("card", "notes-save", { note }),
  remove: (id: string) => emitTo("card", "notes-remove", { id }),
  restore: (note: Note) => emitTo("card", "notes-restore", { note }),
  seen: () => call("seen"),
};

export const engine = {
  connect: (password: string) => call("connect", password),
  newPassword: (password: string) => call("newPassword", password),
  signIn: () => call("signIn"),
  changePassword: (old: string, password: string) => call("changePassword", old, password),
  disconnect: () => call("disconnect"),
  syncNow: () => call("syncNow"),
  unlock: (password: string) => call<boolean>("unlock", password),
  setupVault: (password: string) => call("setupVault", password),
};
