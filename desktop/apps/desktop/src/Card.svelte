<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
</script>

<svelte:window onkeydown={(e) => e.key === "Escape" && invoke("hide_card")} />

<div class="card">
  <header>
    <span class="grip"></span>
  </header>
  <textarea placeholder="Card (bridge to notes coming next)"></textarea>
  <button class="close" onclick={() => invoke("hide_card")}>Close</button>
</div>

<style>
  .card { height: 100vh; display: flex; flex-direction: column; background: var(--floating); border-radius: 24px; border: 1px solid var(--hairline); overflow: hidden; }
  header { height: 38px; display: grid; place-items: center; }
  .grip { width: 38px; height: 4px; border-radius: 2px; background: var(--hairline); }
  textarea { flex: 1; resize: none; border: 0; outline: 0; background: none; padding: 8px 18px; font-size: 16px; }
  .close { height: 44px; color: var(--secondary); border-top: 1px solid var(--hairline); }
</style>
