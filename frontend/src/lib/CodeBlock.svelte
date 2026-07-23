<script lang="ts">
  import DOMPurify from "dompurify";
  import Icon from "./Icon.svelte";
  import { highlightCode } from "./code-highlighter";
  import { sendCommand } from "./protocol";

  type Props = {
    source: string;
    language?: string;
  };

  let { source, language = "text" }: Props = $props();
  let html = $state("");
  let copied = $state(false);
  let generation = 0;

  $effect(() => {
    const current = ++generation;
    html = "";
    highlightCode(source, language)
      .then((value) => {
        if (current === generation) html = DOMPurify.sanitize(value, { USE_PROFILES: { html: true } });
      })
      .catch(() => {
        if (current === generation) html = `<pre class="shiki"><code>${escapeHtml(source)}</code></pre>`;
      });
  });

  function escapeHtml(value: string): string {
    return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
  }

  function copyCode() {
    sendCommand("copyText", { text: source });
    copied = true;
    window.setTimeout(() => copied = false, 1400);
  }

  function insertCode() {
    sendCommand("insertCodeBlock", { text: source });
  }
</script>

<section class="code-block">
  <header>
    <span>{language || "text"}</span>
    <div class="code-block-actions">
      <button title="Insert code into the active editor" aria-label="Insert code into the active editor" onclick={insertCode}>
        <Icon name="text-cursor-input" size={13} />
      </button>
      <button title="Copy code" aria-label="Copy code" onclick={copyCode}>
        <Icon name={copied ? "check" : "copy"} size={13} />
      </button>
    </div>
  </header>
  <div class="code-scroll">
    {#if html}
      {@html html}
    {:else}
      <pre><code>{source}</code></pre>
    {/if}
  </div>
</section>

<style>
  .code-block { margin: .66em 0 .82em; overflow: hidden; border: 1px solid #32363c; border-radius: 5px; background: #191b1e; }
  header { height: 29px; padding: 0 7px 0 10px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #32363c; color: #8f97a2; background: #202328; }
  header span { overflow: hidden; font-size: 9px; font-weight: 600; text-transform: uppercase; text-overflow: ellipsis; white-space: nowrap; }
  .code-block-actions { flex: 0 0 auto; display: inline-flex; align-items: center; gap: 1px; }
  button { width: 23px; height: 23px; padding: 0; display: grid; place-items: center; border: 0; border-radius: 3px; color: #8f97a2; background: transparent; cursor: pointer; }
  button:hover { color: #e1e5eb; background: rgba(255,255,255,.06); }
  .code-scroll { max-width: 100%; overflow: auto; }
  .code-scroll :global(.shiki), pre { box-sizing: border-box; min-width: 100%; width: max-content; margin: 0; padding: .78em .88em; background: #191b1e !important; font: .88em/1.55 "JetBrains Mono", Menlo, Consolas, monospace; }
  .code-scroll :global(code) { font: inherit; }
</style>
