<script lang="ts">
  import { onMount } from "svelte";
  import { init, ready, applyTheme, local, createNote } from "./lib/state";
  import NoteGrid from "./lib/NoteGrid.svelte";
  import Editor from "./lib/Editor.svelte";
  import type { Note } from "@scratchy/core";

  let editing: Note | null = $state(null);
  let deleted: Note | null = $state(null);
  let undoTimer: ReturnType<typeof setTimeout> | undefined;

  onMount(() => {
    applyTheme();
    matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => applyTheme());
    init();
    const key = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key.toLowerCase() === "n") { e.preventDefault(); editing = createNote(); }
    };
    addEventListener("keydown", key);
    return () => removeEventListener("keydown", key);
  });

  async function remove(n: Note) {
    await local.remove(n.id);
    editing = null;
    deleted = n;
    clearTimeout(undoTimer);
    undoTimer = setTimeout(() => (deleted = null), 6000);
  }
  async function undo() {
    if (deleted) await local.restore(deleted);
    deleted = null;
  }
</script>

{#if $ready}
  {#if editing}
    <Editor note={editing} onBack={() => (editing = null)} onDelete={remove} />
  {:else}
    <NoteGrid onOpen={(n) => (editing = n)} onNew={() => (editing = createNote())} />
  {/if}
  {#if deleted}
    <div class="undo" role="status">
      <span>Note deleted</span>
      <button onclick={undo}>Undo</button>
    </div>
  {/if}
{/if}

<style>
  .undo {
    position: fixed; left: 50%; bottom: 28px; transform: translateX(-50%);
    display: flex; align-items: center; gap: 18px; padding: 10px 10px 10px 20px;
    background: var(--text); color: var(--bg); border-radius: 24px; box-shadow: 0 6px 24px #0006;
  }
  .undo button { color: var(--bg); font-weight: 600; padding: 6px 12px; border-radius: 14px; opacity: .85; }
  .undo button:hover { opacity: 1; background: #ffffff22; }
</style>
