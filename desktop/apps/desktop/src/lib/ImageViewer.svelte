<script lang="ts">
  import { imageUrl } from "./attach";

  /** One image, as big as it fits, over the page it was opened from. A click closes it (and Esc, which the page that opened it handles). */
  let { name, onclose }: { name: string; onclose: () => void } = $props();
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<div class="viewer" role="dialog" aria-label="Image" tabindex="-1" onclick={onclose}>
  {#await imageUrl(name) then src}
    {#if src}<img {src} alt="In note" draggable="false" />{/if}
  {/await}
</div>

<style>
  .viewer {
    position: absolute; inset: 0; z-index: 50; display: grid; place-items: center; padding: 24px; box-sizing: border-box;
    background: rgba(0, 0, 0, 0.88); cursor: zoom-out; animation: in 160ms ease-out; border-radius: inherit;
  }
  img { max-width: 100%; max-height: 100%; object-fit: contain; border-radius: 6px; }
  @keyframes in { from { opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .viewer { animation: none; } }
</style>
