/** A note as stored locally and in the encrypted payloads. Mirrors Note.kt on Android. */
export interface Note {
  id: string;
  title: string;
  body: string;
  createdAt: number;
  updatedAt: number;
  pinned: boolean;
  images: string[];
  secret: boolean;
  /** Counts edits. Sync orders versions by this first, so a device with a wrong clock can't win. */
  rev: number;
}

export function newNote(id: string, now = Date.now()): Note {
  return { id, title: "", body: "", createdAt: now, updatedAt: now, pinned: false, images: [], secret: false, rev: 0 };
}

/** Same visible content and flags (ignores timestamps and the edit counter). */
export function sameContent(a: Note, b: Note): boolean {
  return a.title === b.title && a.body === b.body && a.pinned === b.pinned && a.secret === b.secret &&
    a.images.length === b.images.length && a.images.every((v, i) => v === b.images[i]);
}

export function isBlank(n: Note): boolean {
  return n.title.trim() === "" && n.body.trim() === "" && n.images.length === 0;
}
