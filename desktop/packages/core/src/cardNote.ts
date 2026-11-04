import { type Note, newNote } from "./model";

/** Which note the floating card opens on. A port of CardNote.kt: recent / last / scratch / new. */
export type OpenMode = "recent" | "last" | "scratch" | "new";

export interface CardPrefs {
  mode: OpenMode;
  /** The note the card last worked on, and when. */
  lastCardNote?: string;
  lastCardAt?: number;
  /** The permanent "Scratch" note, once it exists. */
  scratchId?: string;
}

export const RECENT_MS = 30 * 60 * 1000;

export interface Pick {
  note: Note;
  /** true = an existing note; false = a fresh blank one. */
  existing: boolean;
  /** The caller should save this note (the Scratch note is created the first time it is needed). */
  create?: boolean;
}

/** Secret notes are never offered by the card, so reopening it never shows one. */
export function pickCardNote(notes: Note[], prefs: CardPrefs, now: number, newId: () => string): Pick {
  const visible = notes.filter((n) => !n.secret);
  const last = prefs.lastCardNote ? visible.find((n) => n.id === prefs.lastCardNote) : undefined;
  const fresh = (): Pick => ({ note: newNote(newId(), now), existing: false });
  switch (prefs.mode) {
    case "new": return fresh();
    case "last": return last ? { note: last, existing: true } : fresh();
    case "scratch": {
      const s = prefs.scratchId ? visible.find((n) => n.id === prefs.scratchId) : undefined;
      if (s) return { note: s, existing: true };
      return { note: { ...newNote(newId(), now), title: "Scratch", pinned: true }, existing: true, create: true };
    }
    default: {                                 // "recent"
      const usedAt = prefs.lastCardAt ?? last?.updatedAt ?? 0;
      if (last && now - Math.max(usedAt, last.updatedAt) < RECENT_MS) return { note: last, existing: true };
      return fresh();
    }
  }
}

/** The ten most recent public notes, pinned first, for the card's "recent notes" list. */
export function recentNotes(notes: Note[], limit = 10): Note[] {
  return notes.filter((n) => !n.secret)
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt)
    .slice(0, limit);
}
