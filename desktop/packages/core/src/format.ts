import type { Note } from "./model";
import type { Side } from "./merge";
import { legacy } from "./markdown";

/** How a note is written inside the encrypted payloads (Drive files and relay messages). Same as NoteJson on Android. */
export function noteToJson(n: Note): Record<string, unknown> {
  return {
    id: n.id, title: n.title, body: n.body, createdAt: n.createdAt, updatedAt: n.updatedAt,
    pinned: n.pinned, images: n.images, secret: n.secret, rev: n.rev,
    category: n.secret ? "" : n.category,
  };
}

export function noteFromJson(o: any): Note {
  return {
    id: String(o.id), title: o.title ?? "", body: legacy(o.body ?? ""), createdAt: o.createdAt ?? 0, updatedAt: o.updatedAt ?? 0,
    pinned: !!o.pinned, images: Array.isArray(o.images) ? o.images.map(String) : [], secret: !!o.secret, rev: o.rev ?? 0,
    category: o.category ?? "",
  };
}

/** One note file: either the note, or a deletion marker. */
export function sideFromJson(o: any): Side {
  return o.deleted ? { note: null, deletedAt: o.at ?? 0 } : { note: noteFromJson(o), deletedAt: 0 };
}

export function payloadJson(s: Side, id: string): Record<string, unknown> {
  return s.note ? noteToJson(s.note) : { id, deleted: true, at: s.deletedAt };
}
