<script lang="ts">
  import { notes } from "./state";
  import { formatWhen, heading, preview } from "./format";
  import type { Note } from "@scratchy/core";

  import { syncStatus } from "./sync";

  let { secretMode, onOpen, onNew, onSync, onVault, onLeaveVault, onLock }: {
    secretMode: boolean; onOpen: (n: Note) => void; onNew: () => void; onSync: () => void;
    onVault: () => void; onLeaveVault: () => void; onLock: () => void;
  } = $props();
  let query = $state("");
  let search: HTMLInputElement;

  const shown = $derived(
    $notes
      .filter((n) => n.secret === secretMode)
      .filter((n) => !query.trim() || (n.title + "\n" + n.body).toLowerCase().includes(query.trim().toLowerCase()))
      .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt),
  );

  // Pulling the list down past its top (two-finger scroll up) reveals a lock; letting go then opens the vault.
  // Nothing shows until the pull is long and deliberate, so it can't happen by accident.
  let pull = $state(0);
  let quiet: ReturnType<typeof setTimeout> | undefined;
  const REVEAL = 320;
  const armed = $derived(pull >= REVEAL);

  function wheel(e: WheelEvent) {
    if (secretMode) return;
    const el = e.currentTarget as HTMLElement;
    if (e.deltaY < 0 && el.scrollTop <= 0) {
      e.preventDefault();
      pull = Math.min(pull - e.deltaY, 700);
      clearTimeout(quiet);
      quiet = setTimeout(release, 220);
    } else if (pull > 0) { pull = 0; }
  }
  function release() {
    const open = pull >= REVEAL;
    pull = 0;
    if (open) onVault();
  }

  function keys(e: KeyboardEvent) {
    if (e.ctrlKey && e.key.toLowerCase() === "f") { e.preventDefault(); search.focus(); }
  }
</script>

<svelte:window onkeydown={keys} />

<main>
  <header>
    {#if secretMode}
      <button class="back" onclick={onLeaveVault} aria-label="Back to notes" title="Back to notes">
        <svg viewBox="0 0 24 24" width="26" height="26"><path fill="currentColor" d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z"/></svg>
      </button>
      <h1>Secret</h1>
      <span class="grow"></span>
      <button class="sync" onclick={onLock} title="Lock now"><span class="dot"></span><span class="label">Lock now</span></button>
    {:else}
      <h1>Notes</h1>
      <span class="grow"></span>
      <button class="lock" onclick={onVault} aria-label="Secret notes" title="Secret notes">
        <svg viewBox="0 0 24 24" width="22" height="22"><path fill="currentColor" d="M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2m-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4M15.1 8H8.9V6a3.1 3.1 0 0 1 6.2 0z"/></svg>
      </button>
      <button class="sync" onclick={onSync} title={$syncStatus.text}>
        <span class="dot {$syncStatus.state}"></span>
        <span class="label">{$syncStatus.state === "off" ? "Sync" : $syncStatus.text}</span>
      </button>
    {/if}
  </header>
  <label class="search">
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14"/></svg>
    <input bind:this={search} bind:value={query} placeholder="Search" />
  </label>

  {#if pull > 0}
    <div class="pull" class:armed style:opacity={Math.min(1, Math.max(0, (pull - 140) / 180))} aria-hidden="true">
      <svg viewBox="0 0 24 24" width="30" height="30"><path fill="currentColor" d="M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2m-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4M15.1 8H8.9V6a3.1 3.1 0 0 1 6.2 0z"/></svg>
    </div>
  {/if}
  <section class="scroll" onwheel={wheel} style:transform={pull > 0 ? `translateY(${Math.min(pull * 0.22, 90)}px)` : ""}>
    {#if shown.length === 0}
      <p class="empty">{query ? "No matches." : secretMode ? "No secret notes yet. Press the + button." : "No notes yet. Press Ctrl+N or the + button."}</p>
    {:else}
      <div class="grid">
        {#each shown as n (n.id)}
          <button class="card" onclick={() => onOpen(n)}>
            <h2 class="bidi">{heading(n) || "Untitled"}</h2>
            <p class="bidi">{preview(n) || "No text"}</p>
            <footer>
              <span>{formatWhen(n.updatedAt)}</span>
              {#if n.pinned}<svg viewBox="0 0 24 24" width="16" height="16" aria-label="Pinned"><path fill="var(--accent)" d="M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3"/></svg>{/if}
            </footer>
          </button>
        {/each}
      </div>
    {/if}
  </section>

  <button class="fab" onclick={onNew} aria-label="New note" title="New note (Ctrl+N)">
    <svg viewBox="0 0 24 24" width="26" height="26"><path fill="currentColor" d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z"/></svg>
  </button>
</main>

<style>
  main { height: 100%; display: flex; flex-direction: column; padding: 28px 28px 0; max-width: 1400px; margin: 0 auto; }
  header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
  h1 { margin: 0 0 0 4px; font-size: 40px; letter-spacing: -0.02em; }
  .grow { flex: 1; }
  .back { width: 44px; height: 44px; display: grid; place-items: center; border-radius: 14px; color: var(--secondary); margin-right: 6px; }
  .back:hover { background: var(--surface); color: var(--text); }
  .lock { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 50%; color: var(--secondary); margin-right: 8px; }
  .lock:hover { background: var(--surface); color: var(--accent); }
  .pull { position: fixed; top: 150px; left: 50%; translate: -50% 0; width: 54px; height: 54px; border-radius: 50%; display: grid; place-items: center; background: var(--surface); color: var(--secondary); z-index: 5; transition: color .12s, background .12s, scale .12s; }
  .pull.armed { color: var(--on-accent); background: var(--accent); scale: 1.12; }
  .scroll { transition: transform .12s ease-out; }
  .sync { display: flex; align-items: center; gap: 9px; height: 38px; padding: 0 16px; border-radius: 19px; background: var(--surface); color: var(--secondary); font-size: 14px; max-width: 55%; }
  .sync:hover { color: var(--text); }
  .sync .label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .dot { width: 9px; height: 9px; border-radius: 50%; background: var(--secondary); flex: none; }
  .dot.idle { background: var(--success); }
  .dot.syncing { background: var(--accent); animation: pulse 1s ease-in-out infinite; }
  .dot.offline, .dot.attention { background: var(--danger); }
  @keyframes pulse { 50% { opacity: .35; } }
  .search {
    display: flex; align-items: center; gap: 10px; padding: 0 16px; height: 46px;
    background: var(--surface); border-radius: 16px; color: var(--secondary); margin-bottom: 14px;
  }
  .search input { flex: 1; background: none; border: 0; outline: 0; color: var(--text); height: 100%; }
  .search input::placeholder { color: var(--secondary); }
  .search:focus-within { outline: 2px solid var(--accent); }
  .scroll { flex: 1; overflow-y: auto; padding: 4px 4px 110px; margin: 0 -4px; }
  .grid { columns: 230px; column-gap: 12px; }
  .card {
    display: block; width: 100%; text-align: start; break-inside: avoid; margin: 0 0 12px;
    background: var(--surface); border-radius: var(--radius); padding: 16px 18px 14px;
    transition: transform .12s ease, box-shadow .12s ease;
  }
  .card:hover { box-shadow: 0 0 0 1.5px var(--hairline); }
  .card:active { transform: scale(0.98); }
  .card h2 { margin: 0; font-size: 17px; font-weight: 700; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .card p {
    margin: 10px 0 0; color: var(--secondary); font-size: 15px; line-height: 1.45; white-space: pre-line;
    display: -webkit-box; -webkit-line-clamp: 6; -webkit-box-orient: vertical; overflow: hidden;
  }
  footer { display: flex; align-items: center; justify-content: space-between; margin-top: 12px; color: var(--secondary); font-size: 13px; }
  .empty { color: var(--secondary); padding: 16px 4px; }
  .fab {
    position: fixed; right: 32px; bottom: 32px; width: 58px; height: 58px; border-radius: 20px;
    background: var(--accent); color: var(--on-accent); display: grid; place-items: center;
    box-shadow: 0 6px 20px #0005; transition: transform .12s ease;
  }
  .fab:hover { transform: scale(1.05); }
  .fab:active { transform: scale(0.94); }
</style>
