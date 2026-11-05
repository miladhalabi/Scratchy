/**
 * A note as the phone's grid shows it (Note.heading, Note.preview, Markdown.render): the heading on top, then the
 * rest as one flowing text with the formatting drawn and the markdown symbols hidden.
 */
import { type Line, type Note, type Run, noteHeading, numberOf, parse, parseInline, tableRows } from "@scratchy/core";

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

function runs(rs: Run[]): string {
  return rs.map((r) => {
    let h = escapeHtml(r.text);
    if (r.code) h = `<code>${h}</code>`;
    if (r.italic) h = `<em>${h}</em>`;
    if (r.bold) h = `<strong>${h}</strong>`;
    if (r.strike) h = `<s>${h}</s>`;
    if (r.link != null) h = `<u class="lnk">${h}</u>`;
    return h;
  }).join("");
}

const SIZES = [1.4, 1.25, 1.12, 1.05, 1, 1];

function line(l: Line, all: Line[], i: number): string {
  switch (l.type) {
    case "heading": return `<strong style="font-size:${SIZES[l.level - 1]}em">${runs(l.runs)}</strong>`;
    case "item": {
      const pad = "  ".repeat(l.level);
      if (l.kind === "task") return `${pad}<span class="box${l.checked ? " on" : ""}"></span> ${l.checked ? `<s class="done">${runs(l.runs)}</s>` : runs(l.runs)}`;
      return `${pad}${l.kind === "bullet" ? "•" : `${numberOf(all, i)}.`} ${runs(l.runs)}`;
    }
    case "quote": return `<span class="dim">│ </span><em>${runs(l.runs)}</em>`;
    case "code": return `<code>${escapeHtml(l.text)}</code>`;
    case "raw": return /^\s*([-*_])(\s*\1){2,}\s*$/.test(l.text) ? `<span class="dim">────────────</span>` : escapeHtml(l.text);
    case "table": {
      return tableRows(l.rows)
        .map((r) => r.filter((c) => c.trim()))
        .filter((r) => r.length)                                       // empty rows aren't worth a line
        .map((r, ri) => { const t = r.map((c) => runs(parseInline(c))).join("  ·  "); return ri === 0 ? `<strong>${t}</strong>` : t; })
        .join("\n");
    }
    default: return runs(l.runs);
  }
}

/**
 * Markdown rendered for reading, at most `maxChars` of it (the phone takes 1500). Remembered by its text: the
 * notes list draws every card again after each sync, and almost all of them are unchanged.
 */
const rendered = new Map<string, string>();
export function render(md: string, maxChars = 1500): string {
  const text = md.slice(0, maxChars);
  let html = rendered.get(text);
  if (html === undefined) {
    const all = parse(text);
    html = all.map((l, i) => line(l, all, i)).join("\n");
    if (rendered.size >= 1000) rendered.delete(rendered.keys().next().value!);   // the oldest goes first
    rendered.set(text, html);
  }
  return html;
}

/** The text under the heading: the whole body, or without its first line when that line is the heading. */
export function cardPreview(n: Note): string {
  if (n.title.trim()) return n.body.trim();
  const lines = n.body.split("\n");
  const first = lines.findIndex((l) => l.trim());
  return first < 0 ? "" : lines.slice(first + 1).join("\n").trim();
}

export const cardHeading = (n: Note) => noteHeading(n) || "Untitled";
