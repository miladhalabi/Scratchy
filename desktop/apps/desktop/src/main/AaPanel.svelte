<script lang="ts">
  import type { Editor } from "@tiptap/core";
  import FormatIcon, { type Kind } from "../lib/editor/FormatIcon.svelte";
  import { indent, insertTable, lineKind, plainHeadings, setLines } from "../lib/editor/schema";

  /** The format panel (showFormatPanel in NoteEditor.kt): labelled tiles in four sections; the one in use lights up. */
  interface Props { editor: Editor; version: number; onlink: () => void; onclose: () => void }
  let { editor, version, onlink, onclose }: Props = $props();

  const kind = $derived.by(() => { void version; return lineKind(editor); });
  const on = (m: string) => { void version; return editor.isActive(m); };

  type Tile = { kind: Kind; label: string; icon?: string; active?: () => boolean; keep?: boolean; run: () => void };
  /** Each row is split evenly; `slots` leaves room at the end like the phone's last row. */
  const sections: { name: string; slots?: number; tiles: Tile[] }[] = [
    { name: "Text", tiles: [
      { kind: "bold", label: "Bold", active: () => on("bold"), keep: true, run: () => editor.chain().focus().toggleBold().run() },
      { kind: "italic", label: "Italic", active: () => on("italic"), keep: true, run: () => editor.chain().focus().toggleItalic().run() },
      { kind: "strike", label: "Strike", active: () => on("strike"), keep: true, run: () => editor.chain().focus().toggleStrike().run() },
      { kind: "code", label: "Code", active: () => on("code"), keep: true, run: () => editor.chain().focus().toggleCode().run() },
      { kind: "link", label: "Link", active: () => on("link"), run: () => onlink() },
    ] },
    { name: "Heading", tiles: [
      { kind: "heading", label: "Large", icon: "H1", active: () => kind === "h1", run: () => setLines(editor, "h1") },
      { kind: "heading", label: "Medium", icon: "H2", active: () => kind === "h2", run: () => setLines(editor, "h2") },
      { kind: "heading", label: "Small", icon: "H3", active: () => kind === "h3", run: () => setLines(editor, "h3") },
      { kind: "heading", label: "Normal", icon: "Aa", run: () => plainHeadings(editor) },
    ] },
    { name: "Lists", tiles: [
      { kind: "bullet", label: "Bullets", active: () => kind === "bullet", run: () => setLines(editor, "bullet") },
      { kind: "number", label: "Numbers", active: () => kind === "number", run: () => setLines(editor, "number") },
      { kind: "task", label: "Checklist", active: () => kind === "task", run: () => setLines(editor, "task") },
      { kind: "quote", label: "Quote", active: () => kind === "quote", run: () => setLines(editor, "quote") },
    ] },
    { name: "Insert", slots: 5, tiles: [
      { kind: "table", label: "Table", run: () => insertTable(editor) },
      { kind: "outdent", label: "Outdent", keep: true, run: () => indent(editor, -1) },
      { kind: "indent", label: "Indent", keep: true, run: () => indent(editor, 1) },
    ] },
  ];
  const tap = (t: Tile) => { t.run(); if (!t.keep) onclose(); };
</script>

<!-- Pressing a tile never takes the focus or the selection away from the text. -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="panel" role="toolbar" aria-label="Format" tabindex="-1" onmousedown={(e) => e.preventDefault()}>
  {#each sections as s}
    <div class="section">{s.name}</div>
    <div class="row">
      {#each s.tiles as t}
        {@const active = t.active?.() ?? false}
        <button class="tile" class:on={active} onclick={() => tap(t)} title={t.label} aria-pressed={active}>
          <FormatIcon kind={t.kind} label={t.icon} {active} />
          <span>{t.label}</span>
        </button>
      {/each}
      {#if s.slots}<span style:flex-grow={s.slots - s.tiles.length}></span>{/if}
    </div>
  {/each}
</div>

<style>
  .panel {
    position: absolute; z-index: 30; top: calc(100% + 6px); right: 8px; width: min(400px, calc(100vw - 24px)); box-sizing: border-box;
    padding: 2px 14px 14px; background: var(--surface); border: 1px solid color-mix(in srgb, var(--text) 14%, transparent);
    border-radius: 22px; box-shadow: 0 10px 30px rgb(0 0 0 / 0.25);
    transform-origin: top right; animation: pop 160ms cubic-bezier(0.2, 0.9, 0.3, 1);
  }
  .section { padding: 10px 4px 4px; font-size: 11px; letter-spacing: 0.1em; text-transform: uppercase; color: var(--secondary); }
  .row { display: flex; }
  .row > * { flex: 1 1 0; min-width: 0; }
  .tile {
    display: flex; flex-direction: column; align-items: center; padding: 2px 0 4px;
    border: 0; background: transparent; color: var(--secondary); font: inherit; font-size: 11px; cursor: pointer;
  }
  .tile.on { color: var(--accent); }
  .tile:active { transform: scale(0.94); }
  .tile span { white-space: nowrap; }
  @keyframes pop { from { opacity: 0; transform: scale(0.96); } }
  @media (prefers-reduced-motion: reduce) { .panel { animation: none; } }
</style>
