<script lang="ts">
  import { MIN_PASSWORD } from "@scratchy/core";
  import { engine } from "../lib/notes";

  /** The lock in front of secret notes (VaultGate.kt): choose a password the first time, then enter it. */
  interface Props { setUp: boolean; onopen: () => void; onclose: () => void }
  let { setUp, onopen, onclose }: Props = $props();
  let pw = $state(""), again = $state("");
  let error = $state("");
  let busy = $state(false);
  let field: HTMLInputElement | undefined = $state();

  async function submit() {
    if (busy) return;
    error = "";
    if (!setUp) {
      if (pw.length < MIN_PASSWORD) { error = `Use at least ${MIN_PASSWORD} characters`; return; }
      if (pw !== again) { error = "Passwords don't match"; return; }
      busy = true;
      try { await engine.setupVault(pw); onopen(); } catch (e) { error = String((e as Error).message ?? e); } finally { busy = false; }
      return;
    }
    busy = true;
    const ok = await engine.unlock(pw).catch(() => false);
    busy = false;
    if (ok) onopen();
    else { error = "Wrong password"; pw = ""; field?.focus(); }
  }
</script>

<svelte:window onkeydown={(e) => { if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); onclose(); } }} />

<div class="gate">
  <button class="back" onclick={onclose} title="Back (Esc)" aria-label="Back">
    <svg viewBox="0 0 24 24" width="20" height="20"><path d="M15 5 8 12l7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
  </button>
  <form onsubmit={(e) => { e.preventDefault(); void submit(); }} aria-label="Secret notes">
    <svg viewBox="0 0 24 24" width="60" height="60" aria-hidden="true"><path fill="var(--accent)" d="M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2m-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4M15.1 8H8.9V6a3.1 3.1 0 0 1 6.2 0z" /></svg>
    <h2>Secret notes</h2>
    <p>{setUp ? "Enter your password" : "Choose a password. Notes marked secret are hidden until you unlock them."}</p>
    <!-- svelte-ignore a11y_autofocus -->
    <input bind:this={field} type="password" placeholder="Password" bind:value={pw} autofocus autocomplete="off" disabled={busy} />
    {#if !setUp}<input type="password" placeholder="Repeat password" bind:value={again} autocomplete="off" disabled={busy} />{/if}
    {#if error}<p class="error">{error}</p>{/if}
    <button class="filled" type="submit" disabled={busy}>{setUp ? "Unlock" : "Continue"}</button>
  </form>
</div>

<style>
  .gate { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; background: var(--background); animation: fade 140ms ease-out; }
  .back {
    position: absolute; top: 10px; left: 12px; width: 40px; height: 40px; border: 0; border-radius: 13px; display: grid; place-items: center;
    background: transparent; color: var(--secondary); cursor: pointer;
  }
  .back:hover { background: var(--wash); color: var(--text); }
  form { width: min(340px, calc(100vw - 48px)); display: flex; flex-direction: column; align-items: stretch; gap: 10px; text-align: center; }
  form > svg { align-self: center; }
  h2 { margin: 6px 0 0; font-size: 26px; font-weight: 700; letter-spacing: -0.02em; }
  p { margin: 0 0 14px; color: var(--secondary); font-size: 15px; line-height: 1.45; }
  input {
    height: 50px; padding: 0 14px; border-radius: 14px; border: 1.5px solid transparent; background: var(--surface);
    color: var(--text); font: inherit; font-size: 17px; text-align: center; outline: none;
  }
  input:focus { border-color: var(--accent); }
  .error { margin: 0; color: var(--danger); font-size: 14px; }
  .filled { height: 50px; margin-top: 6px; border: 0; border-radius: 14px; background: var(--accent); color: var(--on-accent); font: inherit; font-size: 16px; font-weight: 600; cursor: pointer; }
  .filled:disabled { opacity: 0.55; }
  .filled:active:not(:disabled) { transform: scale(0.97); }
  @keyframes fade { from { opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .gate { animation: none; } }
</style>
