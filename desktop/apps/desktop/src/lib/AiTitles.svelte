<script lang="ts">
  import { aiConfigured, suggestTitles } from "./ai";
  import { files } from "./files";

  /**
   * The row of title chips under the title (suggestTitle in NoteEditor.kt): "Thinking…", then three titles to
   * click. The ✨ button that starts it sits next to the title, in the screen that shows this.
   */
  interface Props { onpick: (title: string) => void; onmessage: (text: string) => void }
  let { onpick, onmessage }: Props = $props();
  let chips: string[] = $state([]);
  let tappable = $state(false);
  let busy = false;

  async function imageUrl(name: string): Promise<string | null> {
    const b64 = await files.readAttachment(name).catch(() => null);
    return b64 ? `data:image/${name.endsWith(".png") ? "png" : "jpeg"};base64,${b64}` : null;
  }

  export async function suggest(body: string, image: string | undefined, secret: boolean) {
    if (busy) return;
    if (!(await aiConfigured())) { onmessage("Add your OpenRouter key in Settings first"); return; }
    if (secret) { onmessage("Titles by AI are off for secret notes"); return; }
    if (!body.trim() && !image) { onmessage("Write something or add an image first"); return; }
    busy = true;
    chips = ["Thinking…"]; tappable = false;
    try { chips = await suggestTitles(body, image ? await imageUrl(image) : null); tappable = true; }
    catch (e) { chips = [(e as Error).message || "Couldn't get suggestions"]; tappable = false; }
    finally { busy = false; }
  }
  export function hide() { chips = []; }
</script>

{#if chips.length}
  <div class="row">
    {#each chips as t}
      {#if tappable}
        <button class="chip on" dir="auto" onclick={() => { onpick(t); chips = []; }}>{t}</button>
      {:else}
        <span class="chip" dir="auto">{t}</span>
      {/if}
    {/each}
  </div>
{/if}

<style>
  .row { flex: none; display: flex; gap: 8px; padding: 8px 2px 2px; overflow-x: auto; scrollbar-width: none; }
  .chip {
    flex: none; max-width: 300px; padding: 8px 14px; border: 0; border-radius: 18px; background: var(--hairline); color: var(--secondary);
    font: inherit; font-size: 14px; line-height: 1.3; text-align: start; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical;
  }
  .chip.on { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); cursor: pointer; transition: transform 150ms; }
  .chip.on:active { transform: scale(0.96); }
</style>
