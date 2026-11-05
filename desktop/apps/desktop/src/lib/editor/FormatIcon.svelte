<script lang="ts" module>
  export type Kind = "bold" | "italic" | "strike" | "code" | "heading" | "bullet" | "number" | "task" | "quote" | "link" | "outdent" | "indent" | "table" | "ai";
</script>

<script lang="ts">
  /** One tile icon of the format panel, drawn like FormatIcon.kt: same strokes and sizes, in a 52×40 box. */
  let { kind, label = "", active = false, tint = "" }: { kind: Kind; label?: string; active?: boolean; tint?: string } = $props();
  const rows = [-6, 0, 6];
</script>

<svg viewBox="-26 -20 52 40" width="52" height="40" aria-hidden="true" class:active>
  {#if active}<rect x="-23" y="-15" width="46" height="30" rx="10" class="bg" />{/if}
  <g class="fg" style:color={tint && !active ? tint : null}>
    {#if kind === "bold"}<text y="6.3" font-size="18" font-weight="700">B</text>
    {:else if kind === "italic"}<text y="6.3" font-size="18" font-style="italic">I</text>
    {:else if kind === "strike"}<text y="6.3" font-size="18">S</text><line x1="-6.5" y1="0" x2="6.5" y2="0" stroke-width="1.5" />
    {:else if kind === "code"}<text y="4.6" font-size="13" font-weight="700" class="mono">&lt;/&gt;</text>
    {:else if kind === "heading"}<text y="6" font-size="17" font-weight="700">{label || "H"}</text>
    {:else if kind === "bullet"}
      {#each rows as y}<circle cx="-8" cy={y} r="1.8" class="dot" /><line x1="-3" y1={y} x2="9" y2={y} />{/each}
    {:else if kind === "number"}
      {#each rows as y, i}<text x="-8" y={y + 2.6} font-size="7.5" font-weight="700">{i + 1}</text><line x1="-3" y1={y} x2="9" y2={y} />{/each}
    {:else if kind === "task"}
      <rect x="-8" y="-8" width="16" height="16" rx="3.5" /><path d="M-3.5 0.3 L-0.8 3.2 L4 -3" />
    {:else if kind === "quote"}
      <line x1="-8" y1="-7" x2="-8" y2="7" /><line x1="-3" y1="-4" x2="9" y2="-4" /><line x1="-3" y1="2" x2="7" y2="2" />
    {:else if kind === "link"}
      <g transform="rotate(-45)"><rect x="-10" y="-4" width="12" height="8" rx="4" /><rect x="-2" y="-4" width="12" height="8" rx="4" /></g>
    {:else if kind === "ai"}
      <path class="solid" d="M0 -9 Q0 0 9 0 Q0 0 0 9 Q0 0 -9 0 Q0 0 0 -9 Z" />
      <path class="solid" d="M7.5 -11.1 Q7.5 -7.5 11.1 -7.5 Q7.5 -7.5 7.5 -3.9 Q7.5 -7.5 3.9 -7.5 Q7.5 -7.5 7.5 -11.1 Z" />
    {:else if kind === "table"}
      <rect x="-9" y="-8" width="18" height="16" rx="3" />
      <line x1="-9" y1="-2.5" x2="9" y2="-2.5" /><line x1="-9" y1="3" x2="9" y2="3" /><line x1="-3" y1="-8" x2="-3" y2="8" /><line x1="3" y1="-8" x2="3" y2="8" />
    {:else}
      {@const dir = kind === "indent" ? 1 : -1}
      <line x1="-9" y1="-7" x2="9" y2="-7" /><line x1="-9" y1="7" x2="9" y2="7" /><line x1="1" y1="0" x2="9" y2="0" />
      <path d="M{-3 - dir * 3} -4 L{-3 + dir * 2} 0 L{-3 - dir * 3} 4" />
    {/if}
  </g>
</svg>

<style>
  svg { display: block; overflow: visible; }
  .bg { fill: var(--accent); }
  .fg { color: var(--text); fill: none; stroke: currentColor; stroke-width: 1.9; stroke-linecap: round; stroke-linejoin: round; }
  .active .fg { color: var(--on-accent); }
  text { fill: currentColor; stroke: none; text-anchor: middle; font-family: inherit; }
  text.mono { font-family: ui-monospace, "JetBrains Mono", monospace; }
  .dot, .solid { fill: currentColor; stroke: none; }
</style>
