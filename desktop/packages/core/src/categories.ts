/** Category names, the same rules as Categories.kt on Android. */
import type { Note } from "./model";

export const CATEGORY_MAX = 30;

/** Trims, drops line breaks and quotes, caps the length. */
export function cleanCategory(raw: string): string {
  return raw.replace(/\n/g, " ").trim().replace(/^["'., ]+|["'., ]+$/g, "").slice(0, CATEGORY_MAX).trim();
}

/** The spelling already in use when [raw] only differs in case, so "work" never becomes a second "Work". */
export function canonicalCategory(raw: string, existing: Iterable<string>): string {
  const c = cleanCategory(raw);
  for (const e of existing) if (e.toLowerCase() === c.toLowerCase()) return e;
  return c;
}

/** Names in use, most used first. */
export function categoryNames(notes: Note[]): string[] {
  const counts = new Map<string, number>();
  for (const n of notes) if (!n.secret && n.category.trim()) counts.set(n.category, (counts.get(n.category) ?? 0) + 1);
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || (a[0].toLowerCase() < b[0].toLowerCase() ? -1 : a[0].toLowerCase() > b[0].toLowerCase() ? 1 : 0))
    .map(([k]) => k);
}
