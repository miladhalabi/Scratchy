<script lang="ts">
  import { files } from "../lib/files";

  /** A note's first image, read from the attachments folder (images arrive with sync, so it may not be there yet). */
  let { name }: { name: string } = $props();
  let src = $state("");

  $effect(() => {
    let url = "";
    let gone = false;
    files.readAttachment(name).then((b64) => {
      if (gone || !b64) return;
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      url = URL.createObjectURL(new Blob([bytes]));
      src = url;
    }).catch(() => {});
    return () => { gone = true; if (url) URL.revokeObjectURL(url); };
  });
</script>

{#if src}<img {src} alt="" draggable="false" />{/if}

<style>
  img { display: block; width: 100%; height: 100%; object-fit: cover; }
</style>
