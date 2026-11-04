<script lang="ts">
  import { onMount } from "svelte";
  import { tableRows } from "@scratchy/core";

  interface Props {
    rows: string[][];
    /** New rows; null deletes the table; undefined leaves it as it was. */
    onclose: (rows: string[][] | null | undefined) => void;
  }
  let { rows, onclose }: Props = $props();

  // svelte-ignore state_referenced_locally
  let grid = $state(tableRows(rows).map((r) => [...r]));
  let first: HTMLInputElement | undefined = $state();
  const cols = () => grid[0]?.length ?? 1;

  const addRow = () => { grid.push(Array(cols()).fill("")); };
  const addCol = () => { grid.forEach((r, i) => r.push(i === 0 ? `Column ${r.length + 1}` : "")); };
  const dropRow = (i: number) => { if (grid.length > 1) grid.splice(i, 1); };
  const dropCol = (c: number) => { if (cols() > 1) grid.forEach((r) => r.splice(c, 1)); };

  function keys(e: KeyboardEvent) {
    if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); onclose(grid); }
  }
  onMount(() => first?.focus());
</script>

<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
<div class="scrim" onclick={(e) => { if (e.target === e.currentTarget) onclose(grid); }} onkeydown={keys}>
  <div class="sheet" role="dialog" aria-label="Edit table">
    <div class="top">
      <strong>Table</strong>
      <span class="grow"></span>
      <button class="text" onclick={addRow}>+ Row</button>
      <button class="text" onclick={addCol}>+ Column</button>
    </div>
    <div class="wrap">
      <table>
        <tbody>
          {#each grid as row, ri}
            <tr>
              {#each row as _, ci}
                <td class:head={ri === 0}>
                  {#if ri === 0 && ci === 0}
                    <input bind:this={first} bind:value={grid[ri][ci]} dir="auto" />
                  {:else}
                    <input bind:value={grid[ri][ci]} dir="auto" placeholder={ri === 0 ? "Header" : ""} />
                  {/if}
                </td>
              {/each}
              <td class="x"><button title="Delete row" aria-label="Delete row" disabled={grid.length < 2} onclick={() => dropRow(ri)}>×</button></td>
            </tr>
          {/each}
          <tr>
            {#each grid[0] as _, ci}
              <td class="x"><button title="Delete column" aria-label="Delete column" disabled={cols() < 2} onclick={() => dropCol(ci)}>×</button></td>
            {/each}
          </tr>
        </tbody>
      </table>
    </div>
    <div class="bottom">
      <button class="text danger" onclick={() => onclose(null)}>Delete table</button>
      <span class="grow"></span>
      <button class="text" onclick={() => onclose(undefined)}>Cancel</button>
      <button class="filled" onclick={() => onclose(grid)}>Done</button>
    </div>
  </div>
</div>

<style>
  .scrim { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; background: rgb(0 0 0 / 0.28); }
  .sheet {
    max-width: min(720px, calc(100vw - 32px)); max-height: calc(100vh - 48px); display: flex; flex-direction: column;
    background: var(--floating, #fff); color: var(--text, #111); border-radius: 22px; padding: 16px;
    box-shadow: 0 24px 60px rgb(0 0 0 / 0.3); box-sizing: border-box; gap: 12px;
  }
  .top, .bottom { display: flex; align-items: center; gap: 6px; }
  .grow { flex: 1; }
  .wrap { overflow: auto; min-height: 0; }
  table { border-collapse: separate; border-spacing: 4px; }
  td { padding: 0; }
  input {
    width: 140px; box-sizing: border-box; padding: 8px 10px; border-radius: 10px; font: inherit;
    border: 1px solid var(--hairline, #0002); background: transparent; color: inherit; outline: none;
  }
  input:focus { border-color: var(--accent, #00796b); }
  td.head input { font-weight: 650; background: var(--wash, #00796b22); }
  .x button { width: 28px; height: 28px; border: 0; border-radius: 8px; background: transparent; color: var(--secondary, #666); cursor: pointer; font-size: 16px; }
  .x button:hover:not(:disabled) { background: var(--wash, #0001); color: var(--text); }
  .x button:disabled { opacity: 0.3; cursor: default; }
  button.text, button.filled { border: 0; border-radius: 12px; padding: 8px 14px; font: inherit; font-weight: 600; cursor: pointer; }
  button.text { background: transparent; color: var(--accent, #00796b); }
  button.text:hover { background: var(--wash, #0001); }
  button.text.danger { color: var(--danger, #d93025); }
  button.filled { background: var(--accent, #00796b); color: var(--on-accent, #fff); }
  button:active:not(:disabled) { transform: scale(0.97); }
</style>
