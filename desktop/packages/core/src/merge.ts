import { type Note, sameContent } from "./model";

/**
 * Pure merge logic for sync. A line-for-line port of Merge.kt on Android, so both platforms resolve
 * the same edits the same way.
 *
 * For one note id there are three versions: the `base` both sides last agreed on, `ours` on this device
 * and `theirs` in Drive. A side is either a note or a deletion (note == null, deletedAt = when).
 */

export interface Side {
  note: Note | null;
  deletedAt: number;
}

export const noteSide = (note: Note): Side => ({ note, deletedAt: 0 });
export const goneSide = (deletedAt: number): Side => ({ note: null, deletedAt });

/**
 * `note` is the version both devices should end up with (null = the note is deleted, at `deletedAt`).
 * `conflictCopy` is set when the same lines were changed on both sides: the loser is kept as a new note.
 */
export interface Result {
  note: Note | null;
  deletedAt: number;
  conflictCopy?: Note;
}

/** Which version is newer: higher edit counter first, then timestamp. Ties go to the first argument. */
export function newer(a: Note, b: Note): Note {
  const cmp = b.rev !== a.rev ? b.rev - a.rev : b.updatedAt - a.updatedAt;
  return cmp > 0 ? b : a;
}

/**
 * A note change handed to us directly (not read from Drive) can arrive late or out of order.
 * Ignore it when we already hold a later edit of the same note.
 */
export function staleRelay(local: Note | null, theirs: Note | null): boolean {
  return local != null && theirs != null && theirs.rev < local.rev;
}

const randomId = () => crypto.randomUUID();

export function resolve(
  base: Note | null, ours: Side | null, theirs: Side | null, newId: () => string = randomId,
): Result | null {
  if (ours == null && theirs == null) return null;
  if (theirs == null) return { note: ours!.note, deletedAt: ours!.deletedAt };
  if (ours == null) return { note: theirs.note, deletedAt: theirs.deletedAt };

  const o = ours.note;
  const t = theirs.note;
  if (o == null && t == null) return { note: null, deletedAt: Math.max(ours.deletedAt, theirs.deletedAt) };
  if (o == null) {                       // we deleted it, they still have it
    const editedAfter = (base == null || !sameContent(t!, base)) && t!.updatedAt > ours.deletedAt;
    return editedAfter ? { note: t, deletedAt: 0 } : { note: null, deletedAt: ours.deletedAt };
  }
  if (t == null) {                       // they deleted it, we still have it
    const editedAfter = (base == null || !sameContent(o, base)) && o.updatedAt > theirs.deletedAt;
    return editedAfter ? { note: o, deletedAt: 0 } : { note: null, deletedAt: theirs.deletedAt };
  }
  return both(base, o, t, newId);
}

function both(base: Note | null, o: Note, t: Note, newId: () => string): Result {
  if (sameContent(o, t)) {
    return {
      note: { ...newer(o, t), rev: Math.max(o.rev, t.rev), updatedAt: Math.max(o.updatedAt, t.updatedAt) },
      deletedAt: 0,
    };
  }
  if (base != null) {
    const oChanged = !sameContent(o, base);
    const tChanged = !sameContent(t, base);
    if (!oChanged) return { note: t, deletedAt: 0 };
    if (!tChanged) return { note: o, deletedAt: 0 };
    const merged = merge3(base, o, t);
    if (merged != null) return { note: merged, deletedAt: 0 };
  }
  // Both changed the same lines (or there is no common base): the newer one stays, the other is kept as a copy.
  const winner = newer(o, t);
  const loser = winner === o ? t : o;
  const images = distinct([...winner.images, ...loser.images]);
  const kept: Note = { ...winner, images, rev: Math.max(o.rev, t.rev) + 1, updatedAt: Math.max(o.updatedAt, t.updatedAt) };
  const copy: Note = {
    ...loser, id: newId(), title: (loser.title.trim() === "" ? "Untitled" : loser.title) + " (conflict)",
    images: [], pinned: false, rev: 1, createdAt: loser.updatedAt,
  };
  return { note: kept, deletedAt: 0, conflictCopy: copy };
}

const distinct = <T>(xs: T[]): T[] => [...new Set(xs)];

/** Merges non-clashing edits. Returns null when both sides changed the same part. */
function merge3(base: Note, o: Note, t: Note): Note | null {
  const title = merge1(base.title, o.title, t.title);
  if (title == null) return null;
  const lines = diff3(base.body.split("\n"), o.body.split("\n"), t.body.split("\n"));
  if (lines == null) return null;
  const newest = newer(o, t);
  const images = distinct([...o.images, ...t.images]).filter(
    // dropped on either side relative to the base means removed
    (img) => !(base.images.includes(img) && (!o.images.includes(img) || !t.images.includes(img))),
  );
  const pinned = o.pinned === t.pinned ? o.pinned : o.pinned !== base.pinned ? o.pinned : t.pinned;
  const secret = o.secret === t.secret ? o.secret : o.secret !== base.secret ? o.secret : t.secret;
  const category = secret ? "" : o.category === t.category ? o.category : o.category !== base.category ? o.category : t.category;
  return {
    ...newest, title, body: lines.join("\n"), images, pinned, secret, category,
    rev: Math.max(o.rev, t.rev) + 1, updatedAt: Math.max(o.updatedAt, t.updatedAt),
  };
}

function merge1(base: string, a: string, b: string): string | null {
  if (a === b) return a;
  if (a === base) return b;
  if (b === base) return a;
  return null;
}

// ---- line-based three-way merge (diff3) ----

interface Hunk { start: number; end: number; lines: string[] }          // replaces base[start until end]

function hunks(base: string[], other: string[]): Hunk[] | null {
  const n = base.length, m = other.length;
  if (n * m > 4_000_000) return null;                                    // too big to diff cheaply
  const dp: Int32Array[] = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--)
    dp[i][j] = base[i] === other[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const out: Hunk[] = [];
  let i = 0, j = 0;
  let hs = -1, hj = -1;                                                  // start of the current differing run
  const close = (bi: number, oj: number) => {
    if (hs >= 0) { out.push({ start: hs, end: bi, lines: other.slice(hj, oj) }); hs = -1; }
  };
  while (i < n || j < m) {
    if (i < n && j < m && base[i] === other[j]) { close(i, j); i++; j++; }
    else {
      if (hs < 0) { hs = i; hj = j; }
      if (i < n && (j >= m || dp[i + 1][j] >= dp[i][j + 1])) i++; else j++;
    }
  }
  close(n, m);
  return out;
}

const sameLines = (a: string[], b: string[]) => a.length === b.length && a.every((v, k) => v === b[k]);

/** Returns the merged lines, or null on a clash. */
export function diff3(base: string[], ours: string[], theirs: string[]): string[] | null {
  if (sameLines(ours, theirs)) return ours;
  if (sameLines(ours, base)) return theirs;
  if (sameLines(theirs, base)) return ours;
  const a = hunks(base, ours);
  if (a == null) return null;
  const b = hunks(base, theirs);
  if (b == null) return null;
  const out: string[] = [];
  let pos = 0, i = 0, j = 0;
  const apply = (h: Hunk) => { out.push(...base.slice(pos, h.start), ...h.lines); pos = h.end; };
  while (i < a.length || j < b.length) {
    const ha = a[i], hb = b[j];
    const sameSpot = ha != null && hb != null && ha.start === hb.start && (ha.start === ha.end || hb.start === hb.end);
    if (hb == null) { apply(ha); i++; }
    else if (ha == null) { apply(hb); j++; }
    else if (sameSpot || (ha.start < hb.end && hb.start < ha.end)) {
      if (ha.start === hb.start && ha.end === hb.end && sameLines(ha.lines, hb.lines)) { apply(ha); i++; j++; }
      else return null;
    }
    else if (ha.end <= hb.start) { apply(ha); i++; }
    else { apply(hb); j++; }
  }
  out.push(...base.slice(pos));
  return out;
}
