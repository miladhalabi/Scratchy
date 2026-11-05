<script lang="ts">
  import Icon from "./Icon.svelte";
  import { imageUrl } from "./attach";

  /**
   * A note's images, each a rounded block in the note's flow above the text, kept in proportion inside a modest
   * box (renderImages in NoteEditor.kt): 280×220, or 200×140 in the card. A click opens it; × takes it out of the note.
   */
  interface Props { images: string[]; compact?: boolean; onopen: (name: string) => void; onremove: (name: string) => void }
  let { images, compact = false, onopen, onremove }: Props = $props();
</script>

{#if images.length}
  <div class="images" class:compact>
    {#each images as name (name)}
      <div class="cell">
        {#await imageUrl(name)}
          <span class="wait"></span>
        {:then src}
          {#if src}
            <button class="open" onclick={() => onopen(name)} aria-label="Open image"><img {src} alt="In note" draggable="false" /></button>
          {:else}
            <span class="wait" title="Not here yet"></span>
          {/if}
        {/await}
        <button class="remove" onclick={() => onremove(name)} title="Remove image" aria-label="Remove image"><Icon name="close" size={14} /></button>
      </div>
    {/each}
  </div>
{/if}

<style>
  .images { flex: none; display: flex; flex-direction: column; align-items: flex-start; padding: 0 0 0 4px; }
  .cell { position: relative; margin: 8px 0 6px; }
  .open { display: block; padding: 0; border: 0; background: transparent; cursor: zoom-in; border-radius: 14px; overflow: hidden; }
  img { display: block; max-width: min(280px, 100%); max-height: 220px; border-radius: 14px; background: var(--hairline); }
  .compact img { max-width: min(200px, 100%); max-height: 140px; }
  .wait { display: block; width: 110px; height: 110px; border-radius: 14px; background: var(--hairline); }
  .remove {
    position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; padding: 0; border: 0; border-radius: 12px;
    display: grid; place-items: center; background: #0009; color: #fff; cursor: pointer; transition: transform 150ms;
  }
  .remove:active { transform: scale(0.85); }
</style>
