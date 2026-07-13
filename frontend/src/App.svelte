<script lang="ts">
  import {
    Bot,
    Check,
    ChevronDown,
    ChevronRight,
    CircleAlert,
    Database,
    FileCode2,
    FileDiff,
    Library,
    Menu,
    MessageSquarePlus,
    PanelLeftClose,
    Play,
    Plus,
    RefreshCw,
    Search,
    ScrollText,
    SendHorizontal,
    Settings,
    ShieldAlert,
    Sparkles,
    Square,
    Terminal,
    Undo2,
    UserRound,
    X,
  } from "@lucide/svelte";
  import { onMount } from "svelte";
  import {
    onHostEvent,
    sendCommand,
    type AppSnapshot,
    type EventEnvelope,
    type Mode,
    type ToolRun,
    type WorkspaceSkill,
  } from "./lib/protocol";

  let snapshot: AppSnapshot | null = null;
  let prompt = "";
  let sidebarOpen = true;
  let settingsOpen = false;
  let skillsOpen = false;
  let toolsExpanded = new Set<string>();
  let endpoint = "";
  let model = "";
  let nodePath = "";
  let apiKey = "";
  let autoApproveReadOnly = true;
  let error = "";
  let threadSearch = "";

  onMount(() => {
    if (window.matchMedia("(max-width: 680px)").matches) sidebarOpen = false;
    const unsubscribe = onHostEvent(handleEvent);
    sendCommand("bootstrap");
    return unsubscribe;
  });

  function handleEvent(event: EventEnvelope) {
    if (event.type === "snapshot") {
      snapshot = event.payload as AppSnapshot;
      endpoint = snapshot.settings.endpoint;
      model = snapshot.settings.model;
      nodePath = snapshot.settings.nodePath;
      autoApproveReadOnly = snapshot.settings.autoApproveReadOnly;
      return;
    }
    if (event.type === "error") {
      error = String((event.payload as { message?: string })?.message ?? "Unexpected error");
      return;
    }
    if (event.type === "messageDelta" && snapshot) {
      const data = event.payload as { id: string; delta: string };
      const message = snapshot.messages.find((item) => item.id === data.id);
      if (message) message.content += data.delta;
      snapshot = { ...snapshot, messages: [...snapshot.messages] };
      return;
    }
    if (event.type === "stateChanged" && snapshot) {
      snapshot = { ...snapshot, ...(event.payload as Partial<AppSnapshot>) };
    }
  }

  function submit() {
    const text = prompt.trim();
    if (!text || !snapshot || isBusy()) return;
    prompt = "";
    skillsOpen = false;
    sendCommand("sendMessage", { text, mode: snapshot.mode });
  }

  function setMode(mode: Mode) {
    if (!snapshot) return;
    snapshot = { ...snapshot, mode };
    sendCommand("setMode", { mode });
  }

  function toggleTool(id: string) {
    const next = new Set(toolsExpanded);
    next.has(id) ? next.delete(id) : next.add(id);
    toolsExpanded = next;
  }

  function saveSettings() {
    sendCommand("saveSettings", { endpoint, model, nodePath, apiKey, autoApproveReadOnly });
    apiKey = "";
    settingsOpen = false;
  }

  function approve(tool: ToolRun, approved: boolean) {
    sendCommand("resolveApproval", { toolId: tool.id, approved });
  }

  function openDiff(tool: ToolRun) {
    sendCommand("openDiff", { toolId: tool.id });
  }

  function revertChange(tool: ToolRun) {
    sendCommand("revertChange", { toolId: tool.id });
  }

  function updateContext() {
    sendCommand(snapshot?.context.state === "indexing" ? "getContextStatus" : "indexWorkspace");
  }

  function toggleSkill(skill: WorkspaceSkill) {
    if (isBusy()) return;
    sendCommand("toggleSkill", { skillId: skill.id, selected: !skill.selected });
  }

  function selectedSkillCount() {
    return snapshot?.customization.skills.filter((skill) => skill.selected).length ?? 0;
  }

  function formatTime(timestamp: number) {
    return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(timestamp);
  }

  function isBusy() {
    return snapshot?.runState === "running" || snapshot?.runState === "awaiting_approval";
  }

  function visibleThreads() {
    const query = threadSearch.trim().toLowerCase();
    return query ? snapshot?.threads.filter((thread) => thread.title.toLowerCase().includes(query)) ?? [] : snapshot?.threads ?? [];
  }

  function startNewThread() {
    sendCommand("newThread");
    skillsOpen = false;
    if (window.matchMedia("(max-width: 680px)").matches) sidebarOpen = false;
  }

  function selectThread(threadId: string) {
    sendCommand("selectThread", { threadId });
    skillsOpen = false;
    if (window.matchMedia("(max-width: 680px)").matches) sidebarOpen = false;
  }
</script>

<svelte:window onkeydown={(event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") submit();
  if (event.key === "Escape") { settingsOpen = false; skillsOpen = false; }
}} />

{#if !snapshot}
  <main class="loading"><Sparkles size={18} /><span>Loading CodeAgent</span></main>
{:else}
  <main class="shell" class:sidebar-closed={!sidebarOpen}>
    <aside class="sidebar" aria-label="Tasks">
      <div class="side-head">
        <div class="brand"><span class="brand-mark"><Bot size={16} /></span><strong>CodeAgent</strong></div>
        <button class="icon-button" title="Close tasks" onclick={() => sidebarOpen = false}><PanelLeftClose size={16} /></button>
      </div>
      <button class="primary-command" onclick={startNewThread}><MessageSquarePlus size={16} /><span>New task</span></button>
      <label class="search"><Search size={14} /><input bind:value={threadSearch} aria-label="Search tasks" placeholder="Search" /></label>
      <div class="thread-list">
        {#each visibleThreads() as thread}
          <button class:active={thread.active} class="thread" onclick={() => selectThread(thread.id)}>
            <span>{thread.title}</span><time>{formatTime(thread.updatedAt)}</time>
          </button>
        {/each}
      </div>
    </aside>

    <section class="workspace">
      <header class="topbar">
        <div class="title-row">
          {#if !sidebarOpen}<button class="icon-button" title="Open tasks" onclick={() => sidebarOpen = true}><Menu size={17} /></button>{/if}
          <div><strong>{snapshot.threads.find((item) => item.active)?.title ?? "New task"}</strong><small>{snapshot.projectName}</small></div>
        </div>
        <div class="top-actions">
          <button class="context-pill {snapshot.context.state}" onclick={updateContext}>
            <Database size={14} /><span>{snapshot.context.label}</span>
          </button>
          <button class="icon-button" title="Settings" onclick={() => settingsOpen = true}><Settings size={17} /></button>
        </div>
      </header>

      <div class="conversation">
        {#if snapshot.messages.length === 0}
          <div class="empty-state">
            <div class="empty-icon"><Bot size={24} /></div>
            <h1>What should we work on?</h1>
            <div class="suggestions">
              <button onclick={() => prompt = "Explain the architecture of this project"}><FileCode2 size={15} />Explain this project</button>
              <button onclick={() => prompt = "Find a useful bug and fix it with tests"}><CircleAlert size={15} />Fix a bug</button>
              <button onclick={() => prompt = "Run the tests and investigate any failures"}><Play size={15} />Check the build</button>
            </div>
          </div>
        {:else}
          <div class="message-list">
            {#each snapshot.messages as message}
              <article class="message {message.role}">
                <div class="avatar">{#if message.role === "user"}<UserRound size={15} />{:else}<Bot size={15} />{/if}</div>
                <div class="message-body"><div class="message-label">{message.role === "user" ? "You" : "CodeAgent"}</div><div class="message-text">{message.content}</div></div>
              </article>
            {/each}

            {#each snapshot.tools as tool}
              <section class="tool-card {tool.status}">
                <button class="tool-header" onclick={() => toggleTool(tool.id)}>
                  <span class="tool-icon">{#if tool.name.includes("terminal")}<Terminal size={15} />{:else if tool.name.includes("context")}<Database size={15} />{:else}<FileCode2 size={15} />{/if}</span>
                  <span class="tool-copy"><strong>{tool.name}</strong><small>{tool.summary}</small></span>
                  <span class="tool-status">{tool.status}</span>
                  {#if toolsExpanded.has(tool.id)}<ChevronDown size={15} />{:else}<ChevronRight size={15} />{/if}
                </button>
                {#if toolsExpanded.has(tool.id) && tool.detail}<pre>{tool.detail}</pre>{/if}
                {#if tool.changePath}
                  <div class="change-actions">
                    <span>{tool.changePath}</span>
                    <button title="Open IDE diff" onclick={() => openDiff(tool)}><FileDiff size={14} />Diff</button>
                    {#if tool.canRevert}<button class="revert" title="Revert this change" onclick={() => revertChange(tool)}><Undo2 size={14} />Revert</button>{/if}
                  </div>
                {/if}
                {#if tool.status === "approval"}
                  <div class="approval"><ShieldAlert size={16} /><span>Approval required</span><button onclick={() => approve(tool, false)}><X size={14} />Reject</button><button class="approve" onclick={() => approve(tool, true)}><Check size={14} />Approve</button></div>
                {/if}
              </section>
            {/each}
          </div>
        {/if}
      </div>

      {#if error}<div class="error-banner"><CircleAlert size={15} /><span>{error}</span><button title="Dismiss" onclick={() => error = ""}><X size={14} /></button></div>{/if}

      <footer class="composer-wrap">
        <div class="composer" class:busy={isBusy()}>
          {#if snapshot.attachments.length > 0}
            <div class="context-chips">
              {#each snapshot.attachments as item}
                <span><FileCode2 size={13} /><span title={item.path}>{item.label}</span><button title="Remove context" onclick={() => sendCommand("removeContext", { id: item.id })}><X size={12} /></button></span>
              {/each}
            </div>
          {/if}
          <textarea bind:value={prompt} placeholder={snapshot.mode === "agent" ? "Describe a coding task" : "Ask about this project"} onkeydown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); submit(); }
          }}></textarea>
          <div class="composer-bar">
            <div class="left-controls">
              <button class="icon-button" title="Add context" onclick={() => sendCommand("pickContext")}><Plus size={17} /></button>
              <div class="skill-control">
                <button class="icon-button" class:active={skillsOpen} title="Skills" aria-label="Skills" onclick={() => skillsOpen = !skillsOpen}>
                  <Library size={16} />
                  {#if selectedSkillCount() > 0}<span class="control-count">{selectedSkillCount()}</span>{/if}
                </button>
                {#if skillsOpen}
                  <div class="skills-popover">
                    <header>
                      <div><strong>Skills</strong><small>{snapshot.customization.rules.length} rules active</small></div>
                      <button class="icon-button" title="Refresh rules and skills" onclick={() => sendCommand("refreshCustomization")}><RefreshCw size={14} /></button>
                    </header>
                    {#if snapshot.customization.rules.length > 0}
                      <div class="rules-status" title={snapshot.customization.rules.map((rule) => rule.path).join("\n")}>
                        <ScrollText size={14} /><span>{snapshot.customization.rules.map((rule) => rule.name).join(", ")}</span>
                      </div>
                    {/if}
                    <div class="skills-list">
                      {#if snapshot.customization.skills.length === 0}
                        <div class="skills-empty">No repository skills</div>
                      {:else}
                        {#each snapshot.customization.skills as skill}
                          <label class="skill-option" title={skill.path}>
                            <input
                              type="checkbox"
                              checked={skill.selected}
                              disabled={isBusy() || (!skill.selected && selectedSkillCount() >= snapshot.customization.maxSelectedSkills)}
                              onchange={() => toggleSkill(skill)}
                            />
                            <span><strong>{skill.name}</strong><small>{skill.description}</small></span>
                          </label>
                        {/each}
                      {/if}
                    </div>
                  </div>
                {/if}
              </div>
              <div class="segmented" aria-label="Mode">
                <button class:active={snapshot.mode === "agent"} onclick={() => setMode("agent")}>Agent</button>
                <button class:active={snapshot.mode === "ask"} onclick={() => setMode("ask")}>Ask</button>
              </div>
              <button class="model-button" onclick={() => settingsOpen = true}>{snapshot.settings.model}<ChevronDown size={13} /></button>
            </div>
            {#if isBusy()}
              <button class="send-button stop" title="Stop" onclick={() => sendCommand("cancelRun")}><Square size={14} fill="currentColor" /></button>
            {:else}
              <button class="send-button" title="Send" disabled={!prompt.trim()} onclick={submit}><SendHorizontal size={16} /></button>
            {/if}
          </div>
        </div>
      </footer>
    </section>

    {#if settingsOpen}
      <div class="modal-backdrop" role="presentation" onclick={(event) => event.currentTarget === event.target && (settingsOpen = false)}>
        <dialog open class="settings-panel" aria-label="Settings">
          <header><div><h2>Settings</h2><p>Model and local runtime</p></div><button class="icon-button" title="Close" onclick={() => settingsOpen = false}><X size={17} /></button></header>
          <div class="settings-content">
            <label><span>API endpoint</span><input bind:value={endpoint} /></label>
            <label><span>Model</span><input bind:value={model} /></label>
            <label><span>API key</span><input type="password" bind:value={apiKey} placeholder={snapshot.settings.apiKeyConfigured ? "Configured" : "Not configured"} /></label>
            <label><span>Node.js executable</span><input bind:value={nodePath} /></label>
            <label class="check-setting"><input type="checkbox" bind:checked={autoApproveReadOnly} /><span><strong>Auto-run read-only tools</strong><small>Context retrieval, search, and file reads</small></span></label>
            <div class="runtime-status"><Database size={16} /><div><strong>ContextEngine</strong><span>{snapshot.context.label}</span></div></div>
          </div>
          <footer><button onclick={() => settingsOpen = false}>Cancel</button><button class="save" onclick={saveSettings}>Save</button></footer>
        </dialog>
      </div>
    {/if}
  </main>
{/if}
