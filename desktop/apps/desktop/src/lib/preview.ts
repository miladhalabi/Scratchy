/** A note's body drawn for reading (grid cards): formatting shown, symbols hidden, like Markdown.render on the phone. */
import { type Line, type Run, numberOf, parse, tableRows, parseInline } from "@scratchy/core";

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

function runs(rs: Run[]): string {
  return rs.map((r) => {
    let h = escapeHtml(r.text);
    if (r.code) h = `<code>${h}</code>`;
    if (r.italic) h = `<em>${h}</em>`;
    if (r.bold) h = `<strong>${h}</strong>`;
    if (r.strike) h = `<s>${h}</s>`;
    if (r.link != null) h = `<span class="lnk">${h}</span>`;
    return h;
  }).join("");
}

function line(l: Line, all: Line[], i: number): string {
  switch (l.type) {
    case "heading": return `<div class="h h${Math.min(l.level, 3)}" dir="auto">${runs(l.runs)}</div>`;
    case "item": {
      const mark = l.kind === "task" ? `<span class="box${l.checked ? " on" : ""}"></span>`
        : l.kind === "bullet" ? `<span class="dot">•</span>` : `<span class="num">${numberOf(all, i)}.</span>`;
      return `<div class="li${l.checked ? " done" : ""}" dir="auto" style="--lvl:${l.level}">${mark}<span>${runs(l.runs)}</span></div>`;
    }
    case "quote": return `<div class="q" dir="auto">${runs(l.runs)}</div>`;
    case "code": return `<div class="code">${escapeHtml(l.text) || "&nbsp;"}</div>`;
    case "raw": return /^\s*([-*_])(\s*\1){2,}\s*$/.test(l.text) ? `<hr>` : `<div class="code">${escapeHtml(l.text)}</div>`;
    case "table": {
      const rows = tableRows(l.rows);
      return `<div class="tb">${rows.map((r, ri) => `<div class="${ri === 0 ? "th" : "tr"}" dir="auto">${r.filter((c) => c.trim()).map((c) => runs(parseInline(c))).join("  ·  ")}</div>`).join("")}</div>`;
    }
    default: return `<div dir="auto">${runs(l.runs) || "&nbsp;"}</div>`;
  }
}

/** HTML for the first `max` lines worth showing (blank lines at the start and end left out). */
export function preview(md: string, max = 10): string {
  const all = parse(md);
  let a = 0, b = all.length;
  const blank = (l: Line) => l.type === "p" && l.runs.every((r) => !r.text.trim());
  while (a < b && blank(all[a])) a++;
  while (b > a && blank(all[b - 1])) b--;
  const out: string[] = [];
  for (let i = a; i < b && out.length < max; i++) out.push(line(all[i], all, i));
  return out.join("");
}
