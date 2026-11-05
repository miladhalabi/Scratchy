<script lang="ts">
  import { flushSync, onMount } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";
  import { canonical, newNote, pickCardNote, themeColors, type Note } from "@scratchy/core";
  import { applyTheme } from "../lib/theme";
  import RichEditor from "../lib/editor/RichEditor.svelte";
  import { setLines } from "../lib/editor/schema";
  import Icon from "../lib/Icon.svelte";
  import AiTitles from "../lib/AiTitles.svelte";
  import NoteImages from "../lib/NoteImages.svelte";
  import ImageViewer from "../lib/ImageViewer.svelte";
  import { clipBlob, imageFiles, importImage, readClipboard } from "../lib/attach";
  import { settings } from "../lib/settings";
  import { stepZoom, zoomBadge, zoomKey, zoomWheel } from "../lib/zoom";
  import { cardHeading } from "../lib/preview";
  import { formatWhen } from "../main/when";
  import "../lib/base.css";
  import { startEngine, store } from "../lib/engine";
  import { syncIfStale } from "../lib/sync";
  import { Genie, nearestEdge } from "./genie";
  import { Spring, accelDecel, clamp, decelerate, easeOut } from "./motion";
  import { loadCard, saveCard, type CardState } from "./prefs";

  type Phase = "closed" | "opening" | "open" | "closing";

  const MIN_W = 280, MIN_H = 220, MARGIN = 8;
  let prefs: CardState;
  let phase: Phase = "closed";
  let pinned = $state(false);
  let title = $state("");
  /** The body as the phone would write it back: an untouched note keeps its exact original text. */
  let canon = "";
  let note: Note | null = $state.raw(null);      // raw: compared by identity with the store's copy
  /** The card reopened on a note you already had: say so, and offer a fresh one (the "Continuing" row). */
  let continuing: Note | null = $state(null);
  /** The recent notes list, shown in place of the editor. */
  let recents: Note[] | null = $state(null);

  let root: HTMLDivElement;
  let cardEl: HTMLDivElement;
  let editor: RichEditor;
  let titles: AiTitles | undefined = $state();
  let aiNote = $state("");
  let aiNoteTimer: ReturnType<typeof setTimeout> | undefined;
  const aiSay = (t: string) => { clearTimeout(aiNoteTimer); aiNote = t; aiNoteTimer = setTimeout(() => (aiNote = ""), 4000); };

  // Where the card is drawn. Plain numbers, written straight to the style: no re-render per frame.
  let x = 0, y = 0, w = 400, h = 460;
  const sx = new Spring(0), sy = new Spring(0);
  let springing = false;

  let colors = $state(themeColors("system", false, null));
  const refreshAccent = applyTheme((c) => { colors = c; });

  // Until the surface has been shown once, the page doesn't know its real size yet: use the screen's.
  let sized = false;
  const W = () => (sized ? window.innerWidth : screen.width), H = () => (sized ? window.innerHeight : screen.height);

  function place() {
    cardEl.style.transform = `translate3d(${x}px,${y}px,0)`;
    cardEl.style.width = `${w}px`;
    cardEl.style.height = `${h}px`;
  }

  /** Keeps the whole card on the screen. */
  function fit() {
    w = clamp(w, MIN_W, W() - 2 * MARGIN);
    h = clamp(h, MIN_H, H() - 2 * MARGIN);
    x = clamp(x, MARGIN, W() - w - MARGIN);
    y = clamp(y, MARGIN, H() - h - MARGIN);
  }

  function remember() {
    Object.assign(prefs, { x: Math.round(x), y: Math.round(y), w: Math.round(w), h: Math.round(h), pinned });
    saveCard(prefs);
  }

  /** Pinned, only the card itself takes clicks, so the apps underneath keep working. */
  function syncInput() {
    const rect = pinned && phase !== "closed" ? [Math.floor(x), Math.floor(y), Math.ceil(w), Math.ceil(h)] : null;
    invoke("card_input", { rect }).catch(() => {});
  }

  // ---- notes ----

  let saveTimer: ReturnType<typeof setTimeout> | undefined;
  async function save() {
    clearTimeout(saveTimer);
    if (!note) return;
    const md = editor.markdown();
    const body = md === canon ? note.body : md;
    if (note.title === title && note.body === body) return;
    canon = md;
    note = await store.save({ ...note, title, body, updatedAt: Date.now() });
    prefs.lastCardNote = note.id;
    prefs.lastCardAt = Date.now();
    saveCard(prefs);
  }
  const typed = () => { clearTimeout(saveTimer); saveTimer = setTimeout(save, 400); };

  // ---- images ----

  let viewing: string | null = $state(null);
  /** Text and image size inside the card (Ctrl+= / Ctrl+- / Ctrl+0, Ctrl+scroll); the card keeps its size. */
  let zoom = $state(1);
  function zoomTo(dir: 1 | -1 | 0) {
    zoom = stepZoom(zoom, dir);
    if (prefs) { prefs.zoom = zoom; saveCard(prefs); }
    zoomBadge(cardEl, zoom);
  }
  let titleEl: HTMLInputElement;
  async function setImages(images: string[]) {
    clearTimeout(saveTimer);
    if (!note) return;
    const md = editor.markdown();
    const body = md === canon ? note.body : md;
    canon = md;
    note = await store.save({ ...note, title, body, images, updatedAt: Date.now() });
    prefs.lastCardNote = note.id; prefs.lastCardAt = Date.now(); saveCard(prefs);
  }
  async function addImages(blobs: Blob[]) {
    for (const b of blobs) {
      try { const name = await importImage(b); if (note) await setImages([...note.images, name]); }
      catch { aiSay("Couldn't add that image"); }
    }
  }
  /** The Paste button: a clipboard image is attached, text goes in where you are typing. */
  async function pasteButton() {
    recents = null; chooser = null;
    const inTitle = document.activeElement === titleEl;
    try {
      const c = await readClipboard();
      if (c.kind === "image") addImages([clipBlob(c)]);
      else if (c.kind === "text" && c.text) {
        if (inTitle) {
          const a = titleEl.selectionStart ?? title.length, b = titleEl.selectionEnd ?? a, one = c.text.replace(/\s*\n\s*/g, " ");
          title = title.slice(0, a) + one + title.slice(b);
          requestAnimationFrame(() => { titleEl.focus(); titleEl.setSelectionRange(a + one.length, a + one.length); });
          typed();
        } else editor.paste(c.text);
      } else aiSay(c.kind === "empty" ? "Clipboard is empty" : "Nothing to paste");
    } catch { aiSay("Couldn't read the clipboard"); }
  }
  async function attachLatest() {
    recents = null; chooser = null;
    const s = await invoke<{ mime: string; b64: string } | null>("latest_screenshot").catch(() => null);
    if (s) addImages([clipBlob(s)]); else aiSay("No screenshots in the screenshot folder yet");
  }

  // ---- a new screenshot: asked where it goes, or attached at once ----

  /** Waiting to be attached (the tray icon is green meanwhile); forgotten after the time in Settings. */
  let shotWaiting = false;
  let forgetTimer: ReturnType<typeof setTimeout> | undefined;
  /** The chooser ("Add this screenshot to…"), with its preview. */
  let chooser: { preview: string; current: Note | null } | null = $state(null);
  let attachAfterPick = false;

  function forgetShot() {
    clearTimeout(forgetTimer);
    shotWaiting = false; chooser = null; attachAfterPick = false;
    invoke("shot_clear").catch(() => {});
  }
  function armForget() {
    clearTimeout(forgetTimer);
    forgetTimer = setTimeout(() => { if (chooser) forgetTimer = setTimeout(armForget, 10_000); else forgetShot(); },
      Math.min(3600, Math.max(5, settings().shotTtl)) * 1000);
  }
  async function attachShot() {
    clearTimeout(forgetTimer);
    shotWaiting = false; chooser = null; attachAfterPick = false;
    const s = await invoke<{ mime: string; b64: string } | null>("shot_take").catch(() => null);
    if (s) await addImages([clipBlob(s)]);
  }
  /** The note "Current note" means: the one in the card if it has anything in it, else the last one used. */
  function currentForShot(): Note | null {
    if (note && (note.title || note.body || note.images.length || title || editor.markdown())) return note;
    const last = prefs?.lastCardNote ? store.get(prefs.lastCardNote) : undefined;
    return last && !last.secret ? last : null;
  }
  async function showChooser() {
    recents = null;
    chooser = { preview: "", current: currentForShot() };
    const s = await invoke<{ mime: string; b64: string } | null>("shot_peek").catch(() => null);
    if (!s) { forgetShot(); return; }
    if (chooser) chooser = { ...chooser, preview: `data:${s.mime};base64,${s.b64}` };
  }
  /** Settles a waiting screenshot as the card opens or while it is open. */
  function offerShot() {
    if (!shotWaiting) return;
    const mode = settings().shotMode;
    if (mode === "off") forgetShot();
    else if (mode === "auto") void attachShot();
    else void showChooser();
  }
  function chooseCurrent() {
    const cur = chooser?.current;
    if (cur && cur.id !== note?.id) show(cur);
    void attachShot();
  }
  function chooseNew() { show(null); continuing = null; void attachShot(); }
  function chooseOther() {
    chooser = null; attachAfterPick = true;
    recents = store.all().filter((n) => !n.secret).sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 10);
  }
  function pickRecent(n: Note | null) {
    show(n);
    if (attachAfterPick) void attachShot();
  }

  function pasted(e: ClipboardEvent) {
    const imgs = imageFiles(e.clipboardData?.files);
    if (!imgs.length) return;
    e.preventDefault(); e.stopPropagation();
    addImages(imgs);
  }
  function dropped(e: DragEvent) {
    const imgs = imageFiles(e.dataTransfer?.files);
    if (!imgs.length) return;
    e.preventDefault(); e.stopPropagation();
    addImages(imgs);
  }

  function pick() {
    const p = pickCardNote(store.all(), prefs, Date.now(), () => crypto.randomUUID());
    note = p.note;
    titles?.hide(); aiNote = ""; viewing = null;
    if (p.create) { store.save(p.note).then((n) => { note = n; prefs.scratchId = n.id; saveCard(prefs); }); }
    title = note.title;
    canon = canonical(note.body);
    editor.load(note.body);
    continuing = p.existing && !p.create && (note.title || note.body) ? note : null;
    recents = null;
  }

  /** Puts another note in the card (from the recent list, or a fresh one), saving the one that was there. */
  function show(n: Note | null) {
    save();
    note = n ?? newNote(crypto.randomUUID());
    titles?.hide(); aiNote = ""; viewing = null;
    title = note.title;
    canon = canonical(note.body);
    editor.load(note.body);
    continuing = n;
    recents = null;
    if (n) { prefs.lastCardNote = n.id; prefs.lastCardAt = Date.now(); saveCard(prefs); }
    editor.focus(true);
  }
  function toggleRecents() {
    chooser = null;
    if (recents) attachAfterPick = false;
    recents = recents ? null : store.all().filter((n) => !n.secret).sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 10);
  }
  function checklist() {
    recents = null; chooser = null;
    const ed = editor.instance();
    if (ed) setLines(ed, "task");
  }

  /** A change from sync or the notes window: shown at once, unless there is typing here it would undo. */
  function refresh() {
    if (!note) return;
    const cur = store.get(note.id);
    if (!cur || cur === note) return;
    if (title === cur.title && editor.markdown() === canonical(cur.body)) { note = cur; return; }     // our own save
    if (title !== note.title || editor.markdown() !== canon) return;                                    // mid-edit: ours wins
    note = cur;
    title = cur.title;
    canon = canonical(cur.body);
    editor.load(cur.body);
  }

  // ---- open and close ----

  let anim: { cancel(): void; t(): number } | null = null;
  /** Strips of the card for the genie. Kept after closing, so an unchanged card reopens without rebuilding them. */
  let genie: Genie | null = null;

  /** Runs t from `from` to `to`, timed from the first frame it draws; the easing is applied to the progress. */
  function runT(from: number, to: number, ms: number, ease: (p: number) => number, each: (t: number) => void, done: () => void) {
    let raf = 0, now = from, stopped = false, start = -1;
    const loop = (ts: number) => {
      if (stopped) return;
      if (start < 0) start = ts;
      const p = ms > 0 ? Math.min((ts - start) / ms, 1) : 1;
      now = from + (to - from) * ease(p);
      each(now);
      if (p < 1) raf = requestAnimationFrame(loop); else done();
    };
    raf = requestAnimationFrame(loop);
    return { cancel() { stopped = true; cancelAnimationFrame(raf); }, t: () => now };
  }

  const speed = () => 100 / clamp(prefs.speed, 50, 200);

  /** The strips for the card as it looks now, reusing the kept ones when nothing about it changed. */
  function stripsFor(sw = W(), sh = H()): Genie {
    const r = { x, y, w, h };
    const { edge, tx, ty } = nearestEdge(r, sw, sh);
    const key = [title, editor.markdown(), Math.round(w), Math.round(h), colors.floating, colors.text, pinned, edge === "left" || edge === "right",
      continuing?.id ?? "", continuing?.updatedAt ?? 0, recents ? "r" : "", note?.images.join(",") ?? "", viewing ?? "", chooser ? `c${chooser.preview.length}` : "", zoom].join("|");
    if (genie && genie.key === key) { genie.retarget(r, edge, tx, ty); return genie; }
    genie?.clear();
    genie = new Genie(root, r, edge, tx, ty);
    genie.build(cardEl);
    genie.key = key;
    return genie;
  }

  const edgeShift = () => {
    const { edge } = nearestEdge({ x, y, w, h }, W(), H());
    return [edge === "left" ? -60 : edge === "right" ? 60 : 0, edge === "top" ? -60 : edge === "bottom" ? 60 : 0];
  };

  function open() {
    const reverse = phase === "closing";
    const fromT = reverse && anim ? anim.t() : 1;
    anim?.cancel();
    phase = "opening";
    if (!reverse) { pick(); if (shotWaiting) offerShot(); flushSync(); fit(); place(); }
    syncIfStale();
    void refreshAccent();
    invoke("card_keyboard", { exclusive: !pinned }).catch(() => {});
    syncInput();

    const size = `${W()}x${H()}`;
    /** On the first frame the surface has its real size: a different screen means placing the card again. */
    const firstFrame = () => {
      sized = true;
      if (`${W()}x${H()}` === size) return false;
      fit(); place();
      return true;
    };

    if (prefs.anim === "genie") {
      if (!reverse || !genie) stripsFor();
      genie!.frame(fromT);
      genie!.show();
      let first = true;
      anim = runT(fromT, 0, 420 * speed() * fromT, decelerate(1.3), (t) => {
        if (first) { first = false; if (firstFrame()) stripsFor().show(); }
        genie!.frame(t);
      }, shown);
    } else if (prefs.anim === "slide") {
      cardEl.style.visibility = "visible";
      editor.focus(true);                                  // visible from the start: typing works while it slides in
      let first = true;
      anim = runT(fromT, 0, 240 * speed() * fromT, easeOut, (t) => {
        if (first) { first = false; firstFrame(); }
        const [dx, dy] = edgeShift();
        cardEl.style.transform = `translate3d(${x + dx * t}px,${y + dy * t}px,0)`;
        cardEl.style.opacity = String(1 - t);
      }, shown);
    } else {
      cardEl.style.visibility = "visible";
      requestAnimationFrame(() => { firstFrame(); shown(); });
    }
  }

  /** Fully open: the live card takes over from the animation, and the keyboard goes to the text. */
  function shown() {
    anim = null;
    cardEl.style.visibility = "visible";
    cardEl.style.opacity = "1";
    genie?.hide();
    place();
    phase = "open";
    if (!cardEl.contains(document.activeElement)) editor.focus(true);
  }

  function close() {
    if (phase === "closed" || phase === "closing") return;
    const reverse = phase === "opening";
    const fromT = reverse && anim ? anim.t() : 0;
    anim?.cancel();
    springing = false;
    phase = "closing";
    save();
    if (prefs.anim === "genie") {
      if (!reverse || !genie) stripsFor();
      genie!.frame(fromT);
      genie!.show();
      cardEl.style.visibility = "hidden";
      anim = runT(fromT, 1, 480 * speed() * (1 - fromT), accelDecel, (t) => genie!.frame(t), hidden);
    } else if (prefs.anim === "slide") {
      anim = runT(fromT, 1, 200 * speed() * (1 - fromT), (p) => p * p, (t) => {
        const [dx, dy] = edgeShift();
        cardEl.style.transform = `translate3d(${x + dx * t}px,${y + dy * t}px,0)`;
        cardEl.style.opacity = String(1 - t);
      }, hidden);
    } else hidden();
  }

  function hidden() {
    anim = null;
    genie?.hide();
    cardEl.style.visibility = "hidden";
    cardEl.style.opacity = "1";
    phase = "closed";
    syncInput();
    invoke("card_hide").catch(() => {});
  }

  function toggle() {
    if (phase === "open" || phase === "opening") close(); else open();
  }

  // ---- drag (by the header) ----

  let drag: { id: number; ox: number; oy: number } | null = null;

  function dragStart(e: PointerEvent) {
    if (e.button !== 0 || (e.target as HTMLElement).closest("button") || phase !== "open") return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    springing = false;                                   // grab it mid-flight, from where it is drawn now
    drag = { id: e.pointerId, ox: e.clientX - x, oy: e.clientY - y };
    e.preventDefault();
  }
  function dragMove(e: PointerEvent) {
    if (!drag || e.pointerId !== drag.id) return;
    x = e.clientX - drag.ox;
    y = e.clientY - drag.oy;
    cardEl.style.transform = `translate3d(${x}px,${y}px,0)`;
  }
  function dragEnd(e: PointerEvent) {
    if (!drag || e.pointerId !== drag.id) return;
    drag = null;
    // The card stays where it was let go. Only a card dropped partly off the screen eases back onto it.
    const tx = clamp(x, MARGIN, W() - w - MARGIN);
    const ty = clamp(y, MARGIN, H() - h - MARGIN);
    if (tx === x && ty === y) { remember(); syncInput(); return; }
    for (const [s, to, now] of [[sx, tx, x], [sy, ty, y]] as const) {
      s.value = now; s.velocity = 0; s.target = to; s.tune(0.35, 1);
    }
    springing = true;
    let last = performance.now();
    const loop = (ts: number) => {
      if (!springing) return;
      const dt = (ts - last) / 1000; last = ts;
      const a = sx.step(dt), b = sy.step(dt);
      x = sx.value; y = sy.value;
      cardEl.style.transform = `translate3d(${x}px,${y}px,0)`;
      if (a || b) requestAnimationFrame(loop);
      else { springing = false; remember(); syncInput(); }
    };
    requestAnimationFrame(loop);
  }

  // ---- resize (edges and corners) ----

  let rs: { id: number; dir: string; px: number; py: number; x: number; y: number; w: number; h: number } | null = null;

  function resizeStart(e: PointerEvent) {
    if (e.button !== 0 || phase !== "open") return;
    const dir = (e.currentTarget as HTMLElement).dataset.dir!;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    springing = false;
    rs = { id: e.pointerId, dir, px: e.clientX, py: e.clientY, x, y, w, h };
    e.preventDefault();
    e.stopPropagation();
  }
  function resizeMove(e: PointerEvent) {
    if (!rs || e.pointerId !== rs.id) return;
    const dx = e.clientX - rs.px, dy = e.clientY - rs.py;
    if (rs.dir.includes("e")) w = clamp(rs.w + dx, MIN_W, W() - rs.x - MARGIN);
    if (rs.dir.includes("s")) h = clamp(rs.h + dy, MIN_H, H() - rs.y - MARGIN);
    if (rs.dir.includes("w")) { w = clamp(rs.w - dx, MIN_W, rs.x + rs.w - MARGIN); x = rs.x + rs.w - w; }
    if (rs.dir.includes("n")) { h = clamp(rs.h - dy, MIN_H, rs.y + rs.h - MARGIN); y = rs.y + rs.h - h; }
    place();
  }
  function resizeEnd(e: PointerEvent) {
    if (!rs || e.pointerId !== rs.id) return;
    rs = null;
    remember();
    syncInput();
  }

  // ---- pin, outside clicks, keys ----

  function togglePin() {
    pinned = !pinned;
    remember();
    invoke("card_keyboard", { exclusive: !pinned }).catch(() => {});
    syncInput();
    if (!pinned) editor.focus(true);
  }

  function outside(e: PointerEvent) {
    if (e.target === root && !pinned) close();
  }

  function keys(e: KeyboardEvent) {
    const z = zoomKey(e);
    if (z !== null) { e.preventDefault(); if (phase === "open") zoomTo(z); return; }
    if (e.key === "Escape") { e.preventDefault(); if (viewing) viewing = null; else close(); }
  }

  onMount(() => {
    let off: (() => void) | undefined;
    (async () => {
      prefs = await loadCard();
      pinned = prefs.pinned;
      zoom = prefs.zoom;
      w = prefs.w; h = prefs.h;
      x = prefs.x ?? W() - w - 48;
      y = prefs.y ?? (H() - h) / 2;
      await startEngine();
      store.onChange(refresh);
      // Ready before the first open: the note it would open on, already cut into genie strips.
      pick(); flushSync(); fit(); place();
      if (prefs.anim === "genie") stripsFor().hide();
      off = await listen("card-toggle", toggle);
      await listen("shot", () => {
        if (settings().shotMode === "off") { forgetShot(); return; }
        shotWaiting = true; armForget();
        if (phase === "open" || phase === "opening") offerShot();     // otherwise when the card next opens
      });
      // Settings changed one of the card's own options (opens on, animation, speed).
      await listen<Partial<CardState>>("card-prefs", ({ payload }) => {
        Object.assign(prefs, payload);
        if (payload.zoom) zoom = payload.zoom;
        saveCard(prefs);
        if (payload.anim && payload.anim !== "genie") { genie?.clear(); genie = null; }
      });
      invoke("card_boot").catch(() => {});
    })();
    window.addEventListener("keydown", keys);
    const wheel = (e: WheelEvent) => { const d = zoomWheel(e); if (d !== null && phase === "open") zoomTo(d); };
    cardEl.addEventListener("wheel", wheel, { passive: false });
    return () => { off?.(); window.removeEventListener("keydown", keys); cardEl.removeEventListener("wheel", wheel); };
  });
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="root" bind:this={root} onpointerdown={outside}>
  <div class="card" bind:this={cardEl} style:--z={zoom} role="presentation" onpastecapture={pasted} ondrop={dropped}
    ondragover={(e) => { if (e.dataTransfer?.types.includes("Files")) { e.preventDefault(); e.dataTransfer.dropEffect = "copy"; } }}>
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="head" onpointerdown={dragStart} onpointermove={dragMove} onpointerup={dragEnd} onpointercancel={dragEnd}>
      <button class="icon" class:on={!!recents} title="Recent notes" aria-label="Recent notes" onclick={toggleRecents}><Icon name="list" size={18} /></button>
      <button class="icon" title="Checklist" aria-label="Checklist" onclick={checklist}><Icon name="checklist" size={18} /></button>
      <button class="icon" title="Paste" aria-label="Paste" onmousedown={(e) => e.preventDefault()} onclick={pasteButton}><Icon name="paste" size={18} /></button>
      <button class="icon" title="Attach latest screenshot" aria-label="Attach latest screenshot" onclick={attachLatest}><Icon name="screenshot" size={18} /></button>
      <span class="handle" aria-hidden="true"><i></i></span>
      <button class="icon" class:pinned title={pinned ? "Unpin" : "Pin card: keep it on screen and use the computer normally"} onclick={togglePin} aria-pressed={pinned}><Icon name="pin" size={18} /></button>
    </div>
    {#if continuing}
      <div class="continue">
        <span>Continuing · edited {formatWhen(continuing.updatedAt)}</span>
        <button onclick={() => show(null)}>＋ New note</button>
      </div>
    {/if}
    <div class="content">
      <div class="ed" class:gone={!!recents || !!chooser}>
        <div class="titlerow">
          <input class="title" bind:this={titleEl} placeholder="Title" bind:value={title} oninput={typed} dir="auto" spellcheck="false"
            onkeydown={(e) => { if (e.key === "Enter") { e.preventDefault(); editor.focus(); } }} />
          <button class="icon sparkle" title="Suggest a title" aria-label="Suggest a title"
            onclick={() => titles?.suggest(editor.markdown(), note?.images[0], !!note?.secret)}><Icon name="sparkle" size={18} /></button>
        </div>
        <div class="rule"></div>
        <AiTitles bind:this={titles} onpick={(t) => { title = t; typed(); }} onmessage={aiSay} />
        {#if aiNote}<span class="ainote">{aiNote}</span>{/if}
        <div class="flow scroll">
          <NoteImages images={note?.images ?? []} compact {zoom} onopen={(n) => (viewing = n)} onremove={(n) => note && setImages(note.images.filter((x) => x !== n))} />
          <RichEditor bind:this={editor} class="body" placeholder="Start typing…" onchange={typed} />
        </div>
      </div>
      {#if chooser}
        <div class="chooser scroll">
          <div class="ask">
            {#if chooser.preview}<img src={chooser.preview} alt="The new screenshot" />{:else}<span class="ph"></span>{/if}
            <b>Add this screenshot to…</b>
          </div>
          {#if chooser.current}
            <button class="choice filled" onclick={chooseCurrent}>
              <b>Current note</b><small dir="auto">{chooser.current.secret ? "Secret note" : cardHeading(chooser.current) || "Untitled"}</small>
            </button>
          {/if}
          <button class="choice" onclick={chooseNew}><b>New note</b><small>Start a fresh note with it</small></button>
          <button class="choice" onclick={chooseOther}><b>Other note…</b><small>Pick one from your notes</small></button>
          <button class="notnow" onclick={forgetShot}>Not now</button>
        </div>
      {/if}
      {#if recents}
        <div class="recents scroll">
          <button class="new" onclick={() => pickRecent(null)}>＋  New note</button>
          {#each recents as n (n.id)}
            <button onclick={() => pickRecent(n)} dir="auto">{n.pinned ? "📌 " : ""}{cardHeading(n)}</button>
          {/each}
        </div>
      {/if}
    </div>
    <div class="rule full"></div>
    <button class="closestrip" onclick={close}><Icon name="close" size={16} /><span>Close</span></button>
    <span class="grip" aria-hidden="true"><Icon name="grip" size={20} /></span>
    {#each ["n", "s", "e", "w", "ne", "nw", "se", "sw"] as d}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div class="rz {d}" data-dir={d} onpointerdown={resizeStart} onpointermove={resizeMove} onpointerup={resizeEnd} onpointercancel={resizeEnd}></div>
    {/each}
    {#if viewing}<ImageViewer name={viewing} onclose={() => (viewing = null)} />{/if}
  </div>
</div>

<style>
  :global(html), :global(body) {
    margin: 0; height: 100%; overflow: hidden;
    background: transparent;
    font-size: 15px;
  }
  .root { position: fixed; inset: 0; }
  /* The phone's floating card (OverlayService + Appearance.card): opaque, 20px corners, a hairline edge, a soft shadow. */
  .card {
    position: absolute; left: 0; top: 0;
    display: flex; flex-direction: column; padding-top: 6px;
    background: var(--floating); color: var(--text);
    border-radius: 20px;
    border: 1px solid var(--hairline);
    box-shadow: 0 10px 28px rgb(0 0 0 / 0.3), 0 2px 6px rgb(0 0 0 / 0.16);
    visibility: hidden;
    will-change: transform;
    box-sizing: border-box;
    overflow: hidden;
  }
  .head { flex: none; height: 40px; margin: 0 10px; display: flex; align-items: center; cursor: grab; touch-action: none; user-select: none; }
  .head:active { cursor: grabbing; }
  .handle { flex: 1; height: 100%; display: grid; place-items: center; }
  .handle i { width: 36px; height: 4px; border-radius: 2px; background: var(--hairline); }
  .icon {
    width: 36px; height: 40px; border: 0; border-radius: 12px; display: grid; place-items: center;
    background: transparent; color: var(--secondary); cursor: pointer; transition: transform 150ms;
  }
  .icon:hover, .icon.on { color: var(--text); }
  .icon:active { transform: scale(0.85); }
  .icon.pinned { color: var(--accent); }
  .continue { flex: none; display: flex; align-items: center; margin: 0 10px 0 14px; }
  .continue span { flex: 1; min-width: 0; font-size: 12px; color: var(--secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .continue button { border: 0; background: transparent; padding: 8px 2px 8px 10px; color: var(--accent); font: inherit; font-size: 13px; font-weight: 500; cursor: pointer; }
  .content { flex: 1; min-height: 0; position: relative; margin: 0 10px; display: flex; flex-direction: column; }
  .ed { flex: 1; min-height: 0; display: flex; flex-direction: column; }
  .ed.gone { visibility: hidden; }
  .title {
    flex: none; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; width: 100%; box-sizing: border-box;
    font-size: calc(18px * var(--z)); font-weight: 700; padding: 8px 4px 10px; unicode-bidi: plaintext; text-align: start;
  }
  .title::placeholder { color: var(--secondary); }
  .titlerow { flex: none; display: flex; align-items: center; }
  .titlerow .title { flex: 1; min-width: 0; }
  .icon.sparkle, .icon.sparkle:hover { color: var(--accent); }
  .ainote { flex: none; font-size: 12px; color: var(--secondary); padding: 6px 4px 0; }
  .rule { flex: none; height: 1px; margin: 0 4px; background: var(--hairline); }
  .rule.full { margin: 0; }
  .flow { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; scrollbar-width: none; }
  .card :global(.body) { flex: 1 0 auto; }
  .card :global(.body .rich) { overflow: visible; }
  .card :global(.body .rich) { padding: 8px 4px; font-size: calc(15px * var(--z)); }
  .chooser { position: absolute; inset: 0; overflow-y: auto; display: flex; flex-direction: column; padding: 4px 4px 6px; scrollbar-width: none; }
  .ask { flex: none; display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
  .ask img, .ask .ph { flex: none; width: 44px; height: 56px; border-radius: 10px; object-fit: cover; background: var(--hairline); }
  .ask b { font-size: 15px; font-weight: 500; }
  .choice {
    flex: none; height: 64px; margin-bottom: 8px; padding: 0 16px; border: 0; border-radius: 16px; display: flex; flex-direction: column;
    justify-content: center; align-items: flex-start; background: var(--hairline); color: var(--text); font: inherit; cursor: pointer;
    text-align: start; transition: transform 150ms;
  }
  .choice:active { transform: scale(0.97); }
  .choice b { font-size: 17px; font-weight: 500; }
  .choice small { font-size: 12px; color: var(--secondary); max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; unicode-bidi: plaintext; }
  .choice.filled { background: var(--accent); color: var(--on-accent); }
  .choice.filled small { color: color-mix(in srgb, var(--on-accent) 80%, transparent); }
  .notnow { flex: none; border: 0; background: transparent; color: var(--secondary); font: inherit; font-size: 13px; padding: 10px 0 6px; cursor: pointer; }
  .recents { position: absolute; inset: 0; overflow-y: auto; display: flex; flex-direction: column; scrollbar-width: none; }
  .recents button {
    flex: none; border: 0; background: transparent; padding: 11px 6px; color: var(--text); font: inherit; font-size: calc(15px * var(--z)); text-align: start;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer; unicode-bidi: plaintext; border-radius: 10px;
  }
  .recents button:hover { background: color-mix(in srgb, var(--text) 6%, transparent); }
  .recents .new { color: var(--accent); }
  .closestrip {
    flex: none; height: 46px; border: 0; background: transparent; display: flex; align-items: center; justify-content: center; gap: 8px;
    color: var(--secondary); font: inherit; font-size: 14px; cursor: pointer;
  }
  .closestrip:hover { color: var(--text); }
  .grip { position: absolute; right: 4px; bottom: 4px; padding: 6px; color: var(--secondary); pointer-events: none; }
  .rz { position: absolute; z-index: 2; touch-action: none; }
  .n, .s { left: 14px; right: 14px; height: 6px; cursor: ns-resize; }
  .n { top: 0; } .s { bottom: 0; }
  .e, .w { top: 14px; bottom: 14px; width: 6px; cursor: ew-resize; }
  .e { right: 0; } .w { left: 0; }
  .ne, .nw, .se, .sw { width: 16px; height: 16px; }
  .ne { top: 0; right: 0; cursor: nesw-resize; } .sw { bottom: 0; left: 0; cursor: nesw-resize; }
  .nw { top: 0; left: 0; cursor: nwse-resize; } .se { bottom: 0; right: 0; cursor: nwse-resize; }
  :global(.genie) { position: fixed; inset: 0; pointer-events: none; }
  :global(.strip) { position: absolute; left: 0; top: 0; overflow: hidden; transform-origin: 0 0; will-change: transform; }
  :global(.copy) { visibility: inherit !important; position: absolute !important; left: 0; top: 0; box-shadow: none !important; }
  :global(.as-text) { white-space: pre-wrap; overflow-wrap: break-word; overflow: hidden; }
  :global(.title.as-text) { white-space: pre; flex: none; }
  :global(.as-text.placeholder) { color: var(--secondary); opacity: 0.7; }
</style>
