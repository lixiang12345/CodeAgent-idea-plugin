<script lang="ts">
  import Icon from "./Icon.svelte";

  interface Props {
    step: number;
    total: number;
    title: string;
    description: string;
    actionLabel: string;
    canGoBack: boolean;
    onaction: () => void;
    onback: () => void;
    ondismiss: () => void;
  }

  let {
    step,
    total,
    title,
    description,
    actionLabel,
    canGoBack,
    onaction,
    onback,
    ondismiss,
  }: Props = $props();
</script>

<div
  class="product-coachmark"
  role="dialog"
  aria-modal="false"
  aria-labelledby="product-coachmark-title"
  aria-describedby="product-coachmark-description"
  tabindex="-1"
  onkeydown={(event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      ondismiss();
    }
  }}
>
  <header>
    <span><Icon name="sparkles" size={13} />Quick tour</span>
    <small>Step {step} of {total}</small>
    <button type="button" title="Dismiss quick tour" aria-label="Dismiss quick tour" onclick={ondismiss}>
      <Icon name="x" size={13} />
    </button>
  </header>
  <div class="product-coachmark-copy">
    <strong id="product-coachmark-title">{title}</strong>
    <p id="product-coachmark-description">{description}</p>
  </div>
  <div class="product-coachmark-progress" aria-label={`Tour progress: step ${step} of ${total}`}>
    {#each Array(total) as _, index}
      <i class:active={index < step}></i>
    {/each}
  </div>
  <footer>
    <button type="button" class="quiet" onclick={ondismiss}>Skip tour</button>
    <span></span>
    {#if canGoBack}<button type="button" onclick={onback}>Back</button>{/if}
    <button type="button" class="primary" onclick={onaction}>{actionLabel}</button>
  </footer>
</div>

<style>
  .product-coachmark {
    position: absolute;
    z-index: 64;
    right: 10px;
    bottom: 112px;
    width: min(318px, calc(100% - 20px));
    padding: 10px;
    overflow: hidden;
    border: 1px solid color-mix(in srgb, var(--accent) 46%, var(--line-strong));
    border-radius: var(--ds-radius-4);
    color: var(--text);
    background: color-mix(in srgb, var(--panel-3) 96%, transparent);
    box-shadow: 0 12px 34px rgba(0, 0, 0, .5);
    backdrop-filter: blur(10px);
    animation: coachmark-enter .16s ease-out both;
  }

  header {
    display: grid;
    grid-template-columns: 1fr auto 22px;
    align-items: center;
    gap: 7px;
  }

  header > span {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    color: color-mix(in srgb, var(--accent) 70%, var(--bright));
    font-size: 10px;
    font-weight: 600;
    letter-spacing: .02em;
    text-transform: uppercase;
  }

  header small {
    color: var(--muted);
    font: 9px var(--mono);
  }

  button {
    min-height: 25px;
    padding: 0 8px;
    border: 1px solid var(--line-strong);
    border-radius: var(--ds-radius-2);
    color: var(--text);
    background: color-mix(in srgb, var(--panel-3) 84%, var(--bright) 3%);
    font-size: 9.5px;
    cursor: pointer;
  }

  button:hover,
  button:focus-visible {
    color: var(--bright);
    border-color: color-mix(in srgb, var(--focus-ring) 55%, var(--line-strong));
    background: color-mix(in srgb, var(--panel-3) 76%, var(--bright) 7%);
  }

  button:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 1px;
  }

  header button {
    width: 22px;
    min-height: 22px;
    padding: 0;
    display: grid;
    place-items: center;
    border: 0;
    background: transparent;
  }

  .product-coachmark-copy {
    padding: 10px 2px 9px;
  }

  .product-coachmark-copy strong {
    display: block;
    color: var(--bright);
    font-size: 12px;
    font-weight: 600;
  }

  .product-coachmark-copy p {
    margin: 4px 0 0;
    color: var(--text);
    font-size: 10.5px;
    line-height: 1.45;
    overflow-wrap: anywhere;
  }

  .product-coachmark-progress {
    height: 2px;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 3px;
  }

  .product-coachmark-progress i {
    border-radius: 999px;
    background: var(--line-strong);
  }

  .product-coachmark-progress i.active {
    background: var(--accent);
  }

  footer {
    padding-top: 9px;
    display: grid;
    grid-template-columns: auto 1fr auto auto;
    align-items: center;
    gap: 5px;
  }

  footer .quiet {
    padding-inline: 2px;
    border-color: transparent;
    color: var(--muted);
    background: transparent;
  }

  footer .primary {
    border-color: color-mix(in srgb, var(--accent) 75%, var(--line-strong));
    color: white;
    background: var(--accent);
  }

  @keyframes coachmark-enter {
    from { opacity: 0; transform: translateY(7px); }
    to { opacity: 1; transform: translateY(0); }
  }

  @container (max-width: 380px) {
    .product-coachmark {
      right: 6px;
      bottom: 108px;
      width: calc(100% - 12px);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .product-coachmark { animation: none; }
  }
</style>
