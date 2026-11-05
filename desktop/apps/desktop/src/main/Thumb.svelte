<script lang="ts">
  import { imageUrl } from "../lib/attach";

  /** A note's first image, read from the attachments folder (images arrive with sync, so it may not be there yet). */
  let { name }: { name: string } = $props();
</script>

{#await imageUrl(name) then src}
  {#if src}<img {src} alt="" draggable="false" />{/if}
{/await}

<style>
  img { display: block; width: 100%; height: 100%; object-fit: cover; }
</style>
