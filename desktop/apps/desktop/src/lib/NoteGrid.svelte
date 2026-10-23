<script lang="ts">
  import { notes } from "./state";
  import { formatWhen, heading, preview } from "./format";
  import type { Note } from "@scratchy/core";

  let { onOpen, onNew }: { onOpen: (n: Note) => void; onNew: () => void } = $props();
  let query = $state("");
  let search: HTMLInputElement;

  const shown = $derived(
    $notes
      .filter((n) => !n.secret)
      .filter((n) => !query.trim() || (n.title + "\n" + n.body).toLowerCase().includes(query.trim().toLowerCase()))
      .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt),
  );

  function keys(e: KeyboardEvent) {
    if (e.ctrlKey && e.key.toLowerCase() === "f") { e.preventDefault(); search.focus(); }
  }
</script>

<svelte:window onkeydown={keys} />

<main>
  <header>
    <h1>Notes</h1>
  </header>
  <label class="search">
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14"/></svg>
    <input bind:this={search} bind:value={query} placeholder="Search" />
  </label>

  <section class="scroll">
    {#if shown.length === 0}
      <p class="empty">{query ? "No matches." : "No notes yet. Press Ctrl+N or the + button."}</p>
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
  h1 { margin: 0 0 18px 4px; font-size: 40px; letter-spacing: -0.02em; }
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
