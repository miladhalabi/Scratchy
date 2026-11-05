<script lang="ts">
  import { onMount } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { canonical, canonicalCategory, cleanCategory, CATEGORY_MAX, type Note } from "@scratchy/core";
  import RichEditor from "../lib/editor/RichEditor.svelte";
  import Icon from "../lib/Icon.svelte";
  import Sheet, { type SheetItem } from "../lib/Sheet.svelte";
  import AaPanel from "./AaPanel.svelte";
  import AiTitles from "../lib/AiTitles.svelte";
  import FormatIcon from "../lib/editor/FormatIcon.svelte";
  import NoteImages from "../lib/NoteImages.svelte";
  import ImageViewer from "../lib/ImageViewer.svelte";
  import { clipBlob, imageFiles, importImage, readClipboard } from "../lib/attach";
  import { notes } from "../lib/notes";
  import { aiConfigured, categorize, formatMarkdown } from "../lib/ai";
  import { settings } from "../lib/settings";

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
  // svelte-ignore state_referenced_locally
  let images = $state([...note.images]);
  let viewing: string | null = $state(null);
  /** What this page last loaded or saved, and the body as the phone would write it (untouched notes keep their text). */
  // svelte-ignore state_referenced_locally
  let base = { ...note };
  let canon = "";
  let panel = $state(false);
  let version = $state(0);
  let sheet: { title: string; message?: string; items?: SheetItem[]; field?: { label: string; value: string; max?: number }; confirm?: { label: string; danger?: boolean; run: (v: string) => void } } | null = $state(null);
  let toast: { text: string; timer: ReturnType<typeof setTimeout> } | null = $state(null);
  let titleEl: HTMLTextAreaElement;
  let titles: AiTitles = $state()!;
  /** The quiet category offer under the title: asked at most once per note, never for secret notes. */
  let offer = $state("");
  let offered = false;
  let offerTimer: ReturnType<typeof setTimeout> | undefined, hideOffer: ReturnType<typeof setTimeout> | undefined;
  /** The AI formatting chip: what it is doing, or what it did (with Undo). */
  let aiChip: { text: string; accent: boolean; tap?: () => void } | null = $state(null);
  let aiTimer: ReturnType<typeof setTimeout> | undefined;
  let aiBusy = false;

  function say(text: string) {
    if (toast) clearTimeout(toast.timer);
    toast = { text, timer: setTimeout(() => (toast = null), 2200) };
  }

  function current(): Note {
    const md = editor.markdown();
    return { ...base, title, pinned, secret, category: secret ? "" : category, body: md === canon ? base.body : md, images: [...images] };
  }
  const sameImages = (a: string[], b: string[]) => a.length === b.length && a.every((x, i) => x === b[i]);
  const dirty = () => {
    const c = current();
    return c.title !== base.title || c.body !== base.body || c.pinned !== base.pinned || c.category !== base.category || c.secret !== base.secret
      || !sameImages(c.images, base.images);
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
  const typed = () => {
    clearTimeout(timer); timer = setTimeout(save, 500);
    clearTimeout(offerTimer); offerTimer = setTimeout(offerCategory, 3000);
  };

  // ---- AI ----

  const noteText = () => `${title}\n${editor.markdown()}`.trim();

  function showAi(text: string, accent: boolean, tap?: () => void, hideAfter = 0) {
    clearTimeout(aiTimer);
    aiChip = { text, accent, tap };
    if (hideAfter) aiTimer = setTimeout(() => (aiChip = null), hideAfter);
  }
  /** Headings, lists, bold, tables… added to the note without changing a single word. */
  async function formatWithAi() {
    if (aiBusy) return;
    if (!(await aiConfigured())) { say("Add your OpenRouter key in Settings first"); return; }
    if (secret) { say("AI is off for secret notes"); return; }
    const before = editor.markdown();
    if (!before.trim()) { say("Write something first"); return; }
    if (before.length > 8000) { say("This note is too long to format in one go"); return; }
    aiBusy = true;
    showAi("✨ Formatting…", true);
    try {
      const md = await formatMarkdown(before);
      if (editor.markdown() !== before) showAi("You kept typing, so nothing was changed", false, undefined, 5000);
      else {
        editor.load(md); save();
        showAi("✓ Formatted  ·  Undo", true, () => { editor.load(before); save(); showAi("Back to how it was", false, undefined, 3000); }, 12000);
      }
    } catch (e) {
      showAi((e as Error).message || "Couldn't format this note", false, () => (aiChip = null), 60000);    // stays so you can read it
    } finally { aiBusy = false; }
  }
  async function offerCategory() {
    if (offered || secret || category || !settings().suggestCategories || noteText().length < 25 || !(await aiConfigured())) return;
    offered = true;
    try {
      const name = (await categorize([noteText()], categories)).get(0);
      if (!name || secret || category) return;
      offer = canonicalCategory(cleanCategory(name), categories);
      clearTimeout(hideOffer); hideOffer = setTimeout(() => (offer = ""), 15000);
    } catch { /* a quiet offer: no offer */ }
  }
  async function suggestCategory() {
    if (!(await aiConfigured())) { say("Add your OpenRouter key in Settings first"); return; }
    if (!noteText()) { say("Write something first"); return; }
    say("Thinking…");
    try {
      const name = (await categorize([noteText()], categories)).get(0);
      const c = name ? canonicalCategory(cleanCategory(name), categories) : "";
      if (!c) { say("No suggestion"); return; }
      category = c; save(); say(`Filed under ${c}`);
    } catch (e) { say((e as Error).message || "No suggestion"); }
  }

  // ---- Images ----

  async function addImages(blobs: Blob[]) {
    for (const b of blobs) {
      try { images = [...images, await importImage(b)]; save(); }
      catch { say("Couldn't add that image"); }
    }
  }
  function removeImage(name: string) { images = images.filter((x) => x !== name); save(); }
  /** Images pasted with Ctrl+V become attachments; text goes on to the editor. */
  function pasted(e: ClipboardEvent) {
    const imgs = imageFiles(e.clipboardData?.files);
    if (!imgs.length) return;
    e.preventDefault(); e.stopPropagation();
    addImages(imgs);
  }
  function dragging(e: DragEvent) { if (e.dataTransfer?.types.includes("Files")) { e.preventDefault(); e.dataTransfer.dropEffect = "copy"; } }
  function dropped(e: DragEvent) {
    const imgs = imageFiles(e.dataTransfer?.files);
    if (!imgs.length) return;
    e.preventDefault(); e.stopPropagation();
    addImages(imgs);
  }
  /** The Paste button (pasteFromClipboard): clipboard images become attachments, text goes in at the cursor. */
  async function pasteButton() {
    const inTitle = document.activeElement === titleEl;
    try {
      const c = await readClipboard();
      if (c.kind === "image") addImages([clipBlob(c)]);
      else if (c.kind === "text" && c.text) {
        if (inTitle) {
          const a = titleEl.selectionStart, b = titleEl.selectionEnd, one = c.text.replace(/\s*\n\s*/g, " ");
          title = title.slice(0, a) + one + title.slice(b);
          requestAnimationFrame(() => { titleEl.focus(); titleEl.setSelectionRange(a + one.length, a + one.length); fitTitle(); });
          typed();
        } else editor.paste(c.text);
      } else say(c.kind === "empty" ? "Clipboard is empty" : "Nothing to paste");
    } catch { say("Couldn't read the clipboard"); }
  }

  async function attachLatest() {
    const s = await invoke<{ mime: string; b64: string } | null>("latest_screenshot").catch(() => null);
    if (s) addImages([clipBlob(s)]); else say("No screenshots in the screenshot folder yet");
  }

  function load(n: Note) {
    base = { ...n };
    title = n.title; pinned = n.pinned; category = n.category; secret = n.secret; images = [...n.images];
    canon = canonical(n.body);
    editor.load(n.body);
    requestAnimationFrame(fitTitle);
  }

  // A newer version from the card or another device: take it, unless there is unsaved typing here.
  $effect(() => {
    const n = note;
    if (!editor || n.id !== base.id) return;
    if ((n.title !== base.title || n.body !== base.body || n.pinned !== base.pinned || n.category !== base.category || !sameImages(n.images, base.images)) && !dirty()) load(n);
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
    items.push({ label: "＋  New category", accent: true, run: () => setTimeout(newCategory) });
    items.push({ label: "✦  Suggest with AI", accent: true, run: () => void suggestCategory() });
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
    if (viewing) { if (e.key === "Escape") { e.preventDefault(); viewing = null; } return; }
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

<div class="page" role="presentation" ondragover={dragging} ondrop={dropped}>
  <div class="top">
    <button class="icon text" onclick={back} title="Back (Esc)" aria-label="Back"><Icon name="back" size={26} /></button>
    <span class="grow"></span>
    <button class="icon" onclick={formatWithAi} title="Format with AI" aria-label="Format with AI"><FormatIcon kind="ai" tint="var(--accent)" /></button>
    <button class="icon accent" class:on={panel} onclick={() => (panel = !panel)} title="Format" aria-label="Format" aria-pressed={panel}><Icon name="format" size={26} /></button>
    <button class="icon" onmousedown={(e) => e.preventDefault()} onclick={pasteButton} title="Paste" aria-label="Paste"><Icon name="paste" size={26} /></button>
    <button class="icon" onclick={attachLatest} title="Attach latest screenshot" aria-label="Attach latest screenshot"><Icon name="screenshot" size={26} /></button>
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
  <div class="editor" onpastecapture={pasted}>
    <div class="titlerow">
      <textarea class="title" bind:this={titleEl} bind:value={title} oninput={() => { fitTitle(); typed(); }} placeholder="Title" dir="auto" rows="1" spellcheck="false"
        onkeydown={(e) => { if (e.key === "Enter") { e.preventDefault(); editor.focus(); } }}></textarea>
      <button class="sparkle" onclick={() => titles.suggest(editor.markdown(), base.images[0], secret)} title="Suggest a title" aria-label="Suggest a title"><Icon name="sparkle" size={20} /></button>
    </div>
    <div class="rule"></div>
    <AiTitles bind:this={titles} onpick={(t) => { title = t; requestAnimationFrame(fitTitle); save(); }} onmessage={say} />
    {#if offer}
      <button class="offer" dir="auto" onclick={() => { clearTimeout(hideOffer); category = offer; offer = ""; save(); }}>✦ {offer}</button>
    {/if}
    {#if aiChip}
      {#if aiChip.tap}
        <button class="aichip" class:accent={aiChip.accent} onclick={aiChip.tap}>{aiChip.text}</button>
      {:else}
        <span class="aichip" class:accent={aiChip.accent}>{aiChip.text}</span>
      {/if}
    {/if}
    <div class="flow scroll">
      <NoteImages {images} onopen={(n) => (viewing = n)} onremove={removeImage} />
      <RichEditor bind:this={editor} class="body" placeholder="Start typing…" onchange={typed} onselect={() => version++} />
    </div>
  </div>
  {#if viewing}<ImageViewer name={viewing} onclose={() => (viewing = null)} />{/if}
</div>

{#if toast}<div class="toast" role="status">{toast.text}</div>{/if}

{#if sheet}
  <Sheet {...sheet} onclose={() => (sheet = null)} />
{/if}

<style>
  .page { position: relative; height: 100%; display: flex; flex-direction: column; }
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
    font-size: 26px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.25; padding: 8px 4px 10px; max-height: calc(3 * 1.25em + 18px);
    unicode-bidi: plaintext; text-align: start;
  }
  .title::placeholder { color: var(--secondary); }
  .titlerow { flex: none; display: flex; align-items: center; }
  .titlerow .title { flex: 1; min-width: 0; }
  .sparkle {
    flex: none; width: 38px; height: 38px; border: 0; border-radius: 19px; display: grid; place-items: center; background: transparent;
    color: var(--accent); cursor: pointer; transition: transform 150ms;
  }
  .sparkle:active { transform: scale(0.85); }
  .offer, .aichip {
    flex: none; align-self: flex-start; margin: 6px 0 0 4px; border: 0; font: inherit; font-size: 13px; border-radius: 14px;
    background: color-mix(in srgb, var(--secondary) 15%, transparent); color: var(--secondary);
  }
  .offer { height: 28px; padding: 0 12px; background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); cursor: pointer; }
  .aichip { padding: 6px 12px; border-radius: 16px; max-width: 100%; text-align: start; }
  .aichip.accent { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
  button.aichip { cursor: pointer; }
  .rule { flex: none; height: 1px; margin: 0 4px; background: var(--hairline); }
  .flow { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; }
  .editor :global(.body) { flex: 1 0 auto; }
  .editor :global(.body .rich) { overflow: visible; }
  .editor :global(.body .rich) { padding: 8px 4px 40vh; font-size: 17px; }
  .toast {
    position: fixed; left: 50%; bottom: 40px; transform: translateX(-50%); z-index: 70; max-width: calc(100vw - 48px);
    padding: 10px 16px; border-radius: 20px; background: color-mix(in srgb, var(--text) 88%, transparent); color: var(--background);
    font-size: 14px; animation: fade 160ms ease-out;
  }
  @keyframes fade { from { opacity: 0; } }
</style>
