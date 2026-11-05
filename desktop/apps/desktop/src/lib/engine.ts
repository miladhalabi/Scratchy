/**
 * The notes engine. It lives in the card's window, which is always there (hidden when closed), so the card opens
 * on its note without asking anyone. The notes window is a client: it gets the notes in messages and sends its
 * edits back here, so only one place ever writes the notes file or talks to Drive.
 */
import { emitTo, listen } from "@tauri-apps/api/event";
import { WrongPassword, type Note } from "@scratchy/core";
import { LocalStore } from "./localStore";
import * as sync from "./sync";
import { checkVaultPassword, loadDataKey, loadVault, setVaultPassword, vaultSetUp } from "./vault";
import { invoke } from "@tauri-apps/api/core";
import { loadSettings } from "./settings";

export const store = new LocalStore();

export interface Snapshot { notes: Note[]; sync: sync.SyncStatus; vault: { setUp: boolean } }

/** Everything a client needs to draw. */
const snapshot = (): Snapshot => ({ notes: store.all(), sync: sync.status(), vault: { setUp: vaultSetUp() } });
const push = () => { emitTo("main", "notes", snapshot()).catch(() => {}); };

/** What the notes window can ask for. Each answer goes back as "engine-reply" with the same id. */
const ops: Record<string, (...a: string[]) => Promise<unknown>> = {
  connect: (pw) => sync.connect(pw),
  newPassword: (pw) => sync.enterNewPassword(pw),
  signIn: () => sync.signIn(),
  async changePassword(old, pw) {
    if (vaultSetUp() && !(await checkVaultPassword(old))) throw new Error("Current password is wrong");
    await sync.changePassword(pw);
  },
  disconnect: () => sync.disconnect(),
  syncNow: () => sync.syncNow(),
  seen: async () => sync.syncIfStale(),
  unlock: (pw) => checkVaultPassword(pw),
  setupVault: (pw) => setVaultPassword(pw),
};

export async function startEngine() {
  // The key must be in place before the notes are read, or secret notes can't be opened.
  store.setDataKey(await loadDataKey());
  await loadVault();
  await store.load();
  store.onChange(push);
  await listen("notes-hello", () => { push(); sync.syncIfStale(); });
  await listen<{ note: Note }>("notes-save", ({ payload }) => { store.save(payload.note); });
  await listen<{ id: string }>("notes-remove", ({ payload }) => { store.remove(payload.id); });
  await listen<{ note: Note }>("notes-restore", ({ payload }) => { store.restore(payload.note); });
  await listen<{ id: number; op: string; args: string[] }>("engine-call", async ({ payload: { id, op, args } }) => {
    try {
      const value = await ops[op](...args);
      emitTo("main", "engine-reply", { id, ok: true, value }).catch(() => {});
    } catch (e) {
      const error = e instanceof WrongPassword ? "That isn't the password used on your other device" : String((e as Error)?.message ?? e);
      emitTo("main", "engine-reply", { id, ok: false, error }).catch(() => {});
    }
    push();
  });
  await sync.startSync(store, push, (text) => { emitTo("main", "notes-notice", { text }).catch(() => {}); });
  push();          // a notes window that asked before the engine was ready gets its notes now
  // Start at login is on unless it was turned off; the entry is (re)written so it points at this copy of the app.
  if ((await loadSettings()).startAtLogin) invoke("autostart_set", { on: true }).catch(() => {});
}
