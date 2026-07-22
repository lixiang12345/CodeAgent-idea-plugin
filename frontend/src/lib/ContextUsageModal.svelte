<script lang="ts">
  import { onMount, tick } from "svelte";
  import Icon from "./Icon.svelte";
  import type { AgentRunTelemetry } from "./protocol";

  type CategoryKey = "input" | "tools" | "assistant";
  type ContextCategory = {
    key: CategoryKey;
    label: string;
    tokens: number;
    color: string;
  };

  interface Props {
    telemetry: AgentRunTelemetry;
    onclose: () => void;
  }

  let { telemetry, onclose }: Props = $props();
  let dialogElement: HTMLDivElement | undefined = $state();
  let closeButton: HTMLButtonElement | undefined = $state();
  let expandedCategories = $state<Set<CategoryKey>>(new Set());
  let sectionRefs = $state<Partial<Record<CategoryKey, HTMLElement>>>({});

  const inputTokens = $derived(Math.max(0, telemetry.estimatedInputTokens));
  const toolDefinitionTokens = $derived(Math.max(0, telemetry.toolDefinitionTokens));
  const assistantResponseTokens = $derived(Math.max(0, telemetry.assistantResponseTokens));
  const usedTokens = $derived(inputTokens + toolDefinitionTokens + assistantResponseTokens);
  const maxContextTokens = $derived(Math.max(0, telemetry.contextWindowTokens));
  const usagePercentage = $derived(maxContextTokens > 0
    ? Math.min(100, Math.max(0, Math.round((usedTokens / maxContextTokens) * 100)))
    : 0);
  const hasUsage = $derived(maxContextTokens > 0 && usedTokens > 0);
  const categories = $derived.by<ContextCategory[]>(() => {
    const values: ContextCategory[] = [
      { key: "input", label: "Input / History Estimate", tokens: inputTokens, color: "#3574f0" },
      { key: "tools", label: "Tool Definitions", tokens: toolDefinitionTokens, color: "#9b73d1" },
      { key: "assistant", label: "Assistant Response", tokens: assistantResponseTokens, color: "#db5c5c" },
    ];
    return values.filter((category) => category.tokens > 0).sort((left, right) => right.tokens - left.tokens);
  });
  const usageEmoji = $derived(usagePercentage <= 40 ? "😊" : usagePercentage <= 60 ? "😰" : "😢");
  const compactionTokens = $derived(Math.max(0, telemetry.targetInputTokens + telemetry.toolDefinitionTokens));

  onMount(() => {
    void tick().then(() => closeButton?.focus());
  });

  $effect.pre(() => {
    const nextHasUsage = hasUsage;
    const nextCategoryKeys = new Set(categories.map((category) => category.key));
    const activeElement = document.activeElement;
    if (!(activeElement instanceof HTMLElement) || !dialogElement?.contains(activeElement) || activeElement === closeButton) return;
    const focusedCategory = activeElement.dataset.contextCategory as CategoryKey | undefined;
    if (!nextHasUsage || (focusedCategory !== undefined && !nextCategoryKeys.has(focusedCategory))) {
      (closeButton ?? dialogElement).focus();
    }
  });

  function formatTokenCount(count: number): string {
    return count >= 1000 ? `${(count / 1000).toFixed(1)}k` : count.toLocaleString();
  }

  function segmentWidth(tokens: number): number {
    if (maxContextTokens <= 0) return 0;
    return Math.min(100, Math.max(0, (tokens / maxContextTokens) * 100));
  }

  function toggleCategory(key: CategoryKey) {
    const next = new Set(expandedCategories);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    expandedCategories = next;
  }

  function openCategory(key: CategoryKey) {
    expandedCategories = new Set(expandedCategories).add(key);
    void tick().then(() => sectionRefs[key]?.scrollIntoView({ behavior: "smooth", block: "nearest" }));
  }

  function trapDialogFocus(event: KeyboardEvent) {
    if (event.key !== "Tab" || !dialogElement) return;
    const focusable = Array.from(dialogElement.querySelectorAll<HTMLElement>("button:not(:disabled)"));
    const first = focusable[0];
    const last = focusable.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && (document.activeElement === first || !dialogElement.contains(document.activeElement))) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function keepFocusInDialog(event: FocusEvent) {
    if (!dialogElement || dialogElement.contains(event.target as Node)) return;
    event.stopPropagation();
    (closeButton ?? dialogElement).focus();
  }
</script>

<svelte:window onfocusin={keepFocusInDialog} />

<div class="context-usage-overlay">
  <button type="button" class="context-usage-backdrop" aria-label="Close context usage" onclick={onclose}></button>
  <div bind:this={dialogElement} class="context-usage-dialog" role="dialog" aria-modal="true" aria-labelledby="context-usage-title" tabindex="-1" onkeydown={trapDialogFocus}>
    <header>
      <h2 id="context-usage-title">Context Window Usage</h2>
      <button bind:this={closeButton} type="button" class="context-usage-close" aria-label="Close" onclick={onclose}>
        <Icon name="x" size={15} />
      </button>
    </header>

    <div class="context-usage-body">
      {#if hasUsage}
        <div class="context-usage-summary">
          <span class="context-usage-emoji" aria-hidden="true">{usageEmoji}</span>
          <strong>Context Window</strong>
          <span>{formatTokenCount(usedTokens)} / {formatTokenCount(maxContextTokens)} tokens</span>
        </div>

        <div class="context-usage-bar" role="group" aria-label={`${usagePercentage}% of the context window is used`}>
          {#each categories as category (category.key)}
            <button
              type="button"
              class="context-usage-segment"
              style={`--segment-width:${segmentWidth(category.tokens)}%;--segment-color:${category.color}`}
              title={`${category.label}: ${formatTokenCount(category.tokens)} tokens`}
              aria-label={`Show ${category.label} details`}
              data-context-category={category.key}
              onclick={() => openCategory(category.key)}
            ></button>
          {/each}
        </div>

        <div class="context-usage-legend" aria-label="Context usage categories">
          {#each categories as category (category.key)}
            <span><i style={`--segment-color:${category.color}`}></i>{category.label}</span>
          {/each}
        </div>

        <div class="context-usage-sections">
          {#each categories as category (category.key)}
            <section bind:this={sectionRefs[category.key]} class="context-usage-section">
              <button
                type="button"
                class="context-usage-section-header"
                aria-expanded={expandedCategories.has(category.key)}
                data-context-category={category.key}
                onclick={() => toggleCategory(category.key)}
              >
                <Icon name={expandedCategories.has(category.key) ? "chevron-down" : "chevron-right"} size={13} />
                <i style={`--segment-color:${category.color}`}></i>
                <strong>{category.label}</strong>
                <span>{formatTokenCount(category.tokens)} tokens</span>
              </button>
              {#if expandedCategories.has(category.key)}
                <div class="context-usage-details">
                  <span><small>Total tokens</small><b>{formatTokenCount(category.tokens)}</b></span>
                  {#if category.key === "tools"}
                    <span><small>Tools ready</small><b>{telemetry.activeToolCount.toLocaleString()}</b></span>
                    <span><small>Discoverable tools</small><b>{telemetry.discoverableToolCount.toLocaleString()}</b></span>
                    <span><small>Catalog tools</small><b>{telemetry.catalogToolCount.toLocaleString()}</b></span>
                  {/if}
                </div>
              {/if}
            </section>
          {/each}
        </div>

        <section class="context-runtime-budget" aria-labelledby="context-runtime-budget-title">
          <h3 id="context-runtime-budget-title">Runtime Budget</h3>
          <dl>
            <div><dt>Automatic compaction</dt><dd>{formatTokenCount(compactionTokens)} tokens</dd></div>
            <div><dt>Reserved output</dt><dd>{formatTokenCount(telemetry.reservedOutputTokens)} tokens</dd></div>
            <div><dt>Retrieval budget</dt><dd>{formatTokenCount(telemetry.retrievalBudgetTokens)} tokens</dd></div>
            <div><dt>Compacted tool results</dt><dd>{telemetry.compactedToolResults.toLocaleString()}</dd></div>
            <div><dt>Truncated messages</dt><dd>{telemetry.truncatedMessages.toLocaleString()}</dd></div>
          </dl>
        </section>
      {:else}
        <div class="context-usage-empty">
          <Icon name="gauge" size={20} />
          <p>No context usage data available. Send a message to see context usage.</p>
        </div>
      {/if}
    </div>
  </div>
</div>

<style>
  .context-usage-overlay {
    position: absolute;
    inset: 0;
    z-index: 70;
    display: grid;
    place-items: center;
    padding: 12px;
  }

  .context-usage-backdrop {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: rgba(0, 0, 0, .58);
    cursor: default;
  }

  .context-usage-dialog {
    position: relative;
    width: min(360px, 100%);
    max-height: calc(100% - 24px);
    display: flex;
    flex-direction: column;
    overflow: hidden;
    border: 1px solid #555b63;
    border-radius: 8px;
    color: #c7cbd1;
    background: #25272b;
    box-shadow: 0 14px 42px rgba(0, 0, 0, .62);
  }

  .context-usage-dialog > header {
    min-height: 42px;
    padding: 7px 8px 7px 12px;
    display: flex;
    align-items: center;
    gap: 8px;
    border-bottom: 1px solid #373a40;
  }

  .context-usage-dialog h2 {
    min-width: 0;
    flex: 1;
    margin: 0;
    color: #eceef1;
    font-size: 14px;
    font-weight: 600;
  }

  .context-usage-close {
    width: 26px;
    height: 26px;
    padding: 0;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 4px;
    color: #9299a2;
    background: transparent;
    cursor: pointer;
  }

  .context-usage-close:hover,
  .context-usage-close:focus-visible {
    color: #eef0f3;
    background: rgba(255, 255, 255, .07);
  }

  .context-usage-body {
    min-height: 0;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 11px;
    overflow-y: auto;
  }

  .context-usage-summary {
    min-height: 24px;
    display: flex;
    align-items: center;
    gap: 7px;
  }

  .context-usage-summary strong {
    color: #dde0e4;
    font-size: 12px;
    font-weight: 600;
  }

  .context-usage-summary > span:last-child {
    margin-left: auto;
    color: #969da7;
    font: 10px "JetBrains Mono", Menlo, Consolas, monospace;
    white-space: nowrap;
  }

  .context-usage-emoji {
    font-size: 16px;
    line-height: 1;
  }

  .context-usage-bar {
    width: 100%;
    height: 12px;
    display: flex;
    overflow: hidden;
    border-radius: 4px;
    background: #34373c;
  }

  .context-usage-segment {
    width: var(--segment-width);
    min-width: 2px;
    height: 100%;
    flex: 0 0 var(--segment-width);
    padding: 0;
    border: 0;
    border-radius: 0;
    background: var(--segment-color);
    cursor: pointer;
    transition: opacity .15s ease;
  }

  .context-usage-segment:hover,
  .context-usage-segment:focus-visible {
    opacity: .8;
  }

  .context-usage-legend {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 12px;
    color: #969da6;
    font-size: 10px;
  }

  .context-usage-legend span {
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }

  .context-usage-legend i,
  .context-usage-section-header > i {
    width: 8px;
    height: 8px;
    flex: 0 0 8px;
    border-radius: 2px;
    background: var(--segment-color);
  }

  .context-usage-sections {
    border-block: 1px solid #363940;
  }

  .context-usage-section + .context-usage-section {
    border-top: 1px solid #34373d;
  }

  .context-usage-section-header {
    width: 100%;
    min-height: 34px;
    padding: 5px 6px;
    display: flex;
    align-items: center;
    gap: 5px;
    border: 0;
    color: #aeb4bc;
    background: transparent;
    text-align: left;
    cursor: pointer;
  }

  .context-usage-section-header:hover {
    color: #e3e6e9;
    background: rgba(255, 255, 255, .035);
  }

  .context-usage-section-header strong {
    min-width: 0;
    flex: 1;
    overflow: hidden;
    color: inherit;
    font-size: 10.5px;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .context-usage-section-header span {
    color: #9299a3;
    font: 9.5px "JetBrains Mono", Menlo, Consolas, monospace;
    white-space: nowrap;
  }

  .context-usage-details {
    padding: 0 8px 7px 29px;
  }

  .context-usage-details > span {
    min-height: 25px;
    display: flex;
    align-items: center;
    gap: 12px;
    border-top: 1px solid #32353a;
  }

  .context-usage-details small {
    min-width: 0;
    flex: 1;
    color: #858c95;
    font-size: 9.5px;
  }

  .context-usage-details b {
    color: #b9bec5;
    font: 500 9.5px "JetBrains Mono", Menlo, Consolas, monospace;
  }

  .context-runtime-budget h3 {
    margin: 0 0 5px;
    color: #bfc4ca;
    font-size: 10px;
    font-weight: 600;
  }

  .context-runtime-budget dl {
    margin: 0;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    border-top: 1px solid #34373d;
  }

  .context-runtime-budget dl > div {
    display: contents;
  }

  .context-runtime-budget dt,
  .context-runtime-budget dd {
    min-height: 27px;
    margin: 0;
    padding: 6px 4px;
    border-bottom: 1px solid #34373d;
    font-size: 9.5px;
  }

  .context-runtime-budget dt {
    color: #8f969f;
  }

  .context-runtime-budget dd {
    color: #bbc0c7;
    font-family: "JetBrains Mono", Menlo, Consolas, monospace;
    text-align: right;
    white-space: nowrap;
  }

  .context-usage-empty {
    min-height: 180px;
    padding: 18px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 9px;
    color: #777f89;
    text-align: center;
  }

  .context-usage-empty p {
    max-width: 250px;
    margin: 0;
    font-size: 10.5px;
    line-height: 1.45;
  }

  @media (prefers-reduced-motion: reduce) {
    .context-usage-segment {
      transition: none;
    }
  }
</style>
