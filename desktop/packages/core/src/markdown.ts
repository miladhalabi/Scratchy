/**
 * Notes are markdown. The text stored (and synced, and copied out) is exactly what was typed or pasted.
 * A port of the pure parts of Markdown.kt on Android, so both sides read and write lists the same way.
 */
import type { Note } from "./model";

// ---- lists ----

export type Kind = "task" | "bullet" | "number";

/** The start of a list line: indent, the marker, and how many characters the whole prefix takes. */
export interface Item { indent: string; kind: Kind; marker: string; checked: boolean; number: number; length: number }

const item = /^([ \t]*)(?:([-*+])\s+\[([ xX])]\s|([-*+])\s|(\d{1,3})[.)]\s)/;

export function listItem(line: string): Item | null {
  const m = item.exec(line);
  if (!m) return null;
  const indent = m[1];
  if (m[2] != null) return { indent, kind: "task", marker: m[2], checked: m[3] !== " ", number: 0, length: m[0].length };
  if (m[4] != null) return { indent, kind: "bullet", marker: m[4], checked: false, number: 0, length: m[0].length };
  return { indent, kind: "number", marker: "", checked: false, number: Number(m[5]), length: m[0].length };
}

/** What the next line of the list starts with. */
export function nextItem(it: Item): string {
  switch (it.kind) {
    case "task": return `${it.indent}${it.marker} [ ] `;
    case "bullet": return `${it.indent}${it.marker} `;
    case "number": return `${it.indent}${it.number + 1}. `;
  }
}

/** Index of the character inside "[ ]" of a task line, or -1. */
export function checkIndex(line: string): number {
  const it = listItem(line);
  if (!it || it.kind !== "task") return -1;
  return line.indexOf("[", it.indent.length) + 1;
}

/** The line with its checkbox flipped (non-task lines come back unchanged). */
export function toggled(line: string): string {
  const i = checkIndex(line);
  if (i < 0) return line;
  return line.slice(0, i) + (line[i] === " " ? "x" : " ") + line.slice(i + 1);
}

/** The checklist button: plain or bullet line becomes a task, a task becomes a plain line. */
export function toggleTaskLine(line: string): string {
  const it = listItem(line);
  if (!it) {
    const ind = /^[ \t]*/.exec(line)![0];
    return ind + "- [ ] " + line.slice(ind.length);
  }
  if (it.kind === "task") return it.indent + line.slice(it.length);
  return it.indent + "- [ ] " + line.slice(it.length);
}

/** Notes saved before markdown used ☐ / ☑ at the start of a line. Idempotent. */
export function legacy(body: string): string {
  if (!body.includes("☐") && !body.includes("☑")) return body;
  return body.split("\n").map((l) => {
    if (l.startsWith("☐ ") || l === "☐") return "- [ ] " + l.slice(2);
    if (l.startsWith("☑ ") || l === "☑") return "- [x] " + l.slice(2);
    return l;
  }).join("\n");
}

// ---- comparing texts ignoring the markdown on them ----

const wordRe = /[\p{L}\p{M}\p{N}]+/gu;

/** Just the words of a text, with all markdown symbols, list markers and link addresses left out. */
export function words(md: string): string[] {
  let t = md.replace(/]\([^)\s]*\)/g, "]");
  t = t.replace(/^\s*[-*+]\s+\[[ xX]]\s/gm, "");
  t = t.replace(/^\s*\d{1,3}[.)]\s/gm, "");
  return t.match(wordRe) ?? [];
}

/** Every letter, mark and digit of a text joined together: equal for two texts that differ only in spacing or markdown. */
export const letters = (md: string) => words(md).join("");

/** Where two texts first differ (as short snippets), or null when their letters are the same. */
export function firstDifference(original: string, other: string): [string, string] | null {
  const a = letters(original), b = letters(other);
  if (a === b) return null;
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i++;
  const snip = (s: string) => (i > 6 ? "…" : "") + (s.slice(Math.max(i - 6, 0), Math.min(i + 14, s.length)) || "(nothing)");
  return [snip(a), snip(b)];
}

// ---- plain text of one line (note headings, pickers) ----

export const heading = /^(#{1,6})(\s+)(.*)$/;
export const boldItalic = /\*\*\*(?=\S)(.+?)(?<=\S)\*\*\*/g;
export const bold = /\*\*(?=\S)(.+?)(?<=\S)\*\*/g;
export const italic = /(?<![*\w])\*(?=[^\s*])([^*\n]+?)(?<=[^\s*])\*(?![*\w])/g;
export const strike = /~~(?=\S)(.+?)(?<=\S)~~/g;
export const code = /`([^`\n]+)`/g;
export const link = /\[([^\]\n]+)]\(([^)\s]+)\)/g;
export const rule = /^\s*([-*_])(\s*\1){2,}\s*$/;
export const tableRow = /^\s*\|.*\|\s*$/;
export const tableSep = /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/;

export function plain(line: string): string {
  let t = line.trim();
  const h = heading.exec(t);
  if (h) t = h[3];
  const li = listItem(t);
  if (li) t = t.slice(li.length);
  t = t.replace(/^>\s?/, "");
  t = t.replace(link, "$1").replace(boldItalic, "$1").replace(bold, "$1").replace(strike, "$1");
  t = t.replace(code, "$1").replace(italic, "$1");
  return t.trim();
}

/** Title, or the first non-blank body line (without its markdown) when there is no title. */
export function noteHeading(n: Note): string {
  const title = n.title.trim();
  if (title) return title;
  const line = n.body.split("\n").find((l) => l.trim() !== "")?.trim() ?? "";
  return plain(line) || line;
}
