<script lang="ts">
  import { onMount } from "svelte";
  import { init, ready, applyTheme, local, createNote } from "./lib/state";
  import { startSync, noteEdited, onFocus, syncNotice } from "./lib/sync";
  import SyncPanel from "./lib/SyncPanel.svelte";
  import VaultGate from "./lib/VaultGate.svelte";
  import { vaultOpen, lock } from "./lib/vault";
  import { serveEngine } from "./lib/bridge";
  import { emit } from "@tauri-apps/api/event";
  import { files } from "./lib/files";
  import NoteGrid from "./lib/NoteGrid.svelte";
  import Editor from "./lib/Editor.svelte";
  import type { Note } from "@scratchy/core";

  let editing: Note | null = $state(null);
  let deleted: Note | null = $state(null);
  let showSync = $state(false);
  let gate = $state(false);
  let vaultView = $state(false);
  let undoTimer: ReturnType<typeof setTimeout> | undefined;

  onMount(() => {
    applyTheme();
    matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => applyTheme());
    init(() => { vaultView = false; gate = false; if (editing?.secret) editing = null; }).then(() => {
      local.onEdit(noteEdited);
      void startSync();
      startEngine();
    });
    addEventListener("focus", onFocus);
    const key = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.key.toLowerCase() === "n") { e.preventDefault(); editing = createNote(vaultView); }
    };
    addEventListener("keydown", key);
    return () => { removeEventListener("keydown", key); removeEventListener("focus", onFocus); };
  });

  /** The card lives in its own window and uses this window's notes through small messages. */
  function startEngine() {
    const publicNotes = () => local.all().filter((n) => !n.secret);
    void serveEngine({
      list: () => publicNotes(),
      save: (note: Note) => local.save(note),
      getPrefs: async () => JSON.parse((await files.readText("card_prefs.json")) ?? '{"mode":"recent"}'),
      setPrefs: (p: unknown) => files.writeText("card_prefs.json", JSON.stringify(p)),
    });
    let t: ReturnType<typeof setTimeout> | undefined;
    local.onChange(() => { clearTimeout(t); t = setTimeout(() => void emit("notes-changed", publicNotes()), 60); });
  }

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
    <NoteGrid
      secretMode={vaultView}
      onOpen={(n) => (editing = n)}
      onNew={() => (editing = createNote(vaultView))}
      onSync={() => (showSync = true)}
      onVault={() => ($vaultOpen ? (vaultView = true) : (gate = true))}
      onLeaveVault={() => (vaultView = false)}
      onLock={() => lock()}
    />
  {/if}
  {#if gate}<VaultGate onOpen={() => { gate = false; vaultView = true; }} onClose={() => (gate = false)} />{/if}
  {#if showSync}<SyncPanel onClose={() => (showSync = false)} />{/if}
  {#if $syncNotice}
    <div class="undo" role="status"><span>{$syncNotice}</span><button onclick={() => syncNotice.set("")}>OK</button></div>
  {:else if deleted}
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
