import type { JSONContent } from "@tiptap/core";
import type { Node as PMNode } from "@tiptap/pm/model";
import { type Line, type Run, parse, toMarkdown } from "@scratchy/core";

/** Inline runs to Tiptap text nodes. */
function textNodes(runs: Run[]): JSONContent[] {
  return runs.filter((r) => r.text).map((r) => {
    const marks: JSONContent["marks"] = [];
    if (r.link != null) marks.push({ type: "link", attrs: { href: r.link } });
    if (r.strike) marks.push({ type: "strike" });
    if (r.bold) marks.push({ type: "bold" });
    if (r.italic) marks.push({ type: "italic" });
    if (r.code) marks.push({ type: "code" });
    return marks.length ? { type: "text", text: r.text, marks } : { type: "text", text: r.text };
  });
}

/** The note's lines as the editor's document. Consecutive code lines are one code block, like one fence. */
export function linesToDoc(lines: Line[]): JSONContent {
  const content: JSONContent[] = [];
  for (const l of lines) {
    switch (l.type) {
      case "p": content.push({ type: "paragraph", content: textNodes(l.runs) }); break;
      case "heading": content.push({ type: "heading", attrs: { level: l.level }, content: textNodes(l.runs) }); break;
      case "item": content.push({ type: "item", attrs: { kind: l.kind, level: l.level, checked: l.checked }, content: textNodes(l.runs) }); break;
      case "quote": content.push({ type: "quote", content: textNodes(l.runs) }); break;
      case "raw": content.push({ type: "raw", content: l.text ? [{ type: "text", text: l.text }] : [] }); break;
      case "table": content.push({ type: "table", attrs: { rows: l.rows } }); break;
      case "code": {
        const last = content[content.length - 1];
        if (last?.type === "codeBlock" && !l.lang) {
          const t = (last.content?.[0]?.text ?? "") + "\n" + l.text;
          last.content = [{ type: "text", text: t }];
        } else content.push({ type: "codeBlock", attrs: { language: l.lang || null }, content: l.text ? [{ type: "text", text: l.text }] : [] });
      }
    }
  }
  if (!content.length) content.push({ type: "paragraph" });
  return { type: "doc", content };
}

function runsOf(block: PMNode): Run[] {
  const runs: Run[] = [];
  block.forEach((n) => {
    if (!n.isText) return;
    const r: Run = { text: n.text ?? "" };
    for (const m of n.marks) {
      if (m.type.name === "bold") r.bold = true;
      else if (m.type.name === "italic") r.italic = true;
      else if (m.type.name === "strike") r.strike = true;
      else if (m.type.name === "code") r.code = true;
      else if (m.type.name === "link") r.link = m.attrs.href ?? "";
    }
    runs.push(r);
  });
  return runs;
}

export function docToLines(doc: PMNode): Line[] {
  const out: Line[] = [];
  doc.forEach((b) => {
    switch (b.type.name) {
      case "heading": out.push({ type: "heading", level: b.attrs.level, runs: runsOf(b) }); break;
      case "item": out.push({ type: "item", kind: b.attrs.kind, level: b.attrs.level, checked: b.attrs.checked, runs: runsOf(b) }); break;
      case "quote": out.push({ type: "quote", runs: runsOf(b) }); break;
      case "raw": out.push({ type: "raw", text: b.textContent }); break;
      case "table": out.push({ type: "table", rows: b.attrs.rows }); break;
      case "codeBlock":
        b.textContent.split("\n").forEach((text, i) => out.push({ type: "code", lang: i === 0 ? b.attrs.language ?? "" : "", text }));
        break;
      default: out.push({ type: "p", runs: runsOf(b) });
    }
  });
  return out;
}

export const markdownToDoc = (md: string) => linesToDoc(parse(md));
export const docToMarkdown = (doc: PMNode) => toMarkdown(docToLines(doc));
