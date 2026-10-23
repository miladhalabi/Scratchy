import { type DriveApi, type Note, type Relay, type RemoteFile, type StateEntry, type Store, sameContent, syncNow, applyLive } from "../src";

/** An in-memory stand-in for Drive's app-data folder. */
export class FakeDrive implements DriveApi {
  files = new Map<string, { id: string; version: number; data: Uint8Array }>();
  private n = 1;
  calls = { list: 0, download: 0, upload: 0, version: 0, delete: 0 };
  /** Runs before each upload, to simulate another device writing at the same moment. */
  beforeUpload?: (name: string) => void;

  async list() {
    this.calls.list++;
    const m = new Map<string, RemoteFile>();
    for (const [name, f] of this.files) m.set(name, { id: f.id, version: String(f.version) });
    return m;
  }
  private byId(id: string) { for (const f of this.files.values()) if (f.id === id) return f; throw new Error("no such file " + id); }
  async download(id: string) { this.calls.download++; return this.byId(id).data.slice(); }
  async version(id: string) { this.calls.version++; return String(this.byId(id).version); }
  async upload(id: string | null, name: string, data: Uint8Array) {
    this.calls.upload++;
    this.beforeUpload?.(name);
    const cur = id ? this.byId(id) : this.files.get(name);
    const f = cur ?? { id: "f" + this.n++, version: 0, data };
    f.version++; f.data = data.slice();
    this.files.set(name, f);
    return { id: f.id, version: String(f.version) };
  }
  async delete(id: string) {
    this.calls.delete++;
    for (const [name, f] of this.files) if (f.id === id) this.files.delete(name);
  }
  names() { return [...this.files.keys()].sort(); }
}

/** Local storage, behaving like NoteStore on Android (rev bumps, deletion markers, applySynced). */
export class MemoryStore implements Store {
  map = new Map<string, Note>();
  tombs: Record<string, number> = {};
  state: Record<string, StateEntry> = {};
  images = new Map<string, Uint8Array>();

  async notes() { return [...this.map.values()].map((n) => ({ ...n, images: [...n.images] })); }
  async tombstones() { return { ...this.tombs }; }
  async syncState() { return structuredClone(this.state); }
  async saveSyncState(s: Record<string, StateEntry>) { this.state = structuredClone(s); }
  async imageExists(name: string) { return this.images.has(name); }
  async readImage(name: string) { return this.images.get(name)!; }
  async writeImage(name: string, data: Uint8Array) { this.images.set(name, data); }

  async applySynced(puts: Map<string, Note>, removes: Map<string, number>, seenRev: Map<string, number>) {
    const skipped = new Set<string>();
    const still = (id: string) => (this.map.get(id)?.rev ?? -1) === (seenRev.get(id) ?? -1);
    for (const [id, n] of puts) { if (still(id)) { this.map.set(id, n); delete this.tombs[id]; } else skipped.add(id); }
    for (const [id, at] of removes) { if (still(id)) { this.map.delete(id); this.tombs[id] = at; } else skipped.add(id); }
    return skipped;
  }

  /** What the user does: create or edit a note. */
  save(n: Partial<Note> & { id: string }, at: number) {
    const prev = this.map.get(n.id);
    const next: Note = {
      title: "", body: "", createdAt: at, pinned: false, images: [], secret: false, rev: 0, ...prev, ...n, updatedAt: at,
    };
    next.rev = prev ? (sameContent(prev, next) ? prev.rev : prev.rev + 1) : 1;
    this.map.set(n.id, next);
    delete this.tombs[n.id];
  }
  remove(id: string, at: number) { this.map.delete(id); this.tombs[id] = at; }
  get(id: string) { return this.map.get(id); }
  titles() { return [...this.map.values()].map((n) => n.title).sort(); }
}

/** A device: its own store, plus a link to a shared relay "room". */
export class Device {
  store = new MemoryStore();
  inbox: string[] = [];
  ticks = 0;
  constructor(public name: string, private drive: FakeDrive, private key: Uint8Array, private room: Device[]) { room.push(this); }

  private relay: Relay = {
    relay: (payloads) => { for (const d of this.room) if (d !== this) d.inbox.push(...payloads); },
    tick: () => { for (const d of this.room) if (d !== this) d.ticks++; },
  };
  private deps(extra: object = {}) {
    let n = 0;
    return { drive: this.drive, store: this.store, key: this.key, relay: this.relay, newId: () => `${this.name}-copy-${n++}`, ...extra };
  }
  sync(now?: () => number) { return syncNow(this.deps(now ? { now } : {})); }
  async receive() {
    const out = [];
    for (const p of this.inbox.splice(0)) out.push(await applyLive(this.deps(), p));
    return out;
  }
}
