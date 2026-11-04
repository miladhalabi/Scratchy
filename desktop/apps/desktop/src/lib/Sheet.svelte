<script lang="ts" module>
  export interface SheetItem { label: string; checked?: boolean; accent?: boolean; danger?: boolean; run: () => void }
</script>

<script lang="ts">
  import { onMount } from "svelte";

  /** The app's own dialog card (Dialogs.kt on the phone): a title, a message, a list of choices or a text field. */
  interface Props {
    title: string;
    message?: string;
    items?: SheetItem[];
    /** A text field; `confirm` gets its value. */
    field?: { label: string; value: string; max?: number };
    confirm?: { label: string; danger?: boolean; run: (value: string) => void };
    cancel?: string;
    onclose: () => void;
  }
  let { title, message, items = [], field, confirm, cancel = "Cancel", onclose }: Props = $props();
  // svelte-ignore state_referenced_locally
  let value = $state(field?.value ?? "");
  let input: HTMLInputElement | undefined = $state();
  let box: HTMLDivElement;

  const ok = () => { confirm?.run(value); onclose(); };
  function keys(e: KeyboardEvent) {
    if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); onclose(); }
    else if (e.key === "Enter" && confirm && (field || !items.length)) { e.preventDefault(); ok(); }
  }
  onMount(() => { if (input) { input.focus(); input.select(); } else box.querySelector<HTMLElement>("button")?.focus(); });
</script>

<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
<div class="scrim" onclick={(e) => { if (e.target === e.currentTarget) onclose(); }} onkeydown={keys}>
  <div class="sheet" role="dialog" aria-label={title} bind:this={box}>
    <h2>{title}</h2>
    {#if message}<p>{message}</p>{/if}
    {#if field}
      <input bind:this={input} bind:value placeholder={field.label} maxlength={field.max} dir="auto" />
    {/if}
    {#if items.length}
      <div class="items">
        {#each items as it}
          <button class="item" class:accent={it.accent} class:danger={it.danger} onclick={() => { it.run(); onclose(); }}>
            <span dir="auto">{it.label}</span>
            {#if it.checked}<span class="tick" aria-label="selected">✓</span>{/if}
          </button>
        {/each}
      </div>
    {/if}
    <div class="row">
      <button class="text" onclick={onclose}>{cancel}</button>
      {#if confirm}<button class="filled" class:danger={confirm.danger} onclick={ok}>{confirm.label}</button>{/if}
    </div>
  </div>
</div>

<style>
  .scrim { position: fixed; inset: 0; z-index: 60; display: grid; place-items: center; background: rgb(0 0 0 / 0.3); animation: fade 120ms ease-out; }
  .sheet {
    width: min(400px, calc(100vw - 32px)); max-height: calc(100vh - 48px); overflow: auto; box-sizing: border-box;
    background: var(--floating); color: var(--text); border-radius: 24px; padding: 22px 18px 14px;
    box-shadow: 0 24px 60px rgb(0 0 0 / 0.3); animation: pop 160ms cubic-bezier(0.2, 0.9, 0.3, 1);
  }
  h2 { margin: 0 6px 6px; font-size: 18px; font-weight: 650; letter-spacing: -0.01em; }
  p { margin: 0 6px 12px; color: var(--secondary); line-height: 1.5; }
  input {
    width: 100%; box-sizing: border-box; margin: 6px 0 8px; padding: 11px 14px; border-radius: 14px; font: inherit;
    border: 1.5px solid var(--hairline); background: transparent; color: var(--text); outline: none;
  }
  input:focus { border-color: var(--accent); }
  .items { display: flex; flex-direction: column; margin: 4px 0 6px; }
  .item {
    display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 11px 10px; border: 0; border-radius: 12px;
    background: transparent; color: var(--text); font: inherit; text-align: start; cursor: pointer;
  }
  .item:hover, .item:focus-visible { background: var(--wash); outline: none; }
  .item.accent { color: var(--accent); font-weight: 600; }
  .item.danger { color: var(--danger); }
  .tick { color: var(--accent); font-weight: 700; }
  .row { display: flex; justify-content: flex-end; gap: 6px; margin-top: 6px; }
  button.text, button.filled { border: 0; border-radius: 12px; padding: 9px 16px; font: inherit; font-weight: 600; cursor: pointer; }
  button.text { background: transparent; color: var(--accent); }
  button.text:hover { background: var(--wash); }
  button.filled { background: var(--accent); color: var(--on-accent); }
  button.filled.danger { background: var(--danger); color: #fff; }
  button:active { transform: scale(0.97); }
  @keyframes fade { from { opacity: 0; } }
  @keyframes pop { from { opacity: 0; transform: scale(0.96); } }
  @media (prefers-reduced-motion: reduce) { .scrim, .sheet { animation: none; } }
</style>
