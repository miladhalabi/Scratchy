<script lang="ts">
  import { MIN_PASSWORD } from "@scratchy/core";
  import { setupVault, unlock, vaultSetUp } from "./vault";
  import { usingKeyring } from "./secrets";

  let { onOpen, onClose }: { onOpen: () => void; onClose: () => void } = $props();
  let password = $state("");
  let again = $state("");
  let error = $state("");
  let busy = $state(false);
  const setup = !$vaultSetUp;

  async function submit() {
    error = "";
    if (setup) {
      if (password.length < MIN_PASSWORD) { error = `Use at least ${MIN_PASSWORD} characters.`; return; }
      if (password !== again) { error = "The passwords don't match."; return; }
      busy = true;
      try { await setupVault(password); onOpen(); } catch (e) { error = String((e as Error).message ?? e); } finally { busy = false; }
    } else {
      busy = true;
      const ok = await unlock(password);
      busy = false;
      if (ok) onOpen(); else { error = "Wrong password."; password = ""; }
    }
  }
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && onClose()} />
<div class="scrim" role="presentation" onclick={onClose}></div>
<form class="panel" onsubmit={(e) => { e.preventDefault(); void submit(); }} aria-label="Secret notes">
  <svg viewBox="0 0 24 24" width="44" height="44" aria-hidden="true"><path fill="var(--accent)" d="M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2m-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4M15.1 8H8.9V6a3.1 3.1 0 0 1 6.2 0z"/></svg>
  <h2>Secret notes</h2>
  <p class="sub">{setup ? "Choose a vault password. Use the same one as on your phone so synced secret notes stay readable everywhere." : "Enter your vault password."}</p>
  <!-- svelte-ignore a11y_autofocus -->
  <input type="password" placeholder="Password" bind:value={password} autofocus autocomplete="off" disabled={busy} />
  {#if setup}<input type="password" placeholder="Repeat password" bind:value={again} autocomplete="off" disabled={busy} />{/if}
  {#if error}<p class="error">{error}</p>{/if}
  <button class="primary" type="submit" disabled={busy}>{setup ? "Create vault" : "Unlock"}</button>
  {#if setup && !usingKeyring}
    <p class="note">No system keyring was found, so secret notes are protected by file permissions only.</p>
  {/if}
</form>

<style>
  .scrim { position: fixed; inset: 0; background: #000a; }
  .panel {
    position: fixed; left: 50%; top: 50%; transform: translate(-50%, -50%); width: min(380px, 92vw);
    background: var(--floating); border-radius: 26px; padding: 28px; box-shadow: 0 20px 60px #000a;
    display: flex; flex-direction: column; align-items: stretch; gap: 12px; text-align: center;
  }
  svg { align-self: center; }
  h2 { margin: 0; font-size: 26px; letter-spacing: -0.02em; }
  .sub { margin: 0 0 6px; color: var(--secondary); font-size: 14px; line-height: 1.45; }
  input { height: 48px; padding: 0 14px; border-radius: 14px; border: 0; background: var(--track); color: var(--text); font-size: 16px; text-align: center; }
  .primary { height: 48px; border-radius: 14px; background: var(--accent); color: var(--on-accent); font-weight: 600; margin-top: 4px; }
  .primary:disabled { opacity: .55; }
  .error { margin: 0; color: var(--danger); font-size: 14px; }
  .note { margin: 0; color: var(--secondary); font-size: 12.5px; }
</style>
