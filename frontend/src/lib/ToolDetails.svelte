<script lang="ts">
  import Icon from "./Icon.svelte";
  import MermaidCanvas from "./MermaidCanvas.svelte";
  import type { ToolRun } from "./protocol";

  type ToolDetailKind =
    | "terminal"
    | "process"
    | "diagram"
    | "file"
    | "search"
    | "tasks"
    | "web"
    | "integration"
    | "agent"
    | "diagnostics"
    | "generic";

  interface Props {
    tool: ToolRun;
    onCopy: (text: string, notice: string) => void;
    onOpenTerminal: () => void;
    onOpenMermaid: (tool: ToolRun) => void;
    onOpenDiff: (tool: ToolRun) => void;
    onRevert: (tool: ToolRun) => void;
  }

  let { tool, onCopy, onOpenTerminal, onOpenMermaid, onOpenDiff, onRevert }: Props = $props();

  const processTools = new Set(["launch_process", "list_processes", "read_process", "write_process", "wait_process", "kill_process"]);
  const fileTools = new Set(["read_file", "list_files", "open_file", "write_file", "replace_text", "remove_files", "apply_patch"]);
  const searchTools = new Set(["codebase_retrieval", "conversation_retrieval", "search_text", "git_history"]);
  const taskTools = new Set(["view_tasks", "add_tasks", "update_tasks", "reorg_tasks"]);
  const webTools = new Set(["web_fetch", "web_search", "open_browser"]);
  const integrationPrefixes = ["github_", "linear_", "notion_", "jira_", "confluence_", "glean_", "supabase_", "mcp__", "acp__"];

  function detailKind(name: string): ToolDetailKind {
    if (name === "run_terminal") return "terminal";
    if (processTools.has(name)) return "process";
    if (name === "render_mermaid") return "diagram";
    if (fileTools.has(name)) return "file";
    if (searchTools.has(name)) return "search";
    if (taskTools.has(name)) return "tasks";
    if (webTools.has(name)) return "web";
    if (integrationPrefixes.some((prefix) => name.startsWith(prefix))) return "integration";
    if (name === "subagent" || name === "ask_user") return "agent";
    if (name === "diagnostics") return "diagnostics";
    return "generic";
  }

  function panelTitle(kind: ToolDetailKind): string {
    if (kind === "file") return "File result";
    if (kind === "search") return "Retrieved evidence";
    if (kind === "tasks") return "Task changes";
    if (kind === "web") return "Web result";
    if (kind === "integration") return "Integration result";
    if (kind === "agent") return tool.name === "ask_user" ? "User exchange" : "Subagent result";
    if (kind === "diagnostics") return "IDE diagnostics";
    return "Tool result";
  }

  function providerLabel(name: string): string {
    const prefix = integrationPrefixes.find((candidate) => name.startsWith(candidate));
    if (!prefix) return "Remote";
    const provider = prefix.replaceAll("_", "").replaceAll("-", "");
    if (provider === "mcp") return "MCP";
    if (provider === "acp") return "ACP";
    if (provider === "github") return "GitHub";
    return provider[0].toUpperCase() + provider.slice(1);
  }

  function terminalExitCode(detail: string): number | null {
    const match = detail.match(/^exit=(-?\d+)$/m);
    return match ? Number(match[1]) : null;
  }

  function terminalCommand(detail: string): string {
    return detail.match(/^command=(.*)$/m)?.[1]?.trim() || tool.summary.replace(/\s+\(exit -?\d+\)$/, "");
  }

  function terminalOutput(detail: string): string {
    const lines = detail.split("\n");
    const outputIndex = lines.findIndex((line) => line.trim() === "output:");
    if (outputIndex >= 0) return lines.slice(outputIndex + 1).join("\n").trim();
    return detail.replace(/^command=.*\n?/m, "").replace(/^exit=-?\d+\n?/m, "").replace(/^timeout=(?:true|false)\n?/m, "").trim();
  }

  function terminalState(detail: string): { label: string; failed: boolean } {
    if (/^timeout=true$/m.test(detail)) return { label: "timed out", failed: true };
    const exitCode = terminalExitCode(detail);
    if (exitCode === null) return { label: tool.status, failed: tool.status === "failed" };
    return { label: `exit ${exitCode}`, failed: exitCode !== 0 };
  }

  function processCommand(detail: string): string {
    return detail.match(/^command=(.*)$/m)?.[1]?.trim() || "";
  }

  function processOutput(detail: string): string {
    const lines = detail.split("\n");
    const outputIndex = lines.findIndex((line) => line.trim() === "output:");
    if (outputIndex >= 0) return lines.slice(outputIndex + 1).join("\n").trim();
    const separator = detail.indexOf("\n\n");
    return separator >= 0 ? detail.slice(separator + 2).trim() : "";
  }

  function processMetadata(detail: string): string {
    const separator = detail.indexOf("\n\n");
    const metadata = separator >= 0 ? detail.slice(0, separator) : detail;
    const lines = metadata.split("\n");
    const outputIndex = lines.findIndex((line) => line.trim() === "output:");
    return lines.slice(0, outputIndex >= 0 ? outputIndex : lines.length)
      .filter((line) => !line.startsWith("command="))
      .join("\n")
      .trim();
  }

  function resultLines(detail: string): string[] {
    return detail.split("\n").map((line) => line.trimEnd()).filter((line) => line.trim()).slice(0, 100);
  }

  function isLocation(line: string): boolean {
    return /^(?:[^\s:]+\/)*[^\s:]+:\d+(?::\d+)?(?::|$)/.test(line) || /^\d+\.\s+/.test(line);
  }

  function isUrl(line: string): boolean {
    return /^https?:\/\/\S+$/.test(line.trim());
  }

  function diffClass(line: string): string {
    if (line.startsWith("+") && !line.startsWith("+++")) return "added";
    if (line.startsWith("-") && !line.startsWith("---")) return "removed";
    if (line.startsWith("@@")) return "hunk";
    return "context";
  }

  function diagnosticsFailed(value: string): boolean {
    if (/\b(?:no|zero)\s+(?:registered\s+)?(?:errors?|problems?)\b|\b0\s+(?:errors?|problems?)\b/i.test(value)) return false;
    return /\b(?:errors?|problems?|failed|failure)\b/i.test(value);
  }

  let kind = $derived(detailKind(tool.name));
  let detail = $derived((tool.detail ?? "").trim());
  let lines = $derived(resultLines(detail));
  let terminal = $derived(terminalState(detail));
</script>

<div class="specialized-tool-details {kind}" data-tool-kind={kind}>
  {#if kind === "terminal"}
    <section class="detail-section command-section">
      <header><span><Icon name="square-terminal" size={12} />Command</span><button title="Copy command" onclick={() => onCopy(terminalCommand(detail), "Command copied")}><Icon name="copy" size={12} /></button></header>
      <pre><span class="prompt">$</span> {terminalCommand(detail)}</pre>
    </section>
    {#if detail}
      <section class="detail-section output-section" class:failed={terminal.failed}>
        <header><span><Icon name={terminal.failed ? "circle-alert" : "circle-check"} size={12} />Output</span><i>{terminal.label}</i><button title="Copy output" onclick={() => onCopy(terminalOutput(detail), "Terminal output copied")}><Icon name="copy" size={12} /></button></header>
        <pre>{terminalOutput(detail) || "Command completed without output."}</pre>
      </section>
    {/if}
    <button class="secondary-action" onclick={onOpenTerminal}><Icon name="square-terminal" size={13} />Show Terminal</button>
  {:else if kind === "process"}
    {#if processCommand(detail)}
      <section class="detail-section command-section">
        <header><span><Icon name="square-terminal" size={12} />Command</span><button title="Copy command" onclick={() => onCopy(processCommand(detail), "Command copied")}><Icon name="copy" size={12} /></button></header>
        <pre><span class="prompt">$</span> {processCommand(detail)}</pre>
      </section>
    {/if}
    <section class="detail-section">
      <header><span><Icon name="activity" size={12} />Process lifecycle</span><i>{tool.status}</i><button title="Copy process details" onclick={() => onCopy(processMetadata(detail), "Process details copied")}><Icon name="copy" size={12} /></button></header>
      <pre>{processMetadata(detail) || tool.summary}</pre>
    </section>
    {#if processOutput(detail)}
      <section class="detail-section output-section"><header><span>Output</span><button title="Copy output" onclick={() => onCopy(processOutput(detail), "Process output copied")}><Icon name="copy" size={12} /></button></header><pre>{processOutput(detail)}</pre></section>
    {/if}
  {:else if kind === "diagram" && detail}
    <header class="panel-heading"><span><Icon name="workflow" size={13} />Diagram</span><button title="Copy Mermaid source" onclick={() => onCopy(detail, "Mermaid source copied")}><Icon name="copy" size={12} /></button></header>
    <MermaidCanvas source={detail} compact />
    <button class="secondary-action" onclick={() => onOpenMermaid(tool)}><Icon name="maximize-2" size={13} />Open Canvas</button>
  {:else}
    <header class="panel-heading">
      <span>
        <Icon name={kind === "file" ? "file" : kind === "search" ? "search" : kind === "tasks" ? "list-checks" : kind === "web" ? "globe" : kind === "integration" ? "plug" : kind === "agent" ? "bot" : kind === "diagnostics" ? "circle-alert" : "braces"} size={13} />
        {panelTitle(kind)}
      </span>
      {#if kind === "integration"}<i class="provider-badge">{providerLabel(tool.name)}</i>{/if}
      {#if detail}<button title="Copy details" onclick={() => onCopy(detail, "Tool details copied")}><Icon name="copy" size={12} /></button>{/if}
    </header>

    {#if !detail}
      <p class="empty-detail">No additional output.</p>
    {:else if kind === "file" && lines.some((line) => ["added", "removed", "hunk"].includes(diffClass(line)))}
      <pre class="diff-preview">{#each lines as line}<span class={diffClass(line)}>{line}</span>{/each}</pre>
    {:else if kind === "search" || kind === "tasks" || kind === "web" || kind === "integration"}
      <div class="result-list">
        {#each lines as line}
          <div class:location={isLocation(line)} class:url={isUrl(line)}>
            <Icon name={isUrl(line) ? "external-link" : isLocation(line) ? "file-code" : kind === "tasks" ? "circle-check" : "chevron-right"} size={11} />
            <span>{line}</span>
          </div>
        {/each}
      </div>
    {:else if kind === "diagnostics"}
      <div class="diagnostic-result" class:failed={diagnosticsFailed(detail)}><Icon name={diagnosticsFailed(detail) ? "circle-alert" : "circle-check"} size={14} /><span>{detail}</span></div>
    {:else}
      <pre class="plain-result">{detail}</pre>
    {/if}

    {#if tool.changePath}
      <div class="file-actions">
        <span title={tool.changePath}><Icon name="file-diff" size={12} />{tool.changePath}</span>
        <button onclick={() => onOpenDiff(tool)}>View Diff</button>
        {#if tool.canRevert}<button onclick={() => onRevert(tool)}><Icon name="undo-2" size={11} />Undo</button>{/if}
      </div>
    {/if}
  {/if}
</div>

<style>
  .specialized-tool-details { display: flex; flex-direction: column; gap: 6px; }
  .panel-heading, .detail-section > header { min-height: 25px; display: flex; align-items: center; gap: 6px; color: #b9bec6; }
  .panel-heading { padding-bottom: 5px; border-bottom: 1px solid #34373d; }
  .panel-heading > span, .detail-section > header > span { min-width: 0; flex: 1; display: inline-flex; align-items: center; gap: 5px; font-size: 9px; font-weight: 600; }
  .panel-heading button, .detail-section header button { width: 23px; height: 22px; padding: 0; display: grid; place-items: center; border: 0; border-radius: 3px; color: #9298a1; background: transparent; cursor: pointer; }
  .panel-heading button:hover, .detail-section header button:hover { color: #e0e3e8; background: #35383d; }
  .provider-badge { padding: 2px 5px; border-radius: 8px; color: #aebff0; background: #2d3858; font: normal 8px "JetBrains Mono", monospace; }
  .detail-section { overflow: hidden; border: 1px solid #363a40; border-radius: 4px; background: #1c1e21; }
  .detail-section > header { padding: 0 7px; border-bottom: 1px solid #31343a; background: #25282c; }
  .detail-section > header i { color: #858c96; font: normal 8px "JetBrains Mono", monospace; }
  .detail-section.failed { border-color: #674044; }
  .detail-section.failed > header { color: #e1a0a5; background: #322225; }
  pre { margin: 0; padding: 8px; overflow: auto; color: #c8ccd2; background: #191b1e; font: 9px/1.5 "JetBrains Mono", monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
  .prompt { color: #7ea78c; }
  .result-list { max-height: 310px; overflow: auto; display: flex; flex-direction: column; border: 1px solid #34373d; border-radius: 4px; background: #1b1d20; }
  .result-list > div { min-height: 25px; padding: 4px 7px; display: flex; align-items: flex-start; gap: 6px; color: #b8bdc5; font: 9px/1.45 "JetBrains Mono", monospace; }
  .result-list > div + div { border-top: 1px solid #2c2f34; }
  .result-list > div :global(svg) { flex: 0 0 auto; margin-top: 1px; color: #6f7781; }
  .result-list > div.location { color: #bed0ed; background: rgba(64,91,130,.12); }
  .result-list > div.url { color: #8fb7e8; }
  .result-list span { min-width: 0; overflow-wrap: anywhere; }
  .diff-preview { display: flex; flex-direction: column; padding: 0; border: 1px solid #34373d; border-radius: 4px; }
  .diff-preview span { min-height: 20px; padding: 2px 7px; }
  .diff-preview .added { color: #b8d9bf; background: rgba(57,110,70,.2); }
  .diff-preview .removed { color: #e0b3b7; background: rgba(126,55,62,.2); }
  .diff-preview .hunk { color: #aabce5; background: rgba(65,82,125,.22); }
  .diagnostic-result { min-height: 38px; padding: 8px; display: flex; align-items: flex-start; gap: 7px; border: 1px solid #385642; border-radius: 4px; color: #bcd8c3; background: #203027; font-size: 9px; line-height: 1.45; }
  .diagnostic-result.failed { border-color: #674044; color: #e0b0b4; background: #312225; }
  .empty-detail { margin: 0; padding: 7px; color: #777e87; font-size: 9px; }
  .file-actions { min-height: 30px; padding: 4px 6px; display: flex; align-items: center; gap: 5px; border: 1px solid #3a3d43; border-radius: 4px; background: #222428; }
  .file-actions > span { min-width: 0; flex: 1; display: inline-flex; align-items: center; gap: 5px; overflow: hidden; color: #aeb4bd; font: 8px "JetBrains Mono", monospace; text-overflow: ellipsis; white-space: nowrap; }
  .file-actions button, .secondary-action { min-height: 23px; padding: 0 7px; display: inline-flex; align-items: center; gap: 4px; border: 1px solid #484c53; border-radius: 3px; color: #c2c6cd; background: #2c2f34; font-size: 8px; cursor: pointer; }
  .secondary-action { align-self: flex-start; }
  @media (max-width: 379px) {
    .result-list { max-height: 240px; }
    .file-actions { align-items: stretch; flex-wrap: wrap; }
    .file-actions > span { flex-basis: 100%; }
  }
</style>
