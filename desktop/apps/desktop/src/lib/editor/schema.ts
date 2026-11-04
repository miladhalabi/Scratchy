/**
 * The editor's building blocks, shaped like the phone's RichText: one block per line, lists are flat lines with a
 * depth (0–3), a numbered line counts the run above it, a table is one block edited in its own sheet.
 */
import { Extension, InputRule, Node, mergeAttributes, type Editor } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import Code from "@tiptap/extension-code";
import { Plugin, PluginKey, TextSelection, type EditorState } from "@tiptap/pm/state";
import { Decoration, DecorationSet, type EditorView } from "@tiptap/pm/view";
import { Fragment, Slice, type Node as PMNode } from "@tiptap/pm/model";
import { MAX_LEVEL, parse, parseInline, tableRows } from "@scratchy/core";
import { docToMarkdown, linesToDoc } from "./convert";

const INDENT = 22, MARKER = 26, NUMBER_MARKER = 30;

export const Item = Node.create({
  name: "item",
  group: "block",
  content: "inline*",
  defining: true,
  addAttributes() {
    return {
      kind: { default: "bullet" },
      level: { default: 0 },
      checked: { default: false },
    };
  },
  parseHTML() { return [{ tag: "div.li" }]; },
  renderHTML({ node, HTMLAttributes }) {
    const { kind, level, checked } = node.attrs;
    return ["div", mergeAttributes(HTMLAttributes, {
      class: "li", dir: "auto", "data-kind": kind, "data-level": level, "data-checked": checked ? "true" : "false",
      style: `--indent:${level * INDENT}px;--marker:${kind === "number" ? NUMBER_MARKER : MARKER}px`,
    }), 0];
  },
});

export const Quote = Node.create({
  name: "quote",
  group: "block",
  content: "inline*",
  defining: true,
  parseHTML() { return [{ tag: "div.quote" }]; },
  renderHTML({ HTMLAttributes }) { return ["div", mergeAttributes(HTMLAttributes, { class: "quote", dir: "auto" }), 0]; },
});

/** A line kept exactly as typed: a rule, or a table row without its separator. */
export const Raw = Node.create({
  name: "raw",
  group: "block",
  content: "text*",
  marks: "",
  code: true,
  parseHTML() { return [{ tag: "div.raw" }]; },
  renderHTML({ HTMLAttributes }) { return ["div", mergeAttributes(HTMLAttributes, { class: "raw", dir: "auto" }), 0]; },
});

export interface TableOptions {
  /** Opens the table sheet; `done` gets the new rows, or null to delete the table. */
  edit: (rows: string[][], done: (rows: string[][] | null) => void) => void;
}

/** Cell text is markdown: drawn with its bold and italic, without the symbols. */
function cellHTML(md: string, el: HTMLElement) {
  for (const r of parseInline(md)) {
    let n: HTMLElement | Text = document.createTextNode(r.text);
    for (const [on, tag] of [[r.code, "code"], [r.italic, "em"], [r.bold, "strong"], [r.strike, "s"]] as const) {
      if (on) { const w = document.createElement(tag); w.appendChild(n); n = w; }
    }
    el.appendChild(n);
  }
}

export const Table = Node.create<TableOptions>({
  name: "table",
  group: "block",
  atom: true,
  selectable: true,
  addOptions() { return { edit: () => {} }; },
  addAttributes() { return { rows: { default: [[""]] } }; },
  parseHTML() { return [{ tag: "div.tbl" }]; },
  renderHTML({ HTMLAttributes }) { return ["div", mergeAttributes(HTMLAttributes, { class: "tbl" })]; },
  addNodeView() {
    return ({ node, getPos, editor }) => {
      const dom = document.createElement("div");
      dom.className = "tbl";
      dom.contentEditable = "false";
      let rows: string[][] = node.attrs.rows;
      const draw = () => {
        dom.textContent = "";
        const t = document.createElement("table");
        tableRows(rows).forEach((row, ri) => {
          const tr = t.insertRow();
          for (const c of row) {
            const cell = document.createElement(ri === 0 ? "th" : "td");
            cell.dir = "auto";
            cellHTML(c, cell);
            tr.appendChild(cell);
          }
        });
        dom.appendChild(t);
      };
      draw();
      dom.addEventListener("click", (e) => {
        if (!editor.isEditable) return;
        e.preventDefault();
        this.options.edit(rows, (next) => {
          const pos = typeof getPos === "function" ? getPos() : undefined;
          if (pos == null) return;
          const tr = editor.state.tr;
          if (next) tr.setNodeMarkup(pos, undefined, { rows: tableRows(next) });
          else tr.delete(pos, pos + node.nodeSize);
          editor.view.dispatch(tr);
          editor.commands.focus();
        });
      });
      return {
        dom,
        update(n) {
          if (n.type.name !== "table") return false;
          rows = n.attrs.rows;
          draw();
          return true;
        },
        stopEvent: () => true,
        ignoreMutation: () => true,
      };
    };
  },
});

// ---- typing like the phone ----

const isPlain = (n: PMNode) => n.type.name === "paragraph";

/** "- " at the start of a plain line becomes a bullet, "# " a heading, and so on (RichText.shortcut). */
function shortcut(find: RegExp, make: (m: RegExpMatchArray) => { type: string; attrs?: Record<string, unknown> }) {
  return new InputRule({
    find,
    handler: ({ state, range, match }) => {
      const $from = state.doc.resolve(range.from);
      if (!isPlain($from.parent) || range.from !== $from.start()) return null;
      const { type, attrs } = make(match);
      state.tr.delete(range.from, range.to).setBlockType(range.from, range.from, state.schema.nodes[type], attrs);
    },
  });
}

/** Number shown by each numbered line: the unbroken run of numbered lines above it at the same depth. */
function numbers(doc: PMNode): Map<number, number> {
  const out = new Map<number, number>();
  const blocks: { pos: number; node: PMNode }[] = [];
  doc.forEach((node, pos) => blocks.push({ pos, node }));
  blocks.forEach(({ pos, node }, i) => {
    if (node.type.name !== "item" || node.attrs.kind !== "number") return;
    let n = 1;
    for (let j = i - 1; j >= 0; j--) {
      const s = blocks[j].node;
      if (s.type.name !== "item") break;
      if (s.attrs.level > node.attrs.level) continue;
      if (s.attrs.level < node.attrs.level || s.attrs.kind !== "number") break;
      n++;
    }
    out.set(pos, n);
  });
  return out;
}

function decorations(state: EditorState, placeholder: string): DecorationSet {
  const decos: Decoration[] = [];
  for (const [pos, n] of numbers(state.doc)) {
    const node = state.doc.nodeAt(pos)!;
    decos.push(Decoration.node(pos, pos + node.nodeSize, { "data-n": `${n}.` }));
  }
  const first = state.doc.firstChild;
  if (placeholder && state.doc.childCount === 1 && first && isPlain(first) && first.content.size === 0) {
    decos.push(Decoration.node(0, first.nodeSize, { class: "empty", "data-placeholder": placeholder }));
  }
  return DecorationSet.create(state.doc, decos);
}

/** Is a click on a task's checkbox? (The box sits in the line's leading margin, on the right for RTL text.) */
function onBox(el: HTMLElement, e: MouseEvent): boolean {
  const r = el.getBoundingClientRect();
  const level = Number(el.dataset.level ?? 0);
  const from = level * INDENT - 4, to = level * INDENT + MARKER + 2;
  const d = getComputedStyle(el).direction === "rtl" ? r.right - e.clientX : e.clientX - r.left;
  return d >= from && d <= to && e.clientY >= r.top && e.clientY <= r.top + 30;
}

function setLevel(state: EditorState, by: number) {
  const { from, to } = state.selection;
  const tr = state.tr;
  state.doc.nodesBetween(from, to, (node, pos) => {
    if (node.type.name === "item") {
      tr.setNodeMarkup(pos, undefined, { ...node.attrs, level: Math.min(Math.max(node.attrs.level + by, 0), MAX_LEVEL) });
    }
    return node.type.name === "doc";
  });
  return tr;
}

export const Behaviour = Extension.create<{ placeholder: string }>({
  name: "behaviour",
  addOptions() { return { placeholder: "" }; },
  addInputRules() {
    return [
      shortcut(/^[-*+] $/, () => ({ type: "item", attrs: { kind: "bullet", level: 0, checked: false } })),
      shortcut(/^\[\] $/, () => ({ type: "item", attrs: { kind: "task", level: 0, checked: false } })),
      shortcut(/^\d{1,3}\. $/, () => ({ type: "item", attrs: { kind: "number", level: 0, checked: false } })),
      shortcut(/^> $/, () => ({ type: "quote" })),
      shortcut(/^(#{1,3}) $/, (m) => ({ type: "heading", attrs: { level: m[1].length } })),
    ];
  },
  addKeyboardShortcuts() {
    return {
      // Enter continues a list or quote; on an empty item it backs out one level, then ends the list.
      Enter: ({ editor }) => {
        const { state, view } = editor;
        const { $from } = state.selection;
        const node = $from.parent;
        const name = node.type.name;
        if (name !== "item" && name !== "quote") return false;
        const at = $from.before();
        if (node.content.size === 0) {
          const tr = state.tr;
          if (name === "item" && node.attrs.level > 0) tr.setNodeMarkup(at, undefined, { ...node.attrs, level: node.attrs.level - 1 });
          else tr.setNodeMarkup(at, state.schema.nodes.paragraph);
          view.dispatch(tr);
          return true;
        }
        const tr = state.tr.deleteSelection();
        tr.split(tr.selection.from, 1, [{ type: node.type, attrs: name === "item" ? { ...node.attrs, checked: false } : {} }]);
        view.dispatch(tr.scrollIntoView());
        return true;
      },
      // Backspace at the very start of a formatted line removes the format (one list level first).
      Backspace: ({ editor }) => {
        const { state, view } = editor;
        const { $from, empty } = state.selection;
        if (!empty || $from.parentOffset !== 0) return false;
        const node = $from.parent;
        const at = $from.before();
        if (node.type.name === "item" && node.attrs.level > 0) {
          view.dispatch(state.tr.setNodeMarkup(at, undefined, { ...node.attrs, level: node.attrs.level - 1 }));
          return true;
        }
        if (["item", "heading", "quote", "raw"].includes(node.type.name)) {
          view.dispatch(state.tr.setNodeMarkup(at, state.schema.nodes.paragraph));
          return true;
        }
        return false;
      },
      Tab: ({ editor }) => { editor.view.dispatch(setLevel(editor.state, 1)); return true; },
      "Shift-Tab": ({ editor }) => { editor.view.dispatch(setLevel(editor.state, -1)); return true; },
    };
  },
  addProseMirrorPlugins() {
    const placeholder = this.options.placeholder;
    return [
      new Plugin({
        key: new PluginKey("scratchy-lines"),
        props: {
          decorations: (state) => decorations(state, placeholder),
          handleDOMEvents: {
            // A click on a task's box ticks it without moving the cursor there.
            mousedown: (view: EditorView, e: MouseEvent) => {
              const el = (e.target as HTMLElement).closest?.<HTMLElement>('.li[data-kind="task"]');
              if (!el || !view.editable || !onBox(el, e)) return false;
              const pos = view.posAtDOM(el, 0) - 1;
              const node = view.state.doc.nodeAt(pos);
              if (node?.type.name !== "item") return false;
              e.preventDefault();
              view.dispatch(view.state.tr.setNodeMarkup(pos, undefined, { ...node.attrs, checked: !node.attrs.checked }));
              return true;
            },
          },
          // Copied text leaves as markdown, the way the note is stored.
          clipboardTextSerializer: (slice, view) => {
            try { return docToMarkdown(view.state.schema.topNodeType.create(null, slice.content)); }
            catch { return slice.content.textBetween(0, slice.content.size, "\n"); }
          },
          // Pasted text is read as markdown, so an answer copied from an AI app arrives formatted (insertMarkdown).
          handlePaste: (view, e) => {
            const text = e.clipboardData?.getData("text/plain");
            if (!text || !view.editable) return false;
            if (view.state.selection.$from.parent.type.name === "codeBlock") return false;
            const doc = view.state.schema.nodeFromJSON(linesToDoc(parse(text)));
            const blocks: PMNode[] = [];
            doc.forEach((b) => blocks.push(b));
            const openStart = blocks[0]?.isTextblock ? 1 : 0, openEnd = blocks[blocks.length - 1]?.isTextblock ? 1 : 0;
            view.dispatch(view.state.tr.replaceSelection(new Slice(Fragment.from(blocks), openStart, openEnd)).scrollIntoView());
            return true;
          },
        },
      }),
    ];
  },
});

export function extensions(opts: { placeholder?: string; editTable?: TableOptions["edit"] } = {}) {
  return [
    StarterKit.configure({
      blockquote: false, bulletList: false, orderedList: false, listItem: false, listKeymap: false,
      horizontalRule: false, hardBreak: false, underline: false, trailingNode: false, code: false,
      heading: { levels: [1, 2, 3, 4, 5, 6] },
      link: { openOnClick: false, autolink: false, linkOnPaste: false },
    }),
    // Code can sit inside bold or a link, as markdown allows.
    Code.extend({ excludes: "" }),
    Item, Quote, Raw,
    Table.configure({ edit: opts.editTable ?? (() => {}) }),
    Behaviour.configure({ placeholder: opts.placeholder ?? "" }),
  ];
}

// ---- what the Aa panel does ----

export type Format = "bold" | "italic" | "strike" | "code";

/** The kind of the line with the cursor, for lighting up the panel's tiles. */
export function lineKind(editor: Editor): string {
  const p = editor.state.selection.$from.parent;
  if (p.type.name === "item") return p.attrs.kind;
  if (p.type.name === "heading") return `h${p.attrs.level}`;
  return p.type.name;
}

/** Sets the lines in the selection to a kind, or back to plain lines if they all have it already. */
export function setLines(editor: Editor, kind: "bullet" | "number" | "task" | "quote" | "h1" | "h2" | "h3") {
  const { state, view } = editor;
  const { from, to } = state.selection;
  const want = (n: PMNode) =>
    kind.startsWith("h") ? n.type.name === "heading" && n.attrs.level === Number(kind[1])
      : kind === "quote" ? n.type.name === "quote" : n.type.name === "item" && n.attrs.kind === kind;
  const blocks: { pos: number; node: PMNode }[] = [];
  state.doc.nodesBetween(from, to, (node, pos) => { if (node.isTextblock && node.type.name !== "codeBlock") blocks.push({ pos, node }); return node.type.name === "doc"; });
  const all = blocks.length > 0 && blocks.every((b) => want(b.node));
  const tr = state.tr;
  for (const { pos, node } of blocks) {
    if (all) tr.setNodeMarkup(pos, state.schema.nodes.paragraph);
    else if (kind.startsWith("h")) tr.setNodeMarkup(pos, state.schema.nodes.heading, { level: Number(kind[1]) });
    else if (kind === "quote") tr.setNodeMarkup(pos, state.schema.nodes.quote);
    else tr.setNodeMarkup(pos, state.schema.nodes.item, { kind, level: node.type.name === "item" ? node.attrs.level : 0, checked: false });
  }
  view.dispatch(tr);
  editor.commands.focus();
}

export function indent(editor: Editor, by: 1 | -1) {
  editor.view.dispatch(setLevel(editor.state, by));
  editor.commands.focus();
}

/** Puts a new table on its own line after the line with the cursor. */
export function insertTable(editor: Editor) {
  const { state, view } = editor;
  const $from = state.selection.$from;
  const at = $from.depth > 0 ? $from.after(1) : $from.pos;
  const rows = [["Column 1", "Column 2", "Column 3"], ["", "", ""], ["", "", ""]];
  const tr = state.tr.insert(at, state.schema.nodes.table.create({ rows }));
  const after = at + 1;
  if (after >= tr.doc.content.size || !tr.doc.nodeAt(after)?.isTextblock) tr.insert(after, state.schema.nodes.paragraph.create());
  tr.setSelection(TextSelection.near(tr.doc.resolve(Math.min(after + 1, tr.doc.content.size))));
  view.dispatch(tr);
  editor.commands.focus();
}
