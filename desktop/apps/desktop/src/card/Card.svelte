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
  import { cardHeading } from "../lib/preview";
  import { formatWhen } from "../main/when";
  import "../lib/base.css";
  import { startEngine, store } from "../lib/engine";
  import { syncIfStale } from "../lib/sync";
  import { Genie, nearestEdge } from "./genie";
  import { Spring, Tracker, accelDecel, clamp, decelerate, easeOut, project } from "./motion";
  import { loadCard, saveCard, type CardState } from "./prefs";

  type Phase = "closed" | "opening" | "open" | "closing";

  const MIN_W = 280, MIN_H = 220, MARGIN = 8;
  let prefs: CardState;
  let phase: Phase = "closed";
  let pinned = $state(false);
  let title = $state("");
  /** The body as the phone would write it back: an untouched note keeps its exact original text. */
  let canon = "";
  let note: Note | null = null;
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

  function pick() {
    const p = pickCardNote(store.all(), prefs, Date.now(), () => crypto.randomUUID());
    note = p.note;
    titles?.hide(); aiNote = "";
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
    titles?.hide(); aiNote = "";
    title = note.title;
    canon = canonical(note.body);
    editor.load(note.body);
    continuing = n;
    recents = null;
    if (n) { prefs.lastCardNote = n.id; prefs.lastCardAt = Date.now(); saveCard(prefs); }
    editor.focus(true);
  }
  function toggleRecents() {
    recents = recents ? null : store.all().filter((n) => !n.secret).sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 10);
  }
  function checklist() {
    recents = null;
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
      continuing?.id ?? "", continuing?.updatedAt ?? 0, recents ? "r" : ""].join("|");
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
    if (!reverse) { pick(); flushSync(); fit(); place(); }
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
    editor.focus(true);
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

  let drag: { id: number; ox: number; oy: number; track: Tracker } | null = null;

  function dragStart(e: PointerEvent) {
    if (e.button !== 0 || (e.target as HTMLElement).closest("button") || phase !== "open") return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    springing = false;                                   // grab it mid-flight, from where it is drawn now
    drag = { id: e.pointerId, ox: e.clientX - x, oy: e.clientY - y, track: new Tracker() };
    drag.track.add(e.clientX, e.clientY);
    e.preventDefault();
  }
  function dragMove(e: PointerEvent) {
    if (!drag || e.pointerId !== drag.id) return;
    x = e.clientX - drag.ox;
    y = e.clientY - drag.oy;
    drag.track.add(e.clientX, e.clientY);
    cardEl.style.transform = `translate3d(${x}px,${y}px,0)`;
  }
  function dragEnd(e: PointerEvent) {
    if (!drag || e.pointerId !== drag.id) return;
    const { vx, vy } = drag.track.velocity();
    drag = null;
    // A flick throws the card: land where it would coast to, kept on the screen.
    const tx = clamp(x + project(vx), MARGIN, W() - w - MARGIN);
    const ty = clamp(y + project(vy), MARGIN, H() - h - MARGIN);
    const flick = Math.hypot(vx, vy) > 900;
    for (const [s, v, to, now] of [[sx, vx, tx, x], [sy, vy, ty, y]] as const) {
      s.value = now; s.velocity = v; s.target = to; s.tune(flick ? 0.45 : 0.35, flick ? 0.82 : 1);
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
    if (e.key === "Escape") { e.preventDefault(); close(); }
  }

  onMount(() => {
    let off: (() => void) | undefined;
    (async () => {
      prefs = await loadCard();
      pinned = prefs.pinned;
      w = prefs.w; h = prefs.h;
      x = prefs.x ?? W() - w - 48;
      y = prefs.y ?? (H() - h) / 2;
      await startEngine();
      store.onChange(refresh);
      // Ready before the first open: the note it would open on, already cut into genie strips.
      pick(); flushSync(); fit(); place();
      if (prefs.anim === "genie") stripsFor().hide();
      off = await listen("card-toggle", toggle);
      // Settings changed one of the card's own options (opens on, animation, speed).
      await listen<Partial<CardState>>("card-prefs", ({ payload }) => {
        Object.assign(prefs, payload);
        saveCard(prefs);
        if (payload.anim && payload.anim !== "genie") { genie?.clear(); genie = null; }
      });
      invoke("card_boot").catch(() => {});
    })();
    window.addEventListener("keydown", keys);
    return () => { off?.(); window.removeEventListener("keydown", keys); };
  });
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="root" bind:this={root} onpointerdown={outside}>
  <div class="card" bind:this={cardEl}>
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="head" onpointerdown={dragStart} onpointermove={dragMove} onpointerup={dragEnd} onpointercancel={dragEnd}>
      <button class="icon" class:on={!!recents} title="Recent notes" aria-label="Recent notes" onclick={toggleRecents}><Icon name="list" size={18} /></button>
      <button class="icon" title="Checklist" aria-label="Checklist" onclick={checklist}><Icon name="checklist" size={18} /></button>
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
      <div class="ed" class:gone={!!recents}>
        <div class="titlerow">
          <input class="title" placeholder="Title" bind:value={title} oninput={typed} dir="auto" spellcheck="false"
            onkeydown={(e) => { if (e.key === "Enter") { e.preventDefault(); editor.focus(); } }} />
          <button class="icon sparkle" title="Suggest a title" aria-label="Suggest a title"
            onclick={() => titles?.suggest(editor.markdown(), note?.images[0], !!note?.secret)}><Icon name="sparkle" size={18} /></button>
        </div>
        <div class="rule"></div>
        <AiTitles bind:this={titles} onpick={(t) => { title = t; typed(); }} onmessage={aiSay} />
        {#if aiNote}<span class="ainote">{aiNote}</span>{/if}
        <RichEditor bind:this={editor} class="body" placeholder="Start typing…" onchange={typed} />
      </div>
      {#if recents}
        <div class="recents scroll">
          <button class="new" onclick={() => show(null)}>＋  New note</button>
          {#each recents as n (n.id)}
            <button onclick={() => show(n)} dir="auto">{n.pinned ? "📌 " : ""}{cardHeading(n)}</button>
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
    font-size: 18px; font-weight: 700; padding: 8px 4px 4px; unicode-bidi: plaintext; text-align: start;
  }
  .title::placeholder { color: var(--secondary); }
  .titlerow { flex: none; display: flex; align-items: center; }
  .titlerow .title { flex: 1; min-width: 0; }
  .icon.sparkle, .icon.sparkle:hover { color: var(--accent); }
  .ainote { flex: none; font-size: 12px; color: var(--secondary); padding: 6px 4px 0; }
  .rule { flex: none; height: 1px; margin: 0 4px; background: var(--hairline); }
  .rule.full { margin: 0; }
  .card :global(.body) { flex: 1; min-height: 0; }
  .card :global(.body .rich) { padding: 8px 4px; font-size: 15px; }
  .recents { position: absolute; inset: 0; overflow-y: auto; display: flex; flex-direction: column; scrollbar-width: none; }
  .recents button {
    flex: none; border: 0; background: transparent; padding: 11px 6px; color: var(--text); font: inherit; font-size: 15px; text-align: start;
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
