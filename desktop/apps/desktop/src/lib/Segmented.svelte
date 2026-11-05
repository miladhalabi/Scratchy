<script lang="ts">
  /** A row of choices with an accent highlight that slides to the one you pick (SegmentedControl in SettingsUi.kt). */
  interface Props { options: string[]; selected: number; onselect: (i: number) => void; disabled?: boolean }
  let { options, selected, onselect, disabled = false }: Props = $props();
</script>

<div class="seg" class:disabled role="radiogroup" style:--n={options.length} style:--i={selected}>
  <span class="thumb" aria-hidden="true"></span>
  {#each options as o, i}
    <button role="radio" aria-checked={i === selected} class:on={i === selected} {disabled} onclick={() => { if (i !== selected) onselect(i); }}>{o}</button>
  {/each}
</div>

<style>
  .seg { position: relative; display: flex; padding: 3px; border-radius: 12px; background: var(--track); }
  .seg.disabled { opacity: 0.4; }
  .thumb {
    position: absolute; top: 3px; left: 3px; height: 34px; width: calc((100% - 6px) / var(--n)); border-radius: 10px;
    background: var(--accent); transform: translateX(calc(100% * var(--i)));
    transition: transform 300ms cubic-bezier(0.3, 1.25, 0.4, 1);
  }
  button {
    position: relative; flex: 1 1 0; min-width: 0; height: 34px; border: 0; background: transparent; color: var(--text);
    font: inherit; font-size: 14px; font-weight: 500; white-space: nowrap; cursor: pointer;
  }
  button.on { color: var(--on-accent); }
  button:disabled { cursor: default; }
  @media (prefers-reduced-motion: reduce) { .thumb { transition: none; } }
</style>
