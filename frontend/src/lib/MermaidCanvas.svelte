<script lang="ts">
  import { loadMermaid } from "./mermaid-loader";

  interface Props {
    source: string;
    scale?: number;
    compact?: boolean;
  }

  let { source, scale = 1, compact = false }: Props = $props();
  let svg = $state("");
  let error = $state("");
  let generation = 0;

  $effect(() => {
    const activeGeneration = ++generation;
    const currentSource = source;
    svg = "";
    error = "";
    void loadMermaid()
      .then((mermaid) => mermaid.render(`codeagent-mermaid-${activeGeneration}-${Date.now()}`, currentSource))
      .then((result) => {
        if (activeGeneration === generation) svg = result.svg;
      })
      .catch((cause: unknown) => {
        if (activeGeneration === generation) error = cause instanceof Error ? cause.message : String(cause);
      });
  });
</script>

<div class="mermaid-render" class:compact style={`--diagram-scale:${scale}`}>
  {#if error}
    <div class="mermaid-error"><strong>Diagram could not be rendered</strong><span>{error}</span></div>
  {:else if svg}
    <div class="mermaid-svg">{@html svg}</div>
  {:else}
    <div class="mermaid-loading">Rendering diagram...</div>
  {/if}
</div>

<style>
  .mermaid-render { width: 100%; height: 100%; min-height: 220px; overflow: auto; display: grid; place-items: center; color: #d8dadd; background: #191b1e; }
  .mermaid-render.compact { min-height: 150px; max-height: 240px; border: 1px solid #34373d; border-radius: 4px; }
  .mermaid-svg { padding: 20px; transform: scale(var(--diagram-scale)); transform-origin: center; transition: transform .12s ease; }
  .mermaid-svg :global(svg) { display: block; max-width: none; height: auto; }
  .mermaid-loading { color: #777c84; font-size: 10px; }
  .mermaid-error { max-width: 520px; padding: 18px; display: flex; flex-direction: column; gap: 7px; text-align: center; }
  .mermaid-error strong { color: #e38a90; font-size: 11px; }
  .mermaid-error span { color: #9b8083; font: 9px/1.45 "JetBrains Mono", monospace; overflow-wrap: anywhere; }
</style>
