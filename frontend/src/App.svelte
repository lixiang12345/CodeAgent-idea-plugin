<script lang="ts">
  import {
    AtSign,
    Bot,
    Braces,
    Check,
    ChevronDown,
    ChevronLeft,
    ChevronRight,
    CircleAlert,
    CircleCheck,
    Copy,
    Database,
    Download,
    Ellipsis,
    ExternalLink,
    File,
    FileCode2,
    FileDiff,
    FilePen,
    Folder,
    GitBranch,
    GitCommitHorizontal,
    Globe,
    History,
    ImagePlus,
    Images,
    Layers,
    Library,
    ListChecks,
    Menu,
    Maximize2,
    MessageCircle,
    MessageSquarePlus,
    Minus,
    Paperclip,
    Pin,
    Play,
    Plug,
    Plus,
    RefreshCw,
    Search,
    SendHorizontal,
    Settings,
    Share2,
    ShieldAlert,
    Sparkles,
    Square,
    SquareTerminal,
    Undo2,
    UserRound,
    Trash2,
    Upload,
    WandSparkles,
    Workflow,
    X,
  } from "@lucide/svelte";
  import { onMount } from "svelte";
  import MermaidCanvas from "./lib/MermaidCanvas.svelte";
  import {
    onHostEvent,
    sendCommand,
    type AppSnapshot,
    type EventEnvelope,
    type GitFile,
    type GitSnapshot,
    type ImageCanvasSnapshot,
    type Mode,
    type ToolRun,
    type WorkspaceRule,
    type WorkspaceSkill,
  } from "./lib/protocol";

  const settingsGroups = [
    { label: "", items: ["Home"] },
    { label: "Integrations", items: ["Services", "MCP Servers"] },
    { label: "Preferences", items: ["Rules & Guidelines", "API Keys", "Commands", "Skills", "Hooks", "Agents", "Plugins"] },
    { label: "IDE & Workspace", items: ["User Experience", "Feature Flags", "Beta"] },
    { label: "Account", items: ["Account", "Subscription"] },
  ];
  const betaSections = new Set(["Commands", "Skills", "Agents", "Plugins"]);

  let snapshot: AppSnapshot | null = null;
  let prompt = "";
  type WorkspaceView = "chat" | "settings" | "mermaid" | "git" | "tasks" | "images";
  let currentView: WorkspaceView = "chat";
  let settingsSection = "Home";
  let settingsNavigationOpen = true;
  let threadDrawerOpen = false;
  let modeMenuOpen = false;
  let skillsOpen = false;
  let tasksOpen = true;
  let mermaidSource = "flowchart LR\n  IDE[IDEA Plugin] --> Backend[Agent Backend]";
  let mermaidTitle = "Agent architecture";
  let mermaidMode: "diagram" | "code" = "diagram";
  let mermaidScale = 1;
  let ruleEditorOpen = false;
  let ruleFileName = "";
  let ruleContent = "";
  let ruleTrigger: WorkspaceRule["trigger"] = "always";
  let ruleDescription = "";
  let editingExistingRule = false;
  let toolsExpanded = new Set<string>();
  let backendUrl = "";
  let nodePath = "";
  let backendToken = "";
  let autoApproveReadOnly = true;
  let error = "";
  let notice = "";
  let threadSearch = "";
  let moreMenuOpen = false;
  let taskFilter: "all" | "pending" | "running" | "done" = "all";
  let newTaskName = "";
  let commitMessage = "";
  let gitLoading = false;
  let gitSnapshot: GitSnapshot = { available: false, branch: "", repository: "", unstaged: [], staged: [] };
  let imageCanvas: ImageCanvasSnapshot = { directory: "", images: [], truncated: false };
  let imageSettingsOpen = false;
  let imageColumns = 3;
  let imageZoom = 100;

  onMount(() => {
    const unsubscribe = onHostEvent(handleEvent);
    sendCommand("bootstrap");
    return unsubscribe;
  });

  function handleEvent(event: EventEnvelope) {
    if (event.type === "snapshot") {
      snapshot = event.payload as AppSnapshot;
      backendUrl = snapshot.settings.backendUrl;
      nodePath = snapshot.settings.nodePath;
      autoApproveReadOnly = snapshot.settings.autoApproveReadOnly;
      return;
    }
    if (event.type === "error") {
      error = String((event.payload as { message?: string })?.message ?? "Unexpected error");
      return;
    }
    if (event.type === "notice") {
      notice = String((event.payload as { message?: string })?.message ?? "Done");
      return;
    }
    if (event.type === "gitSnapshot") {
      gitSnapshot = event.payload as GitSnapshot;
      gitLoading = false;
      return;
    }
    if (event.type === "gitCommitSuggested") {
      commitMessage = String((event.payload as { message?: string })?.message ?? "");
      return;
    }
    if (event.type === "imageCanvas") {
      imageCanvas = event.payload as ImageCanvasSnapshot;
      return;
    }
    if (event.type === "messageDelta" && snapshot) {
      const data = event.payload as { id: string; delta: string };
      const message = snapshot.messages.find((item) => item.id === data.id);
      if (message) message.content += data.delta;
      snapshot = { ...snapshot, messages: [...snapshot.messages] };
      return;
    }
    if (event.type === "stateChanged" && snapshot) snapshot = { ...snapshot, ...(event.payload as Partial<AppSnapshot>) };
  }

  function submit() {
    const text = prompt.trim();
    if (!text || !snapshot || isBusy()) return;
    prompt = "";
    skillsOpen = false;
    modeMenuOpen = false;
    sendCommand("sendMessage", { text, mode: snapshot.mode });
  }

  function setMode(mode: Mode) {
    if (!snapshot) return;
    snapshot = { ...snapshot, mode };
    modeMenuOpen = false;
    sendCommand("setMode", { mode });
  }

  function toggleTool(id: string) {
    const next = new Set(toolsExpanded);
    next.has(id) ? next.delete(id) : next.add(id);
    toolsExpanded = next;
  }

  function setAllTools(expanded: boolean) {
    toolsExpanded = expanded && snapshot ? new Set(snapshot.tools.map((tool) => tool.id)) : new Set();
  }

  function saveSettings() {
    sendCommand("saveSettings", { backendUrl, nodePath, backendToken, autoApproveReadOnly });
    backendToken = "";
  }

  function copyThread() {
    sendCommand("copyThread");
    notice = "Thread copied as Markdown";
  }

  function toggleAutoRun() {
    autoApproveReadOnly = !autoApproveReadOnly;
    sendCommand("saveSettings", { backendUrl, nodePath, backendToken: "", autoApproveReadOnly });
  }

  function updateContext() {
    sendCommand(snapshot?.context.state === "indexing" ? "getContextStatus" : "indexWorkspace");
  }

  function toggleSkill(skill: WorkspaceSkill) {
    if (!isBusy()) sendCommand("toggleSkill", { skillId: skill.id, selected: !skill.selected });
  }

  function selectedSkillCount() {
    return snapshot?.customization.skills.filter((skill) => skill.selected).length ?? 0;
  }

  function isBusy() {
    return snapshot?.runState === "running" || snapshot?.runState === "awaiting_approval";
  }

  function visibleThreads() {
    const query = threadSearch.trim().toLowerCase();
    return query ? snapshot?.threads.filter((thread) => thread.title.toLowerCase().includes(query)) ?? [] : snapshot?.threads ?? [];
  }

  function formatTime(timestamp: number) {
    return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(timestamp);
  }

  function startNewThread() {
    sendCommand("newThread", { mode: snapshot?.mode ?? "agent" });
    threadDrawerOpen = false;
  }

  function selectThread(threadId: string) {
    sendCommand("selectThread", { threadId });
    threadDrawerOpen = false;
  }

  function openSettings(section = "Home") {
    settingsSection = section;
    settingsNavigationOpen = true;
    currentView = "settings";
  }

  function openWorkspaceView(view: WorkspaceView) {
    currentView = view;
    moreMenuOpen = false;
    if (view === "git") {
      gitLoading = true;
      sendCommand("refreshGit");
    }
    if (view === "images") sendCommand("refreshImageCanvas");
  }

  function filteredTasks() {
    if (!snapshot || taskFilter === "all") return snapshot?.tasks ?? [];
    if (taskFilter === "pending") return snapshot.tasks.filter((task) => task.state === "not_started");
    if (taskFilter === "running") return snapshot.tasks.filter((task) => task.state === "in_progress");
    return snapshot.tasks.filter((task) => task.state === "completed" || task.state === "cancelled");
  }

  function addTask() {
    const name = newTaskName.trim();
    if (!name) return;
    sendCommand("addTask", { name });
    newTaskName = "";
  }

  function gitPaths(files: GitFile[]) {
    return files.map((file) => file.path);
  }

  function chooseSettingsSection(section: string) {
    settingsSection = section;
    ruleEditorOpen = false;
    settingsNavigationOpen = false;
  }

  function toolTitle(tool: ToolRun) {
    const titles: Record<string, string> = {
      codebase_retrieval: "CodeAgent Context Engine",
      read_file: "Read",
      list_files: "View",
      search_text: "Grep Search",
      write_file: "Creating",
      replace_text: "Editing",
      run_terminal: "Terminal",
      open_file: "Open in Editor",
      diagnostics: "Diagnostics",
      git_history: "Git Commit Retrieval",
      view_tasks: "View Task List",
      add_tasks: "Add Tasks",
      update_tasks: "Update Task List",
      reorg_tasks: "Reorganize Task List",
      render_mermaid: "Render Mermaid",
    };
    return titles[tool.name] ?? tool.name.replaceAll("_", " ");
  }

  function statusLabel(status: ToolRun["status"]) {
    if (status === "completed") return "done";
    if (status === "approval") return "approve";
    return status;
  }

  function changeTools() {
    return snapshot?.tools.filter((tool) => Boolean(tool.changePath)) ?? [];
  }

  function openMermaid(tool: ToolRun) {
    if (!tool.detail) return;
    mermaidSource = tool.detail;
    mermaidTitle = tool.summary || "Mermaid diagram";
    mermaidMode = "diagram";
    mermaidScale = 1;
    currentView = "mermaid";
  }

  function editRule(rule?: WorkspaceRule) {
    ruleFileName = rule?.path.replace(".codeagent/rules/", "") ?? "new-rule.md";
    ruleContent = rule?.content ?? "# New rule\n\nDescribe the project guidance here.";
    ruleTrigger = rule?.trigger ?? "always";
    ruleDescription = rule?.description ?? "";
    editingExistingRule = Boolean(rule);
    ruleEditorOpen = true;
  }

  function saveRule() {
    if (!ruleFileName.trim() || !ruleContent.trim()) return;
    sendCommand("saveRule", { fileName: ruleFileName, content: ruleContent, trigger: ruleTrigger, description: ruleDescription });
    ruleEditorOpen = false;
  }
</script>

<svelte:window onkeydown={(event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") submit();
  if (event.key === "Escape") {
    threadDrawerOpen = false;
    modeMenuOpen = false;
    skillsOpen = false;
    moreMenuOpen = false;
  }
}} />

{#if !snapshot}
  <main class="loading"><Sparkles size={16} /><span>Starting CodeAgent</span></main>
{:else}
  <main class="shell" class:settings-active={currentView === "settings"} class:canvas-active={currentView === "mermaid"}>
    <header class="app-header">
      <div class="app-title"><span class="app-logo"><Braces size={13} /></span><strong>CodeAgent</strong></div>
      <div class="header-actions">
        {#if currentView === "chat"}
          <button class="icon-button" title="Threads" aria-label="Threads" onclick={() => threadDrawerOpen = true}><Menu size={15} /></button>
          <button class="icon-button" title="New thread" aria-label="New thread" onclick={startNewThread}><Plus size={16} /></button>
          <button class="icon-button" title="Copy thread as Markdown" aria-label="Copy thread as Markdown" onclick={copyThread}><Share2 size={14} /></button>
          <div class="more-control">
            <button class="icon-button" class:active={moreMenuOpen} title="Workspace tools" aria-label="Workspace tools" onclick={() => moreMenuOpen = !moreMenuOpen}><Ellipsis size={16} /></button>
            {#if moreMenuOpen}
              <div class="workspace-menu">
                <button onclick={() => openWorkspaceView("tasks")}><ListChecks size={14} /><span><strong>Tasks</strong><small>Plan and run agent work</small></span></button>
                <button onclick={() => openWorkspaceView("git")}><GitBranch size={14} /><span><strong>Git</strong><small>Review, stage, and commit</small></span></button>
                <button onclick={() => openWorkspaceView("images")}><Images size={14} /><span><strong>Image Canvas</strong><small>Browse project images</small></span></button>
                <button onclick={() => openSettings()}><Settings size={14} /><span><strong>Settings</strong><small>Services and workspace</small></span></button>
              </div>
            {/if}
          </div>
        {:else}
          <button class="icon-button" title="Back to chat" aria-label="Back to chat" onclick={() => currentView = "chat"}><X size={16} /></button>
        {/if}
      </div>
    </header>

    {#if currentView === "chat"}
      <section class="chat-view">
        <header class="thread-header">
          <button class="icon-button compact" title="Threads" onclick={() => threadDrawerOpen = true}><Menu size={14} /></button>
          <strong class="thread-title">{snapshot.threads.find((thread) => thread.active)?.title ?? "New thread"}</strong>
          <span class="ready"><CircleCheck size={12} />{snapshot.runState === "idle" ? "Ready" : snapshot.runState.replaceAll("_", " ")}</span>
          <button class="context-meter" title={snapshot.context.label} onclick={updateContext}>Context <b>{snapshot.context.state === "ready" ? "42%" : "--"}</b></button>
          <button class="icon-button compact" title="History" onclick={() => threadDrawerOpen = true}><History size={14} /></button>
          <button class="icon-button compact" title="Settings" onclick={() => openSettings()}><Settings size={14} /></button>
        </header>

        <div class="repository-strip">
          <button title="Open Git workspace" onclick={() => openWorkspaceView("git")}><GitBranch size={12} /><span>{snapshot.projectName}</span></button>
          <button title="Repository guidelines" onclick={() => openSettings("Rules & Guidelines")}><Library size={12} /><span>Guidelines</span></button>
          <span class="repository-spacer"></span>
          <button class="index-state {snapshot.context.state}" title={snapshot.context.label} onclick={updateContext}>
            <Database size={12} /><span>{snapshot.context.state === "ready" ? "Indexed" : snapshot.context.label}</span>
          </button>
        </div>

        <div class="conversation">
          {#if snapshot.messages.length === 0}
            <div class="empty-state">
              <span class="empty-logo"><Braces size={18} /></span>
              <h1>Start a new task</h1>
              <p>Ask about the project or let Agent make an approved change.</p>
              <button onclick={() => prompt = "Explain how this project is structured"}><FileCode2 size={14} />Explain this project</button>
              <button onclick={() => prompt = "Find a bug and fix it with a regression test"}><CircleAlert size={14} />Fix a bug with tests</button>
              <button onclick={() => prompt = "Run the most relevant tests and investigate failures"}><Play size={14} />Check the build</button>
            </div>
          {:else}
            <div class="message-list">
              {#each snapshot.messages.filter((message) => message.role === "user") as message}
                <article class="user-message">
                  <header><span>You</span><time>{formatTime(message.createdAt)}</time><UserRound size={14} /></header>
                  <div>{message.content}</div>
                </article>
              {/each}

              {#if snapshot.tools.length > 0 || snapshot.messages.some((message) => message.role === "assistant")}
                <section class="agent-turn">
                  <header class="agent-meta">
                    <span class="agent-avatar"><Braces size={12} /></span><strong>CodeAgent</strong>
                    <span>{formatTime(snapshot.messages.find((message) => message.role === "assistant")?.createdAt ?? Date.now())}</span>
                    <div class="message-actions"><button title="Copy response"><Copy size={13} /></button><button title="Retry"><RefreshCw size={13} /></button></div>
                  </header>

                  {#if snapshot.tools.length > 0}
                    <div class="pass-summary">
                      <SquareTerminal size={14} />
                      <strong>Agent tool pass</strong>
                      <span>{snapshot.tools.filter((tool) => tool.status === "completed").length} / {snapshot.tools.length}</span>
                      <button onclick={() => setAllTools(true)}>Expand all</button>
                      <button onclick={() => setAllTools(false)}>Collapse all</button>
                    </div>
                    {#if isBusy()}<div class="thinking"><Bot size={13} />Thinking - coordinating project tools</div>{/if}

                    <div class="tool-list">
                      {#each snapshot.tools as tool}
                        <section class="tool-card {tool.status}">
                          <button class="tool-header" onclick={() => toggleTool(tool.id)} aria-expanded={toolsExpanded.has(tool.id)}>
                            <span class="tool-icon">
                              {#if tool.name === "codebase_retrieval"}<Database size={14} />
                              {:else if tool.name === "run_terminal"}<SquareTerminal size={14} />
                              {:else if tool.name === "list_files"}<Folder size={14} />
                              {:else if tool.name === "search_text"}<Search size={14} />
                              {:else if tool.name === "diagnostics"}<CircleAlert size={14} />
                              {:else if tool.name === "git_history"}<GitCommitHorizontal size={14} />
                              {:else if tool.name.includes("tasks")}<ListChecks size={14} />
                              {:else if tool.name === "render_mermaid"}<Workflow size={14} />
                              {:else if tool.name === "write_file" || tool.name === "replace_text"}<FilePen size={14} />
                              {:else if tool.name === "open_file"}<ExternalLink size={14} />
                              {:else}<File size={14} />{/if}
                            </span>
                            <span class="tool-copy"><strong>{toolTitle(tool)}</strong><small>{tool.summary}</small></span>
                            <span class="tool-status">{statusLabel(tool.status)}</span>
                            {#if toolsExpanded.has(tool.id)}<ChevronDown size={14} />{:else}<ChevronRight size={14} />{/if}
                          </button>
                          {#if toolsExpanded.has(tool.id)}
                            <div class="tool-detail">
                              <span class="detail-label">Details</span>
                              {#if tool.name === "render_mermaid" && tool.detail}
                                <MermaidCanvas source={tool.detail} compact />
                                <button class="secondary-action" onclick={() => openMermaid(tool)}><Maximize2 size={13} />Open Canvas</button>
                              {:else if tool.detail}<pre>{tool.detail}</pre>{:else}<p>No additional output.</p>{/if}
                              {#if tool.changePath}
                                <div class="file-actions"><span>{tool.changePath}</span><button onclick={() => sendCommand("openDiff", { toolId: tool.id })}><FileDiff size={13} />View Diff</button>{#if tool.canRevert}<button onclick={() => sendCommand("revertChange", { toolId: tool.id })}><Undo2 size={13} />Undo</button>{/if}</div>
                              {/if}
                              {#if tool.name === "run_terminal"}<button class="secondary-action" onclick={() => sendCommand("openTerminal")}><SquareTerminal size={13} />Open Terminal</button>{/if}
                            </div>
                          {/if}
                          {#if tool.status === "approval"}
                            <div class="approval"><ShieldAlert size={14} /><span>Approval required</span><button onclick={() => sendCommand("resolveApproval", { toolId: tool.id, approved: false })}>Skip</button><button class="approve" onclick={() => sendCommand("resolveApproval", { toolId: tool.id, approved: true })}>Approve</button></div>
                          {/if}
                        </section>
                      {/each}
                    </div>
                  {/if}

                  {#if snapshot.tasks.length > 0}
                    <section class="task-panel">
                      <button class="task-panel-header" onclick={() => tasksOpen = !tasksOpen} aria-expanded={tasksOpen}>
                        <ListChecks size={14} /><strong>Task List</strong>
                        <span>{snapshot.tasks.filter((task) => task.state === "completed").length}/{snapshot.tasks.length}</span>
                        {#if tasksOpen}<ChevronDown size={14} />{:else}<ChevronRight size={14} />{/if}
                      </button>
                      {#if tasksOpen}
                        <div class="task-items">
                          {#each snapshot.tasks as task, index}
                            <label class:completed={task.state === "completed"} class:active={task.state === "in_progress"}>
                              <input type="checkbox" checked={task.state === "completed"} onchange={() => sendCommand("setTaskState", { taskId: task.id, state: task.state === "completed" ? "not_started" : "completed" })} />
                              <span><i>{index + 1}</i>{task.name}</span>
                              {#if task.state === "in_progress"}<b>running</b>{/if}
                            </label>
                          {/each}
                        </div>
                        <footer><button onclick={() => openWorkspaceView("tasks")}>Open Tasks</button><button onclick={() => sendCommand("clearCompletedTasks")}>Clear completed</button></footer>
                      {/if}
                    </section>
                  {/if}

                  {#each snapshot.messages.filter((message) => message.role === "assistant") as message}
                    {#if message.content}<div class="assistant-message">{message.content}</div>{/if}
                  {/each}
                </section>
              {/if}
            </div>
          {/if}
        </div>

        {#if error}<div class="error-banner"><CircleAlert size={14} /><span>{error}</span><button title="Dismiss" onclick={() => error = ""}><X size={13} /></button></div>{/if}
        {#if notice}<div class="notice-banner"><Check size={13} /><span>{notice}</span><button title="Dismiss" onclick={() => notice = ""}><X size={13} /></button></div>{/if}

        <footer class="composer-wrap">
          {#if changeTools().length > 0}
            <div class="change-summary"><span><FileDiff size={13} />{changeTools().length} {changeTools().length === 1 ? "file" : "files"} changed</span><button onclick={() => sendCommand("reviewChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Review</button><button onclick={() => sendCommand("keepChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Keep All</button><button class="discard" onclick={() => sendCommand("discardChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Discard All</button></div>
          {/if}
          {#if snapshot.attachments.length > 0}
            <div class="context-chips">
              {#each snapshot.attachments as item}
                <span><File size={12} /><b title={item.path}>{item.label}</b><button title="Remove" onclick={() => sendCommand("removeContext", { id: item.id })}><X size={11} /></button></span>
              {/each}
            </div>
          {/if}
          <div class="composer" class:busy={isBusy()}>
            <textarea bind:value={prompt} placeholder="Type a message or command..." onkeydown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); submit(); }
            }}></textarea>
            <div class="composer-toolbar">
              <div class="mode-control">
                <button class="mode-button" onclick={() => modeMenuOpen = !modeMenuOpen}><span>{snapshot.mode === "ask" ? "Ask" : snapshot.mode === "chat" ? "Chat" : "Agent"}</span><ChevronDown size={12} /></button>
                {#if modeMenuOpen}
                  <div class="mode-menu">
                    <button class:active={snapshot.mode === "agent"} onclick={() => setMode("agent")}><Bot size={14} /><span><strong>Agent</strong><small>Plan, edit, and run tools</small></span>{#if snapshot.mode === "agent"}<Check size={13} />{/if}</button>
                    <button class:active={snapshot.mode === "chat"} onclick={() => setMode("chat")}><MessageCircle size={14} /><span><strong>Chat</strong><small>Collaborate with read-only context</small></span>{#if snapshot.mode === "chat"}<Check size={13} />{/if}</button>
                    <button class:active={snapshot.mode === "ask"} onclick={() => setMode("ask")}><Search size={14} /><span><strong>Ask</strong><small>Investigate and explain</small></span>{#if snapshot.mode === "ask"}<Check size={13} />{/if}</button>
                  </div>
                {/if}
              </div>
              <span class="model-select" title="Model is selected by the backend">auto-detect <ChevronDown size={11} /></span>
              <span class="toolbar-spacer"></span>
              <div class="skill-control">
                <button class:active={skillsOpen} title="Skills" onclick={() => skillsOpen = !skillsOpen}><Layers size={14} />{#if selectedSkillCount() > 0}<i>{selectedSkillCount()}</i>{/if}</button>
                {#if skillsOpen}
                  <div class="skills-popover">
                    <header><div><strong>Skills</strong><small>{snapshot.customization.rules.length} active rules</small></div><button title="Refresh" onclick={() => sendCommand("refreshCustomization")}><RefreshCw size={13} /></button></header>
                    {#each snapshot.customization.skills as skill}
                      <label><input type="checkbox" checked={skill.selected} disabled={isBusy() || (!skill.selected && selectedSkillCount() >= snapshot.customization.maxSelectedSkills)} onchange={() => toggleSkill(skill)} /><span><strong>{skill.name}</strong><small>{skill.description}</small></span></label>
                    {:else}<p>No repository skills found.</p>{/each}
                  </div>
                {/if}
              </div>
              <button title="Mention project file" onclick={() => sendCommand("pickContext")}><AtSign size={14} /></button>
              <button title="Slash commands" onclick={() => prompt = prompt || "/"}><SquareTerminal size={14} /></button>
              <button title="Attach file or image" onclick={() => sendCommand("pickContext")}><Paperclip size={14} /></button>
              <button title="Prompt enhancer is not connected" disabled><WandSparkles size={14} /></button>
            </div>
            <div class="send-row">
              <button class="auto-toggle" class:active={autoApproveReadOnly} title="Automatically run read-only tools" onclick={toggleAutoRun}>Auto {autoApproveReadOnly ? "ON" : "OFF"}</button>
              {#if isBusy()}<button class="send-button stop" title="Stop" onclick={() => sendCommand("cancelRun")}><Square size={13} fill="currentColor" /></button>
              {:else}<button class="send-button" title="Send" disabled={!prompt.trim()} onclick={submit}><SendHorizontal size={15} /></button>{/if}
            </div>
          </div>
        </footer>
      </section>
    {:else if currentView === "settings"}
      <section class="settings-view">
        <header class="settings-header"><button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><ChevronLeft size={15} /></button><strong>Settings</strong><span></span><button class="audit-button" disabled title="Audit is not connected">Audit</button></header>
        <div class="settings-layout" class:navigation-open={settingsNavigationOpen}>
          <nav class="settings-navigation">
            {#each settingsGroups as group}
              {#if group.label}<div class="settings-group-label">{group.label}</div>{/if}
              {#each group.items as item}
                <button class:active={settingsSection === item} onclick={() => chooseSettingsSection(item)}>
                  {#if item === "Home"}<Braces size={14} />{:else if item === "Services"}<Plug size={14} />{:else if item === "MCP Servers"}<GitCommitHorizontal size={14} />{:else if item.includes("Rules")}<Library size={14} />{:else if item === "API Keys"}<ShieldAlert size={14} />{:else if item === "Skills"}<Layers size={14} />{:else if item === "Hooks"}<Workflow size={14} />{:else}<Settings size={14} />{/if}
                  <span>{item}</span>{#if betaSections.has(item)}<i>Beta</i>{/if}
                </button>
              {/each}
            {/each}
          </nav>

          <div class="settings-content">
            <button class="settings-nav-toggle" onclick={() => settingsNavigationOpen = true}><Menu size={14} />All settings</button>
            <div class="breadcrumb">Home <span>/</span> {settingsSection}</div>
            {#if settingsSection === "Home"}
              <h1>Project Home</h1><p class="settings-lead">CodeAgent for {snapshot.projectName}</p>
              <div class="settings-stats"><article><strong>{snapshot.context.files ?? "--"}</strong><span>Files indexed</span></article><article><strong>{snapshot.threads.length}</strong><span>Threads</span></article></div>
              <section class="settings-block"><header><strong>Codebase Indexing</strong><button onclick={updateContext}>Rebuild Index</button></header><p>{snapshot.context.label}</p><div class="progress"><span class:ready={snapshot.context.state === "ready"}></span></div></section>
              <section class="settings-block"><header><strong>Agent Backend</strong><button onclick={() => chooseSettingsSection("Services")}>Configure</button></header><p>{backendUrl}</p></section>
              <section class="settings-block"><header><strong>Workspace</strong></header><div class="workspace-row"><Folder size={15} /><span>{snapshot.projectName}</span><i>active</i></div></section>
            {:else if settingsSection === "Services" || settingsSection === "API Keys"}
              <h1>Services</h1><p class="settings-lead">Connect this IDE capability gateway to the deployed Agent backend.</p>
              <section class="settings-form settings-block">
                <label><span>Backend URL</span><input bind:value={backendUrl} /></label>
                <label><span>Backend token</span><input type="password" bind:value={backendToken} placeholder={snapshot.settings.backendTokenConfigured ? "Configured" : "Not configured"} /></label>
                <label><span>Node.js executable</span><input bind:value={nodePath} /></label>
                <label class="toggle-row"><input type="checkbox" bind:checked={autoApproveReadOnly} /><span><strong>Auto-run read-only tools</strong><small>Context retrieval, search, and file reads</small></span></label>
                <footer><button class="primary" onclick={saveSettings}>Save settings</button></footer>
              </section>
            {:else if settingsSection === "Rules & Guidelines"}
              {#if ruleEditorOpen}
                <div class="rule-editor-title"><button class="icon-button compact" title="Back to rules" onclick={() => ruleEditorOpen = false}><ChevronLeft size={14} /></button><div><h1>Rule: {ruleFileName}</h1><p class="settings-lead">Markdown guidance stored in the current repository.</p></div></div>
                <section class="settings-block rule-editor">
                  <label><span>File name</span><input bind:value={ruleFileName} disabled={editingExistingRule} /></label>
                  <label><span>Description</span><input bind:value={ruleDescription} maxlength="240" placeholder="When should the Agent use this rule?" /></label>
                  <fieldset><legend>Trigger</legend><div class="trigger-control"><button class:active={ruleTrigger === "always"} onclick={() => ruleTrigger = "always"}>Always</button><button class:active={ruleTrigger === "manual"} onclick={() => ruleTrigger = "manual"}>Manual</button><button class:active={ruleTrigger === "agent"} onclick={() => ruleTrigger = "agent"}>Agent</button></div></fieldset>
                  <label><span>Rule content</span><textarea bind:value={ruleContent} spellcheck="false"></textarea></label>
                  <footer><button onclick={() => ruleEditorOpen = false}>Cancel</button><button class="primary" disabled={!ruleFileName.trim() || !ruleContent.trim()} onclick={saveRule}>Save rule</button></footer>
                </section>
              {:else}
                <div class="section-title"><div><h1>Rules & Guidelines</h1><p class="settings-lead">Repository instructions validated by the IDEA capability gateway.</p></div><button onclick={() => editRule()}><Plus size={13} />New Rule</button></div>
                <section class="settings-block rule-list">
                  {#each snapshot.customization.rules as rule}
                    <div><Library size={14} /><button class="rule-copy" onclick={() => editRule(rule)}><strong>{rule.name}</strong><small>{rule.description || rule.path}</small></button><i>{rule.trigger}</i>{#if rule.trigger === "manual"}<label title="Enable for this thread"><input type="checkbox" checked={rule.selected} onchange={() => sendCommand("toggleRule", { ruleId: rule.id, selected: !rule.selected })} /></label>{/if}<button class="icon-button compact" title="Edit rule" onclick={() => editRule(rule)}><FilePen size={13} /></button></div>
                  {:else}<p>No repository rules found.</p>{/each}
                </section>
              {/if}
            {:else if settingsSection === "Skills"}
              <h1>Skills <em>Beta</em></h1><p class="settings-lead">Select task methods from the message composer.</p>
              <section class="settings-block list-block">{#each snapshot.customization.skills as skill}<div><Layers size={14} /><span><strong>{skill.name}</strong><small>{skill.description}</small></span><i>{skill.selected ? "On" : "Off"}</i></div>{:else}<p>No repository skills found.</p>{/each}</section>
            {:else if settingsSection === "MCP Servers" || settingsSection === "Services"}
              <h1>{settingsSection}</h1>
            {:else}
              <h1>{settingsSection}{#if betaSections.has(settingsSection)} <em>Beta</em>{/if}</h1>
              <p class="settings-lead">This surface is unavailable until its backend capability is connected.</p>
              <section class="settings-block unavailable"><Plug size={20} /><strong>Not connected</strong><p>No operation will be simulated from this page.</p></section>
            {/if}
          </div>
        </div>
      </section>
    {:else if currentView === "git"}
      <section class="workspace-view">
        <header class="canvas-header"><button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><ChevronLeft size={15} /></button><GitBranch size={14} /><strong>Git</strong><button class="icon-button compact" title="Refresh Git status" onclick={() => { gitLoading = true; sendCommand("refreshGit"); }}><RefreshCw size={13} /></button></header>
        <div class="workspace-body">
          {#if gitLoading}
            <div class="workspace-empty"><RefreshCw size={19} /><strong>Reading repository status</strong></div>
          {:else if gitSnapshot.error || !gitSnapshot.available}
            <div class="workspace-empty"><GitBranch size={20} /><strong>Git unavailable</strong><p>{gitSnapshot.error ?? "This project is not a Git repository."}</p></div>
          {:else}
            <div class="workspace-context"><span><GitBranch size={13} />{gitSnapshot.branch}</span><small>repo: {gitSnapshot.repository}</small></div>
            <section class="workspace-section">
              <header><div><strong>Unstaged</strong><small>{gitSnapshot.unstaged.length} files</small></div><button disabled={gitSnapshot.unstaged.length === 0} onclick={() => sendCommand("stageGit", { paths: gitPaths(gitSnapshot.unstaged) })}>Stage All</button></header>
              <div class="workspace-list">
                {#each gitSnapshot.unstaged as file}
                  <div class="workspace-file"><File size={14} /><span><strong>{file.path}</strong><small>{file.status}</small></span><button onclick={() => sendCommand("stageGit", { paths: [file.path] })}>Stage</button><button class="icon-button compact" title="Open working tree Diff" onclick={() => sendCommand("openGitDiff", { path: file.path, staged: false })}><FileDiff size={13} /></button></div>
                {:else}<div class="workspace-list-empty">No unstaged changes</div>{/each}
              </div>
            </section>
            <section class="workspace-section">
              <header><div><strong>Reviewed and Approved</strong><small>{gitSnapshot.staged.length} files</small></div></header>
              <div class="workspace-list">
                {#each gitSnapshot.staged as file}
                  <div class="workspace-file"><File size={14} /><span><strong>{file.path}</strong><small>{file.status}</small></span><button onclick={() => sendCommand("unstageGit", { paths: [file.path] })}>Unstage</button><button class="icon-button compact" title="Open staged Diff" onclick={() => sendCommand("openGitDiff", { path: file.path, staged: true })}><FileDiff size={13} /></button></div>
                {:else}<div class="workspace-list-empty">No staged files to commit</div>{/each}
              </div>
            </section>
            <section class="workspace-section commit-section">
              <header><div><strong>Commit message</strong><small>Commits only the staged files above</small></div></header>
              <textarea bind:value={commitMessage} placeholder="Enter commit message..." maxlength="4000"></textarea>
              <footer><button disabled={gitSnapshot.staged.length === 0} onclick={() => sendCommand("suggestCommitMessage", { files: gitSnapshot.staged })}><Sparkles size={13} />Generate message</button><button class="primary" disabled={gitSnapshot.staged.length === 0 || !commitMessage.trim()} onclick={() => sendCommand("commitGit", { message: commitMessage })}><GitCommitHorizontal size={13} />Commit</button></footer>
            </section>
          {/if}
        </div>
      </section>
    {:else if currentView === "tasks"}
      <section class="workspace-view">
        <header class="canvas-header"><button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><ChevronLeft size={15} /></button><ListChecks size={14} /><strong>Tasks</strong><span class="workspace-count">{snapshot.tasks.filter((task) => task.state === "completed").length}/{snapshot.tasks.length}</span></header>
        <div class="workspace-body task-workspace">
          <div class="workspace-actions">
            <div class="segmented-control"><button class:active={taskFilter === "all"} onclick={() => taskFilter = "all"}>All</button><button class:active={taskFilter === "pending"} onclick={() => taskFilter = "pending"}>Pending</button><button class:active={taskFilter === "running"} onclick={() => taskFilter = "running"}>Running</button><button class:active={taskFilter === "done"} onclick={() => taskFilter = "done"}>Done</button></div>
            <button class="primary" disabled={isBusy() || snapshot.tasks.every((task) => task.state === "completed" || task.state === "cancelled")} onclick={() => sendCommand("runAllTasks")}><Play size={13} />Run All</button>
          </div>
          <div class="task-import-actions"><button onclick={() => sendCommand("exportTasks")} disabled={snapshot.tasks.length === 0}><Upload size={12} />Export</button><button onclick={() => sendCommand("importTasks")}><Download size={12} />Import</button><button onclick={() => sendCommand("clearCompletedTasks")} disabled={!snapshot.tasks.some((task) => task.state === "completed" || task.state === "cancelled")}>Clear Completed</button><button class="danger" onclick={() => sendCommand("clearTasks")} disabled={snapshot.tasks.length === 0}><Trash2 size={12} />Clear All</button></div>
          <form class="task-add" onsubmit={(event) => { event.preventDefault(); addTask(); }}><input bind:value={newTaskName} maxlength="240" placeholder="Add a new task" /><button class="primary" disabled={!newTaskName.trim()}><Plus size={13} />Add New</button></form>
          <div class="task-workspace-list">
            {#each filteredTasks() as task, index}
              <div class="task-workspace-row" class:completed={task.state === "completed"} class:running={task.state === "in_progress"}>
                <button class="task-state" title="Toggle complete" onclick={() => sendCommand("setTaskState", { taskId: task.id, state: task.state === "completed" ? "not_started" : "completed" })}>{#if task.state === "completed"}<CircleCheck size={15} />{:else}<span></span>{/if}</button>
                <i>{index + 1}</i><span><strong>{task.name}</strong><small>{task.state.replaceAll("_", " ")}</small></span>
                <button class="icon-button compact" title="Run task" disabled={isBusy() || task.state === "completed"} onclick={() => sendCommand("runTask", { taskId: task.id })}><Play size={13} /></button>
                <button class="icon-button compact danger" title="Delete task" onclick={() => sendCommand("deleteTask", { taskId: task.id })}><Trash2 size={13} /></button>
              </div>
            {:else}<div class="workspace-empty"><ListChecks size={20} /><strong>Get Started with Tasks</strong><p>Break Agent work into runnable steps.</p></div>{/each}
          </div>
        </div>
      </section>
    {:else if currentView === "images"}
      <section class="workspace-view image-workspace">
        <header class="canvas-header"><button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><ChevronLeft size={15} /></button><Images size={14} /><strong>Image Canvas</strong><button class="icon-button compact" title="Refresh images" onclick={() => sendCommand("refreshImageCanvas")}><RefreshCw size={13} /></button></header>
        <div class="workspace-body">
          <div class="image-toolbar"><span><Folder size={13} /><b>{imageCanvas.directory || "No directory selected"}</b></span><button onclick={() => sendCommand("browseImageDirectory")}>Browse</button><button class:active={imageSettingsOpen} title="View settings" onclick={() => imageSettingsOpen = !imageSettingsOpen}><Settings size={13} /></button></div>
          {#if imageSettingsOpen}<div class="image-settings"><label>Columns <input type="number" min="1" max="5" bind:value={imageColumns} /></label><label>Zoom <input type="range" min="50" max="200" bind:value={imageZoom} /><span>{imageZoom}%</span></label></div>{/if}
          {#if imageCanvas.error}
            <div class="workspace-empty"><CircleAlert size={20} /><strong>Image Canvas unavailable</strong><p>{imageCanvas.error}</p></div>
          {:else if imageCanvas.images.length === 0}
            <div class="workspace-empty"><ImagePlus size={20} /><strong>No images found</strong><p>Choose a project directory containing PNG, JPEG, GIF, or WebP files.</p><button class="primary" onclick={() => sendCommand("browseImageDirectory")}>Choose Directory</button></div>
          {:else}
            <div class="image-grid" style={`--image-columns:${imageColumns};--image-zoom:${imageZoom / 100}`}>
              {#each imageCanvas.images as image}
                <article class="image-item"><div class="image-thumb"><img src={image.dataUrl} alt={image.name} /></div><div class="image-meta"><strong title={image.path}>{image.name}</strong><small>{Math.max(1, Math.round(image.sizeBytes / 1024))} KB</small><div><button class="primary" onclick={() => sendCommand("attachImage", { path: image.path })}><AtSign size={12} />Mention</button><button onclick={() => sendCommand("openImage", { path: image.path })}><ExternalLink size={12} />Open</button></div></div></article>
              {/each}
            </div>
            {#if imageCanvas.truncated}<p class="image-limit">Showing the first images within the 10 MB preview budget.</p>{/if}
          {/if}
        </div>
      </section>
    {:else}
      <section class="mermaid-view">
        <header class="canvas-header">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><ChevronLeft size={15} /></button>
          <strong>{mermaidTitle}</strong>
          <div class="canvas-tabs"><button class:active={mermaidMode === "diagram"} onclick={() => mermaidMode = "diagram"}>Diagram</button><button class:active={mermaidMode === "code"} onclick={() => mermaidMode = "code"}>Code</button></div>
          <button class="icon-button compact" title="Zoom out" disabled={mermaidMode === "code"} onclick={() => mermaidScale = Math.max(.5, mermaidScale - .1)}><Minus size={14} /></button>
          <span class="zoom-value">{Math.round(mermaidScale * 100)}%</span>
          <button class="icon-button compact" title="Zoom in" disabled={mermaidMode === "code"} onclick={() => mermaidScale = Math.min(2, mermaidScale + .1)}><Plus size={14} /></button>
          <button class="fit-button" disabled={mermaidMode === "code"} onclick={() => mermaidScale = 1}><Maximize2 size={13} />Fit</button>
          <button class="icon-button compact" title="Open Mermaid source in IDE editor" onclick={() => sendCommand("openMermaidEditor", { title: mermaidTitle, code: mermaidSource })}><ExternalLink size={13} /></button>
        </header>
        <div class="canvas-body">
          {#if mermaidMode === "diagram"}<MermaidCanvas source={mermaidSource} scale={mermaidScale} />{:else}<pre>{mermaidSource}</pre>{/if}
        </div>
      </section>
    {/if}

    {#if currentView !== "chat" && error}<div class="global-banner error-banner"><CircleAlert size={14} /><span>{error}</span><button title="Dismiss" onclick={() => error = ""}><X size={13} /></button></div>{/if}
    {#if currentView !== "chat" && notice}<div class="global-banner notice-banner"><Check size={13} /><span>{notice}</span><button title="Dismiss" onclick={() => notice = ""}><X size={13} /></button></div>{/if}

    {#if threadDrawerOpen}
      <button class="drawer-backdrop" aria-label="Close threads" onclick={() => threadDrawerOpen = false}></button>
      <aside class="thread-drawer">
        <header><strong>Threads</strong><button class="icon-button" title="Close" onclick={() => threadDrawerOpen = false}><X size={15} /></button></header>
        <button class="new-thread" onclick={startNewThread}><MessageSquarePlus size={14} />New {snapshot.mode === "agent" ? "Agent" : snapshot.mode === "chat" ? "Chat" : "Ask"}</button>
        <label class="thread-search"><Search size={13} /><input bind:value={threadSearch} placeholder="Search threads" /></label>
        <div class="thread-list">
          {#each visibleThreads() as thread}
            <div class="thread-row" class:active={thread.active}>
              <button class="thread-select" onclick={() => selectThread(thread.id)}><span><strong>{thread.title}</strong><small>{formatTime(thread.updatedAt)}</small></span><i>{thread.mode}</i></button>
              <button class="icon-button compact" class:pinned={thread.pinned} title={thread.pinned ? "Unpin thread" : "Pin thread"} onclick={() => sendCommand("toggleThreadPinned", { threadId: thread.id })}><Pin size={12} /></button>
              <button class="icon-button compact delete-thread" title="Delete thread" onclick={() => sendCommand("deleteThread", { threadId: thread.id })}><Trash2 size={12} /></button>
            </div>
          {/each}
        </div>
        <footer><button onclick={() => sendCommand("importThread")}><Download size={13} />Import</button><button onclick={copyThread}><Share2 size={13} />Export</button></footer>
      </aside>
    {/if}
  </main>
{/if}
