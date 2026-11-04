<script lang="ts">
  import { onMount } from "svelte";
  import { canonical, canonicalCategory, cleanCategory, CATEGORY_MAX, type Note } from "@scratchy/core";
  import RichEditor from "../lib/editor/RichEditor.svelte";
  import Sheet, { type SheetItem } from "../lib/Sheet.svelte";
  import AaPanel from "./AaPanel.svelte";
  import { notes } from "../lib/notes";

  /** The full note editor, with autosave. A change that arrives from elsewhere is shown unless you are mid-edit. */
  interface Props { note: Note; categories: string[]; onback: () => void; ondelete: (n: Note) => void }
  let { note, categories, onback, ondelete }: Props = $props();

  let editor: RichEditor = $state()!;
  // svelte-ignore state_referenced_locally
  let title = $state(note.title);
  // svelte-ignore state_referenced_locally
  let pinned = $state(note.pinned);
  // svelte-ignore state_referenced_locally
  let category = $state(note.category);
  /** What this page last loaded or saved, and the body as the phone would write it (untouched notes keep their text). */
  // svelte-ignore state_referenced_locally
  let base = { ...note };
  let canon = "";
  let panel = $state(false);
  let version = $state(0);
  let sheet: { title: string; items?: SheetItem[]; field?: { label: string; value: string; max?: number }; confirm?: { label: string; danger?: boolean; run: (v: string) => void } } | null = $state(null);
  let titleEl: HTMLInputElement;

  function current(): Note {
    const md = editor.markdown();
    return { ...base, title, pinned, category, body: md === canon ? base.body : md };
  }
  const dirty = () => { const c = current(); return c.title !== base.title || c.body !== base.body || c.pinned !== base.pinned || c.category !== base.category; };

  let timer: ReturnType<typeof setTimeout> | undefined;
  function save() {
    clearTimeout(timer);
    if (!editor || !dirty()) return;
    const c = { ...current(), updatedAt: Date.now() };
    canon = editor.markdown();
    base = c;
    notes.save(c);
  }
  const typed = () => { clearTimeout(timer); timer = setTimeout(save, 400); };

  function load(n: Note) {
    base = { ...n };
    title = n.title; pinned = n.pinned; category = n.category;
    canon = canonical(n.body);
    editor.load(n.body);
  }

  // A newer version from the card or another device: take it, unless there is unsaved typing here.
  $effect(() => {
    const n = note;
    if (!editor || n.id !== base.id) return;
    if ((n.title !== base.title || n.body !== base.body || n.pinned !== base.pinned || n.category !== base.category) && !dirty()) load(n);
  });

  function back() { save(); onback(); }

  function pickCategory() {
    const items: SheetItem[] = categories.map((c) => ({ label: c, checked: c === category, run: () => { category = c; save(); } }));
    items.push({ label: "New category…", accent: true, run: () => setTimeout(newCategory) });
    if (category) items.push({ label: "No category", run: () => { category = ""; save(); } });
    sheet = { title: "Category", items };
  }
  function newCategory() {
    sheet = { title: "New category", field: { label: "Category name", value: "", max: CATEGORY_MAX },
      confirm: { label: "Add", run: (v) => { const c = cleanCategory(v); if (c) { category = canonicalCategory(c, categories); save(); } } } };
  }
  function editLink() {
    const ed = editor.instance();
    if (!ed) return;
    const had = ed.getAttributes("link").href ?? "";
    sheet = { title: had ? "Edit link" : "Add link", field: { label: "https://…", value: had },
      confirm: { label: "Save", run: (v) => {
        const url = v.trim();
        if (!url) ed.chain().focus().extendMarkRange("link").unsetLink().run();
        else ed.chain().focus().extendMarkRange("link").setLink({ href: url }).run();
      } } };
  }

  function keys(e: KeyboardEvent) {
    if (sheet) return;
    if (e.key === "Escape") { e.preventDefault(); if (panel) panel = false; else back(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") { e.preventDefault(); save(); }
  }

  onMount(() => {
    load(note);
    if (!note.title && !note.body) titleEl.focus(); else editor.focus(true);
    return () => save();
  });
</script>

<svelte:window onkeydown={keys} onblur={save} />

<div class="page">
  <header>
    <button class="icon" onclick={back} title="Back (Esc)" aria-label="Back">
      <svg viewBox="0 0 24 24" width="20" height="20"><path d="M15 5 8 12l7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
    </button>
    <span class="grow"></span>
    {#if !note.secret}<button class="chip" class:set={!!category} onclick={pickCategory} title="Category">{category || "Category"}</button>{/if}
    <button class="icon" class:on={panel} onclick={() => (panel = !panel)} title="Format" aria-label="Format" aria-pressed={panel}><span class="aa">Aa</span></button>
    <button class="icon" class:on={pinned} onclick={() => { pinned = !pinned; save(); }} title={pinned ? "Unpin" : "Pin"} aria-label="Pin" aria-pressed={pinned}>
      <svg viewBox="0 0 24 24" width="19" height="19"><path d="M14.5 3.5 20.5 9.5 17 11l-3.5 3.5.5 4-1.5 1.5-3.5-3.5L5 20.5 3.5 19 7.5 15 4 11.5 5.5 10l4 .5L13 7z" fill={pinned ? "currentColor" : "none"} stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" /></svg>
    </button>
    <button class="icon" onclick={() => { save(); ondelete(current()); }} title="Delete" aria-label="Delete">
      <svg viewBox="0 0 24 24" width="19" height="19"><path d="M5 7h14M10 7V5h4v2M7 7l1 12h8l1-12" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
    </button>
    {#if panel && editor?.instance()}
      <AaPanel editor={editor.instance()!} {version} onlink={editLink} onclose={() => (panel = false)} />
    {/if}
  </header>
  <div class="paper">
    <input class="title" bind:this={titleEl} bind:value={title} oninput={typed} placeholder="Title" dir="auto" spellcheck="false"
      onkeydown={(e) => { if (e.key === "Enter" || e.key === "ArrowDown") { e.preventDefault(); editor.focus(); } }} />
    <RichEditor bind:this={editor} class="body" placeholder="Write it down…" onchange={typed} onselect={() => version++} />
  </div>
</div>

{#if sheet}
  <Sheet {...sheet} onclose={() => (sheet = null)} />
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; }
  header { position: relative; flex: none; display: flex; align-items: center; gap: 4px; padding: 10px 12px; }
  .grow { flex: 1; }
  .icon {
    width: 40px; height: 40px; border: 0; border-radius: 13px; display: grid; place-items: center;
    background: transparent; color: var(--secondary); cursor: pointer;
  }
  .icon:hover { background: var(--wash); color: var(--text); }
  .icon:active { transform: scale(0.95); }
  .icon.on { color: var(--accent); background: var(--wash); }
  .aa { font-weight: 700; font-size: 16px; letter-spacing: -0.02em; }
  .chip {
    border: 1px solid var(--hairline); border-radius: 999px; padding: 6px 13px; background: transparent; color: var(--secondary);
    font: inherit; font-size: 13px; cursor: pointer; margin-right: 4px; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  .chip.set { color: var(--accent); background: var(--wash); border-color: transparent; font-weight: 600; }
  .chip:hover { border-color: var(--accent); }
  .paper { flex: 1; min-height: 0; display: flex; flex-direction: column; width: 100%; max-width: 780px; margin: 0 auto; box-sizing: border-box; }
  .title {
    flex: none; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit;
    font-size: 26px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.2; padding: 8px 28px 10px;
  }
  .title::placeholder { color: var(--secondary); opacity: 0.6; }
  .paper :global(.body) { flex: 1; }
  .paper :global(.body .rich) { padding: 0 28px 40vh; font-size: 16px; }
</style>
