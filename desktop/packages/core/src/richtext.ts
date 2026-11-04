/**
 * The rich editor's model, ported from RichText.kt so both apps read and write a note's markdown the same way.
 * A note is a list of lines; each line is one paragraph with one kind (heading, list item, quote, code, ...).
 * Lists are flat, with a depth of 0–3, exactly like the phone; nothing is nested.
 *
 * `toMarkdown(parse(md))` is the phone's canonical form of `md`. Editors compare against it so a note that was
 * only opened keeps its exact original text.
 */
import { type Kind, bold as boldRe, boldItalic, code as codeRe, heading as headingRe, italic, link as linkRe, listItem, rule, strike as strikeRe, tableRow, tableSep } from "./markdown";

export interface Marks { bold?: boolean; italic?: boolean; strike?: boolean; code?: boolean; link?: string }
export interface Run extends Marks { text: string }

export type Line =
  | { type: "p"; runs: Run[] }
  | { type: "heading"; level: number; runs: Run[] }
  | { type: "item"; kind: Kind; level: number; checked: boolean; runs: Run[] }
  | { type: "quote"; runs: Run[] }
  /** One line of a fenced code block; `lang` is only kept on the block's first line. */
  | { type: "code"; lang: string; text: string }
  /** A line kept exactly as typed (a rule, or a table row without its separator line). */
  | { type: "raw"; text: string }
  /** Every row including the header (row 0), all the same width. Cell text is markdown. */
  | { type: "table"; rows: string[][] };

export const MAX_LEVEL = 3;

// ---- markdown -> lines ----

const PUA = 0xe000;
const escape = /\\([\\`*_{}[\]()#+\-.!>~|])/g;
// \w is ASCII-only here, as in Java's regex on the phone.
const underBold = /(?<![\w])__(?=\S)(.+?)(?<=\S)__(?![\w])/g;
const underItalic = /(?<![\w])_(?=[^\s_])([^_\n]+?)(?<=[^\s_])_(?![\w])/g;

function unescape(s: string): string {
  let out = "";
  for (const ch of s) {
    const c = ch.codePointAt(0)!;
    out += c >= PUA && c <= PUA + 127 ? String.fromCharCode(c - PUA) : ch;
  }
  return out;
}

/** Table rows padded to the same width. */
export function tableRows(rows: string[][]): string[][] {
  const r = rows.length ? rows : [[""]];
  const cols = Math.max(1, ...r.map((x) => x.length));
  return r.map((x) => Array.from({ length: cols }, (_, i) => x[i] ?? ""));
}

function splitRow(line: string): string[] {
  let t = line.trim();
  if (t.startsWith("|")) t = t.slice(1);
  if (t.endsWith("|") && !t.endsWith("\\|")) t = t.slice(0, -1);
  return t.split(/(?<!\\)\|/).map((c) => c.replace(/\\\|/g, "|").trim());
}

/** Inline markdown to text runs. Headings never carry bold (they are bold already). */
export function parseInline(src: string, allowBold = true): Run[] {
  let text = "";
  const recs: { m: Marks; s: number; e: number }[] = [];
  const kinds: [number, RegExp][] = [[1, codeRe], [8, boldItalic], [2, boldRe], [3, strikeRe], [4, italic], [5, linkRe], [6, underBold], [7, underItalic]];
  const go = (s: string) => {
    let pos = 0;
    while (pos < s.length) {
      let best: RegExpExecArray | null = null, kind = 0;
      for (const [k, re] of kinds) {
        re.lastIndex = pos;
        const m = re.exec(s);
        if (m && (!best || m.index < best.index)) { best = m; kind = k; }
      }
      if (!best) { text += s.slice(pos); break; }
      text += s.slice(pos, best.index);
      const start = text.length;
      if (kind === 1) { text += best[1]; recs.push({ m: { code: true }, s: start, e: text.length }); }
      else {
        go(best[1]);
        const e = text.length;
        if (kind === 2 || kind === 6) { if (allowBold) recs.push({ m: { bold: true }, s: start, e }); }
        else if (kind === 3) recs.push({ m: { strike: true }, s: start, e });
        else if (kind === 8) { if (allowBold) recs.push({ m: { bold: true }, s: start, e }); recs.push({ m: { italic: true }, s: start, e }); }
        else if (kind === 4 || kind === 7) recs.push({ m: { italic: true }, s: start, e });
        else recs.push({ m: { link: unescape(best[2]) }, s: start, e });
      }
      pos = best.index + best[0].length;
    }
  };
  go(src.replace(escape, (_, c: string) => String.fromCharCode(PUA + c.charCodeAt(0))));
  const plain = unescape(text);
  // Cut the text wherever a style starts or ends.
  const cuts = new Set([0, plain.length]);
  for (const r of recs) if (r.e > r.s) { cuts.add(r.s); cuts.add(r.e); }
  const pts = [...cuts].sort((a, b) => a - b);
  const runs: Run[] = [];
  for (let i = 0; i < pts.length - 1; i++) {
    const a = pts[i], b = pts[i + 1];
    if (a === b) continue;
    const run: Run = { text: plain.slice(a, b) };
    for (const r of recs) if (r.e > r.s && r.s <= a && r.e >= b) Object.assign(run, r.m);
    runs.push(run);
  }
  return runs;
}

export function parse(md: string): Line[] {
  const out: Line[] = [];
  let fence: string | null = null, fenceFirst = false;
  const lines = md.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const t = line.trimStart();
    if (t.startsWith("```")) {
      if (fence == null) { fence = t.slice(3).trim(); fenceFirst = true; } else fence = null;
      continue;
    }
    if (fence != null) {
      out.push({ type: "code", lang: fenceFirst ? fence : "", text: line });
      fenceFirst = false;
      continue;
    }
    if (tableRow.test(line) && i + 1 < lines.length && tableSep.test(lines[i + 1])) {
      const rows = [splitRow(line)];
      let j = i + 2;
      while (j < lines.length && tableRow.test(lines[j])) rows.push(splitRow(lines[j++]));
      out.push({ type: "table", rows: tableRows(rows) });
      i = j - 1;
      continue;
    }
    const h = headingRe.exec(line);
    const li = listItem(line);
    if (rule.test(line) || tableRow.test(line)) out.push({ type: "raw", text: line });
    else if (h) out.push({ type: "heading", level: h[1].length, runs: parseInline(h[3], false) });
    else if (li) out.push({
      type: "item", kind: li.kind, checked: li.checked, runs: parseInline(line.slice(li.length)),
      level: Math.min(Math.floor(li.indent.replace(/\t/g, "  ").length / 2), MAX_LEVEL),
    });
    else if (line.startsWith(">")) out.push({ type: "quote", runs: parseInline(line.replace(/^>/, "").replace(/^ /, "")) });
    else out.push({ type: "p", runs: parseInline(line) });
  }
  return out;
}

// ---- lines -> markdown ----

const blank = (s: string) => /^\s*$/.test(s);
const letterOrDigit = (c: string) => /[\p{L}\p{Nd}]/u.test(c);

/** Escapes characters that would be read as formatting. `ahead` is the rest of the line, to look for "](". */
function esc(s: string, ahead: string): string {
  let out = "";
  for (let i = 0; i < s.length; i++) {
    const ch = s[i], prev = i > 0 ? s[i - 1] : " ", next = i + 1 < s.length ? s[i + 1] : " ";
    if (ch === "\\" || ch === "*" || ch === "`") out += "\\" + ch;
    else if (ch === "~" && (next === "~" || prev === "~")) out += "\\" + ch;
    else if (ch === "_" && (!letterOrDigit(prev) || !letterOrDigit(next))) out += "\\" + ch;
    else if (ch === "[" && (s.indexOf("](", i) >= 0 || ahead.includes("]("))) out += "\\" + ch;
    else out += ch;
  }
  return out;
}

export function inlineToMarkdown(runs: Run[], allowBold = true): string {
  const full = runs.map((r) => r.text).join("");
  let out = "";
  const open: string[] = [], closers: string[] = [];
  const close = (downTo: number) => {
    while (open.length > downTo) {
      let ws = out.length;
      while (ws > 0 && /\s/.test(out[ws - 1])) ws--;
      const spaces = out.slice(ws);
      out = out.slice(0, ws) + closers.pop()! + spaces;
      open.pop();
    }
  };
  let at = 0;
  for (const r of runs) {
    const text = r.text;
    at += text.length;
    if (!text) continue;
    const want: [string, string][] = [];
    if (r.link != null) want.push(["[", `](${r.link})`]);
    if (r.strike) want.push(["~~", "~~"]);
    if (allowBold && r.bold) want.push(["**", "**"]);
    if (r.italic) want.push(["*", "*"]);
    if (r.code) want.push(["`", "`"]);
    const ahead = full.slice(at);
    if (blank(text)) { out += r.code ? text : esc(text, ahead); continue; }   // blank gaps stay inside whatever is open
    let common = 0;
    while (common < open.length && common < want.length && open[common] === want[common][0] + "@" + want[common][1]) common++;
    close(common);
    const lead = /^\s*/.exec(text)![0];
    let body = text.slice(lead.length);
    if (open.length < want.length) out += lead; else body = text;
    for (let k = open.length; k < want.length; k++) { out += want[k][0]; open.push(want[k][0] + "@" + want[k][1]); closers.push(want[k][1]); }
    out += r.code ? body : esc(body, ahead);
  }
  close(0);
  return out;
}

const lineStartTrap = /^(\s*)(#{1,6}\s|[-*+]\s|\d{1,3}[.)]\s|>|```|([-*_])(\s*\3){2,}\s*$)/;

/** A plain line that would be read as a heading, list or quote gets its first marker character escaped. */
function guardLineStart(s: string): string {
  const m = lineStartTrap.exec(s);
  if (!m) return s;
  const at = m[1].length;
  if (/\d/.test(s[at])) { const dot = s.search(/[.)]/); return s.slice(0, dot) + "\\" + s.slice(dot); }
  return s.slice(0, at) + "\\" + s.slice(at);
}

const joinRow = (cells: string[]) => "| " + cells.map((c) => c.replace(/\|/g, "\\|").replace(/\n/g, " ")).join(" | ") + " |";

/** Which number a numbered line shows: the unbroken run of numbered lines above it at the same depth. */
export function numberOf(lines: Line[], i: number): number {
  const me = lines[i];
  if (me.type !== "item") return 1;
  let n = 1;
  for (let j = i - 1; j >= 0; j--) {
    const s = lines[j];
    if (s.type !== "item") break;
    if (s.level > me.level) continue;                 // a nested item: skip over it
    if (s.level < me.level || s.kind !== "number") break;
    n++;
  }
  return n;
}

export function toMarkdown(lines: Line[]): string {
  const out: string[] = [];
  let inFence = false;
  lines.forEach((l, i) => {
    if (l.type === "code") {
      if (!inFence) { out.push("```" + l.lang); inFence = true; }
      out.push(l.text);
      return;
    }
    if (inFence) { out.push("```"); inFence = false; }
    switch (l.type) {
      case "table": {
        const rows = tableRows(l.rows);
        out.push(joinRow(rows[0]), "| " + rows[0].map(() => "---").join(" | ") + " |", ...rows.slice(1).map(joinRow));
        break;
      }
      case "raw": out.push(l.text); break;
      case "heading": out.push("#".repeat(l.level) + " " + inlineToMarkdown(l.runs, false)); break;
      case "item": {
        const marker = l.kind === "task" ? (l.checked ? "- [x] " : "- [ ] ") : l.kind === "bullet" ? "- " : `${numberOf(lines, i)}. `;
        out.push("  ".repeat(l.level) + marker + inlineToMarkdown(l.runs));
        break;
      }
      case "quote": out.push("> " + inlineToMarkdown(l.runs)); break;
      default: out.push(guardLineStart(inlineToMarkdown(l.runs)));
    }
  });
  if (inFence) out.push("```");
  return out.join("\n");
}

/** How the phone would write `md` back after reading it. */
export const canonical = (md: string) => toMarkdown(parse(md));
