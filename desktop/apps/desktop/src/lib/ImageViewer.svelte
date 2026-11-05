<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import Icon from "./Icon.svelte";
  import { imageUrl } from "./attach";

  /**
   * One image, as big as it fits, over the page it was opened from, with Save to Downloads and Copy.
   * A click beside the image closes it (and Esc, which the page that opened it handles).
   */
  let { name, title = "", onclose }: { name: string; title?: string; onclose: () => void } = $props();
  let status = $state("");
  let timer: ReturnType<typeof setTimeout> | undefined;
  const tell = (t: string) => { clearTimeout(timer); status = t; timer = setTimeout(() => (status = ""), 3000); };

  async function save() {
    try {
      const path = await invoke<string>("save_image", { name, title });
      tell(`Saved to Downloads as ${path.split("/").pop()}`);
    } catch (e) { tell(`Couldn't save it: ${e}`); }
  }
  async function copy() {
    try { await invoke("copy_image", { name }); tell("Copied"); }
    catch { tell("Couldn't copy it"); }
  }
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<div class="viewer" role="dialog" aria-label="Image" tabindex="-1" onclick={onclose}>
  {#await imageUrl(name) then src}
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    {#if src}<img {src} alt="In note" draggable="false" onclick={(e) => e.stopPropagation()} />{/if}
  {/await}
  <div class="bar" role="toolbar" tabindex="-1" onclick={(e) => e.stopPropagation()}>
    <button onclick={save} title="Save to Downloads"><Icon name="download" size={20} /><span>Save</span></button>
    <button onclick={copy} title="Copy image"><Icon name="copy" size={18} /><span>Copy</span></button>
    <button class="round" onclick={onclose} title="Close (Esc)" aria-label="Close"><Icon name="close" size={20} /></button>
  </div>
  {#if status}<div class="status" role="status">{status}</div>{/if}
</div>

<style>
  .viewer {
    position: absolute; inset: 0; z-index: 50; background: rgba(0, 0, 0, 0.88); cursor: zoom-out;
    animation: in 160ms ease-out; border-radius: inherit; overflow: hidden;
  }
  /* Fitted inside the space under the bar, whole, never cropped. */
  img {
    position: absolute; left: 24px; right: 24px; top: 64px; bottom: 24px; width: calc(100% - 48px); height: calc(100% - 88px);
    object-fit: contain; cursor: default;
  }
  .bar { position: absolute; top: 12px; right: 12px; display: flex; gap: 8px; cursor: default; }
  .bar button {
    height: 40px; padding: 0 14px; border: 0; border-radius: 20px; display: flex; align-items: center; gap: 6px;
    background: rgb(255 255 255 / 0.14); color: #fff; font: inherit; font-size: 14px; font-weight: 500; cursor: pointer;
    transition: transform 150ms, background 150ms;
  }
  .bar button:hover { background: rgb(255 255 255 / 0.22); }
  .bar button:active { transform: scale(0.94); }
  .bar .round { width: 40px; padding: 0; justify-content: center; }
  .status {
    position: absolute; left: 50%; bottom: 24px; transform: translateX(-50%); max-width: calc(100% - 48px);
    padding: 8px 16px; border-radius: 18px; background: rgb(255 255 255 / 0.92); color: #111; font-size: 13px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; pointer-events: none;
  }
  @keyframes in { from { opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .viewer { animation: none; } }
</style>
