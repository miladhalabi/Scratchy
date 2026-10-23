<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { local, notes } from "./state";
  import { vaultOpen, vaultSetUp } from "./vault";
  import { type Note, continueChecklist, flipBox, toggleChecklist as toggleLine } from "@scratchy/core";

  let { note, onBack, onDelete }: { note: Note; onBack: () => void; onDelete: (n: Note) => void } = $props();

  // The editor is created fresh for each note, so starting from its first value is intended.
  // svelte-ignore state_referenced_locally
  let title = $state(note.title);
  // svelte-ignore state_referenced_locally
  let body = $state(note.body);
  // svelte-ignore state_referenced_locally
  let pinned = $state(note.pinned);
  // svelte-ignore state_referenced_locally
  let secret = $state(note.secret);
  let madeSecretHere = false;
  let hint = $state("");
  // svelte-ignore state_referenced_locally
  let current: Note = note;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let bodyEl: HTMLTextAreaElement;
  let titleEl: HTMLInputElement;

  async function commit() {
    clearTimeout(timer);
    const changed = title !== current.title || body !== current.body || pinned !== current.pinned || secret !== current.secret;
    if (!changed) return;
    current = await local.save({ ...current, title, body, pinned, secret, updatedAt: Date.now() });
  }
  function schedule() { clearTimeout(timer); timer = setTimeout(commit, 500); }

  onMount(() => { (note.title || note.body ? bodyEl : titleEl).focus(); });
  onDestroy(() => { void commit(); });

  // Another device (or a sync) changed this note: show it, unless there are unsaved edits here.
  $effect(() => {
    const stored = $notes.find((n) => n.id === current.id);
    if (!stored || stored.rev <= current.rev) return;
    if (title !== current.title || body !== current.body || pinned !== current.pinned || secret !== current.secret) return;   // unsaved typing wins
    current = stored;
    title = stored.title; body = stored.body; pinned = stored.pinned; secret = stored.secret;
  });

  async function back() { await commit(); onBack(); }
  async function del() { await commit(); onDelete(current); }
  function togglePin() { pinned = !pinned; schedule(); }
  function toggleSecret() {
    hint = "";
    if (!secret) {
      if (!$vaultSetUp) { hint = "Set up the vault first: use the lock button on the notes screen."; return; }
      secret = true; madeSecretHere = true; hint = "Secret. Find it under the lock on the notes screen.";
    } else {
      if (!madeSecretHere && !$vaultOpen) { hint = "Unlock the vault first to make this note public."; return; }
      secret = false; hint = "No longer secret.";
    }
    schedule();
  }

  function apply(e: { body: string; cursor: number }) {
    body = e.body; schedule();
    queueMicrotask(() => { bodyEl.focus(); bodyEl.setSelectionRange(e.cursor, e.cursor); });
  }
  const toggleChecklist = () => apply(toggleLine(body, bodyEl.selectionStart ?? 0));

  /** Enter on a checklist line starts a new item; Enter on an empty item ends the list. */
  function keydown(e: KeyboardEvent) {
    if (e.key !== "Enter" || e.shiftKey || e.isComposing) return;
    const next = continueChecklist(body, bodyEl.selectionStart ?? 0);
    if (next) { e.preventDefault(); apply(next); }
  }

  /** Click a ☐ / ☑ to flip it. */
  function click() {
    const pos = bodyEl.selectionStart ?? 0;
    if (bodyEl.selectionEnd !== pos) return;
    const flipped = flipBox(body, pos);
    if (flipped != null) { body = flipped; schedule(); }
  }
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && back()} />

<div class="page">
  <div class="bar">
    <button class="icon" onclick={back} aria-label="Back" title="Back (Esc)">
      <svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z"/></svg>
    </button>
    <span class="spacer"></span>
    <button class="icon" onclick={toggleChecklist} aria-label="Checklist" title="Checklist">
      <svg viewBox="0 0 24 24" width="22" height="22"><path fill="currentColor" d="M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2m-9 14-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8z"/></svg>
    </button>
    <button class="icon" class:on={secret} onclick={toggleSecret} aria-label="Secret note" title="Secret note">
      <svg viewBox="0 0 24 24" width="22" height="22"><path fill="currentColor" d="M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2m-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4M15.1 8H8.9V6a3.1 3.1 0 0 1 6.2 0z"/></svg>
    </button>
    <button class="icon" class:on={pinned} onclick={togglePin} aria-label="Pin" title="Pin">
      <svg viewBox="0 0 24 24" width="22" height="22"><path fill="currentColor" d="M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3"/></svg>
    </button>
    <button class="icon" onclick={del} aria-label="Delete" title="Delete">
      <svg viewBox="0 0 24 24" width="22" height="22"><path fill="currentColor" d="M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6zM19 4h-3.5l-1-1h-5l-1 1H5v2h14z"/></svg>
    </button>
  </div>

  {#if hint}<p class="hint" role="status">{hint}</p>{/if}
  <div class="sheet">
    <input class="title bidi" bind:this={titleEl} bind:value={title} oninput={schedule} placeholder="Title"
      onkeydown={(e) => { if (e.key === "Enter") { e.preventDefault(); bodyEl.focus(); } }} />
    <textarea class="body bidi" bind:this={bodyEl} bind:value={body} oninput={schedule} onkeydown={keydown} onclick={click}
      placeholder="Start typing…"></textarea>
  </div>
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; max-width: 860px; margin: 0 auto; padding: 14px 24px 24px; }
  .bar { display: flex; align-items: center; gap: 4px; padding-bottom: 6px; }
  .spacer { flex: 1; }
  .icon { width: 44px; height: 44px; display: grid; place-items: center; border-radius: 14px; color: var(--secondary); }
  .icon:hover { background: var(--surface); color: var(--text); }
  .icon.on { color: var(--accent); }
  .hint { margin: 0 6px 6px; color: var(--secondary); font-size: 14px; }
  .sheet { flex: 1; display: flex; flex-direction: column; min-height: 0; }
  .title {
    background: none; border: 0; outline: 0; font-size: 32px; font-weight: 700; letter-spacing: -0.02em;
    padding: 10px 6px; border-bottom: 1px solid var(--hairline);
  }
  .title::placeholder, .body::placeholder { color: var(--secondary); opacity: .8; }
  .body {
    flex: 1; resize: none; background: none; border: 0; outline: 0; padding: 14px 6px; font-size: 17px; line-height: 1.6;
    white-space: pre-wrap; min-height: 0;
  }
</style>
