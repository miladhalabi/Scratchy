<script lang="ts">
  import type { Editor } from "@tiptap/core";
  import { indent, insertTable, lineKind, setLines } from "../lib/editor/schema";

  /** The Aa panel: large labelled tiles; the tile for whatever the cursor is in lights up. */
  interface Props { editor: Editor; version: number; onlink: () => void; onclose: () => void }
  let { editor, version, onlink, onclose }: Props = $props();

  const kind = $derived.by(() => { void version; return lineKind(editor); });
  const on = (m: string) => { void version; return editor.isActive(m); };

  type Tile = { label: string; icon: string; active?: () => boolean; run: () => void };
  const inline: Tile[] = [
    { label: "Bold", icon: "B", active: () => on("bold"), run: () => editor.chain().focus().toggleBold().run() },
    { label: "Italic", icon: "I", active: () => on("italic"), run: () => editor.chain().focus().toggleItalic().run() },
    { label: "Strike", icon: "S", active: () => on("strike"), run: () => editor.chain().focus().toggleStrike().run() },
    { label: "Code", icon: "</>", active: () => on("code"), run: () => editor.chain().focus().toggleCode().run() },
    { label: "Link", icon: "🔗", active: () => on("link"), run: () => onlink() },
  ];
  const lines: Tile[] = [
    { label: "Heading 1", icon: "H1", active: () => kind === "h1", run: () => setLines(editor, "h1") },
    { label: "Heading 2", icon: "H2", active: () => kind === "h2", run: () => setLines(editor, "h2") },
    { label: "Heading 3", icon: "H3", active: () => kind === "h3", run: () => setLines(editor, "h3") },
    { label: "Bullets", icon: "•", active: () => kind === "bullet", run: () => setLines(editor, "bullet") },
    { label: "Numbers", icon: "1.", active: () => kind === "number", run: () => setLines(editor, "number") },
    { label: "Checklist", icon: "☑", active: () => kind === "task", run: () => setLines(editor, "task") },
    { label: "Quote", icon: "❝", active: () => kind === "quote", run: () => setLines(editor, "quote") },
    { label: "Table", icon: "▦", run: () => { insertTable(editor); onclose(); } },
    { label: "Indent", icon: "→", run: () => indent(editor, 1) },
    { label: "Outdent", icon: "←", run: () => indent(editor, -1) },
  ];
</script>

<!-- Pressing a tile never takes the focus or the selection away from the text. -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="panel" role="toolbar" aria-label="Formatting" tabindex="-1" onmousedown={(e) => e.preventDefault()}>
  <div class="row">
    {#each inline as t}
      <button class="tile" class:on={t.active?.()} onclick={t.run} title={t.label} aria-pressed={t.active?.() ?? false}>
        <span class="ic" class:b={t.icon === "B"} class:i={t.icon === "I"} class:s={t.icon === "S"}>{t.icon}</span><span class="lb">{t.label}</span>
      </button>
    {/each}
  </div>
  <div class="grid">
    {#each lines as t}
      <button class="tile" class:on={t.active?.()} onclick={t.run} title={t.label} aria-pressed={t.active?.() ?? false}>
        <span class="ic">{t.icon}</span><span class="lb">{t.label}</span>
      </button>
    {/each}
  </div>
</div>

<style>
  .panel {
    position: absolute; z-index: 30; top: 52px; right: 12px; width: 372px; padding: 12px; box-sizing: border-box;
    background: var(--floating); border: 1px solid var(--hairline); border-radius: 22px;
    box-shadow: 0 18px 48px rgb(0 0 0 / 0.22); display: flex; flex-direction: column; gap: 8px;
    transform-origin: top right; animation: pop 150ms cubic-bezier(0.2, 0.9, 0.3, 1);
  }
  .row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; }
  .grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; }
  .tile {
    display: flex; flex-direction: column; align-items: center; gap: 3px; padding: 10px 2px 8px; border: 0; border-radius: 14px;
    background: var(--track); color: var(--text); cursor: pointer; font: inherit;
  }
  .tile:hover { background: color-mix(in srgb, var(--accent) 14%, var(--track)); }
  .tile:active { transform: scale(0.96); }
  .tile.on { background: var(--accent); color: var(--on-accent); }
  .ic { font-size: 17px; line-height: 22px; font-weight: 650; }
  .ic.b { font-weight: 800; } .ic.i { font-style: italic; font-family: Georgia, serif; } .ic.s { text-decoration: line-through; }
  .lb { font-size: 11px; color: inherit; opacity: 0.8; }
  @keyframes pop { from { opacity: 0; transform: scale(0.95); } }
  @media (prefers-reduced-motion: reduce) { .panel { animation: none; } }
</style>
