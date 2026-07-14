<script lang="ts">
  import Icon from "./Icon.svelte";
  import {
    sendCommand,
    type ConfigurationKind,
    type ConfigurationSnapshot,
    type HookExecution,
    type HookRuntimeSnapshot,
    type McpRuntimeSnapshot,
    type McpServerRuntime,
    type ModelOption,
    type ProductConfiguration,
  } from "./protocol";

  export let section: string;
  export let configurationSnapshot: ConfigurationSnapshot;
  export let mcpRuntime: McpRuntimeSnapshot;
  export let hookRuntime: HookRuntimeSnapshot;
  export let models: ModelOption[] = [];

  type Draft = {
    id: string;
    name: string;
    description: string;
    enabled: boolean;
    prompt: string;
    argumentHint: string;
    commandMode: string;
    commandAgentProfileId: string;
    event: string;
    hookRunPolicy: string;
    hookFailurePolicy: string;
    command: string;
    timeoutSeconds: number;
    agentType: string;
    systemPrompt: string;
    model: string;
    allowedTools: string;
    maxTurns: number;
    maxToolCalls: number;
    maxSubagentCalls: number;
    verificationPolicy: string;
    contextWindowTokens: number;
    reservedOutputTokens: number;
    source: string;
    version: string;
    capabilities: string;
    transport: string;
    args: string;
    cwd: string;
    url: string;
    authMode: string;
    tokenEnvironment: string;
    requiredEnvironment: string;
  };

  const sectionKinds: Record<string, ConfigurationKind> = {
    "MCP Servers": "mcp",
    Commands: "commands",
    Hooks: "hooks",
    Agents: "agents",
    Plugins: "plugins",
  };

  const copy: Record<ConfigurationKind, { title: string; lead: string; singular: string; icon: string }> = {
    mcp: {
      title: "MCP Servers",
      lead: "Store local stdio or remote MCP endpoints without putting credentials in product configuration.",
      singular: "MCP server",
      icon: "mcp",
    },
    commands: {
      title: "Commands",
      lead: "Reusable prompt entry points available to the Agent and command palette.",
      singular: "command",
      icon: "square-terminal",
    },
    hooks: {
      title: "Hooks",
      lead: "Bounded lifecycle commands with explicit activation, environment inheritance, failure policy, and execution audit.",
      singular: "hook",
      icon: "workflow",
    },
    agents: {
      title: "Agents",
      lead: "Specialized Agent roles with explicit prompts, tool allowlists, models, and turn budgets.",
      singular: "agent",
      icon: "bot",
    },
    plugins: {
      title: "Plugins",
      lead: "Product extension sources and declared capabilities. Installation lifecycle is connected separately.",
      singular: "plugin",
      icon: "layers",
    },
    "tool-permissions": {
      title: "Tool Permissions",
      lead: "Account and workspace tool policies.",
      singular: "tool policy",
      icon: "shield",
    },
  };

  let editorOpen = false;
  let editing = false;
  let draft = emptyDraft();
  let previousSection = section;

  $: kind = sectionKinds[section] ?? "commands";
  $: page = copy[kind];
  $: items = configurationSnapshot.items[kind] ?? [];
  $: busy = configurationSnapshot.state === "loading";
$: if (section !== previousSection) {
    previousSection = section;
    editorOpen = false;
    editing = false;
    draft = emptyDraft();
  }

  function emptyDraft(): Draft {
    return {
      id: "",
      name: "",
      description: "",
      enabled: true,
      prompt: "",
      argumentHint: "",
      commandMode: "inherit",
      commandAgentProfileId: "",
      event: "before-run",
      hookRunPolicy: "manual",
      hookFailurePolicy: "continue",
      command: "",
      timeoutSeconds: 60,
      agentType: "general",
      systemPrompt: "",
      model: "",
      allowedTools: "",
      maxTurns: 12,
      maxToolCalls: 48,
      maxSubagentCalls: 4,
      verificationPolicy: "after-mutation",
      contextWindowTokens: 64000,
      reservedOutputTokens: 8192,
      source: "",
      version: "",
      capabilities: "",
      transport: "stdio",
      args: "",
      cwd: "",
      url: "",
      authMode: "none",
      tokenEnvironment: "",
      requiredEnvironment: "",
    };
  }

  function text(value: unknown): string {
    return typeof value === "string" ? value : "";
  }

  function numberValue(value: unknown, fallback: number): number {
    return typeof value === "number" && Number.isFinite(value) ? value : fallback;
  }

  function listValue(value: unknown): string {
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string").join("\n") : "";
  }

  function parseList(value: string): string[] {
    return [...new Set(value.split(/[\n,]/).map((item) => item.trim()).filter(Boolean))];
  }

  function beginCreate() {
    editing = false;
    draft = emptyDraft();
    editorOpen = true;
  }

  function beginEdit(item: ProductConfiguration) {
    const value = item.value;
    editing = true;
    draft = {
      ...emptyDraft(),
      id: item.id,
      name: text(value.name),
      description: text(value.description),
      enabled: value.enabled !== false,
      prompt: text(value.prompt),
      argumentHint: text(value.argumentHint),
      commandMode: text(value.mode) || "inherit",
      commandAgentProfileId: text(value.agentProfileId),
      event: text(value.event) || "before-run",
      hookRunPolicy: text(value.runPolicy) || "manual",
      hookFailurePolicy: text(value.failurePolicy) || "continue",
      command: text(value.command),
      timeoutSeconds: numberValue(value.timeoutSeconds, 60),
      agentType: text(value.agentType) || "general",
      systemPrompt: text(value.systemPrompt),
      model: text(value.model),
      allowedTools: listValue(value.allowedTools),
      maxTurns: numberValue(value.maxTurns, 12),
      maxToolCalls: numberValue(value.maxToolCalls, 48),
      maxSubagentCalls: numberValue(value.maxSubagentCalls, 4),
      verificationPolicy: text(value.verificationPolicy) || "after-mutation",
      contextWindowTokens: numberValue(value.contextWindowTokens, 64000),
      reservedOutputTokens: numberValue(value.reservedOutputTokens, 8192),
      source: text(value.source),
      version: text(value.version),
      capabilities: listValue(value.capabilities),
      transport: text(value.transport) || "stdio",
      args: listValue(value.args),
      cwd: text(value.cwd),
      url: text(value.url),
      authMode: text(value.authMode) || "none",
      tokenEnvironment: text(value.tokenEnvironment),
      requiredEnvironment: listValue(value.requiredEnvironment),
    };
    editorOpen = true;
  }

  function configurationId(): string {
    const explicit = draft.id.trim();
    if (explicit) return explicit;
    return draft.name
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9._-]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 120);
  }

  function buildValue(): Record<string, unknown> {
    const common = {
      name: draft.name.trim(),
      description: draft.description.trim() || null,
      enabled: draft.enabled,
    };
    if (kind === "commands") {
      return {
        ...common,
        prompt: draft.prompt,
        argumentHint: draft.argumentHint.trim() || null,
        mode: draft.commandMode,
        agentProfileId: draft.commandAgentProfileId.trim() || null,
      };
    }
    if (kind === "hooks") {
      return {
        ...common,
        event: draft.event,
        runPolicy: draft.hookRunPolicy,
        failurePolicy: draft.hookFailurePolicy,
        command: draft.command,
        timeoutSeconds: draft.timeoutSeconds,
        requiredEnvironment: parseList(draft.requiredEnvironment),
      };
    }
    if (kind === "agents") {
      return {
        ...common,
        agentType: draft.agentType,
        systemPrompt: draft.systemPrompt,
        model: draft.model.trim() || null,
        allowedTools: parseList(draft.allowedTools),
        maxTurns: draft.maxTurns,
        maxToolCalls: draft.maxToolCalls,
        maxSubagentCalls: draft.maxSubagentCalls,
        verificationPolicy: draft.verificationPolicy,
        contextWindowTokens: draft.contextWindowTokens,
        reservedOutputTokens: draft.reservedOutputTokens,
      };
    }
    if (kind === "plugins") {
      return {
        ...common,
        source: draft.source.trim(),
        version: draft.version.trim() || null,
        capabilities: parseList(draft.capabilities),
      };
    }
    return {
      ...common,
      transport: draft.transport,
      command: draft.transport === "stdio" ? draft.command.trim() : null,
      args: draft.transport === "stdio" ? parseList(draft.args) : [],
      cwd: draft.transport === "stdio" ? draft.cwd.trim() || null : null,
      url: draft.transport === "stdio" ? null : draft.url.trim(),
      authMode: draft.transport === "stdio" ? "none" : draft.authMode,
      tokenEnvironment: draft.authMode === "bearer-environment" ? draft.tokenEnvironment.trim() : null,
      requiredEnvironment: parseList(draft.requiredEnvironment),
      timeoutSeconds: draft.timeoutSeconds,
    };
  }

  function canSave(): boolean {
    if (!configurationId() || !/^[A-Za-z0-9._-]{1,120}$/.test(configurationId()) || !draft.name.trim()) return false;
    if (kind === "commands") return Boolean(draft.prompt.trim());
    if (kind === "hooks") return Boolean(draft.command.trim());
    if (kind === "agents") {
      return draft.contextWindowTokens >= 32768
        && draft.maxTurns >= 1
        && draft.maxTurns <= 64
        && draft.maxToolCalls >= 1
        && draft.maxToolCalls <= 256
        && draft.maxSubagentCalls >= 0
        && draft.maxSubagentCalls <= 16
        && draft.contextWindowTokens <= 2000000
        && draft.reservedOutputTokens >= 1024
        && draft.reservedOutputTokens <= 65536
        && draft.reservedOutputTokens < draft.contextWindowTokens;
    }
    if (kind === "plugins") return Boolean(draft.source.trim());
    return draft.transport === "stdio" ? Boolean(draft.command.trim()) : Boolean(draft.url.trim());
  }

  function save() {
    if (!canSave()) return;
    if (
      kind === "hooks"
      && draft.hookRunPolicy === "automatic"
      && !window.confirm("Allow this hook to execute project commands automatically for its selected lifecycle event?")
    ) return;
    sendCommand("saveConfiguration", { kind, id: configurationId(), value: buildValue() });
    editorOpen = false;
  }

  function toggle(item: ProductConfiguration) {
    sendCommand("saveConfiguration", {
      kind,
      id: item.id,
      value: { ...item.value, enabled: item.value.enabled === false },
    });
  }

  function remove(item: ProductConfiguration) {
    const name = text(item.value.name) || item.id;
    if (!window.confirm(`Delete ${name}?`)) return;
    sendCommand("deleteConfiguration", { kind, id: item.id });
    if (editing && draft.id === item.id) editorOpen = false;
  }

  function summary(item: ProductConfiguration): string {
    const value = item.value;
    if (kind === "mcp") {
      const runtime = runtimeFor(item.id);
      return `${text(value.transport) || "stdio"} · ${runtime?.state ?? "not activated"} · ${runtime?.tools.length ?? 0} tools`;
    }
    if (kind === "commands") {
      const mode = text(value.mode) || "inherit";
      const profile = text(value.agentProfileId);
      return [text(value.argumentHint) || "Prompt command", mode, profile].filter(Boolean).join(" · ");
    }
    if (kind === "hooks") {
      return [
        text(value.event) || "before-run",
        text(value.runPolicy) || "manual",
        text(value.failurePolicy) || "continue",
        `${numberValue(value.timeoutSeconds, 60)}s`,
      ].join(" · ");
    }
    if (kind === "agents") return `${text(value.agentType) || "general"} · ${numberValue(value.maxTurns, 12)} turns · ${numberValue(value.maxToolCalls, 48)} tools · ${Math.round(numberValue(value.contextWindowTokens, 64000) / 1000)}k context`;
    return text(value.version) || text(value.source) || "Extension source";
  }

  function runtimeFor(serverId: string): McpServerRuntime | undefined {
    return mcpRuntime.servers.find((server) => server.id === serverId);
  }

  function controlMcp(command: "startMcpServer" | "stopMcpServer" | "restartMcpServer" | "testMcpServer", serverId: string) {
    sendCommand(command, { serverId });
  }

  function testHook(hookId: string) {
    sendCommand("testHook", { hookId });
  }

  function hookDuration(execution: HookExecution): string {
    if (execution.durationMs < 1000) return `${execution.durationMs} ms`;
    return `${(execution.durationMs / 1000).toFixed(execution.durationMs < 10_000 ? 1 : 0)} s`;
  }

  function hookStartedAt(execution: HookExecution): string {
    const parsed = new Date(execution.startedAt);
    return Number.isNaN(parsed.getTime()) ? execution.startedAt : parsed.toLocaleString();
  }
</script>

{#if editorOpen}
  <div class="configuration-title">
    <button class="icon-button compact" title="Back to list" onclick={() => editorOpen = false}><Icon name="chevron-left" size={14} /></button>
    <div>
      <h1>{editing ? "Edit" : "New"} {page.singular}</h1>
      <p class="configuration-lead">Server-validated, account-scoped product configuration.</p>
    </div>
  </div>
  <section class="configuration-editor">
    <label>
      <span>ID</span>
      <input bind:value={draft.id} disabled={editing} maxlength="120" placeholder="lowercase-id" />
      <small>Letters, numbers, dots, underscores, and hyphens.</small>
    </label>
    <label><span>Name</span><input bind:value={draft.name} maxlength="160" /></label>
    <label><span>Description</span><textarea class="compact-textarea" bind:value={draft.description} maxlength="2000"></textarea></label>
    <label class="configuration-toggle">
      <input type="checkbox" bind:checked={draft.enabled} />
      <span><strong>Enabled</strong><small>Disabled records stay stored but are excluded from runtime activation.</small></span>
    </label>

    {#if kind === "commands"}
      <label><span>Argument hint</span><input bind:value={draft.argumentHint} maxlength="500" placeholder="[scope] [options]" /></label>
      <label><span>Run mode</span><select bind:value={draft.commandMode}><option value="inherit">Current thread mode</option><option value="agent">Agent</option><option value="chat">Chat</option><option value="ask">Ask</option></select></label>
      <label><span>Agent profile</span><select bind:value={draft.commandAgentProfileId}><option value="">Current thread profile</option><option value="general">General Agent</option><option value="search">Search Agent</option><option value="context">Context Agent</option><option value="prompt">Prompt Engineer</option><option value="loop">Loop Agent</option>{#each configurationSnapshot.items.agents ?? [] as agent}{#if agent.value.enabled !== false}<option value={agent.id}>{text(agent.value.name) || agent.id}</option>{/if}{/each}</select></label>
      <label><span>Prompt template</span><textarea bind:value={draft.prompt} spellcheck="true"></textarea></label>
      <div class="runtime-note"><Icon name="info" size={13} /><span>Use <code>&#123;&#123;arguments&#125;&#125;</code>, <code>&#123;&#123;project&#125;&#125;</code>, and <code>&#123;&#123;command&#125;&#125;</code> placeholders. Arguments are appended in a bounded block when the template omits the arguments placeholder.</span></div>
    {:else if kind === "hooks"}
      <label><span>Event</span><select bind:value={draft.event}><option value="before-run">Before run</option><option value="after-run">After run</option><option value="before-tool">Before tool</option><option value="after-tool">After tool</option><option value="on-error">On error</option></select></label>
      <label><span>Run policy</span><select bind:value={draft.hookRunPolicy}><option value="manual">Manual only</option><option value="automatic">Automatic on lifecycle event</option></select></label>
      <label><span>Failure policy</span><select bind:value={draft.hookFailurePolicy}><option value="continue">Record failure and continue</option><option value="fail-run">Stop the Agent run</option></select></label>
      <label><span>Command</span><textarea class="command-textarea" bind:value={draft.command} spellcheck="false"></textarea></label>
      <label><span>Required environment</span><textarea class="list-textarea" bind:value={draft.requiredEnvironment} spellcheck="false" placeholder="API_TOKEN"></textarea><small>Names only. Values are inherited from the IDE process when explicitly allowlisted.</small></label>
      <label><span>Timeout seconds</span><input type="number" min="1" max="600" bind:value={draft.timeoutSeconds} /></label>
      <div class="runtime-note"><Icon name="shield" size={13} /><span>Hooks run in the project root with a bounded timeout and minimal environment. New hooks default to manual execution; automatic hooks require explicit confirmation.</span></div>
    {:else if kind === "agents"}
      <label><span>Agent type</span><select bind:value={draft.agentType}><option value="general">General</option><option value="search">Search</option><option value="context">Context</option><option value="prompt">Prompt engineer</option><option value="loop">Loop / verifier</option></select></label>
      <label><span>Model</span><select bind:value={draft.model}><option value="">Backend default</option>{#each models as model}<option value={model.id}>{model.id}</option>{/each}</select></label>
      <label><span>Allowed tools</span><textarea class="list-textarea" bind:value={draft.allowedTools} spellcheck="false" placeholder="read_file&#10;search_text"></textarea><small>One tool name per line.</small></label>
      <label><span>Maximum turns</span><input type="number" min="1" max="64" bind:value={draft.maxTurns} /></label>
      <label><span>Maximum tool calls</span><input type="number" min="1" max="256" bind:value={draft.maxToolCalls} /><small>Hard per-run budget across local, backend, discovery, and delegated calls.</small></label>
      <label><span>Maximum subagents</span><input type="number" min="0" max="16" bind:value={draft.maxSubagentCalls} /><small>Limits bounded specialist delegations within one run.</small></label>
      <label><span>Verification policy</span><select bind:value={draft.verificationPolicy}><option value="none">No runtime gate</option><option value="after-mutation">Require verification after mutation</option></select></label>
      <label><span>Context window tokens</span><input type="number" min="32768" max="2000000" step="1024" bind:value={draft.contextWindowTokens} /><small>Total model context available to this Agent profile.</small></label>
      <label><span>Reserved output tokens</span><input type="number" min="1024" max="65536" step="1024" bind:value={draft.reservedOutputTokens} /><small>Held back from input so the model can finish its response and tool plan.</small></label>
      <label><span>Custom instructions</span><textarea bind:value={draft.systemPrompt} spellcheck="true"></textarea><small>Account-scoped guidance. The current user request, runtime safety, approvals, and tool policy take priority.</small></label>
    {:else if kind === "plugins"}
      <label><span>Source</span><input bind:value={draft.source} maxlength="2000" placeholder="registry package or HTTPS repository" /></label>
      <label><span>Version</span><input bind:value={draft.version} maxlength="240" placeholder="Optional version constraint" /></label>
      <label><span>Capabilities</span><textarea class="list-textarea" bind:value={draft.capabilities} spellcheck="false"></textarea><small>One declared capability per line.</small></label>
    {:else}
      <fieldset>
        <legend>Transport</legend>
        <div class="configuration-segments">
          <button class:active={draft.transport === "stdio"} onclick={() => draft.transport = "stdio"}>stdio</button>
          <button class:active={draft.transport === "streamable-http"} onclick={() => draft.transport = "streamable-http"}>HTTP</button>
          <button class:active={draft.transport === "sse"} onclick={() => draft.transport = "sse"}>SSE</button>
        </div>
      </fieldset>
      {#if draft.transport === "stdio"}
        <label><span>Command</span><input bind:value={draft.command} maxlength="4000" placeholder="npx" /></label>
        <label><span>Arguments</span><textarea class="list-textarea" bind:value={draft.args} spellcheck="false" placeholder="-y&#10;@modelcontextprotocol/server-filesystem"></textarea><small>One argument per line.</small></label>
        <label><span>Working directory</span><input bind:value={draft.cwd} maxlength="4000" placeholder="Optional" /></label>
      {:else}
        <label><span>Endpoint URL</span><input bind:value={draft.url} maxlength="4000" placeholder="https://example.com/mcp" /></label>
        <label><span>Authentication</span><select bind:value={draft.authMode}><option value="none">None</option><option value="bearer-environment">Bearer token from environment</option></select></label>
        {#if draft.authMode === "bearer-environment"}
          <label><span>Token environment variable</span><input bind:value={draft.tokenEnvironment} maxlength="128" placeholder="MCP_ACCESS_TOKEN" /></label>
        {/if}
      {/if}
      <label><span>Required environment</span><textarea class="list-textarea" bind:value={draft.requiredEnvironment} spellcheck="false" placeholder="API_TOKEN"></textarea><small>Names only. Secret values stay outside CodeAgent.</small></label>
      <label><span>Timeout seconds</span><input type="number" min="1" max="600" bind:value={draft.timeoutSeconds} /></label>
      <div class="runtime-note"><Icon name="info" size={13} /><span>Enabled definitions connect through the local MCP gateway. Only allowlisted environment names are inherited, and tool calls remain subject to Agent approval policy.</span></div>
    {/if}

    <footer>
      <button onclick={() => editorOpen = false}>Cancel</button>
      <button class="primary" disabled={!canSave() || busy} onclick={save}><Icon name="save" size={12} />Save</button>
    </footer>
  </section>
{:else}
  <div class="configuration-title">
    <div>
      <h1>{page.title}{#if kind === "commands" || kind === "agents" || kind === "plugins"} <em>Beta</em>{/if}</h1>
      <p class="configuration-lead">{page.lead}</p>
    </div>
    <button class="new-configuration" disabled={busy || configurationSnapshot.state === "unavailable"} onclick={beginCreate}><Icon name="plus" size={13} />Add</button>
  </div>

  <div class="configuration-status">
    <span class="status-dot {configurationSnapshot.state}"></span>
    <span><strong>{configurationSnapshot.state}</strong><small>{configurationSnapshot.label}</small></span>
    <button class="icon-button compact" title="Refresh configurations" disabled={busy} onclick={() => sendCommand("refreshConfigurations")}><Icon name="refresh-ccw" size={13} /></button>
  </div>

  {#if kind === "mcp"}
    <div class="configuration-status mcp-gateway-status">
      <span class="status-dot {mcpRuntime.state}"></span>
      <span><strong>MCP gateway · {mcpRuntime.state}</strong><small>{mcpRuntime.label}</small></span>
      <button class="icon-button compact" title="Refresh MCP runtime" onclick={() => sendCommand("refreshMcpRuntime")}><Icon name="activity" size={13} /></button>
    </div>
  {/if}

  {#if kind === "hooks"}
    <div class="configuration-status hook-runtime-status">
      <span class="status-dot {hookRuntime.state}"></span>
      <span><strong>Hook runtime · {hookRuntime.state}</strong><small>{hookRuntime.label}</small></span>
      <i>{hookRuntime.automatic}/{hookRuntime.configured} automatic</i>
    </div>
  {/if}

  {#if configurationSnapshot.state === "unavailable" || configurationSnapshot.state === "error"}
    <section class="configuration-empty">
      <Icon name="circle-alert" size={19} />
      <strong>{configurationSnapshot.state === "error" ? "Configuration service error" : "Sign in required"}</strong>
      <p>{configurationSnapshot.label}</p>
    </section>
  {:else}
    <section class="configuration-list">
      {#each items as item}
        {@const runtime = kind === "mcp" ? runtimeFor(item.id) : undefined}
        <article class:mcp-item={kind === "mcp"}>
          <span class="configuration-icon"><Icon name={page.icon} size={14} /></span>
          <button class="configuration-copy" onclick={() => beginEdit(item)}>
            <strong>{text(item.value.name) || item.id}</strong>
            <small>{text(item.value.description) || summary(item)}</small>
            <i>{summary(item)}</i>
          </button>
          <label class="switch" title={item.value.enabled === false ? "Enable" : "Disable"}>
            <input type="checkbox" checked={item.value.enabled !== false} disabled={busy} onchange={() => toggle(item)} />
            <span></span>
          </label>
          {#if kind === "hooks"}
            <button class="icon-button compact" title="Test hook" disabled={busy || item.value.enabled === false} onclick={() => testHook(item.id)}><Icon name="play" size={11} /></button>
          {/if}
          <button class="icon-button compact" title="Edit" disabled={busy} onclick={() => beginEdit(item)}><Icon name="pencil" size={12} /></button>
          <button class="icon-button compact danger" title="Delete" disabled={busy} onclick={() => remove(item)}><Icon name="trash-2" size={12} /></button>
          {#if kind === "mcp"}
            <div class="mcp-runtime-row">
              <span class="mcp-runtime-copy">
                <i class="runtime-state {runtime?.state ?? (item.value.enabled === false ? 'disabled' : 'stopped')}">{runtime?.state ?? (item.value.enabled === false ? "disabled" : "stopped")}</i>
                <small>{runtime?.label ?? (item.value.enabled === false ? "Disabled" : "Waiting for gateway activation")}</small>
                {#if runtime?.pid}<small>PID {runtime.pid}</small>{/if}
                {#if runtime?.latencyMs !== undefined}<small>{runtime.latencyMs} ms</small>{/if}
              </span>
              <span class="mcp-runtime-actions">
                {#if runtime?.state === "ready" || runtime?.state === "degraded" || runtime?.state === "error"}
                  <button class="icon-button compact" title="Stop MCP server" onclick={() => controlMcp("stopMcpServer", item.id)}><Icon name="square" size={11} /></button>
                  <button class="icon-button compact" title="Restart MCP server" onclick={() => controlMcp("restartMcpServer", item.id)}><Icon name="refresh-ccw" size={11} /></button>
                {:else}
                  <button class="icon-button compact" title="Start MCP server" disabled={item.value.enabled === false || runtime?.state === "starting" || runtime?.state === "stopping"} onclick={() => controlMcp("startMcpServer", item.id)}><Icon name="play" size={11} /></button>
                {/if}
                <button class="icon-button compact" title="Test MCP connection" disabled={item.value.enabled === false || runtime?.state === "starting" || runtime?.state === "stopping"} onclick={() => controlMcp("testMcpServer", item.id)}><Icon name="activity" size={11} /></button>
              </span>
              {#if runtime && runtime.tools.length > 0}
                <details class="mcp-tool-list">
                  <summary>{runtime.tools.length} discovered {runtime.tools.length === 1 ? "tool" : "tools"}</summary>
                  {#each runtime.tools as tool}
                    <div><strong>{tool.title ?? tool.name}</strong><small>{tool.description}</small><i>{tool.risk === "read_only" ? "read only" : "approval required"}</i></div>
                  {/each}
                </details>
              {/if}
            </div>
          {/if}
        </article>
      {:else}
        <div class="configuration-empty">
          <Icon name={page.icon} size={19} />
          <strong>No {page.title.toLowerCase()} configured</strong>
          <p>Create the first {page.singular} for this account.</p>
          <button onclick={beginCreate}><Icon name="plus" size={12} />Add {page.singular}</button>
        </div>
      {/each}
    </section>
  {/if}

  {#if kind === "hooks"}
    <section class="hook-audit">
      <header>
        <span><strong>Recent executions</strong><small>Latest {hookRuntime.recent.length} of 50 retained for this IDE session</small></span>
        <i>{hookRuntime.recent.length}</i>
      </header>
      {#each hookRuntime.recent as execution}
        <details class="hook-execution">
          <summary>
            <span class="hook-execution-state {execution.status}"><Icon name={execution.status === "completed" ? "check" : "circle-alert"} size={11} /></span>
            <span class="hook-execution-copy"><strong>{execution.hookName}</strong><small>{execution.event} · {hookDuration(execution)} · {hookStartedAt(execution)}</small></span>
            <i>{execution.summary}</i>
          </summary>
          <div class="hook-execution-detail">
            <span>Hook <code>{execution.hookId}</code>{#if execution.exitCode !== undefined} · exit {execution.exitCode}{/if}</span>
            {#if execution.detail}<pre>{execution.detail}</pre>{/if}
          </div>
        </details>
      {:else}
        <div class="hook-audit-empty"><Icon name="activity" size={16} /><span>No hook executions in this IDE session.</span></div>
      {/each}
    </section>
  {/if}
{/if}

<style>
  .configuration-title { display: flex; align-items: flex-start; gap: 7px; }
  .configuration-title > div { min-width: 0; flex: 1; }
  .configuration-title h1 { margin: 8px 0 3px; font-size: 16px; font-weight: 600; }
  .configuration-title h1 em { padding: 2px 5px; border-radius: 4px; color: #9cb0eb; background: #2c375a; font: normal 8px var(--mono); vertical-align: middle; }
  .configuration-lead { margin: 0 0 14px; color: var(--muted); font-size: 9px; line-height: 1.45; }
  .new-configuration { height: 26px; margin-top: 7px; padding: 0 8px; display: inline-flex; align-items: center; gap: 4px; border: 1px solid #456b50; border-radius: 4px; color: #b9dec2; background: #253b2b; font-size: 8px; cursor: pointer; }
  .new-configuration:disabled { opacity: .45; cursor: default; }
  .configuration-status { min-height: 39px; margin-bottom: 8px; padding: 6px 7px; display: flex; align-items: center; gap: 7px; border: 1px solid var(--line); border-radius: 5px; background: #1b1d20; }
  .configuration-status > span:nth-child(2) { min-width: 0; flex: 1; display: flex; flex-direction: column; }
  .configuration-status strong { font-size: 8px; text-transform: capitalize; }
  .configuration-status small { overflow: hidden; text-overflow: ellipsis; color: var(--muted); font-size: 7.5px; white-space: nowrap; }
  .status-dot { width: 8px; height: 8px; flex: 0 0 8px; border-radius: 50%; background: #777c84; box-shadow: 0 0 0 2px #31343a; }
  .status-dot.ready { background: #62b47a; box-shadow: 0 0 0 2px #2d4d36; }
  .status-dot.loading { background: #d0a05b; box-shadow: 0 0 0 2px #51432d; }
  .status-dot.error, .status-dot.degraded { background: #dc6e74; box-shadow: 0 0 0 2px #523238; }
  .mcp-gateway-status { margin-top: -2px; }
  .hook-runtime-status { margin-top: -2px; }
  .hook-runtime-status > i { color: #8793a2; font: normal 7px var(--mono); white-space: nowrap; }
  .configuration-list { overflow: hidden; border: 1px solid var(--line); border-radius: 5px; background: #202225; }
  .configuration-list article { min-height: 62px; padding: 6px 7px; display: flex; align-items: center; gap: 6px; }
  .configuration-list article + article { border-top: 1px solid var(--line); }
  .configuration-list article.mcp-item { flex-wrap: wrap; }
  .configuration-icon { width: 28px; height: 28px; flex: 0 0 28px; display: grid; place-items: center; border: 1px solid #3c424b; border-radius: 5px; color: #aebbd0; background: #282d34; }
  .configuration-copy { min-width: 0; flex: 1; padding: 2px 0; display: flex; flex-direction: column; align-items: flex-start; border: 0; color: #cbd0d6; background: transparent; text-align: left; cursor: pointer; }
  .configuration-copy strong, .configuration-copy small, .configuration-copy i { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .configuration-copy strong { font-size: 9px; }
  .configuration-copy small { color: var(--muted); font-size: 8px; }
  .configuration-copy i { color: #8190a4; font: normal 7px var(--mono); }
  .mcp-runtime-row { width: calc(100% - 34px); margin-left: 34px; padding: 6px 0 1px; display: flex; align-items: center; gap: 6px; border-top: 1px solid #30343a; }
  .mcp-runtime-copy { min-width: 0; flex: 1; display: flex; align-items: center; gap: 6px; }
  .mcp-runtime-copy small { overflow: hidden; text-overflow: ellipsis; color: var(--muted); font: 7px var(--mono); white-space: nowrap; }
  .runtime-state { padding: 2px 5px; border-radius: 3px; color: #aab0b7; background: #30343a; font: normal 7px var(--mono); text-transform: uppercase; }
  .runtime-state.ready { color: #a7d9b3; background: #294331; }
  .runtime-state.starting, .runtime-state.stopping { color: #e0c28b; background: #4a3c24; }
  .runtime-state.error, .runtime-state.degraded { color: #e4a1a6; background: #4a292e; }
  .mcp-runtime-actions { display: flex; gap: 4px; }
  .mcp-tool-list { width: 100%; color: var(--muted); font-size: 8px; }
  .mcp-tool-list summary { padding: 5px 0; cursor: pointer; }
  .mcp-tool-list > div { padding: 5px 0; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 2px 8px; border-top: 1px solid #30343a; }
  .mcp-tool-list strong { overflow: hidden; text-overflow: ellipsis; color: #c1c6cc; font-size: 8px; white-space: nowrap; }
  .mcp-tool-list small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mcp-tool-list i { grid-column: 2; grid-row: 1 / 3; align-self: center; color: #8393a7; font: normal 7px var(--mono); }
  .switch { position: relative; width: 27px; height: 16px; flex: 0 0 27px; }
  .switch input { position: absolute; opacity: 0; }
  .switch span { position: absolute; inset: 0; border: 1px solid #4a4e54; border-radius: 8px; background: #303338; cursor: pointer; }
  .switch span::after { content: ""; position: absolute; top: 2px; left: 2px; width: 10px; height: 10px; border-radius: 50%; background: #8a8f96; transition: transform .12s ease; }
  .switch input:checked + span { border-color: #426b4e; background: #294331; }
  .switch input:checked + span::after { transform: translateX(11px); background: #7bc28d; }
  .configuration-empty { min-height: 160px; padding: 18px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--muted); text-align: center; }
  .configuration-empty strong { margin-top: 7px; color: #c4c8cd; font-size: 10px; }
  .configuration-empty p { margin: 5px 0 10px; font-size: 8px; }
  .configuration-empty button { height: 25px; padding: 0 8px; display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--line-strong); border-radius: 3px; background: #2d3035; font-size: 8px; cursor: pointer; }
  .configuration-editor { margin-bottom: 8px; padding: 10px; border: 1px solid var(--line); border-radius: 5px; background: #202225; }
  .configuration-editor > label { display: flex; flex-direction: column; gap: 5px; margin-bottom: 11px; color: #aeb3ba; font-size: 9px; }
  .configuration-editor input:not([type="checkbox"]), .configuration-editor select, .configuration-editor textarea { width: 100%; outline: 0; border: 1px solid var(--line); border-radius: 4px; color: var(--text); background: #181a1d; font-size: 9px; }
  .configuration-editor input:not([type="checkbox"]), .configuration-editor select { height: 31px; padding: 0 8px; }
  .configuration-editor textarea { min-height: 220px; padding: 9px; resize: vertical; font: 9px/1.5 var(--mono); }
  .configuration-editor textarea.compact-textarea { min-height: 70px; font-family: inherit; }
  .configuration-editor textarea.command-textarea { min-height: 90px; }
  .configuration-editor textarea.list-textarea { min-height: 88px; }
  .configuration-editor input:focus, .configuration-editor select:focus, .configuration-editor textarea:focus { border-color: #5478b0; }
  .configuration-editor input:disabled { color: #777c84; background: #202226; }
  .configuration-editor label > small { color: var(--muted); font-size: 7.5px; }
  .configuration-toggle { flex-direction: row !important; align-items: center; }
  .configuration-toggle > span { display: flex; flex-direction: column; }
  .configuration-editor fieldset { margin: 0 0 11px; padding: 0; border: 0; }
  .configuration-editor legend { margin-bottom: 5px; color: #aeb3ba; font-size: 9px; }
  .configuration-segments { height: 28px; padding: 2px; display: flex; border: 1px solid var(--line); border-radius: 4px; background: #181a1d; }
  .configuration-segments button { min-width: 0; flex: 1; border: 0; border-radius: 3px; color: var(--muted); background: transparent; font-size: 8px; cursor: pointer; }
  .configuration-segments button.active { color: var(--text); background: #30343a; }
  .runtime-note { margin: 2px 0 11px; padding: 8px; display: flex; align-items: flex-start; gap: 6px; border-left: 2px solid #52739f; color: #9faab8; background: #1b2027; font-size: 8px; line-height: 1.45; }
  .hook-audit { margin-top: 8px; overflow: hidden; border: 1px solid var(--line); border-radius: 5px; background: #202225; }
  .hook-audit > header { min-height: 39px; padding: 6px 8px; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid var(--line); }
  .hook-audit > header > span { min-width: 0; flex: 1; display: flex; flex-direction: column; }
  .hook-audit > header strong { font-size: 8px; }
  .hook-audit > header small { color: var(--muted); font-size: 7.5px; }
  .hook-audit > header > i { min-width: 20px; padding: 2px 5px; border-radius: 3px; color: #9ba9bc; background: #2d3239; font: normal 7px var(--mono); text-align: center; }
  .hook-execution + .hook-execution { border-top: 1px solid var(--line); }
  .hook-execution summary { min-height: 45px; padding: 6px 8px; display: flex; align-items: center; gap: 7px; cursor: pointer; list-style: none; }
  .hook-execution summary::-webkit-details-marker { display: none; }
  .hook-execution-state { width: 22px; height: 22px; flex: 0 0 22px; display: grid; place-items: center; border-radius: 4px; color: #e0a4aa; background: #482b30; }
  .hook-execution-state.completed { color: #a9d9b5; background: #294331; }
  .hook-execution-copy { min-width: 0; flex: 1; display: flex; flex-direction: column; }
  .hook-execution-copy strong, .hook-execution-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .hook-execution-copy strong { font-size: 8px; }
  .hook-execution-copy small { color: var(--muted); font: 7px var(--mono); }
  .hook-execution summary > i { max-width: 34%; overflow: hidden; text-overflow: ellipsis; color: #929dab; font: normal 7px var(--mono); white-space: nowrap; }
  .hook-execution-detail { padding: 0 8px 8px 37px; color: var(--muted); font: 7.5px/1.45 var(--mono); }
  .hook-execution-detail pre { max-height: 180px; margin: 6px 0 0; padding: 7px; overflow: auto; border: 1px solid #343940; border-radius: 4px; color: #bec4cb; background: #181a1d; white-space: pre-wrap; overflow-wrap: anywhere; }
  .hook-audit-empty { min-height: 74px; display: flex; align-items: center; justify-content: center; gap: 7px; color: var(--muted); font-size: 8px; }
  .configuration-editor footer { display: flex; justify-content: flex-end; gap: 6px; }
  .configuration-editor footer button { height: 26px; padding: 0 9px; display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--line-strong); border-radius: 3px; background: #2d3035; font-size: 8px; cursor: pointer; }
  .configuration-editor footer button.primary { border-color: #416aa9; color: white; background: #3665ac; }
  .configuration-editor footer button:disabled { opacity: .45; cursor: default; }
</style>
