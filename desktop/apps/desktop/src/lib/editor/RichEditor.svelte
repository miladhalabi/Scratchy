<script lang="ts">
  import { onMount } from "svelte";
  import { Editor } from "@tiptap/core";
  import { extensions, pasteMarkdown } from "./schema";
  import { docToMarkdown, markdownToDoc } from "./convert";
  import TableSheet from "./TableSheet.svelte";
  import "./editor.css";

  interface Props {
    placeholder?: string;
    /** Called after every change, with the body as markdown. */
    onchange?: (md: string) => void;
    /** Called when the cursor or the formatting under it changes (for the Aa panel). */
    onselect?: () => void;
    class?: string;
  }
  let { placeholder = "", onchange, onselect, class: cls = "" }: Props = $props();

  let el: HTMLDivElement;
  let editor: Editor | null = null;
  let table: { rows: string[][]; done: (rows: string[][] | null) => void } | null = $state(null);
  let loading = false;

  /** Shows another note's body. Doesn't count as a change. */
  export function load(md: string) {
    if (!editor) { pending = md; return; }
    loading = true;
    editor.commands.setContent(markdownToDoc(md), { emitUpdate: false });
    editor.commands.setTextSelection(0);
    loading = false;
  }
  let pending: string | null = null;

  export const markdown = () => (editor ? docToMarkdown(editor.state.doc) : pending ?? "");
  export const instance = () => editor;
  /** Text from the Paste button, put in like a paste. */
  export function paste(text: string) { if (editor) { editor.commands.focus(); pasteMarkdown(editor.view, text); } }
  export function focus(end = false) { editor?.commands.focus(end ? "end" : undefined, { scrollIntoView: false }); }

  onMount(() => {
    editor = new Editor({
      element: el,
      extensions: extensions({ placeholder, editTable: (rows, done) => { table = { rows, done }; } }),
      content: markdownToDoc(pending ?? ""),
      editorProps: { attributes: { class: "rich scroll", spellcheck: "false" } },
      onUpdate: ({ editor }) => { if (!loading) onchange?.(docToMarkdown(editor.state.doc)); },
      onSelectionUpdate: () => onselect?.(),
      onTransaction: () => onselect?.(),
    });
    pending = null;
    return () => editor?.destroy();
  });
</script>

<div class="host {cls}" bind:this={el}></div>
{#if table}
  <TableSheet rows={table.rows} onclose={(rows) => { const t = table; table = null; t?.done(rows === undefined ? t.rows : rows); }} />
{/if}

<style>
  .host { display: flex; flex-direction: column; min-height: 0; }
  .host :global(.rich) { flex: 1; min-height: 0; overflow-y: auto; outline: none; }
</style>
