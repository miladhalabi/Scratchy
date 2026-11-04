<script lang="ts">
  import { onMount } from "svelte";
  import { canonical, canonicalCategory, cleanCategory, CATEGORY_MAX, type Note } from "@scratchy/core";
  import RichEditor from "../lib/editor/RichEditor.svelte";
  import Icon from "../lib/Icon.svelte";
  import Sheet, { type SheetItem } from "../lib/Sheet.svelte";
  import AaPanel from "./AaPanel.svelte";
  import { notes } from "../lib/notes";

  /** The full note screen (NoteActivity on the phone), with autosave. A change from elsewhere shows unless you are mid-edit. */
  interface Props { note: Note; categories: string[]; vaultSetUp: boolean; onback: () => void; ondelete: (n: Note) => void }
  let { note, categories, vaultSetUp, onback, ondelete }: Props = $props();

  let editor: RichEditor = $state()!;
  // svelte-ignore state_referenced_locally
  let title = $state(note.title);
  // svelte-ignore state_referenced_locally
  let pinned = $state(note.pinned);
  // svelte-ignore state_referenced_locally
  let category = $state(note.category);
  // svelte-ignore state_referenced_locally
  let secret = $state(note.secret);
  /** What this page last loaded or saved, and the body as the phone would write it (untouched notes keep their text). */
  // svelte-ignore state_referenced_locally
  let base = { ...note };
  let canon = "";
  let panel = $state(false);
  let version = $state(0);
  let sheet: { title: string; message?: string; items?: SheetItem[]; field?: { label: string; value: string; max?: number }; confirm?: { label: string; danger?: boolean; run: (v: string) => void } } | null = $state(null);
  let toast: { text: string; timer: ReturnType<typeof setTimeout> } | null = $state(null);
  let titleEl: HTMLTextAreaElement;

  function say(text: string) {
    if (toast) clearTimeout(toast.timer);
    toast = { text, timer: setTimeout(() => (toast = null), 2200) };
  }

  function current(): Note {
    const md = editor.markdown();
    return { ...base, title, pinned, secret, category: secret ? "" : category, body: md === canon ? base.body : md };
  }
  const dirty = () => {
    const c = current();
    return c.title !== base.title || c.body !== base.body || c.pinned !== base.pinned || c.category !== base.category || c.secret !== base.secret;
  };

  let timer: ReturnType<typeof setTimeout> | undefined;
  function save() {
    clearTimeout(timer);
    if (!editor || !dirty()) return;
    const c = { ...current(), updatedAt: Date.now() };
    canon = editor.markdown();
    base = c;
    notes.save(c);
  }
  const typed = () => { clearTimeout(timer); timer = setTimeout(save, 500); };

  function load(n: Note) {
    base = { ...n };
    title = n.title; pinned = n.pinned; category = n.category; secret = n.secret;
    canon = canonical(n.body);
    editor.load(n.body);
    requestAnimationFrame(fitTitle);
  }

  // A newer version from the card or another device: take it, unless there is unsaved typing here.
  $effect(() => {
    const n = note;
    if (!editor || n.id !== base.id) return;
    if ((n.title !== base.title || n.body !== base.body || n.pinned !== base.pinned || n.category !== base.category) && !dirty()) load(n);
  });

  function back() { save(); onback(); }

  function togglePin() { pinned = !pinned; save(); }
  function toggleSecret() {
    if (!secret && !vaultSetUp) { say("Set up secret notes first: press Ctrl+Shift+L in the notes list"); return; }
    secret = !secret;
    save();
    say(secret ? "Secret. Find it under the lock." : "No longer secret");
  }
  function confirmDelete() {
    sheet = { title: "Delete this note?", message: "This can't be undone.",
      confirm: { label: "Delete", danger: true, run: () => { save(); ondelete(current()); } } };
  }

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

  /** The title wraps onto up to three lines, like the phone's. */
  function fitTitle() {
    if (!titleEl) return;
    titleEl.style.height = "auto";
    titleEl.style.height = `${titleEl.scrollHeight}px`;
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

<svelte:window onkeydown={keys} onblur={save} onresize={fitTitle} />

<div class="page">
  <div class="top">
    <button class="icon text" onclick={back} title="Back (Esc)" aria-label="Back"><Icon name="back" size={26} /></button>
    <span class="grow"></span>
    <button class="icon accent" class:on={panel} onclick={() => (panel = !panel)} title="Format" aria-label="Format" aria-pressed={panel}><Icon name="format" size={26} /></button>
    <button class="icon" class:accent={secret} onclick={toggleSecret} title="Secret note" aria-label="Secret note" aria-pressed={secret}><Icon name="lock" size={26} /></button>
    <button class="icon" class:accent={pinned} onclick={togglePin} title={pinned ? "Unpin" : "Pin"} aria-label="Pin" aria-pressed={pinned}><Icon name="pin" size={26} /></button>
    <button class="icon" onclick={confirmDelete} title="Delete" aria-label="Delete"><Icon name="delete" size={26} /></button>
    {#if panel && editor?.instance()}
      <AaPanel editor={editor.instance()!} {version} onlink={editLink} onclose={() => (panel = false)} />
    {/if}
  </div>
  {#if !secret}
    <button class="pill" class:set={!!category} onclick={pickCategory} dir="auto">{category || "＋ Category"}</button>
  {/if}
  <div class="editor">
    <textarea class="title" bind:this={titleEl} bind:value={title} oninput={() => { fitTitle(); typed(); }} placeholder="Title" dir="auto" rows="1" spellcheck="false"
      onkeydown={(e) => { if (e.key === "Enter") { e.preventDefault(); editor.focus(); } }}></textarea>
    <div class="rule"></div>
    <RichEditor bind:this={editor} class="body" placeholder="Start typing…" onchange={typed} onselect={() => version++} />
  </div>
</div>

{#if toast}<div class="toast" role="status">{toast.text}</div>{/if}

{#if sheet}
  <Sheet {...sheet} onclose={() => (sheet = null)} />
{/if}

<style>
  .page { height: 100%; display: flex; flex-direction: column; }
  .top { position: relative; flex: none; display: flex; align-items: center; padding: 8px 8px 4px; }
  .grow { flex: 1; }
  .icon {
    width: 44px; height: 44px; border: 0; border-radius: 22px; display: grid; place-items: center;
    background: transparent; color: var(--secondary); cursor: pointer; transition: transform 150ms;
  }
  .icon.text { color: var(--text); }
  .icon.accent { color: var(--accent); }
  .icon:hover, .icon.on { background: color-mix(in srgb, var(--text) 7%, transparent); }
  .icon:active { transform: scale(0.85); }
  .pill {
    flex: none; align-self: flex-start; height: 30px; max-width: 260px; margin: 0 0 4px 20px; padding: 0 12px; border: 0; border-radius: 15px;
    background: var(--surface); color: var(--secondary); font: inherit; font-size: 13px; font-weight: 500;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer; transition: transform 150ms;
  }
  .pill.set { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
  .pill:active { transform: scale(0.95); }
  .editor { flex: 1; min-height: 0; display: flex; flex-direction: column; width: 100%; max-width: 820px; margin: 0 auto; padding: 0 16px; box-sizing: border-box; }
  .title {
    flex: none; resize: none; overflow: hidden; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit;
    font-size: 26px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.25; padding: 8px 4px 4px; max-height: calc(3 * 1.25em + 12px);
    unicode-bidi: plaintext; text-align: start;
  }
  .title::placeholder { color: var(--secondary); }
  .rule { flex: none; height: 1px; margin: 0 4px; background: var(--hairline); }
  .editor :global(.body) { flex: 1; min-height: 0; }
  .editor :global(.body .rich) { padding: 8px 4px 40vh; font-size: 17px; }
  .toast {
    position: fixed; left: 50%; bottom: 40px; transform: translateX(-50%); z-index: 70; max-width: calc(100vw - 48px);
    padding: 10px 16px; border-radius: 20px; background: color-mix(in srgb, var(--text) 88%, transparent); color: var(--background);
    font-size: 14px; animation: fade 160ms ease-out;
  }
  @keyframes fade { from { opacity: 0; } }
</style>
