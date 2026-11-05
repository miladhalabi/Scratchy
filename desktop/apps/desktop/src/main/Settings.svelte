<script lang="ts">
  import { onMount } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { emitTo } from "@tauri-apps/api/event";
  import { accentOf, MIN_PASSWORD, themeColors } from "@scratchy/core";
  import type { SyncStatus } from "../lib/sync";
  import { engine } from "../lib/notes";
  import { loadCard, type CardState } from "../card/prefs";
  import { DEFAULT_MODEL, saveSettings, watchSettings, type AppSettings } from "../lib/settings";
  import { aiKey, ping, setAiKey } from "../lib/ai";
  import { getSystemAccent } from "../lib/theme";
  import Icon from "../lib/Icon.svelte";
  import Segmented from "../lib/Segmented.svelte";
  import { MAX_ZOOM, MIN_ZOOM, stepZoom } from "../lib/zoom";

  /** Settings, laid out like the phone's SettingsActivity: a menu of topics, one page each. */
  interface Props { sync: SyncStatus; vaultSetUp: boolean; onback: () => void }
  let { sync, vaultSetUp, onback }: Props = $props();

  type Page = "menu" | "shortcut" | "card" | "look" | "shots" | "sync" | "vault" | "ai";
  const titles: Record<Page, string> = {
    menu: "Settings", shortcut: "Shortcut", card: "Card", look: "Appearance", shots: "Screenshots",
    sync: "Sync", vault: "Secret notes", ai: "AI titles",
  };
  let page: Page = $state("menu");
  let s: AppSettings | null = $state(null);
  let card: CardState | null = $state(null);
  let advanced = $state(false);
  let key = $state("");
  let keyShown = $state(false);
  let test = $state("");
  let togglePath = $state("");
  let copied = $state("");
  let startAtLogin = $state(false);
  let hyprland = $state(false);

  // ---- the sync and password forms ----
  type Form = "connect" | "newPassword" | "change" | null;
  let form: Form = $state(null);
  let pw = $state(""), pw2 = $state(""), old = $state("");
  let busy = $state(false);
  let error = $state("");
  let done = $state("");

  function go(p: Page) { page = p; advanced = false; form = null; error = ""; done = ""; test = ""; }
  function show(f: Form) { form = f; pw = pw2 = old = ""; error = ""; done = ""; }
  async function run(job: () => Promise<unknown>, ok = "") {
    busy = true; error = ""; done = "";
    try { await job(); form = null; done = ok; }
    catch (e) { error = String((e as Error)?.message ?? e); }
    finally { busy = false; }
  }
  function submit() {
    if (busy) return;
    if (form === "connect") { if (!pw) { error = "Type your vault password"; return; } void run(() => engine.connect(pw)); }
    else if (form === "newPassword") { if (!pw) { error = "Type the new vault password"; return; } void run(() => engine.newPassword(pw)); }
    else if (form === "change") {
      if (pw.length < MIN_PASSWORD) { error = `Use at least ${MIN_PASSWORD} characters`; return; }
      if (pw !== pw2) { error = "The new passwords don't match"; return; }
      void run(() => engine.changePassword(old, pw), "Password changed. Enter it on your other devices.");
    }
  }

  // ---- values ----
  const set = (patch: Partial<AppSettings>) => { if (s) s = { ...s, ...patch }; void saveSettings(patch); };
  /** The card owns card.json; it is told, and saves. */
  function setCard(patch: Partial<CardState>) {
    if (card) card = { ...card, ...patch };
    void emitTo("card", "card-prefs", patch);
  }
  let keyTimer: ReturnType<typeof setTimeout> | undefined;
  function typedKey() { clearTimeout(keyTimer); keyTimer = setTimeout(() => void setAiKey(key), 400); }
  async function copy(text: string) {
    await navigator.clipboard.writeText(text).catch(() => {});
    copied = text; setTimeout(() => { if (copied === text) copied = ""; }, 1500);
  }

  const modes = [["recent", "Recent"], ["last", "Last"], ["scratch", "Scratch"], ["new", "New"]] as const;
  const modeHelp: Record<string, string> = {
    recent: "Continues the note you last wrote in the card if you touched it in the last 30 minutes, otherwise starts a new one.",
    last: "Always continues the last note you wrote in the card, however long ago.",
    scratch: "Always opens one permanent, pinned note called Scratch and keeps adding to it.",
    new: "Always starts a new blank note. Empty notes are never saved.",
  };
  const anims = [["genie", "Genie"], ["slide", "Slide"], ["none", "None"]] as const;
  const shots = [["ask", "Ask"], ["auto", "Auto-attach"], ["off", "Off"]] as const;
  const shotHelp: Record<string, string> = {
    ask: "The tray icon turns green, and the card asks where it goes: current note, new note or another note.",
    auto: "Added to the open note on its own. If the card is closed, the next note you open gets it.",
    off: "Ignored. The screenshot button still attaches your latest one on demand.",
  };
  const ttls = [[15, "15 s"], [30, "30 s"], [60, "1 min"], [120, "2 min"], [300, "5 min"]] as const;
  const colorNames = [["system", "System"], ["orange", "Orange"], ["blue", "Blue"], ["teal", "Teal"], ["green", "Green"], ["purple", "Purple"]] as const;
  const dark = matchMedia("(prefers-color-scheme: dark)").matches;
  const swatch = (name: string) => name === "system" ? themeColors("system", dark, getSystemAccent()).accent : accentOf(name)[dark ? 1 : 0];
  const label = <T,>(list: readonly (readonly [T, string])[], v: T) => list.find((x) => x[0] === v)?.[1] ?? list[0][1];

  const bindLine = $derived(`bind = SUPER ALT, N, exec, ${togglePath}`);

  function keys(e: KeyboardEvent) {
    if (e.key !== "Escape" || busy) return;
    e.preventDefault();
    if (form) form = null; else if (page !== "menu") go("menu"); else onback();
  }

  onMount(() => {
    const off = watchSettings((v) => { s = v; });
    loadCard().then((c) => { card = c; });
    aiKey().then((k) => { key = k; });
    invoke<string>("toggle_path").then((p) => { togglePath = p; });
    invoke<boolean>("autostart_get").then((v) => { startAtLogin = v; });
    invoke<boolean>("hyprland").then((v) => { hyprland = v; });
    return () => { off.then((f) => f()); };
  });
</script>

<svelte:window onkeydown={keys} />

<div class="page">
  <div class="scroll">
    <button class="back" onclick={() => (page === "menu" ? onback() : go("menu"))} title="Back (Esc)" aria-label="Back"><Icon name="back" size={26} /></button>
    <h1>{titles[page]}</h1>

    {#if page === "menu" && s && card}
      <div class="group">
        <button class="nav" onclick={() => go("shortcut")}>
          <span class="badge"><Icon name="pen" size={22} /></span>
          <span class="txt"><b>Shortcut</b><small>Super+Alt+N · {startAtLogin ? "starts at login" : "doesn't start at login"}</small></span>
          <span class="chev"><Icon name="chevron" size={22} /></span>
        </button>
        <button class="nav" onclick={() => go("card")}>
          <span class="badge"><Icon name="pen" size={22} /></span>
          <span class="txt"><b>Card</b><small>Opens on: {label(modes, card.mode)}</small></span>
          <span class="chev"><Icon name="chevron" size={22} /></span>
        </button>
        <button class="nav" onclick={() => go("look")}>
          <span class="badge"><Icon name="palette" size={22} /></span>
          <span class="txt"><b>Appearance</b><small>{label(colorNames, s.color)} · {label(anims, card.anim)}</small></span>
          <span class="chev"><Icon name="chevron" size={22} /></span>
        </button>
        <button class="nav" onclick={() => go("shots")}>
          <span class="badge"><Icon name="screenshot" size={22} /></span>
          <span class="txt"><b>Screenshots</b><small>{label(shots, s.shotMode)}</small></span>
          <span class="chev"><Icon name="chevron" size={22} /></span>
        </button>
      </div>
      <div class="gap"></div>
      <div class="group">
        <button class="nav" onclick={() => go("sync")}>
          <span class="badge"><Icon name="sync" size={22} /></span>
          <span class="txt"><b>Sync</b><small>{sync.connected ? sync.text : "Not connected"}</small></span>
          <span class="chev"><Icon name="chevron" size={22} /></span>
        </button>
        <button class="nav" onclick={() => go("vault")}>
          <span class="badge"><Icon name="lock" size={22} /></span>
          <span class="txt"><b>Secret notes</b><small>{vaultSetUp ? "Password set" : "Not set up yet"}</small></span>
          <span class="chev"><Icon name="chevron" size={22} /></span>
        </button>
        <button class="nav" onclick={() => go("ai")}>
          <span class="badge"><Icon name="sparkle" size={22} /></span>
          <span class="txt"><b>AI titles</b><small>{key ? "Key added" : "No key yet"}</small></span>
          <span class="chev"><Icon name="chevron" size={22} /></span>
        </button>
      </div>

    {:else if page === "shortcut"}
      <div class="group">
        <div class="row">
          <p class="title">Open the card from anywhere</p>
          <p class="caption">Hyprland runs the shortcut for Scratchy. If Super+Alt+N doesn't open the card, add this line to ~/.config/hypr/bindings.conf:</p>
          <div class="code"><code>{bindLine}</code><button onclick={() => copy(bindLine)}>{copied === bindLine ? "Copied" : "Copy"}</button></div>
        </div>
        <div class="row">
          <p class="title">Start at login</p>
          <div class="seg"><Segmented options={["On", "Off"]} selected={startAtLogin ? 0 : 1}
            onselect={(i) => { startAtLogin = i === 0; set({ startAtLogin }); invoke("autostart_set", { on: startAtLogin }).catch(() => {}); }} /></div>
          <p class="caption">Scratchy starts quietly in the tray, so the card is ready the first time you press the shortcut.</p>
        </div>
      </div>

    {:else if page === "card" && card}
      <div class="group">
        <div class="row">
          <p class="title">When the card opens</p>
          <div class="seg"><Segmented options={modes.map((m) => m[1])} selected={modes.findIndex((m) => m[0] === card!.mode)}
            onselect={(i) => setCard({ mode: modes[i][0] })} /></div>
          <p class="caption">{modeHelp[card.mode]}</p>
        </div>
      </div>

    {:else if page === "look" && s && card}
      <h2>Color</h2>
      <div class="group">
        <div class="row">
          <div class="swatches">
            {#each colorNames as [name, l]}
              <button class="sw" class:on={s.color === name} style:background={swatch(name)} title={l} aria-label={l} aria-pressed={s.color === name}
                onclick={() => set({ color: name })}>{#if name === "system"}✦{/if}</button>
            {/each}
          </div>
          <p class="caption">System follows your desktop's accent color (your Omarchy theme, or GNOME's accent). If it doesn't look right, pick a color.</p>
        </div>
      </div>
      <h2>Zoom</h2>
      <div class="group">
        <div class="row zoomrow">
          <span class="grow">Notes window</span>
          <button class="zb" aria-label="Zoom out" disabled={s.zoom <= MIN_ZOOM} onclick={() => set({ zoom: stepZoom(s!.zoom, -1) })}>−</button>
          <button class="zv" title="Back to 100%" onclick={() => set({ zoom: 1 })}>{Math.round(s.zoom * 100)}%</button>
          <button class="zb" aria-label="Zoom in" disabled={s.zoom >= MAX_ZOOM} onclick={() => set({ zoom: stepZoom(s!.zoom, 1) })}>＋</button>
        </div>
        <div class="row zoomrow">
          <span class="grow">Card text</span>
          <button class="zb" aria-label="Smaller" disabled={card.zoom <= MIN_ZOOM} onclick={() => setCard({ zoom: stepZoom(card!.zoom, -1) })}>−</button>
          <button class="zv" title="Back to 100%" onclick={() => setCard({ zoom: 1 })}>{Math.round(card.zoom * 100)}%</button>
          <button class="zb" aria-label="Bigger" disabled={card.zoom >= MAX_ZOOM} onclick={() => setCard({ zoom: stepZoom(card!.zoom, 1) })}>＋</button>
        </div>
        <p class="caption zcap">Or Ctrl+= and Ctrl+- (Ctrl+0 for 100%), or Ctrl+scroll, in the notes window and in the card.</p>
      </div>
      <h2>Animation</h2>
      <div class="group">
        <div class="row">
          <p class="title">Opening and closing</p>
          <div class="seg"><Segmented options={anims.map((a) => a[1])} selected={anims.findIndex((a) => a[0] === card!.anim)}
            onselect={(i) => setCard({ anim: anims[i][0] })} /></div>
          <p class="caption">Genie pulls the card out of the nearest screen edge and back in. Slide slides it in from that edge.</p>
        </div>
        {#if card.anim === "slide"}
          <div class="row">
            <p class="title">Fade</p>
            <div class="seg"><Segmented options={["With fade", "Without fade"]} selected={card.fade ? 0 : 1}
              onselect={(i) => setCard({ fade: i === 0 })} /></div>
            <p class="caption">With fade, the card fades in over a short slide. Without, it slides all the way in from the edge of the screen.</p>
          </div>
        {/if}
        <div class="row" class:dim={card.anim === "none"}>
          <div class="slider"><span>Speed</span><b>{card.speed}%</b></div>
          <input type="range" min="50" max="200" step="5" value={card.speed} disabled={card.anim === "none"}
            oninput={(e) => setCard({ speed: +(e.currentTarget as HTMLInputElement).value })} />
          <p class="caption">100% is the default. Lower is slower, higher is faster.</p>
        </div>
      </div>
      <h2>Card</h2>
      <div class="group">
        <div class="row">
          <div class="slider"><span>Background</span><b>{card.opacity >= 1 ? "Solid" : `${Math.round(card.opacity * 100)}%`}</b></div>
          <input type="range" min="20" max="100" step="5" value={Math.round(card.opacity * 100)}
            oninput={(e) => setCard({ opacity: +(e.currentTarget as HTMLInputElement).value / 100 })} />
          <p class="caption">Lower lets what is behind the card show through. The text stays solid.</p>
        </div>
        <div class="row" class:dim={!hyprland || card.opacity >= 1}>
          <p class="title">Blur behind the card</p>
          <div class="seg"><Segmented options={["On", "Off"]} selected={card.blur ? 0 : 1} disabled={!hyprland || card.opacity >= 1}
            onselect={(i) => setCard({ blur: i === 0 })} /></div>
          <p class="caption">{hyprland
            ? "Blurs only what is under the card, for a frosted look, when the background isn't solid. Scratchy asks Hyprland for it while it runs; your config isn't changed."
            : "Needs Hyprland."}</p>
        </div>
      </div>

    {:else if page === "shots" && s}
      <div class="group">
        <div class="row">
          <p class="title">When you take one</p>
          <div class="seg"><Segmented options={shots.map((m) => m[1])} selected={shots.findIndex((m) => m[0] === s!.shotMode)}
            onselect={(i) => set({ shotMode: shots[i][0] })} /></div>
          <p class="caption">{shotHelp[s.shotMode]} Copying an image counts too.</p>
        </div>
        <button class="row adv" onclick={() => (advanced = !advanced)}><span>Advanced</span><span class="chev" class:open={advanced}><Icon name="chevron" size={22} /></span></button>
        {#if advanced}
          <div class="row">
            <p class="title">Forget a waiting screenshot after</p>
            <div class="seg"><Segmented options={ttls.map((t) => t[1])} selected={Math.max(0, ttls.findIndex((t) => t[0] === s!.shotTtl))}
              onselect={(i) => set({ shotTtl: ttls[i][0] })} /></div>
            <p class="caption">After this long the card stops offering it and the tray icon goes back to normal.</p>
          </div>
          <div class="row">
            <p class="title">Screenshot folder</p>
            <input class="field" value={s.shotDir} placeholder="The usual one (~/Pictures on Omarchy)" spellcheck="false"
              onchange={(e) => set({ shotDir: (e.currentTarget as HTMLInputElement).value.trim() })} />
          </div>
        {/if}
      </div>

    {:else if page === "sync"}
      <div class="group">
        <div class="row">
          <p class="caption">
            Keeps your notes, secret notes and images in sync across your devices through the hidden app folder of your Google Drive.
            Everything is encrypted on this computer first with a key made from your vault password, so Google can't read it.
            Use the same vault password on every device. If you forget it, the synced copy can't be recovered.
          </p>
        </div>
        <div class="row">
          {#if form === "connect" || form === "newPassword"}
            <form onsubmit={(e) => { e.preventDefault(); submit(); }}>
              <!-- svelte-ignore a11y_autofocus -->
              <input class="field" type="password" bind:value={pw} autofocus autocomplete="off" disabled={busy}
                placeholder={form === "connect" ? "Vault password" : "New vault password"} />
              <p class="caption">{form === "connect" ? "Enter your vault password. It encrypts your notes before they leave this computer." : "Enter the vault password you set on your other device."}</p>
              {#if busy && form === "connect"}<p class="caption">Finish signing in to Google in your browser, then come back here.</p>{/if}
              {#if error}<p class="error">{error}</p>{/if}
              <div class="buttons">
                <button type="button" class="text" onclick={() => (form = null)} disabled={busy}>Cancel</button>
                <button type="submit" class="filled" disabled={busy}>{busy ? (form === "connect" ? "Waiting for Google…" : "Checking…") : form === "connect" ? "Connect" : "Continue"}</button>
              </div>
            </form>
          {:else}
            {#if sync.needsSignIn}
              <button class="filled wide" onclick={() => run(() => engine.signIn())} disabled={busy}>{busy ? "Waiting for Google…" : "Sign in to Google again"}</button>
            {:else}
              <button class="filled wide" disabled={busy || sync.state === "syncing"}
                onclick={() => (sync.connected ? run(() => engine.syncNow()) : show("connect"))}>{sync.connected ? "Sync now" : "Connect Google Drive"}</button>
            {/if}
            <p class="status">{busy && !sync.connected ? "Waiting for Google…" : sync.connected ? sync.text : "Not connected"}</p>
            {#if sync.needsPassword}<button class="link" onclick={() => show("newPassword")}>Enter the new vault password</button>{/if}
            {#if error}<p class="error">{error}</p>{/if}
            {#if sync.connected}<button class="text wide" onclick={() => run(() => engine.disconnect())} disabled={busy}>Disconnect</button>{/if}
          {/if}
        </div>
      </div>

    {:else if page === "vault"}
      <div class="group">
        <div class="row">
          <p class="caption">Secret notes are hidden from every list and search, and their text is encrypted on this computer.</p>
        </div>
        <div class="row">
          <p class="title">Vault password</p>
          <p class="caption">Opens secret notes and encrypts your synced notes. Changing it re-encrypts everything in Drive; enter the new one on your other devices afterwards.</p>
          {#if form === "change"}
            <form onsubmit={(e) => { e.preventDefault(); submit(); }}>
              {#if vaultSetUp}
                <!-- svelte-ignore a11y_autofocus -->
                <input class="field" type="password" bind:value={old} placeholder="Current password" autocomplete="off" disabled={busy} autofocus />
              {/if}
              <input class="field" type="password" bind:value={pw} placeholder="New password" autocomplete="off" disabled={busy} />
              <input class="field" type="password" bind:value={pw2} placeholder="Repeat new password" autocomplete="off" disabled={busy} />
              {#if error}<p class="error">{error}</p>{/if}
              <div class="buttons">
                <button type="button" class="text" onclick={() => (form = null)} disabled={busy}>Cancel</button>
                <button type="submit" class="filled" disabled={busy}>{busy ? "Changing…" : "Change"}</button>
              </div>
            </form>
          {:else}
            <button class="link" onclick={() => show("change")}>{vaultSetUp ? "Change vault password" : "Set a vault password"}</button>
          {/if}
          {#if done}<p class="done">{done}</p>{/if}
        </div>
      </div>

    {:else if page === "ai" && s}
      <div class="group">
        <div class="row">
          <p class="caption">Click the ✨ next to a note's title and Scratchy suggests three titles from its text, or from its first image. Only that note is sent, only when you click, to OpenRouter.</p>
        </div>
        <div class="row">
          <p class="title">Suggest a category</p>
          <div class="seg"><Segmented options={["On", "Off"]} selected={s.suggestCategories ? 0 : 1} onselect={(i) => set({ suggestCategories: i === 0 })} /></div>
          <p class="caption">While you write a new note, a small chip offers a category. Click it to accept, or ignore it and it fades away. Needs the key below.</p>
        </div>
        <div class="row">
          <p class="title">OpenRouter key</p>
          <div class="keyrow">
            <input class="field" type={keyShown ? "text" : "password"} bind:value={key} oninput={typedKey} placeholder="Paste your key" autocomplete="off" spellcheck="false" />
            <button class="text" onclick={() => (keyShown = !keyShown)}>{keyShown ? "Hide" : "Show"}</button>
          </div>
          <p class="caption">Kept in your system's keyring.</p>
        </div>
        <div class="row">
          <button class="filled wide" onclick={async () => { clearTimeout(keyTimer); await setAiKey(key); test = "Testing…"; test = await ping(); }}>Test connection</button>
          {#if test}<p class="status">{test}</p>{/if}
        </div>
        <button class="row adv" onclick={() => (advanced = !advanced)}><span>Models</span><span class="chev" class:open={advanced}><Icon name="chevron" size={22} /></span></button>
        {#if advanced}
          <div class="row">
            <p class="title">Text model</p>
            <input class="field" value={s.textModel} placeholder={DEFAULT_MODEL} spellcheck="false" onchange={(e) => set({ textModel: (e.currentTarget as HTMLInputElement).value.trim() })} />
          </div>
          <div class="row">
            <p class="title">Image model</p>
            <input class="field" value={s.imageModel} placeholder="Same as text model" spellcheck="false" onchange={(e) => set({ imageModel: (e.currentTarget as HTMLInputElement).value.trim() })} />
            <p class="caption">Must understand images. Notes with an image use this one.</p>
          </div>
        {/if}
      </div>
    {/if}
  </div>
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; }
  .scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 8px 20px 48px; scrollbar-width: none; }
  .scroll > * { max-width: 640px; margin-left: auto; margin-right: auto; }
  .back {
    display: grid; place-items: center; width: 44px; height: 44px; margin-left: -10px; border: 0; border-radius: 22px; background: transparent;
    color: var(--text); cursor: pointer; transition: transform 150ms;
  }
  .scroll > .back { margin-left: max(-10px, calc((100% - 640px) / 2 - 10px)); }
  .back:active { transform: scale(0.85); }
  h1 { margin: 4px auto 18px; font-size: 34px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.2; }
  h2 { margin: 26px auto 8px; padding-left: 14px; font-size: 12px; font-weight: 500; letter-spacing: 0.09em; text-transform: uppercase; color: var(--secondary); }
  .gap { height: 12px; }
  .group { background: var(--surface); border-radius: 22px; padding: 4px 18px; display: flex; flex-direction: column; }
  .group > * + * { border-top: 1px solid var(--hairline); }
  .row { padding: 16px 0; display: flex; flex-direction: column; }
  button.row { border-left: 0; border-right: 0; border-bottom: 0; background: transparent; font: inherit; color: inherit; text-align: start; cursor: pointer; }
  .adv { flex-direction: row; align-items: center; }
  .adv > span:first-child { flex: 1; font-size: 15px; color: var(--secondary); }
  .chev { display: grid; color: var(--secondary); transform: rotate(90deg); transition: transform 200ms; }
  .chev.open { transform: rotate(270deg); }
  .nav .chev { transform: none; }
  .nav {
    display: flex; align-items: center; gap: 14px; padding: 16px 0; border-left: 0; border-right: 0; border-bottom: 0; background: transparent;
    font: inherit; color: inherit; text-align: start; cursor: pointer;
  }
  .group > .nav:first-child { border-top: 0; }
  .badge { flex: none; width: 42px; height: 42px; border-radius: 21px; display: grid; place-items: center; background: var(--wash); color: var(--accent); }
  .txt { flex: 1; min-width: 0; display: flex; flex-direction: column; }
  .txt b { font-weight: 400; font-size: 16px; color: var(--text); }
  .txt small { font-size: 13px; color: var(--secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .title { margin: 0; font-size: 16px; color: var(--text); }
  .caption { margin: 4px 0 0; font-size: 13px; line-height: 1.4; color: var(--secondary); }
  .zoomrow { flex-direction: row; align-items: center; gap: 6px; padding: 10px 0; }
  .zoomrow .grow { flex: 1; font-size: 16px; }
  .zb, .zv {
    height: 36px; border: 0; border-radius: 18px; background: var(--track); color: var(--text); font: inherit; cursor: pointer;
    transition: transform 150ms;
  }
  .zb { width: 36px; font-size: 18px; }
  .zv { min-width: 64px; font-size: 14px; font-weight: 500; font-variant-numeric: tabular-nums; }
  .zb:active, .zv:active { transform: scale(0.92); }
  .zb:disabled { opacity: 0.35; cursor: default; }
  .zcap { padding-bottom: 14px; }
  .seg { margin-top: 10px; }
  .dim { opacity: 0.4; }
  .slider { display: flex; font-size: 16px; } .slider span { flex: 1; } .slider b { font-weight: 500; color: var(--accent); }
  input[type="range"] { width: 100%; margin: 12px 0 2px; accent-color: var(--accent); }
  .swatches { display: flex; }
  .sw {
    flex: 1 1 0; max-width: 42px; height: 42px; margin: 3px auto; border: 0; border-radius: 50%; cursor: pointer; color: #fff; font-size: 16px;
    transition: transform 150ms;
  }
  .sw.on { box-shadow: 0 0 0 3px var(--text); }
  .sw:active { transform: scale(0.9); }
  .code { display: flex; align-items: center; gap: 8px; margin-top: 10px; padding: 10px 12px; border-radius: 12px; background: var(--track); }
  .code code { flex: 1; min-width: 0; font-family: ui-monospace, "JetBrains Mono", monospace; font-size: 12.5px; overflow-wrap: anywhere; }
  .code button, .keyrow .text { flex: none; border: 0; background: transparent; color: var(--accent); font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; padding: 4px 6px; }
  .keyrow { display: flex; align-items: center; gap: 6px; margin-top: 10px; }
  .keyrow .field { flex: 1; margin: 0; }
  .field {
    margin-top: 10px; padding: 12px 14px; border: 0; border-radius: 12px; background: var(--track); color: var(--text);
    font: inherit; font-size: 16px; outline: none; min-width: 0;
  }
  .field:focus { box-shadow: inset 0 0 0 1.5px var(--accent); }
  .field::placeholder { color: var(--secondary); }
  form { display: flex; flex-direction: column; }
  form .field:first-child { margin-top: 0; }
  .buttons { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
  button.filled, button.text { height: 46px; padding: 0 20px; border: 0; border-radius: 14px; font: inherit; font-size: 16px; font-weight: 500; cursor: pointer; transition: transform 150ms; }
  button.filled { background: var(--accent); color: var(--on-accent); }
  button.text { background: transparent; color: var(--accent); }
  button.wide { width: 100%; }
  .row > button.text.wide { margin-top: 6px; height: 40px; }
  button:disabled { opacity: 0.55; cursor: default; }
  button.filled:active:not(:disabled), button.text:active:not(:disabled) { transform: scale(0.97); }
  .link { align-self: flex-start; margin-top: 4px; padding: 10px 8px 2px 2px; border: 0; background: transparent; color: var(--accent); font: inherit; font-size: 15px; font-weight: 500; cursor: pointer; }
  .status { margin: 0; padding: 10px 0 0 2px; font-size: 14px; color: var(--secondary); }
  .error { margin: 8px 0 0; color: var(--danger); font-size: 13.5px; }
  .done { margin: 8px 0 0; color: var(--success); font-size: 13.5px; }
</style>
