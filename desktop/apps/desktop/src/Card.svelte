<script lang="ts">
  import { onMount, tick } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";
  import {
    type CardPrefs, type Note, continueChecklist, flipBox, isBlank, pickCardNote, recentNotes, toggleChecklist,
  } from "@scratchy/core";
  import { call } from "./lib/bridge";
  import { formatWhen, heading } from "./lib/format";

  let notes: Note[] = $state([]);
  let prefs: CardPrefs = $state({ mode: "recent" });
  let current: Note | null = $state(null);
  let title = $state("");
  let body = $state("");
  let continuing = $state(false);
  let showRecents = $state(false);
  let pinned = $state(false);
  let error = $state("");
  let bodyEl: HTMLTextAreaElement | undefined = $state();
  let titleEl: HTMLInputElement | undefined = $state();
  let timer: ReturnType<typeof setTimeout> | undefined;

  const dirty = () => current != null && (title !== current.title || body !== current.body);

  async function commit() {
    clearTimeout(timer);
    if (!current || !dirty()) return;
    const saved = await call<Note>("save", { ...current, title, body, updatedAt: Date.now() });
    current = saved;
    if (!isBlank(saved)) await remember(saved.id);
  }
  const schedule = () => { clearTimeout(timer); timer = setTimeout(() => void commit().catch(() => {}), 500); };

  async function remember(id: string) {
    prefs = { ...prefs, lastCardNote: id, lastCardAt: Date.now() };
    await call("setPrefs", prefs);
  }

  async function load(n: Note, cont: boolean) {
    current = n; title = n.title; body = n.body; continuing = cont; showRecents = false;
    await tick();
    (n.title || n.body ? bodyEl : titleEl)?.focus();
  }

  /** Called every time the card appears: choose the note to show, like the phone's "when the card opens". */
  async function open() {
    try {
      error = "";
      const [n, p, o] = await Promise.all([call<Note[]>("list"), call<CardPrefs>("getPrefs"), invoke<{ pinned: boolean }>("overlay_prefs")]);
      notes = n; prefs = p; pinned = o.pinned;
      const pick = pickCardNote(n, p, Date.now(), () => crypto.randomUUID());
      let note = pick.note;
      if (pick.create) {                                   // the Scratch note is created the first time it is needed
        note = await call<Note>("save", note);
        prefs = { ...prefs, scratchId: note.id };
        await call("setPrefs", prefs);
      }
      await load(note, pick.existing && !pick.create);
    } catch (e) { error = String((e as Error).message ?? e); }
  }

  async function close() { await commit().catch(() => {}); await invoke("hide_card"); }

  async function newNote() {
    await commit().catch(() => {});
    await load({ id: crypto.randomUUID(), title: "", body: "", createdAt: Date.now(), updatedAt: Date.now(), pinned: false, images: [], secret: false, rev: 0 }, false);
  }

  async function pick(n: Note) {
    await commit().catch(() => {});
    await load(n, true);
    await remember(n.id);
  }

  function apply(e: { body: string; cursor: number }) {
    body = e.body; schedule();
    queueMicrotask(() => { bodyEl?.focus(); bodyEl?.setSelectionRange(e.cursor, e.cursor); });
  }
  function keydown(e: KeyboardEvent) {
    if (e.key !== "Enter" || e.shiftKey || e.isComposing) return;
    const next = continueChecklist(body, bodyEl?.selectionStart ?? 0);
    if (next) { e.preventDefault(); apply(next); }
  }
  function bodyClick() {
    const pos = bodyEl?.selectionStart ?? 0;
    if (bodyEl?.selectionEnd !== pos) return;
    const f = flipBox(body, pos);
    if (f != null) { body = f; schedule(); }
  }

  async function togglePin() {
    pinned = !pinned;
    await invoke("set_pinned", { pinned });
  }

  // ---- moving and resizing the card by dragging ----
  // The card is a layer surface, so we move it ourselves: pointer motion becomes new margins.
  let box = { x: 0, y: 0, w: 0, h: 0 };
  let drag: { kind: "move" | "size"; gx: number; gy: number } | null = null;
  let busy = false;

  async function startDrag(e: PointerEvent, kind: "move" | "size") {
    const p = await invoke<{ cardX: number | null; cardY: number | null; cardW: number; cardH: number }>("overlay_prefs");
    box = { x: p.cardX ?? 0, y: p.cardY ?? 0, w: p.cardW, h: p.cardH };
    drag = { kind, gx: e.clientX, gy: e.clientY };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  }
  async function moveDrag(e: PointerEvent) {
    if (!drag || busy) return;
    const dx = e.clientX - drag.gx, dy = e.clientY - drag.gy;
    if (!dx && !dy) return;
    busy = true;
    try {
      if (drag.kind === "move") { box.x += dx; box.y += dy; await invoke("move_card", { x: Math.round(box.x), y: Math.round(box.y) }); }
      else { box.w += dx; box.h += dy; drag.gx = e.clientX; drag.gy = e.clientY; await invoke("resize_card", { w: Math.round(box.w), h: Math.round(box.h) }); }
    } finally { busy = false; }
  }
  const endDrag = () => { drag = null; };

  onMount(() => {
    const offs = [
      listen("card-shown", () => void open()),
      listen("card-hide", () => void commit().catch(() => {})),
      listen<Note[]>("notes-changed", (e) => {
        notes = e.payload;
        // Another device changed the note on screen: show it, unless there are unsaved edits here.
        const stored = e.payload.find((n) => n.id === current?.id);
        if (stored && current && stored.rev > current.rev && !dirty()) { current = stored; title = stored.title; body = stored.body; }
      }),
    ];
    return () => offs.forEach((p) => void p.then((f) => f()));
  });
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && close()} />

<div class="card">
  <header>
    <button class="icon" class:on={showRecents} onclick={() => (showRecents = !showRecents)} aria-label="Recent notes" title="Recent notes">
      <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M3 13h2v-2H3zm0 4h2v-2H3zm0-8h2V7H3zm4 4h14v-2H7zm0 4h14v-2H7zM7 7v2h14V7z"/></svg>
    </button>
    <button class="icon" onclick={() => apply(toggleChecklist(body, bodyEl?.selectionStart ?? 0))} aria-label="Checklist" title="Checklist">
      <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2m-9 14-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8z"/></svg>
    </button>
    <!-- The middle of the header is the handle: drag it to move the card. -->
    <div class="handle" role="presentation" onpointerdown={(e) => startDrag(e, "move")} onpointermove={moveDrag} onpointerup={endDrag} onpointercancel={endDrag}>
      <span class="grip"></span>
    </div>
    <button class="icon" class:on={pinned} onclick={togglePin} aria-label="Keep on screen" title={pinned ? "Pinned: stays open" : "Pin: keep the card on screen"}>
      <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3"/></svg>
    </button>
  </header>

  {#if error}
    <p class="error">{error}</p>
  {:else if showRecents}
    <div class="recents">
      <button class="row accent" onclick={newNote}>＋ New note</button>
      {#each recentNotes(notes) as n (n.id)}
        <button class="row bidi" onclick={() => pick(n)}>{n.pinned ? "📌 " : ""}{heading(n) || "Untitled"}</button>
      {/each}
    </div>
  {:else if current}
    {#if continuing}
      <div class="continue">
        <span>Continuing · edited {formatWhen(current.updatedAt)}</span>
        <button onclick={newNote}>＋ New note</button>
      </div>
    {/if}
    <input class="title bidi" bind:this={titleEl} bind:value={title} oninput={schedule} placeholder="Title"
      onkeydown={(e) => { if (e.key === "Enter") { e.preventDefault(); bodyEl?.focus(); } }} />
    <textarea class="body bidi" bind:this={bodyEl} bind:value={body} oninput={schedule} onkeydown={keydown} onclick={bodyClick}
      placeholder="Start typing…"></textarea>
  {/if}

  <!-- The whole bottom strip closes the card. -->
  <button class="close" onclick={close}>Close</button>
  <div class="grow" role="presentation" onpointerdown={(e) => startDrag(e, "size")} onpointermove={moveDrag} onpointerup={endDrag} onpointercancel={endDrag}></div>
</div>

<style>
  .card { position: relative; height: 100vh; display: flex; flex-direction: column; background: var(--floating); border-radius: 24px; border: 1px solid var(--hairline); overflow: hidden; }
  header { display: flex; align-items: center; padding: 6px 8px 0; flex: none; }
  .icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 12px; color: var(--secondary); }
  .icon:hover { background: var(--track); color: var(--text); }
  .icon.on { color: var(--accent); }
  .handle { flex: 1; height: 36px; display: grid; place-items: center; cursor: grab; touch-action: none; }
  .handle:active { cursor: grabbing; }
  .grip { width: 38px; height: 4px; border-radius: 2px; background: var(--hairline); }
  .continue { display: flex; align-items: center; justify-content: space-between; margin: 2px 16px 0; font-size: 12.5px; color: var(--secondary); }
  .continue button { color: var(--accent); font-weight: 600; padding: 6px 4px; }
  .title { margin: 4px 14px 0; padding: 8px 4px 6px; background: none; border: 0; outline: 0; font-size: 19px; font-weight: 700; border-bottom: 1px solid var(--hairline); }
  .body { flex: 1; min-height: 0; resize: none; margin: 0 14px; padding: 10px 4px; background: none; border: 0; outline: 0; font-size: 15.5px; line-height: 1.55; }
  .title::placeholder, .body::placeholder { color: var(--secondary); opacity: .8; }
  .recents { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 10px; }
  .row { display: block; width: 100%; text-align: start; padding: 11px 10px; border-radius: 12px; font-size: 15px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .row:hover { background: var(--track); }
  .row.accent { color: var(--accent); font-weight: 600; }
  .error { margin: 16px; color: var(--danger); font-size: 14px; }
  .close { flex: none; height: 46px; color: var(--secondary); border-top: 1px solid var(--hairline); }
  .close:hover { background: var(--track); color: var(--text); }
  .grow { position: absolute; right: 0; bottom: 0; width: 26px; height: 26px; cursor: nwse-resize; touch-action: none; }
  .grow::after { content: ""; position: absolute; right: 7px; bottom: 7px; width: 9px; height: 9px; border-right: 2px solid var(--hairline); border-bottom: 2px solid var(--hairline); border-radius: 0 0 3px 0; }
</style>
