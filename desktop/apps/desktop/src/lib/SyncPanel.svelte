<script lang="ts">
  import { WrongPassword } from "@scratchy/core";
  import { connectGoogle, disconnect, isConnected, syncNow, syncStatus } from "./sync";

  let { onClose }: { onClose: () => void } = $props();
  let password = $state("");
  let busy = $state(false);
  let error = $state("");
  let connected = $state(isConnected());

  async function connect() {
    if (!password) { error = "Type your vault password."; return; }
    busy = true; error = "";
    try { await connectGoogle(password); connected = true; password = ""; }
    catch (e) {
      error = e instanceof WrongPassword ? "That isn't the password used on your phone." : String((e as Error)?.message ?? e);
    } finally { busy = false; }
  }
  async function off() { await disconnect(); connected = false; }
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && onClose()} />

<div class="scrim" role="presentation" onclick={onClose}></div>
<div class="panel" role="dialog" aria-label="Sync">
  <h2>Sync</h2>
  <p class="help">
    Keeps your notes in step with your phone through the hidden app folder of your Google Drive. Everything is encrypted
    on this computer first with a key made from your vault password, so Google can't read it. Use the same vault password
    as on your phone.
  </p>

  {#if connected}
    <div class="status" class:warn={$syncStatus.state === "attention"}>{$syncStatus.text}</div>
    <div class="row">
      <button class="primary" onclick={() => syncNow()} disabled={$syncStatus.state === "syncing"}>Sync now</button>
      <button class="quiet" onclick={off}>Disconnect</button>
    </div>
  {:else}
    <label>
      Vault password
      <input type="password" bind:value={password} onkeydown={(e) => e.key === "Enter" && connect()} disabled={busy} autocomplete="off" />
    </label>
    <button class="primary wide" onclick={connect} disabled={busy}>{busy ? "Waiting for Google…" : "Connect Google Drive"}</button>
    {#if busy}<p class="hint">Finish signing in in your browser, then come back here.</p>{/if}
    {#if error}<p class="error">{error}</p>{/if}
  {/if}
  <button class="quiet close" onclick={onClose}>Close</button>
</div>

<style>
  .scrim { position: fixed; inset: 0; background: #0008; }
  .panel {
    position: fixed; left: 50%; top: 50%; transform: translate(-50%, -50%); width: min(480px, 92vw);
    background: var(--floating); border-radius: 26px; padding: 26px 26px 18px; box-shadow: 0 20px 60px #000a;
    display: flex; flex-direction: column; gap: 14px;
  }
  h2 { margin: 0; font-size: 26px; letter-spacing: -0.02em; }
  .help { margin: 0; color: var(--secondary); font-size: 14px; line-height: 1.5; }
  label { display: flex; flex-direction: column; gap: 6px; font-size: 14px; color: var(--secondary); }
  input { height: 46px; padding: 0 14px; border-radius: 14px; border: 0; background: var(--track); color: var(--text); font-size: 16px; }
  .row { display: flex; gap: 10px; }
  .primary { background: var(--accent); color: var(--on-accent); height: 46px; padding: 0 22px; border-radius: 14px; font-weight: 600; }
  .primary:disabled { opacity: .55; cursor: default; }
  .wide { width: 100%; }
  .quiet { color: var(--accent); height: 40px; padding: 0 14px; border-radius: 12px; }
  .quiet:hover { background: var(--accent-container); }
  .close { align-self: flex-end; color: var(--secondary); }
  .status { padding: 12px 14px; border-radius: 14px; background: var(--track); font-size: 15px; }
  .status.warn { outline: 1.5px solid var(--accent); }
  .hint { margin: 0; color: var(--secondary); font-size: 13px; }
  .error { margin: 0; color: var(--danger); font-size: 14px; }
</style>
