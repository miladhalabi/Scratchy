<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
</script>

<!-- Invisible, full screen, below the card: a click anywhere outside the card closes it. -->
<div class="catch" role="presentation" onpointerdown={() => invoke("hide_card")}></div>

<style>
  .catch { width: 100vw; height: 100vh; background: transparent; }
</style>
