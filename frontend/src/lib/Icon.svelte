<script lang="ts">
  import { iconSvg } from "./icons";

  interface Props {
    name: string;
    size?: number | string;
    class?: string;
    title?: string;
  }

  let { name, size = 16, class: className = "", title }: Props = $props();

  const markup = $derived(iconSvg(name));
  const dim = $derived(typeof size === "number" ? `${size}px` : size);
</script>

{#if markup}
  <span
    class="i {className}"
    class:empty={!markup}
    data-icon={name}
    title={title}
    style={`width:${dim};height:${dim}`}
    aria-hidden={title ? undefined : "true"}
    role={title ? "img" : undefined}
    aria-label={title}
  >{@html markup}</span>
{:else}
  <span
    class="i missing {className}"
    data-icon={name}
    title={title ?? name}
    style={`width:${dim};height:${dim}`}
    aria-hidden="true"
  ></span>
{/if}

<style>
  .i {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    color: currentColor;
    line-height: 0;
  }
  .i :global(svg) {
    width: 100%;
    height: 100%;
    display: block;
  }
  .i.missing {
    border: 1px dashed currentColor;
    border-radius: 2px;
    opacity: 0.45;
  }
</style>
