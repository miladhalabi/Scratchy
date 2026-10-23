<script lang="ts">
  import { onMount } from "svelte";
  import { invoke } from "@tauri-apps/api/core";

  let right = $state(false);
  let gap = $state(4);
  let down = $state(false);
  let hover = $state(false);

  onMount(async () => {
    const p = await invoke<{ right: boolean; gap: number }>("overlay_prefs");
    right = p.right; gap = p.gap;
  });

  function press() { down = true; void invoke("toggle_card"); }
</script>

<!-- The whole strip is the touch target; the visible pill is only a few pixels wide, like the phone's bar. -->
<div class="strip" class:right role="button" tabindex="-1" aria-label="Open Scratchy"
  onpointerdown={press} onpointerup={() => (down = false)} onpointerleave={() => { down = false; hover = false; }}
  onpointerenter={() => (hover = true)}>
  <div class="pill" class:hover class:down style:margin-left={right ? "0" : `${gap}px`} style:margin-right={right ? `${gap}px` : "0"}></div>
</div>

<style>
  .strip { width: 100vw; height: 100vh; display: flex; align-items: center; justify-content: flex-start; cursor: pointer; }
  .strip.right { justify-content: flex-end; }
  .pill {
    width: 6px; height: 84px; border-radius: 3px; background: var(--accent);
    transition: width .16s cubic-bezier(.3,1.4,.5,1), height .16s ease, opacity .16s;
    opacity: .92;
  }
  .pill.hover { width: 9px; height: 92px; opacity: 1; }
  .pill.down { width: 11px; height: 78px; }
</style>
