<script lang="ts">
  import { MIN_PASSWORD } from "@scratchy/core";
  import type { SyncStatus } from "../lib/sync";
  import { engine } from "../lib/notes";

  /** Settings (SettingsActivity on the phone). For now: sync, and the vault password. */
  interface Props { sync: SyncStatus; vaultSetUp: boolean; onback: () => void }
  let { sync, vaultSetUp, onback }: Props = $props();

  type Form = "connect" | "newPassword" | "change" | null;
  let form: Form = $state(null);
  let pw = $state(""), pw2 = $state(""), old = $state("");
  let busy = $state(false);
  let error = $state("");
  let done = $state("");

  function show(f: Form) { form = f; pw = pw2 = old = ""; error = ""; done = ""; }

  async function run(job: () => Promise<unknown>, ok = "") {
    busy = true; error = ""; done = "";
    try { await job(); form = null; done = ok; }
    catch (e) { error = String((e as Error)?.message ?? e); }
    finally { busy = false; }
  }

  function submit() {
    if (busy) return;
    if (form === "connect") {
      if (!pw) { error = "Type your vault password"; return; }
      void run(() => engine.connect(pw));
    } else if (form === "newPassword") {
      if (!pw) { error = "Type the new vault password"; return; }
      void run(() => engine.newPassword(pw));
    } else if (form === "change") {
      if (pw.length < MIN_PASSWORD) { error = `Use at least ${MIN_PASSWORD} characters`; return; }
      if (pw !== pw2) { error = "The new passwords don't match"; return; }
      void run(() => engine.changePassword(old, pw), "Password changed. Enter it on your other devices.");
    }
  }

  function keys(e: KeyboardEvent) {
    if (e.key !== "Escape") return;
    e.preventDefault();
    if (form && !busy) form = null; else if (!busy) onback();
  }
</script>

<svelte:window onkeydown={keys} />

<div class="page">
  <header>
    <button class="icon" onclick={onback} title="Back (Esc)" aria-label="Back">
      <svg viewBox="0 0 24 24" width="20" height="20"><path d="M15 5 8 12l7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
    </button>
    <h1>Settings</h1>
  </header>

  <div class="scroll">
    <section>
      <h2>Sync</h2>
      <div class="group">
        <p class="caption">
          Keeps your notes, secret notes and images in sync with your phone through the hidden app folder of your Google Drive.
          Everything is encrypted on this computer first with a key made from your vault password, so Google can't read it.
          Use the same vault password on every device. If you forget it, the synced copy can't be recovered.
        </p>
        <div class="status" class:warn={sync.state === "attention"} class:off={sync.state === "offline"}>
          <span class="dot" class:spin={sync.state === "syncing"}></span>{sync.text}
        </div>

        {#if form === "connect" || form === "newPassword"}
          <form onsubmit={(e) => { e.preventDefault(); submit(); }}>
            <!-- svelte-ignore a11y_autofocus -->
            <input type="password" bind:value={pw} autofocus autocomplete="off" disabled={busy}
              placeholder={form === "connect" ? "Vault password" : "New vault password"} />
            {#if form === "newPassword"}<p class="hint">Enter the vault password you set on your other device.</p>{/if}
            {#if busy && form === "connect"}<p class="hint">Finish signing in to Google in your browser, then come back here.</p>{/if}
            {#if error}<p class="error">{error}</p>{/if}
            <div class="row">
              <button type="button" class="text" onclick={() => (form = null)} disabled={busy}>Cancel</button>
              <button type="submit" class="filled" disabled={busy}>{busy ? (form === "connect" ? "Waiting for Google…" : "Checking…") : form === "connect" ? "Connect" : "Continue"}</button>
            </div>
          </form>
        {:else if sync.connected}
          {#if sync.needsSignIn}
            <button class="filled wide" onclick={() => run(() => engine.signIn())} disabled={busy}>{busy ? "Waiting for Google…" : "Sign in to Google again"}</button>
            {#if busy}<p class="hint">Finish signing in to Google in your browser, then come back here.</p>{/if}
          {/if}
          {#if sync.needsPassword}
            <button class="link" onclick={() => show("newPassword")}>Enter the new vault password</button>
          {/if}
          <div class="row">
            <button class="filled" onclick={() => run(() => engine.syncNow())} disabled={busy || sync.state === "syncing"}>Sync now</button>
            <button class="text" onclick={() => run(() => engine.disconnect())} disabled={busy}>Disconnect</button>
          </div>
        {:else}
          <button class="filled wide" onclick={() => show("connect")}>Connect Google Drive</button>
        {/if}
      </div>
    </section>

    <section>
      <h2>Vault</h2>
      <div class="group">
        <p class="title">Vault password</p>
        <p class="caption">Opens secret notes and encrypts your synced notes. Changing it re-encrypts everything in Drive; enter the new one on your other devices afterwards.</p>
        {#if form === "change"}
          <form onsubmit={(e) => { e.preventDefault(); submit(); }}>
            {#if vaultSetUp}
              <!-- svelte-ignore a11y_autofocus -->
              <input type="password" bind:value={old} placeholder="Current password" autocomplete="off" disabled={busy} autofocus />
            {/if}
            <input type="password" bind:value={pw} placeholder="New password" autocomplete="off" disabled={busy} />
            <input type="password" bind:value={pw2} placeholder="Repeat new password" autocomplete="off" disabled={busy} />
            {#if error}<p class="error">{error}</p>{/if}
            <div class="row">
              <button type="button" class="text" onclick={() => (form = null)} disabled={busy}>Cancel</button>
              <button type="submit" class="filled" disabled={busy}>{busy ? "Changing…" : "Change"}</button>
            </div>
          </form>
        {:else}
          <button class="link" onclick={() => show("change")}>{vaultSetUp ? "Change vault password" : "Set a vault password"}</button>
        {/if}
        {#if done}<p class="done">{done}</p>{/if}
        {#if error && !form}<p class="error">{error}</p>{/if}
      </div>
    </section>
  </div>
</div>

<style>
  .page { height: 100%; display: flex; flex-direction: column; }
  header { flex: none; display: flex; align-items: center; gap: 8px; padding: 10px 12px; }
  h1 { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.02em; }
  .icon {
    width: 40px; height: 40px; border: 0; border-radius: 13px; display: grid; place-items: center;
    background: transparent; color: var(--secondary); cursor: pointer;
  }
  .icon:hover { background: var(--wash); color: var(--text); }
  .scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 16px 48px; }
  section { max-width: 620px; margin: 0 auto 22px; }
  h2 { margin: 0 6px 8px; font-size: 13px; font-weight: 650; color: var(--accent); text-transform: uppercase; letter-spacing: 0.06em; }
  .group { background: var(--surface); border: 1px solid var(--hairline); border-radius: 20px; padding: 16px; display: flex; flex-direction: column; gap: 12px; }
  .title { margin: 0; font-weight: 650; }
  .caption { margin: 0; color: var(--secondary); font-size: 13.5px; line-height: 1.5; }
  .status { display: flex; align-items: center; gap: 10px; padding: 11px 14px; border-radius: 14px; background: var(--track); font-size: 14.5px; }
  .status.warn { box-shadow: inset 0 0 0 1.5px var(--danger); }
  .dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--success); }
  .status.warn .dot { background: var(--danger); }
  .status.off .dot { background: var(--secondary); }
  .dot.spin { background: var(--accent); animation: pulse 900ms ease-in-out infinite alternate; }
  form { display: flex; flex-direction: column; gap: 10px; }
  input {
    height: 46px; padding: 0 14px; border-radius: 14px; border: 1.5px solid var(--hairline); background: transparent;
    color: var(--text); font: inherit; font-size: 15px; outline: none;
  }
  input:focus { border-color: var(--accent); }
  .row { display: flex; gap: 8px; justify-content: flex-end; }
  button.text, button.filled { border: 0; border-radius: 13px; height: 42px; padding: 0 18px; font: inherit; font-weight: 600; cursor: pointer; }
  button.filled { background: var(--accent); color: var(--on-accent); }
  button.text { background: transparent; color: var(--accent); }
  button.text:hover { background: var(--wash); }
  button:disabled { opacity: 0.55; cursor: default; }
  button:active:not(:disabled) { transform: scale(0.97); }
  .wide { width: 100%; }
  .link { align-self: flex-start; border: 0; background: transparent; padding: 2px 0; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .link:hover { text-decoration: underline; }
  .hint { margin: 0; color: var(--secondary); font-size: 13px; }
  .error { margin: 0; color: var(--danger); font-size: 13.5px; }
  .done { margin: 0; color: var(--success); font-size: 13.5px; }
  @keyframes pulse { from { opacity: 0.35; } }
  @media (prefers-reduced-motion: reduce) { .dot.spin { animation: none; } }
</style>
