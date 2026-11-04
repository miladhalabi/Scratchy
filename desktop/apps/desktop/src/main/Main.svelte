<script lang="ts">
  import { onMount, tick } from "svelte";
  import { canonicalCategory, categoryNames, cleanCategory, CATEGORY_MAX, newNote, noteHeading, plain, type Note } from "@scratchy/core";
  import { notes } from "../lib/notes";
  import type { SyncStatus } from "../lib/sync";
  import { preview } from "../lib/preview";
  import { applyTheme } from "../lib/theme";
  import Sheet, { type SheetItem } from "../lib/Sheet.svelte";
  import NotePage from "./NotePage.svelte";
  import Settings from "./Settings.svelte";
  import VaultGate from "./VaultGate.svelte";
  import Thumb from "./Thumb.svelte";
  import "../lib/editor/editor.css";

  /** The notes window: the grid, search and categories, and the full editor for one note at a time. */
  applyTheme();
  // Every component's styles end up in one sheet shared with the see-through card window, so the page styles key off this.
  document.documentElement.classList.add("notes");

  let all: Note[] = $state([]);
  let ready = $state(false);
  let query = $state("");
  let filter = $state("");
  /** The note open in the editor; a new one lives only here until it has something in it. */
  let open: Note | null = $state(null);
  let undo: { note: Note; timer: ReturnType<typeof setTimeout> } | null = $state(null);
  let menu: { note: Note; x: number; y: number } | null = $state(null);
  let sheet: { title: string; items?: SheetItem[]; field?: { label: string; value: string; max?: number }; confirm?: { label: string; danger?: boolean; run: (v: string) => void } } | null = $state(null);
  let searchEl: HTMLInputElement = $state()!;
  let width = $state(1000);
  let sync: SyncStatus = $state({ connected: false, state: "off", text: "", needsPassword: false, needsSignIn: false });
  let vaultSetUp = $state(false);
  let settings = $state(false);
  /** The vault: secret notes only, behind the password. Opened with Ctrl+Shift+L, and nothing on screen says so. */
  let secret = $state(false);
  let gate = $state(false);
  let notice: { text: string; timer: ReturnType<typeof setTimeout> } | null = $state(null);

  const visible = $derived(all.filter((n) => n.secret === secret));
  const categories = $derived(categoryNames(all.filter((n) => !n.secret)));
  const shown = $derived.by(() => {
    const q = query.trim().toLowerCase();
    return visible
      .filter((n) => secret || !filter || n.category === filter)
      .filter((n) => !q || n.title.toLowerCase().includes(q) || n.body.split("\n").map(plain).join("\n").toLowerCase().includes(q) || n.category.toLowerCase().includes(q))
      .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt);
  });
  const cols = $derived(Math.max(1, Math.min(5, Math.floor((width - 32) / 250))));
  /** Notes dealt across the columns left to right, so reading order stays row by row. */
  const columns = $derived.by(() => {
    const out: Note[][] = Array.from({ length: cols }, () => []);
    shown.forEach((n, i) => out[i % cols].push(n));
    return out;
  });
  /** The open note as the store has it now (another device may have changed it), or the new one. */
  const live = $derived(open ? (all.find((n) => n.id === open!.id) ?? open) : null);

  $effect(() => { if (filter && !categories.includes(filter)) filter = ""; });

  function create() {
    menu = null;
    open = { ...newNote(crypto.randomUUID()), secret, category: secret ? "" : filter };
  }

  // ---- the vault ----

  const GRACE_MS = 30_000;              // how long the vault stays open after you leave the window
  let away: ReturnType<typeof setTimeout> | undefined;
  function lock() {
    clearTimeout(away);
    if (open?.secret) open = null;
    secret = false; gate = false; query = "";
  }
  function vaultKey() {
    if (secret) lock();
    else { open = null; settings = false; gate = true; }
  }
  function blurred() { if (secret) { clearTimeout(away); away = setTimeout(lock, GRACE_MS); } }
  function focused() { clearTimeout(away); notes.seen().catch(() => {}); }
  function edit(n: Note) { menu = null; open = n; }

  function remove(n: Note) {
    menu = null;
    if (open?.id === n.id) open = null;
    if (!all.some((x) => x.id === n.id)) return;           // a new note that was never saved
    if (undo) clearTimeout(undo.timer);
    notes.remove(n.id);
    undo = { note: n, timer: setTimeout(() => (undo = null), 6000) };
  }
  function restore() {
    if (!undo) return;
    clearTimeout(undo.timer);
    notes.restore(undo.note);
    undo = null;
  }
  const togglePin = (n: Note) => { menu = null; notes.save({ ...n, pinned: !n.pinned, updatedAt: Date.now() }); };

  function pickCategory(n: Note) {
    menu = null;
    const set = (c: string) => notes.save({ ...n, category: c, updatedAt: Date.now() });
    const items: SheetItem[] = categories.map((c) => ({ label: c, checked: c === n.category, run: () => set(c) }));
    items.push({ label: "New category…", accent: true, run: () => setTimeout(() => {
      sheet = { title: "New category", field: { label: "Category name", value: "", max: CATEGORY_MAX },
        confirm: { label: "Add", run: (v) => { const c = cleanCategory(v); if (c) set(canonicalCategory(c, categories)); } } };
    }) });
    if (n.category) items.push({ label: "No category", run: () => set("") });
    sheet = { title: "Category", items };
  }

  function contextMenu(e: MouseEvent, n: Note) {
    e.preventDefault();
    menu = { note: n, x: Math.min(e.clientX, innerWidth - 196), y: Math.min(e.clientY, innerHeight - 160) };
  }

  function keys(e: KeyboardEvent) {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.shiftKey && e.code === "KeyL") { e.preventDefault(); vaultKey(); return; }
    if (open || sheet || settings || gate) return;
    if (mod && e.key === ",") { e.preventDefault(); settings = true; return; }
    if (mod && e.key.toLowerCase() === "n") { e.preventDefault(); create(); }
    else if (mod && e.key.toLowerCase() === "f") { e.preventDefault(); searchEl.focus(); searchEl.select(); }
    else if (mod && e.key.toLowerCase() === "z" && undo) { e.preventDefault(); restore(); }
    else if (e.key === "Escape") {
      if (menu) menu = null;
      else if (query) { query = ""; searchEl.blur(); }
      else if (filter) filter = "";
      else if (secret) lock();
    }
  }
  function cardKeys(e: KeyboardEvent, n: Note) {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); edit(n); }
    else if (e.key === "Delete" || e.key === "Backspace") { e.preventDefault(); remove(n); }
  }

  /** Back from the editor: the grid gets the keyboard again, on the note you were in. */
  async function back() {
    const id = open?.id;
    open = null;
    await tick();
    document.querySelector<HTMLElement>(`[data-id="${id}"]`)?.focus();
  }

  onMount(() => {
    const off = notes.watch((s) => { all = s.notes; sync = s.sync; vaultSetUp = s.vault.setUp; ready = true; });
    const offNotice = notes.onNotice((text) => {
      if (notice) clearTimeout(notice.timer);
      notice = { text, timer: setTimeout(() => (notice = null), 8000) };
    });
    return () => { off.then((f) => f()); offNotice.then((f) => f()); };
  });
</script>

<svelte:window onkeydown={keys} onclick={() => (menu = null)} onblur={blurred} onfocus={focused} bind:innerWidth={width} />

<div class="app">
  {#if live}
    <NotePage note={live} {categories} onback={back} ondelete={remove} />
  {:else if settings}
    <Settings {sync} {vaultSetUp} onback={() => (settings = false)} />
  {:else}
    {#if secret}
      <div class="vaultbar">
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2m-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4M15.1 8H8.9V6a3.1 3.1 0 0 1 6.2 0z" /></svg>
        <strong>Secret</strong>
        <span class="grow"></span>
        <button onclick={lock}>Lock</button>
      </div>
    {/if}
    <header>
      <div class="search">
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><circle cx="11" cy="11" r="6.5" fill="none" stroke="currentColor" stroke-width="1.8" /><path d="m16 16 4 4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
        <input bind:this={searchEl} bind:value={query} placeholder={secret ? "Search secret notes" : "Search notes"} spellcheck="false" dir="auto"
          onkeydown={(e) => { if (e.key === "ArrowDown" || e.key === "Enter") { e.preventDefault(); document.querySelector<HTMLElement>(".note")?.focus(); } }} />
        {#if query}<button class="clear" onclick={() => { query = ""; searchEl.focus(); }} aria-label="Clear search">×</button>{/if}
      </div>
      <button class="new" onclick={create} title="New note (Ctrl+N)">
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" /></svg>
        New note
      </button>
      <button class="gear" onclick={() => (settings = true)} title="Settings (Ctrl+,)" aria-label="Settings">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4Z" fill="none" stroke="currentColor" stroke-width="1.7" /><path d="M19.4 13.5a7.6 7.6 0 0 0 0-3l2-1.6-2-3.4-2.4 1a7.6 7.6 0 0 0-2.6-1.5L14 2.5h-4l-.4 2.5A7.6 7.6 0 0 0 7 6.5l-2.4-1-2 3.4 2 1.6a7.6 7.6 0 0 0 0 3l-2 1.6 2 3.4 2.4-1a7.6 7.6 0 0 0 2.6 1.5l.4 2.5h4l.4-2.5a7.6 7.6 0 0 0 2.6-1.5l2.4 1 2-3.4z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" /></svg>
      </button>
    </header>
    {#if sync.connected && !secret}
      <button class="syncline" class:warn={sync.state === "attention"} onclick={() => (settings = true)}>{sync.text}</button>
    {/if}

    {#if categories.length && !secret}
      <div class="chips" role="tablist" aria-label="Categories">
        <button class="chip" class:on={!filter} role="tab" aria-selected={!filter} onclick={() => (filter = "")}>All</button>
        {#each categories as c}
          <button class="chip" class:on={filter === c} role="tab" aria-selected={filter === c} onclick={() => (filter = filter === c ? "" : c)} dir="auto">{c}</button>
        {/each}
      </div>
    {/if}

    <main>
      {#if !ready}
        <!-- the first message from the engine is a few milliseconds away -->
      {:else if !shown.length}
        <div class="empty">
          {#if query || filter}
            <p>Nothing matches.</p>
          {:else}
            <p>{secret ? "No secret notes yet." : "No notes yet."}</p>
            <button class="new" onclick={create}>Write the first one</button>
          {/if}
        </div>
      {:else}
        <div class="grid" style="--cols:{cols}">
          {#each columns as col}
            <div class="col">
              {#each col as n (n.id)}
                <div class="note" class:pinned={n.pinned} data-id={n.id} role="button" tabindex="0" aria-label={noteHeading(n) || "Untitled note"}
                  onclick={() => edit(n)} onkeydown={(e) => cardKeys(e, n)} oncontextmenu={(e) => contextMenu(e, n)}>
                  {#if n.images.length}<Thumb name={n.images[0]} />{/if}
                  <div class="inner">
                    {#if n.title.trim()}<div class="t" dir="auto">{n.title}</div>{/if}
                    {#if n.body.trim()}<div class="b">{@html preview(n.body, n.title.trim() ? 9 : 10)}</div>{/if}
                    {#if n.pinned || (n.category && !filter)}
                      <div class="meta">
                        {#if n.category && !filter}<span class="cat" dir="auto">{n.category}</span>{/if}
                        {#if n.pinned}<svg class="pin" viewBox="0 0 24 24" width="14" height="14" aria-label="Pinned"><path d="M14.5 3.5 20.5 9.5 17 11l-3.5 3.5.5 4-1.5 1.5-3.5-3.5L5 20.5 3.5 19 7.5 15 4 11.5 5.5 10l4 .5L13 7z" fill="currentColor" /></svg>{/if}
                      </div>
                    {/if}
                  </div>
                </div>
              {/each}
            </div>
          {/each}
        </div>
      {/if}
    </main>
  {/if}

  {#if notice}
    <div class="undo" role="status"><span>{notice.text}</span><button onclick={() => (notice = null)}>OK</button></div>
  {:else if undo}
    <div class="undo" role="status">
      <span>Note deleted</span>
      <button onclick={restore}>Undo</button>
    </div>
  {/if}
</div>

{#if menu}
  {@const n = menu.note}
  <!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
  <div class="menu" style="left:{menu.x}px; top:{menu.y}px" role="menu" tabindex="-1" onclick={(e) => e.stopPropagation()}>
    <button role="menuitem" onclick={() => edit(n)}>Open</button>
    <button role="menuitem" onclick={() => togglePin(n)}>{n.pinned ? "Unpin" : "Pin"}</button>
    {#if !n.secret}<button role="menuitem" onclick={() => pickCategory(n)}>Category…</button>{/if}
    <button role="menuitem" class="danger" onclick={() => remove(n)}>Delete</button>
  </div>
{/if}

{#if sheet}
  <Sheet {...sheet} onclose={() => (sheet = null)} />
{/if}

{#if gate}
  <VaultGate setUp={vaultSetUp} onopen={() => { gate = false; secret = true; query = ""; filter = ""; }} onclose={() => (gate = false)} />
{/if}

<style>
  :global(html.notes), :global(html.notes body) { margin: 0; height: 100%; background: var(--background); color: var(--text); }
  :global(html.notes body) { font: 15px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; -webkit-font-smoothing: antialiased; }
  :global(html.notes #app) { height: 100%; }
  .app { height: 100%; display: flex; flex-direction: column; overflow: hidden; }

  header { flex: none; display: flex; align-items: center; gap: 10px; padding: 14px 16px 8px; }
  .search {
    flex: 1; max-width: 640px; margin: 0 auto; display: flex; align-items: center; gap: 8px; height: 44px; padding: 0 14px;
    border-radius: 22px; background: var(--surface); color: var(--secondary); border: 1px solid var(--hairline);
  }
  .search:focus-within { border-color: var(--accent); }
  .search input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: 15px; }
  .search input::placeholder { color: var(--secondary); }
  .clear { border: 0; background: transparent; color: var(--secondary); font-size: 20px; line-height: 1; cursor: pointer; padding: 0 2px; }
  .new {
    flex: none; display: flex; align-items: center; gap: 6px; height: 44px; padding: 0 18px 0 14px; border: 0; border-radius: 22px;
    background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer;
  }
  .new:hover { filter: brightness(1.06); }
  .new:active { transform: scale(0.97); }

  .gear {
    flex: none; width: 44px; height: 44px; border: 0; border-radius: 22px; display: grid; place-items: center;
    background: transparent; color: var(--secondary); cursor: pointer;
  }
  .gear:hover { background: var(--wash); color: var(--text); }
  .syncline {
    flex: none; align-self: center; margin: -2px 0 4px; padding: 2px 10px; border: 0; border-radius: 8px; background: transparent;
    color: var(--secondary); font: inherit; font-size: 12.5px; cursor: pointer;
  }
  .syncline:hover { color: var(--text); }
  .syncline.warn { color: var(--danger); font-weight: 600; }
  .vaultbar { flex: none; display: flex; align-items: center; gap: 8px; padding: 12px 20px 0; color: var(--accent); }
  .vaultbar strong { font-size: 17px; letter-spacing: -0.01em; }
  .vaultbar .grow { flex: 1; }
  .vaultbar button { border: 0; border-radius: 12px; padding: 7px 14px; background: var(--wash); color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .chips { flex: none; display: flex; gap: 6px; padding: 4px 16px 8px; overflow-x: auto; scrollbar-width: none; }
  .chip {
    flex: none; border: 1px solid var(--hairline); border-radius: 999px; padding: 6px 14px; background: transparent; color: var(--secondary);
    font: inherit; font-size: 13px; cursor: pointer; white-space: nowrap;
  }
  .chip:hover { color: var(--text); }
  .chip.on { background: var(--wash); color: var(--accent); border-color: transparent; font-weight: 600; }

  main { flex: 1; min-height: 0; overflow-y: auto; padding: 6px 16px 96px; }
  .grid { display: grid; grid-template-columns: repeat(var(--cols), minmax(0, 1fr)); gap: 12px; max-width: 1400px; margin: 0 auto; align-items: start; }
  .col { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
  .note {
    border-radius: 16px; background: var(--surface); border: 1px solid var(--hairline); cursor: pointer; overflow: hidden;
    outline: none; transition: border-color 120ms, box-shadow 120ms;
  }
  .note:hover { border-color: color-mix(in srgb, var(--accent) 40%, var(--hairline)); }
  .note:focus-visible { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent); }
  .note:active { transform: scale(0.99); }
  .inner { padding: 12px 14px; }
  .t { font-weight: 650; font-size: 15.5px; letter-spacing: -0.01em; margin-bottom: 4px; overflow-wrap: anywhere; }
  .b { color: var(--secondary); font-size: 13.5px; line-height: 1.5; overflow-wrap: anywhere; max-height: 15em; overflow: hidden; }
  .meta { display: flex; align-items: center; gap: 6px; margin-top: 8px; }
  .cat { font-size: 11.5px; color: var(--accent); background: var(--wash); border-radius: 999px; padding: 2px 9px; font-weight: 600; max-width: 70%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pin { color: var(--accent); margin-left: auto; }

  /* the preview's own markup (preview.ts) */
  .b :global(.h) { color: var(--text); font-weight: 650; }
  .b :global(.h1) { font-size: 1.15em; }
  .b :global(.li) { display: flex; gap: 6px; padding-inline-start: calc(var(--lvl) * 14px); }
  .b :global(.li.done) { text-decoration: line-through; opacity: 0.65; }
  .b :global(.box) { flex: none; width: 11px; height: 11px; margin-top: 0.3em; border: 1.5px solid currentColor; border-radius: 3.5px; }
  .b :global(.box.on) { background: var(--accent); border-color: var(--accent); }
  .b :global(.num), .b :global(.dot) { flex: none; min-width: 0.9em; }
  .b :global(.q) { border-inline-start: 2.5px solid var(--accent); padding-inline-start: 8px; font-style: italic; }
  .b :global(.code), .b :global(code) { font-family: ui-monospace, "JetBrains Mono", monospace; font-size: 0.92em; }
  .b :global(.code) { white-space: pre-wrap; background: var(--track); padding: 0 6px; }
  .b :global(code) { background: var(--track); border-radius: 4px; padding: 0 3px; }
  .b :global(.lnk) { color: var(--accent); }
  .b :global(.tb) { border: 1px solid var(--hairline); border-radius: 8px; padding: 3px 7px; margin: 2px 0; }
  .b :global(.th) { font-weight: 650; color: var(--text); }
  .b :global(hr) { border: 0; border-top: 1px solid var(--hairline); margin: 6px 0; }

  .empty { display: flex; flex-direction: column; align-items: center; gap: 12px; margin-top: 18vh; color: var(--secondary); }
  .empty p { margin: 0; font-size: 16px; }

  .undo {
    position: fixed; left: 50%; bottom: 22px; transform: translateX(-50%); z-index: 40; display: flex; align-items: center; gap: 14px;
    padding: 8px 8px 8px 18px; border-radius: 16px; background: var(--text); color: var(--background);
    box-shadow: 0 12px 32px rgb(0 0 0 / 0.25); animation: rise 200ms cubic-bezier(0.2, 0.9, 0.3, 1);
  }
  .undo button { border: 0; border-radius: 10px; padding: 7px 14px; background: transparent; color: var(--wash); font: inherit; font-weight: 700; cursor: pointer; }
  .undo button:hover { background: color-mix(in srgb, var(--background) 14%, transparent); }

  .menu {
    position: fixed; z-index: 45; min-width: 180px; padding: 6px; border-radius: 14px; display: flex; flex-direction: column;
    background: var(--floating); border: 1px solid var(--hairline); box-shadow: 0 14px 40px rgb(0 0 0 / 0.22);
    animation: pop 120ms cubic-bezier(0.2, 0.9, 0.3, 1); transform-origin: top left;
  }
  .menu button { border: 0; border-radius: 9px; padding: 8px 12px; text-align: start; background: transparent; color: var(--text); font: inherit; cursor: pointer; }
  .menu button:hover { background: var(--wash); }
  .menu .danger { color: var(--danger); }

  @keyframes rise { from { opacity: 0; transform: translate(-50%, 12px); } }
  @keyframes pop { from { opacity: 0; transform: scale(0.96); } }
  @media (prefers-reduced-motion: reduce) { .undo, .menu { animation: none; } .note { transition: none; } }
</style>
