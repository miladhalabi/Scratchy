import { type Note, type StateEntry, type Store, isBlank, noteFromJson, noteToJson, sameContent } from "@scratchy/core";
import { files } from "./files";

const b64ToBytes = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const bytesToB64 = (b: Uint8Array) => { let s = ""; for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000)); return btoa(s); };

/**
 * Notes on this computer, in the app data folder: notes.json, deleted.json (deletion markers, kept 90 days),
 * sync_state.json. Implements the sync engine's Store, and the app's own save/remove with the same rules as
 * NoteStore on Android (edit counter bumps only on real changes; emptied notes become deletions).
 */
export class LocalStore implements Store {
  private map = new Map<string, Note>();
  private tombs: Record<string, number> = {};
  private state: Record<string, StateEntry> = {};
  private listeners = new Set<() => void>();
  /** Called after any change to the notes (not for sync bookkeeping). */
  onChange(fn: () => void) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  private fire() { this.listeners.forEach((f) => f()); }
  private editListeners = new Set<(id: string) => void>();
  /** Called when the USER changes a note (not for changes that arrive from sync), so sync can start. */
  onEdit(fn: (id: string) => void) { this.editListeners.add(fn); return () => this.editListeners.delete(fn); }
  private edited(id: string) { this.editListeners.forEach((f) => f(id)); }

  async load() {
    const n = await files.readText("notes.json");
    if (n) for (const o of JSON.parse(n)) { const note = noteFromJson(o); this.map.set(note.id, note); }
    const d = await files.readText("deleted.json");
    if (d) this.tombs = JSON.parse(d);
    const s = await files.readText("sync_state.json");
    if (s) {
      const raw = JSON.parse(s) as Record<string, { base: unknown; ver: string; tomb: number }>;
      for (const [id, e] of Object.entries(raw)) this.state[id] = { base: e.base ? noteFromJson(e.base) : null, ver: e.ver, tomb: e.tomb };
    }
    this.pruneTombs();
  }

  private pruneTombs() {
    const cutoff = Date.now() - 90 * 24 * 3600 * 1000;
    for (const [id, at] of Object.entries(this.tombs)) if (at < cutoff) delete this.tombs[id];
  }

  private async persistNotes() {
    await files.writeText("notes.json", JSON.stringify([...this.map.values()].map(noteToJson)));
    await files.writeText("deleted.json", JSON.stringify(this.tombs));
  }

  // ---- what the app does ----

  all(): Note[] { return [...this.map.values()]; }
  get(id: string): Note | undefined { return this.map.get(id); }

  /** Saves an edit. A note that became empty is removed (and remembered as deleted) instead of kept blank. */
  async save(note: Note): Promise<Note> {
    const prev = this.map.get(note.id);
    if (isBlank(note)) {
      if (prev) await this.remove(note.id);
      return note;
    }
    const rev = !prev ? Math.max(note.rev, 1) : sameContent(prev, note) ? Math.max(prev.rev, note.rev) : Math.max(prev.rev, note.rev) + 1;
    const saved = { ...note, rev };
    this.map.set(saved.id, saved);
    delete this.tombs[saved.id];
    await this.persistNotes();
    this.fire();
    this.edited(saved.id);
    return saved;
  }

  async remove(id: string) {
    this.map.delete(id);
    this.tombs[id] = Date.now();
    await this.persistNotes();
    this.fire();
    this.edited(id);
  }

  /** Undo of a delete: puts the note back and forgets the deletion marker. */
  async restore(note: Note) {
    this.map.set(note.id, note);
    delete this.tombs[note.id];
    await this.persistNotes();
    this.fire();
    this.edited(note.id);
  }

  // ---- the sync engine's view ----

  async notes() { return this.all().map((n) => ({ ...n, images: [...n.images] })); }
  async tombstones() { return { ...this.tombs }; }
  async syncState() { return structuredClone(this.state); }
  async saveSyncState(s: Record<string, StateEntry>) {
    this.state = structuredClone(s);
    await files.writeText("sync_state.json", JSON.stringify(s));
  }
  async imageExists(name: string) { return files.hasAttachment(name); }
  async readImage(name: string) { const b = await files.readAttachment(name); return b ? b64ToBytes(b) : new Uint8Array(); }
  async writeImage(name: string, data: Uint8Array) { await files.writeAttachment(name, bytesToB64(data)); }

  async applySynced(puts: Map<string, Note>, removes: Map<string, number>, seenRev: Map<string, number>) {
    const skipped = new Set<string>();
    const still = (id: string) => (this.map.get(id)?.rev ?? -1) === (seenRev.get(id) ?? -1);
    for (const [id, n] of puts) { if (still(id)) { this.map.set(id, n); delete this.tombs[id]; } else skipped.add(id); }
    for (const [id, at] of removes) { if (still(id)) { this.map.delete(id); this.tombs[id] = at; } else skipped.add(id); }
    await this.persistNotes();
    this.fire();
    return skipped;
  }
}
