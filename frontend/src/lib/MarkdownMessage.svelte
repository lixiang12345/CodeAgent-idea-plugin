<script lang="ts">
  import DOMPurify from "dompurify";
  import { marked } from "marked";
  import CodeBlock from "./CodeBlock.svelte";
  import MermaidCanvas from "./MermaidCanvas.svelte";
  import Icon from "./Icon.svelte";

  type Props = {
    content: string;
    onOpenMermaid?: (source: string) => void;
  };

  type Segment =
    | { kind: "markdown"; html: string }
    | { kind: "code"; source: string; language: string }
    | { kind: "mermaid"; source: string };

  let { content, onOpenMermaid }: Props = $props();

  function renderMarkdown(source: string) {
    if (!source.trim()) return "";
    const rendered = marked.parse(source, {
      async: false,
      breaks: true,
      gfm: true,
    });
    return DOMPurify.sanitize(String(rendered), {
      USE_PROFILES: { html: true },
    });
  }

  function splitMarkdown(source: string): Segment[] {
    const lines = source.replaceAll("\r\n", "\n").split("\n");
    const segments: Segment[] = [];
    let markdown: string[] = [];

    const flushMarkdown = () => {
      const value = markdown.join("\n");
      if (value.trim()) segments.push({ kind: "markdown", html: renderMarkdown(value) });
      markdown = [];
    };

    for (let index = 0; index < lines.length; index += 1) {
      const opening = lines[index].match(/^\s*(`{3,}|~{3,})\s*([^\s`]*)?.*$/);
      if (!opening) {
        markdown.push(lines[index]);
        continue;
      }

      const fence = opening[1];
      const language = (opening[2] || "text").toLowerCase();
      const code: string[] = [];
      let closingIndex = index + 1;
      while (closingIndex < lines.length && lines[closingIndex].trim() !== fence) {
        code.push(lines[closingIndex]);
        closingIndex += 1;
      }
      if (closingIndex >= lines.length) {
        markdown.push(lines[index]);
        continue;
      }

      flushMarkdown();
      const source = code.join("\n");
      if (source.trim()) {
        if (language === "mermaid") segments.push({ kind: "mermaid", source: source.trim() });
        else segments.push({ kind: "code", source, language });
      }
      index = closingIndex;
    }

    flushMarkdown();
    return segments;
  }

  let segments = $derived(splitMarkdown(content));
</script>

<div class="markdown-message">
  {#each segments as segment, index (`${segment.kind}-${index}`)}
    {#if segment.kind === "markdown"}
      <div class="markdown-body">{@html segment.html}</div>
    {:else if segment.kind === "code"}
      <CodeBlock source={segment.source} language={segment.language} />
    {:else}
      <section class="inline-mermaid">
        <header>
          <span><Icon name="workflow" size={13} />Diagram</span>
          {#if onOpenMermaid}
            <button title="Open diagram canvas" aria-label="Open diagram canvas" onclick={() => onOpenMermaid?.(segment.source)}>
              <Icon name="maximize-2" size={13} />
            </button>
          {/if}
        </header>
        <MermaidCanvas source={segment.source} compact />
      </section>
    {/if}
  {/each}
</div>

<style>
  .markdown-message { min-width: 0; color: inherit; font: inherit; line-height: inherit; overflow-wrap: anywhere; }
  .markdown-body { min-width: 0; }
  .markdown-body :global(> :first-child) { margin-top: 0; }
  .markdown-body :global(> :last-child) { margin-bottom: 0; }
  .markdown-body :global(p) { margin: 0 0 .66em; }
  .markdown-body :global(h1),
  .markdown-body :global(h2),
  .markdown-body :global(h3),
  .markdown-body :global(h4) { margin: 1em 0 .45em; color: #eeeef0; font-weight: 600; line-height: 1.3; letter-spacing: 0; }
  .markdown-body :global(h1) { font-size: 1.3em; }
  .markdown-body :global(h2) { font-size: 1.18em; }
  .markdown-body :global(h3) { font-size: 1.08em; }
  .markdown-body :global(h4) { font-size: 1em; }
  .markdown-body :global(ul),
  .markdown-body :global(ol) { margin: .42em 0 .72em; padding-left: 1.65em; }
  .markdown-body :global(li) { margin: .18em 0; padding-left: .08em; }
  .markdown-body :global(li > p) { margin: 0; }
  .markdown-body :global(blockquote) { margin: .66em 0; padding: .18em 0 .18em .8em; border-left: 2px solid #555b64; color: #aeb3bb; }
  .markdown-body :global(code) { padding: .08em .34em; border-radius: 3px; color: #d8dde5; background: rgba(255,255,255,.07); font: .92em/1.45 "JetBrains Mono", Menlo, Consolas, monospace; }
  .markdown-body :global(pre) { box-sizing: border-box; width: 100%; max-width: 100%; margin: .66em 0 .82em; padding: .72em .82em; overflow: auto; border: 1px solid #32363c; border-radius: 5px; color: #cbd2dc; background: #191b1e; font: .88em/1.52 "JetBrains Mono", Menlo, Consolas, monospace; white-space: pre; }
  .markdown-body :global(pre code) { padding: 0; color: inherit; background: transparent; font: inherit; }
  .markdown-body :global(a) { color: #8ab4f8; text-decoration: none; }
  .markdown-body :global(a:hover) { text-decoration: underline; }
  .markdown-body :global(hr) { height: 1px; margin: 1em 0; border: 0; background: #34373d; }
  .markdown-body :global(table) { display: block; width: 100%; margin: .66em 0 .82em; overflow-x: auto; border-collapse: collapse; font-size: .94em; }
  .markdown-body :global(th),
  .markdown-body :global(td) { min-width: 72px; padding: .42em .58em; border: 1px solid #383c42; text-align: left; vertical-align: top; }
  .markdown-body :global(th) { color: #e2e5e9; background: #25282c; }
  .markdown-body :global(img) { display: block; max-width: 100%; height: auto; border-radius: 4px; }
  .inline-mermaid { margin: .72em 0 .9em; overflow: hidden; border: 1px solid #363a40; border-radius: 6px; background: #191b1e; }
  .inline-mermaid > header { height: 29px; padding: 0 7px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #34373d; color: #aeb5bf; font-size: 10px; }
  .inline-mermaid > header span { display: inline-flex; align-items: center; gap: 5px; }
  .inline-mermaid > header button { width: 23px; height: 23px; padding: 0; display: grid; place-items: center; border: 0; border-radius: 3px; color: #808893; background: transparent; cursor: pointer; }
  .inline-mermaid > header button:hover { color: #d8dde4; background: rgba(255,255,255,.06); }
</style>
