<script lang="ts">
  import { onMount, tick } from "svelte";
  import { canonicalCategory, categoryNames, cleanCategory, CATEGORY_MAX, newNote, plain, type Note } from "@scratchy/core";
  import { notes } from "../lib/notes";
  import type { SyncStatus } from "../lib/sync";
  import { cardHeading, cardPreview, render } from "../lib/preview";
  import { applyTheme } from "../lib/theme";
  import Icon from "../lib/Icon.svelte";
  import Sheet, { type SheetItem } from "../lib/Sheet.svelte";
  import NotePage from "./NotePage.svelte";
  import Settings from "./Settings.svelte";
  import VaultGate from "./VaultGate.svelte";
  import Thumb from "./Thumb.svelte";
  import { formatWhen } from "./when";
  import "../lib/base.css";
  import "../lib/editor/editor.css";

  /** The notes window, laid out like the phone's MainActivity: title, status, search, category chips, two-up cards. */
  applyTheme();
  // Every component's styles end up in one sheet shared with the see-through card window, so the page styles key off this.
  document.documentElement.classList.add("notes");

  let all: Note[] = $state([]);
  let ready = $state(false);
  let query = $state("");
  /** null: every note; "": the ones with no category; otherwise one category. */
  let filter: string | null = $state(null);
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
  const plainNotes = $derived(all.filter((n) => !n.secret));
  const categories = $derived(categoryNames(plainNotes));
  const uncategorized = $derived(plainNotes.filter((n) => !n.category.trim()).length);
  const shown = $derived.by(() => {
    const q = query.trim().toLowerCase();
    return visible
      .filter((n) => secret || filter == null || n.category === filter)
      .filter((n) => !q || n.title.toLowerCase().includes(q) || n.body.split("\n").map(plain).join("\n").toLowerCase().includes(q) || n.category.toLowerCase().includes(q))
      .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt);
  });
  // Two columns like the phone; more when the window is wide.
  const GAP = 10;
  const cols = $derived(Math.max(2, Math.min(6, Math.floor((width - 40 + GAP) / (230 + GAP)))));
  /** Rough card height, only used to balance the columns (estimateHeight on the phone). */
  const estimate = (n: Note) => {
    const p = cardPreview(n);
    return 90 + Math.min(6, Math.floor(p.length / 18) + (p.match(/\n/g)?.length ?? 0)) * 22 + (n.images.length ? 130 : 0);
  };
  /** Each note goes to the shorter column, so cards of different heights pack neatly. */
  const columns = $derived.by(() => {
    const out: Note[][] = Array.from({ length: cols }, () => []);
    const h = new Array(cols).fill(0);
    for (const n of shown) { const i = h.indexOf(Math.min(...h)); h[i] += estimate(n); out[i].push(n); }
    return out;
  });
  /** The open note as the store has it now (another device may have changed it), or the new one. */
  const live = $derived(open ? (all.find((n) => n.id === open!.id) ?? open) : null);

  $effect(() => { if (filter && !categories.includes(filter)) filter = null; });

  function create() {
    menu = null;
    open = { ...newNote(crypto.randomUUID()), secret, category: secret ? "" : filter ?? "" };
  }
  function edit(n: Note) { menu = null; open = n; }

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

  // ---- delete with undo ----

  function remove(n: Note) {
    menu = null;
    if (open?.id === n.id) open = null;
    if (!all.some((x) => x.id === n.id)) return;           // a new note that was never saved
    if (undo) clearTimeout(undo.timer);
    notes.remove(n.id);
    undo = { note: n, timer: setTimeout(() => (undo = null), 5000) };
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
    menu = { note: n, x: Math.min(e.clientX, innerWidth - 196), y: Math.min(e.clientY, innerHeight - 170) };
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
      else if (filter != null) filter = null;
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
    <NotePage note={live} {categories} {vaultSetUp} onback={back} ondelete={remove} />
  {:else if settings}
    <Settings {sync} {vaultSetUp} onback={() => (settings = false)} />
  {:else}
    <div class="column">
      <div class="titlerow">
        <h1>{secret ? "Secret" : "Notes"}</h1>
        {#if secret}
          <button class="iconbtn accent" onclick={lock} title="Lock now" aria-label="Lock now"><Icon name="lock" size={22} /></button>
        {:else}
          <button class="iconbtn" onclick={() => (settings = true)} title="Settings (Ctrl+,)" aria-label="Settings"><Icon name="settings" size={22} /></button>
        {/if}
      </div>

      {#if !secret}
        <div class="status"><span class="dot"></span>Press Super+Alt+N from any app</div>
        {#if sync.connected}
          <button class="syncline" class:warn={sync.state === "attention"} onclick={() => (settings = true)}>Sync: {sync.text}</button>
        {/if}
      {/if}

      <label class="search">
        <Icon name="search" size={20} />
        <input bind:this={searchEl} bind:value={query} placeholder="Search" spellcheck="false" dir="auto"
          onkeydown={(e) => { if (e.key === "ArrowDown" || e.key === "Enter") { e.preventDefault(); document.querySelector<HTMLElement>(".note")?.focus(); } }} />
      </label>

      {#if !secret && plainNotes.length}
        <div class="chips" role="tablist" aria-label="Categories">
          <button class="chip" class:on={filter == null} role="tab" aria-selected={filter == null} onclick={() => (filter = null)}>All</button>
          {#each categories as c}
            <button class="chip" class:on={filter === c} role="tab" aria-selected={filter === c} onclick={() => (filter = c)} dir="auto">{c}</button>
          {/each}
          {#if categories.length && uncategorized}
            <button class="chip" class:on={filter === ""} role="tab" aria-selected={filter === ""} onclick={() => (filter = "")}>None</button>
          {/if}
        </div>
      {/if}

      <main class:nochips={secret || !plainNotes.length}>
        {#if !ready}
          <!-- the first message from the engine is a few milliseconds away -->
        {:else if !shown.length}
          <p class="empty">{query.trim() ? "No matches." : secret ? "No secret notes yet. Press Ctrl+N." : "No notes yet. Press Ctrl+N or Super+Alt+N."}</p>
        {:else}
          <div class="grid" style:--cols={cols}>
            {#each columns as col}
              <div class="col">
                {#each col as n (n.id)}
                  {@const body = cardPreview(n)}
                  <div class="note" data-id={n.id} role="button" tabindex="0" aria-label={cardHeading(n)}
                    onclick={() => edit(n)} onkeydown={(e) => cardKeys(e, n)} oncontextmenu={(e) => contextMenu(e, n)}>
                    <div class="t" dir="auto">{cardHeading(n)}</div>
                    {#if n.images.length}
                      <div class="img">
                        <Thumb name={n.images[0]} />
                        {#if n.images.length > 1}<span class="more">+{n.images.length - 1}</span>{/if}
                      </div>
                    {/if}
                    <div class="b" dir="auto">{#if body}{@html render(body)}{:else}No text{/if}</div>
                    <div class="meta">
                      <span class="when">{formatWhen(n.updatedAt)}</span>
                      {#if n.category.trim() && filter !== n.category}<span class="cat" dir="auto">{n.category}</span>{/if}
                      {#if n.pinned}<span class="pin" aria-label="Pinned"><Icon name="pin" size={16} /></span>{/if}
                    </div>
                  </div>
                {/each}
              </div>
            {/each}
          </div>
        {/if}
      </main>
    </div>

    <button class="fab" onclick={create} title="New note (Ctrl+N)" aria-label="New note"><Icon name="add" size={24} /></button>
  {/if}

  {#if notice}
    <div class="bar" role="status"><span>{notice.text}</span><button onclick={() => (notice = null)}>OK</button></div>
  {:else if undo}
    <div class="bar" role="status"><span>Note deleted</span><button onclick={restore}>Undo</button></div>
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
  <VaultGate setUp={vaultSetUp} onopen={() => { gate = false; secret = true; query = ""; filter = null; }} onclose={() => (gate = false)} />
{/if}

<style>
  :global(html.notes), :global(html.notes body) { margin: 0; height: 100%; background: var(--background); color: var(--text); overflow: hidden; }
  :global(html.notes body) { font-size: 15px; line-height: normal; }
  :global(html.notes #app) { height: 100%; }
  .app { position: relative; height: 100%; display: flex; flex-direction: column; overflow: hidden; }

  .column { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: 24px 20px 0; }
  .titlerow { flex: none; display: flex; align-items: center; }
  h1 { flex: 1; margin: 0; font-size: 34px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.2; }
  .iconbtn {
    width: 40px; height: 40px; margin-inline-start: 4px; border: 0; border-radius: 20px; display: grid; place-items: center;
    background: transparent; color: var(--secondary); cursor: pointer; transition: transform 150ms;
  }
  .iconbtn.accent { color: var(--accent); }
  .iconbtn:hover { background: color-mix(in srgb, var(--text) 7%, transparent); }
  .iconbtn:active { transform: scale(0.85); }

  .status { flex: none; display: flex; align-items: center; gap: 8px; padding: 6px 0 14px 2px; font-size: 14px; color: var(--secondary); }
  .dot { width: 8px; height: 8px; border-radius: 4px; background: var(--success); }
  .syncline {
    flex: none; align-self: flex-start; margin: 0; padding: 0 0 10px 2px; border: 0; background: transparent;
    color: var(--secondary); font: inherit; font-size: 13px; cursor: pointer; text-align: start;
  }
  .syncline:hover { color: var(--text); }
  .syncline.warn { color: var(--accent); }

  .search {
    flex: none; display: flex; align-items: center; height: 42px; padding: 0 12px; border-radius: 12px;
    background: var(--surface); color: var(--secondary); cursor: text;
  }
  .search input { flex: 1; min-width: 0; height: 100%; padding: 0 0 0 8px; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: 16px; }
  .search input::placeholder { color: var(--secondary); }

  .chips { flex: none; display: flex; gap: 8px; padding: 10px 0; overflow-x: auto; scrollbar-width: none; }
  .chip {
    flex: none; height: 34px; padding: 0 14px; border: 0; border-radius: 17px; background: var(--surface); color: var(--text);
    font: inherit; font-size: 14px; font-weight: 500; white-space: nowrap; cursor: pointer; transition: transform 150ms;
  }
  .chip.on { background: var(--accent); color: var(--on-accent); }
  .chip:active { transform: scale(0.95); }

  main { flex: 1; min-height: 0; overflow-y: auto; scrollbar-width: none; margin: 0 -20px; padding: 8px 20px 96px; }
  main.nochips { padding-top: 18px; }
  .grid { display: grid; grid-template-columns: repeat(var(--cols), minmax(0, 1fr)); gap: 10px; align-items: start; }
  .col { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
  .note {
    padding: 16px 16px 14px; border-radius: 24px; background: var(--surface); cursor: pointer; outline: none;
    transition: transform 160ms cubic-bezier(0.2, 0.9, 0.3, 1);
  }
  .note:active { transform: scale(0.97); }
  .note:focus-visible { box-shadow: 0 0 0 2px var(--accent); }
  .t {
    font-size: 17px; font-weight: 700; color: var(--text); line-height: 1.3; overflow-wrap: anywhere;
    display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; unicode-bidi: plaintext; text-align: start;
  }
  .img { position: relative; height: 120px; margin-top: 10px; border-radius: 12px; overflow: hidden; background: var(--hairline); }
  .more { position: absolute; right: 8px; bottom: 8px; padding: 3px 8px; border-radius: 10px; background: rgb(0 0 0 / 0.6); color: #fff; font-size: 13px; }
  .b {
    padding-top: 10px; font-size: 15px; line-height: 1.3; color: var(--secondary); white-space: pre-wrap; overflow-wrap: anywhere;
    display: -webkit-box; -webkit-line-clamp: 6; line-clamp: 6; -webkit-box-orient: vertical; overflow: hidden; unicode-bidi: plaintext; text-align: start;
  }
  .b :global(code) { font-family: ui-monospace, "JetBrains Mono", monospace; background: color-mix(in srgb, var(--secondary) 15%, transparent); }
  .b :global(.lnk) { color: var(--accent); }
  .b :global(.dim) { color: color-mix(in srgb, var(--secondary) 67%, transparent); }
  .b :global(.done) { color: var(--secondary); }
  .b :global(.box) {
    display: inline-block; width: 0.95em; height: 0.95em; vertical-align: -0.12em; box-sizing: border-box;
    border: 1.7px solid var(--secondary); border-radius: 0.285em;
  }
  .b :global(.box.on) {
    border: 0;
    background: var(--accent) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Cpath d='M26 54 43 70 76 32' fill='none' stroke='white' stroke-width='14' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E") center / 100% no-repeat;
  }
  .meta { display: flex; align-items: center; padding-top: 12px; }
  .when { flex: 1; font-size: 13px; color: var(--secondary); }
  .cat {
    max-width: 90px; margin-inline-end: 8px; padding: 2px 8px; border-radius: 9px; font-size: 12px; color: var(--accent);
    background: color-mix(in srgb, var(--accent) 15%, transparent); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .pin { color: var(--accent); }
  .empty { margin: 0; padding: 16px 0 0 4px; font-size: 15px; color: var(--secondary); }

  .fab {
    position: absolute; right: 20px; bottom: 24px; width: 56px; height: 56px; border: 0; border-radius: 28px; display: grid; place-items: center;
    background: var(--accent); color: var(--on-accent); cursor: pointer; box-shadow: 0 6px 14px rgb(0 0 0 / 0.28);
    transition: transform 160ms cubic-bezier(0.2, 0.9, 0.3, 1);
  }
  .fab:active { transform: scale(0.92); }

  .bar {
    position: absolute; left: 20px; right: 96px; bottom: 28px; z-index: 40; height: 48px; display: flex; align-items: center;
    padding: 0 6px 0 18px; border-radius: 24px; background: var(--text); color: var(--background); font-size: 15px;
    box-shadow: 0 8px 20px rgb(0 0 0 / 0.25); animation: rise 350ms cubic-bezier(0.2, 0.9, 0.3, 1);
  }
  .bar span { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .bar button { border: 0; padding: 12px 14px; background: transparent; color: var(--accent-inverse); font: inherit; font-weight: 500; cursor: pointer; }

  .menu {
    position: fixed; z-index: 45; min-width: 180px; padding: 6px; border-radius: 16px; display: flex; flex-direction: column;
    background: var(--floating); border: 1px solid var(--hairline); box-shadow: 0 14px 40px rgb(0 0 0 / 0.22);
    animation: pop 120ms cubic-bezier(0.2, 0.9, 0.3, 1); transform-origin: top left;
  }
  .menu button { border: 0; border-radius: 10px; padding: 9px 12px; text-align: start; background: transparent; color: var(--text); font: inherit; font-size: 15px; cursor: pointer; }
  .menu button:hover { background: color-mix(in srgb, var(--text) 7%, transparent); }
  .menu .danger { color: var(--danger); }

  @keyframes rise { from { transform: translateY(90px); } }
  @keyframes pop { from { opacity: 0; transform: scale(0.96); } }
  @media (prefers-reduced-motion: reduce) { .bar, .menu { animation: none; } .note, .fab, .chip { transition: none; } }
</style>
