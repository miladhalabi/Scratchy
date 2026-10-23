<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { local } from "./state";
  import type { Note } from "@scratchy/core";

  let { note, onBack, onDelete }: { note: Note; onBack: () => void; onDelete: (n: Note) => void } = $props();

  // The editor is created fresh for each note, so starting from its first value is intended.
  // svelte-ignore state_referenced_locally
  let title = $state(note.title);
  // svelte-ignore state_referenced_locally
  let body = $state(note.body);
  // svelte-ignore state_referenced_locally
  let pinned = $state(note.pinned);
  // svelte-ignore state_referenced_locally
  let current: Note = note;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let bodyEl: HTMLTextAreaElement;
  let titleEl: HTMLInputElement;

  const BOX_OFF = "☐", BOX_ON = "☑";

  async function commit() {
    clearTimeout(timer);
    const changed = title !== current.title || body !== current.body || pinned !== current.pinned;
    if (!changed) return;
    current = await local.save({ ...current, title, body, pinned, updatedAt: Date.now() });
  }
  function schedule() { clearTimeout(timer); timer = setTimeout(commit, 500); }

  onMount(() => { (note.title || note.body ? bodyEl : titleEl).focus(); });
  onDestroy(() => { void commit(); });

  async function back() { await commit(); onBack(); }
  async function del() { await commit(); onDelete(current); }
  function togglePin() { pinned = !pinned; schedule(); }

  /** Adds or removes the checkbox at the start of the line with the cursor. */
  function toggleChecklist() {
    const pos = bodyEl.selectionStart ?? 0;
    const start = body.lastIndexOf("\n", pos - 1) + 1;
    const has = body.startsWith(BOX_OFF + " ", start) || body.startsWith(BOX_ON + " ", start);
    body = has ? body.slice(0, start) + body.slice(start + 2) : body.slice(0, start) + BOX_OFF + " " + body.slice(start);
    schedule();
    queueMicrotask(() => { bodyEl.focus(); bodyEl.setSelectionRange(pos + (has ? -2 : 2), pos + (has ? -2 : 2)); });
  }

  /** Enter on a checklist line starts a new item; Enter on an empty item ends the list. */
  function keydown(e: KeyboardEvent) {
    if (e.key !== "Enter" || e.shiftKey || e.isComposing) return;
    const pos = bodyEl.selectionStart ?? 0;
    const start = body.lastIndexOf("\n", pos - 1) + 1;
    const line = body.slice(start, pos);
    if (!line.startsWith(BOX_OFF + " ") && !line.startsWith(BOX_ON + " ")) return;
    e.preventDefault();
    if (line.length === 2) { body = body.slice(0, start) + body.slice(pos); queueMicrotask(() => bodyEl.setSelectionRange(start, start)); }
    else { body = body.slice(0, pos) + "\n" + BOX_OFF + " " + body.slice(pos); queueMicrotask(() => bodyEl.setSelectionRange(pos + 3, pos + 3)); }
    schedule();
  }

  /** Click a ☐ / ☑ to flip it. */
  function click(e: MouseEvent) {
    const pos = bodyEl.selectionStart ?? 0;
    if (bodyEl.selectionEnd !== pos) return;
    const start = body.lastIndexOf("\n", pos - 1) + 1;
    const c = body[start];
    if (pos <= start + 1 && (c === BOX_OFF || c === BOX_ON)) {
      body = body.slice(0, start) + (c === BOX_OFF ? BOX_ON : BOX_OFF) + body.slice(start + 1);
      schedule();
    }
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
    <button class="icon" class:on={pinned} onclick={togglePin} aria-label="Pin" title="Pin">
      <svg viewBox="0 0 24 24" width="22" height="22"><path fill="currentColor" d="M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3"/></svg>
    </button>
    <button class="icon" onclick={del} aria-label="Delete" title="Delete">
      <svg viewBox="0 0 24 24" width="22" height="22"><path fill="currentColor" d="M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6zM19 4h-3.5l-1-1h-5l-1 1H5v2h14z"/></svg>
    </button>
  </div>

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
