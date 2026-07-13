<script lang="ts">
  import { onMount } from "svelte";
  import Icon from "./lib/Icon.svelte";
  import MermaidCanvas from "./lib/MermaidCanvas.svelte";
  import { ICON_NAMES } from "./lib/icons";
  import { MENTION_KINDS, SLASH_COMMANDS, TOOL_CATALOG } from "./lib/tools-catalog";
  import {
    onHostEvent,
    sendCommand,
    type AppSnapshot,
    type EventEnvelope,
    type GitFile,
    type GitSnapshot,
    type ImageCanvasSnapshot,
    type MessageDelta,
    type Mode,
    type ToolRun,
    type WorkspaceRule,
    type WorkspaceSkill,
  } from "./lib/protocol";

  type SettingsItem = { id: string; label: string; icon: string; badge?: string };
  type SettingsGroup = { label: string; items: SettingsItem[] };

  const settingsGroups: SettingsGroup[] = [
    { label: "", items: [{ id: "Home", label: "Home", icon: "plugin-icon" }] },
    {
      label: "Integrations",
      items: [
        { id: "Services", label: "Services", icon: "plug" },
        { id: "MCP Servers", label: "MCP Servers", icon: "mcp" },
      ],
    },
    {
      label: "Preferences",
      items: [
        { id: "Rules & Guidelines", label: "Rules & Guidelines", icon: "pencil-ruler" },
        { id: "API Keys", label: "API Keys", icon: "key-round" },
        { id: "Commands", label: "Commands", icon: "square-terminal", badge: "Beta" },
        { id: "Skills", label: "Skills", icon: "wand-sparkles", badge: "Beta" },
        { id: "Hooks", label: "Hooks", icon: "workflow" },
        { id: "Agents", label: "Agents", icon: "bot", badge: "Beta" },
        { id: "Plugins", label: "Plugins", icon: "layers", badge: "Beta" },
      ],
    },
    {
      label: "IDE & Workspace",
      items: [
        { id: "User Experience", label: "User Experience", icon: "sliders-horizontal" },
        { id: "Feature Flags", label: "Feature Flags", icon: "flag" },
        { id: "Beta", label: "Beta", icon: "flask-conical" },
      ],
    },
    {
      label: "Account",
      items: [
        { id: "Account", label: "Account", icon: "user-round" },
        { id: "Subscription", label: "Subscription", icon: "credit-card" },
      ],
    },
  ];

  const toolIcons: Record<string, string> = {
    codebase_retrieval: "augment-logo",
    read_file: "file",
    list_files: "folder",
    search_text: "search",
    write_file: "file-plus",
    replace_text: "file-pen",
    run_terminal: "square-terminal",
    open_file: "external-link",
    diagnostics: "circle-alert",
    git_history: "git-commit-horizontal",
    view_tasks: "list-checks",
    add_tasks: "list-checks",
    update_tasks: "list-checks",
    reorg_tasks: "list-checks",
    render_mermaid: "workflow",
  };

  let snapshot: AppSnapshot | null = null;
  let prompt = "";
  type WorkspaceView = "chat" | "settings" | "mermaid" | "git" | "tasks" | "images" | "tools" | "icons" | "edits" | "feedback";
  let currentView: WorkspaceView = "chat";
  let settingsSection = "Home";
  let settingsNavigationOpen = true;
  let threadDrawerOpen = false;
  let modeMenuOpen = false;
  let modelMenuOpen = false;
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
  let threadOptOpen = false;
  let taskFilter: "all" | "pending" | "running" | "done" = "all";
  let newTaskName = "";
  let commitMessage = "";
  let gitLoading = false;
  let gitSnapshot: GitSnapshot = { available: false, branch: "", repository: "", unstaged: [], staged: [] };
  let imageCanvas: ImageCanvasSnapshot = { directory: "", images: [], truncated: false };
  let imageSettingsOpen = false;
  let imageColumns = 3;
  let imageZoom = 100;
  let chatZoom = 100;
  let slashOpen = false;
  let atOpen = false;
  let iconFilter = "";
  let toolFilter = "";
  let feedbackText = "";
  let renaming = false;
  let renameTitle = "";

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
      const data = event.payload as MessageDelta;
      const message = snapshot.messages.find((item) => item.id === data.id);
      if (message) {
        message.content += data.delta;
        message.turnIndex = data.turnIndex;
      }
      snapshot = { ...snapshot, messages: [...snapshot.messages] };
      return;
    }
    if (event.type === "stateChanged" && snapshot) snapshot = { ...snapshot, ...(event.payload as Partial<AppSnapshot>) };
  }

  function closeMenus() {
    moreMenuOpen = false;
    threadOptOpen = false;
    modeMenuOpen = false;
    modelMenuOpen = false;
    skillsOpen = false;
    slashOpen = false;
    atOpen = false;
  }

  function submit() {
    const text = prompt.trim();
    if (!text || !snapshot) return;
    prompt = "";
    closeMenus();
    sendCommand(isBusy() ? "queueMessage" : "sendMessage", { text, mode: snapshot.mode });
  }

  function setMode(mode: Mode) {
    if (!snapshot) return;
    snapshot = { ...snapshot, mode };
    modeMenuOpen = false;
    sendCommand("setMode", { mode });
  }

  function selectModel(modelId: string) {
    if (!snapshot || isBusy()) return;
    snapshot = { ...snapshot, models: { ...snapshot.models, selectedModel: modelId } };
    modelMenuOpen = false;
    sendCommand("selectModel", { modelId });
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
  }

  function exportThread() {
    sendCommand("exportThread");
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

  function startNewThread(mode?: Mode) {
    sendCommand("newThread", { mode: mode ?? snapshot?.mode ?? "agent" });
    threadDrawerOpen = false;
    closeMenus();
  }

  function selectThread(threadId: string) {
    sendCommand("selectThread", { threadId });
    threadDrawerOpen = false;
  }

  function openSettings(section = "Home") {
    settingsSection = section;
    settingsNavigationOpen = true;
    currentView = "settings";
    closeMenus();
  }

  function openWorkspaceView(view: WorkspaceView) {
    currentView = view;
    closeMenus();
    if (view === "git") {
      gitLoading = true;
      sendCommand("refreshGit");
    }
    if (view === "images") sendCommand("refreshImageCanvas");
  }

  function beginRename() {
    renameTitle = activeThread()?.title ?? "";
    renaming = true;
    closeMenus();
  }

  function commitRename() {
    const id = activeThread()?.id;
    const title = renameTitle.trim();
    if (!id || !title) {
      renaming = false;
      return;
    }
    sendCommand("renameThread", { threadId: id, title });
    renaming = false;
  }

  function insertSlash(command: string) {
    prompt = `${command} `;
    slashOpen = false;
  }

  function insertMention(kind: string) {
    atOpen = false;
    if (kind === "file") {
      sendCommand("pickContext");
      return;
    }
    if (kind === "rule") {
      openSettings("Rules & Guidelines");
      return;
    }
    prompt = `${prompt}${prompt.endsWith(" ") || !prompt ? "" : " "}@`;
  }

  function filteredSlash() {
    const q = prompt.startsWith("/") ? prompt.slice(1).toLowerCase() : "";
    return SLASH_COMMANDS.filter((item) => !q || item.command.slice(1).includes(q) || item.description.toLowerCase().includes(q));
  }

  function filteredTools() {
    const q = toolFilter.trim().toLowerCase();
    return TOOL_CATALOG.filter((tool) => !q || tool.id.includes(q) || tool.name.toLowerCase().includes(q) || tool.desc.toLowerCase().includes(q));
  }

  function filteredIcons() {
    const q = iconFilter.trim().toLowerCase();
    return ICON_NAMES.filter((name) => !q || name.includes(q)).slice(0, 240);
  }

  function insertToolSeed(toolId: string) {
    const entry = TOOL_CATALOG.find((tool) => tool.id === toolId);
    if (!entry) return;
    prompt = entry.connected
      ? `Use the ${entry.name} tool to help with: `
      : `[${entry.name}] is not connected in this build. `;
    currentView = "chat";
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

  function toolIcon(tool: ToolRun) {
    return toolIcons[tool.name] ?? "file";
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

  function activeThread() {
    return snapshot?.threads.find((thread) => thread.active);
  }

  function modeLabel(mode: Mode | string = snapshot?.mode ?? "agent") {
    if (mode === "ask") return "Ask";
    if (mode === "chat") return "Chat";
    return "Agent";
  }

  function adjustChatZoom(delta: number) {
    chatZoom = Math.min(140, Math.max(85, chatZoom + delta));
  }

  function seedSlash() {
    if (!prompt.startsWith("/")) prompt = "/";
    slashOpen = true;
    atOpen = false;
    modeMenuOpen = false;
    modelMenuOpen = false;
    skillsOpen = false;
  }

  function seedMention() {
    atOpen = true;
    slashOpen = false;
    modeMenuOpen = false;
    modelMenuOpen = false;
    skillsOpen = false;
  }

  type TimelineItem =
    | { kind: "user"; message: AppSnapshot["messages"][number] }
    | { kind: "assistant"; message: AppSnapshot["messages"][number]; lead: boolean }
    | { kind: "tools"; turnIndex: number; tools: ToolRun[] }
    | { kind: "tasks" };

  function conversationTimeline(): TimelineItem[] {
    if (!snapshot) return [];
    const items: TimelineItem[] = [];
    const messages = snapshot.messages;
    const lastUserIndex = messages.map((message) => message.role).lastIndexOf("user");
    let assistantLeadShown = false;
    const insertedToolTurns = new Set<number>();

    function insertToolTurn(turnIndex: number) {
      if (insertedToolTurns.has(turnIndex)) return;
      const tools = snapshot?.tools.filter((tool) => (tool.turnIndex ?? 0) === turnIndex) ?? [];
      if (tools.length > 0) items.push({ kind: "tools", turnIndex, tools });
      insertedToolTurns.add(turnIndex);
    }

    for (let index = 0; index < messages.length; index += 1) {
      const message = messages[index];
      if (message.role === "user") {
        items.push({ kind: "user", message });
        continue;
      }
      if (message.role !== "assistant") continue;

      const afterLastUser = lastUserIndex >= 0 && index > lastUserIndex;
      const turnIndex = message.turnIndex;
      if (afterLastUser && turnIndex !== undefined) {
        snapshot.tools
          .map((tool) => tool.turnIndex ?? 0)
          .filter((toolTurn) => toolTurn < turnIndex)
          .sort((left, right) => left - right)
          .forEach(insertToolTurn);
      }

      items.push({ kind: "assistant", message, lead: !assistantLeadShown });
      assistantLeadShown = true;

      if (afterLastUser && turnIndex !== undefined) insertToolTurn(turnIndex);
    }

    snapshot.tools
      .map((tool) => tool.turnIndex ?? 0)
      .sort((left, right) => left - right)
      .forEach(insertToolTurn);
    if (snapshot.tasks.length > 0) items.push({ kind: "tasks" });
    return items;
  }
</script>

<svelte:window onkeydown={(event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") submit();
  if (event.key === "Escape") {
    threadDrawerOpen = false;
    closeMenus();
  }
}} />

{#if !snapshot}
  <main class="loading"><Icon name="plugin-icon" size={18} /><span>Starting CodeAgent</span></main>
{:else}
  <main
    class="shell"
    class:settings-active={currentView === "settings"}
    class:canvas-active={currentView === "mermaid" || currentView === "images" || currentView === "tools" || currentView === "icons" || currentView === "edits" || currentView === "feedback"}
    style={`--chat-zoom:${chatZoom / 100}`}
  >
    <header class="app-header tw-head">
      <div class="app-title">
        <span class="app-logo"><Icon name="plugin-icon" size={14} /></span>
        <strong>CodeAgent</strong>
      </div>
      <div class="header-actions">
        {#if currentView === "chat"}
          <button class="icon-button" title="Threads" aria-label="Threads" onclick={() => { closeMenus(); threadDrawerOpen = true; }}><Icon name="menu" size={15} /></button>
          <button class="icon-button" title="New Thread" aria-label="New Thread" onclick={() => startNewThread()}><Icon name="plus" size={16} /></button>
          <button class="icon-button" title="Share / copy thread" aria-label="Share" onclick={copyThread}><Icon name="share-2" size={14} /></button>
          <div class="more-control">
            <button class="icon-button" class:active={moreMenuOpen} title="More options" aria-label="More options" onclick={() => { moreMenuOpen = !moreMenuOpen; threadOptOpen = false; }}><Icon name="ellipsis" size={16} /></button>
            {#if moreMenuOpen}
              <div class="workspace-menu menu">
                <button onclick={() => { beginRename(); }}><Icon name="square-pen" size={14} /><span>Rename</span></button>
                <button onclick={() => { exportThread(); closeMenus(); }}><Icon name="upload" size={14} /><span>Export conversation</span></button>
                <button onclick={() => { sendCommand("importThread"); closeMenus(); }}><Icon name="download" size={14} /><span>Import conversation</span></button>
                <button onclick={() => { const id = activeThread()?.id; if (id) sendCommand("toggleThreadPinned", { threadId: id }); closeMenus(); }}><Icon name="pin" size={14} /><span>Pin / Unpin</span></button>
                <button class="danger" onclick={() => { const id = activeThread()?.id; if (id) sendCommand("deleteThread", { threadId: id }); closeMenus(); }}><Icon name="trash" size={14} /><span>Delete</span></button>
                <div class="menu-sep"></div>
                <button onclick={() => openWorkspaceView("tasks")}><Icon name="list-checks" size={14} /><span>Agent Tasklist</span></button>
                <button onclick={() => openWorkspaceView("edits")}><Icon name="file-diff" size={14} /><span>Agent Edits</span></button>
                <button onclick={() => openWorkspaceView("git")}><Icon name="git-branch" size={14} /><span>Git Changes</span></button>
                <button onclick={() => openWorkspaceView("images")}><Icon name="layers-2" size={14} /><span>Context Canvas</span></button>
                <button onclick={() => openWorkspaceView("tools")}><Icon name="wrench" size={14} /><span>Tools catalog</span></button>
                <button onclick={() => openWorkspaceView("icons")}><Icon name="sparkles" size={14} /><span>Icon gallery</span></button>
                <button onclick={() => openWorkspaceView("feedback")}><Icon name="message-circle" size={14} /><span>Report Issue</span></button>
                <div class="menu-sep"></div>
                <button onclick={() => openSettings()}><Icon name="settings-2" size={14} /><span>Settings</span></button>
              </div>
            {/if}
          </div>
        {:else}
          <button class="icon-button" title="Back to chat" aria-label="Back to chat" onclick={() => currentView = "chat"}><Icon name="x" size={16} /></button>
        {/if}
      </div>
    </header>

    {#if currentView === "chat"}
      <section class="chat-view">
        <header class="thread-header ch">
          <button class="icon-button compact" title="Threads" onclick={() => { closeMenus(); threadDrawerOpen = true; }}><Icon name="menu" size={14} /></button>
          {#if renaming}
            <form class="rename-form" onsubmit={(event) => { event.preventDefault(); commitRename(); }}>
              <input bind:value={renameTitle} maxlength="48" aria-label="Rename thread" />
              <button type="submit" class="btn sm primary">Save</button>
              <button type="button" class="btn sm" onclick={() => renaming = false}>Cancel</button>
            </form>
          {:else}
            <strong class="thread-title" title="Double-click to rename" ondblclick={beginRename}>{activeThread()?.title ?? "New thread"}</strong>
          {/if}
          <span class="ready chip green"><Icon name="circle-check" size={12} />{snapshot.runState === "idle" ? "Ready" : snapshot.runState.replaceAll("_", " ")}</span>
          <div class="ch-actions">
            <button class="context-meter" title={snapshot.context.label} onclick={updateContext}><Icon name="gauge" size={12} /> Context <b>{snapshot.context.state === "ready" ? "42%" : "--"}</b></button>
            <button class="icon-button compact" title="Chat zoom out" onclick={() => adjustChatZoom(-5)}>−</button>
            <button class="icon-button compact" title="Chat zoom in" onclick={() => adjustChatZoom(5)}>+</button>
            <button class="icon-button compact" title="Share link to session" onclick={copyThread}><Icon name="share-2" size={13} /></button>
            <div class="more-control">
              <button class="icon-button compact" class:active={threadOptOpen} title="Thread options" onclick={() => { threadOptOpen = !threadOptOpen; moreMenuOpen = false; }}><Icon name="ellipsis" size={14} /></button>
              {#if threadOptOpen}
                <div class="workspace-menu menu thread-opt-menu">
                  <button onclick={() => beginRename()}><Icon name="square-pen" size={13} /><span>Rename thread</span></button>
                  <button onclick={() => { const id = activeThread()?.id; if (id) sendCommand("toggleThreadPinned", { threadId: id }); closeMenus(); }}><Icon name="pin" size={13} /><span>Pin / Unpin</span></button>
                  <button onclick={() => { copyThread(); closeMenus(); }}><Icon name="share-2" size={13} /><span>Share link to session</span></button>
                  <button onclick={() => { exportThread(); closeMenus(); }}><Icon name="upload" size={13} /><span>Export conversation</span></button>
                  <button onclick={() => { sendCommand("importThread"); closeMenus(); }}><Icon name="file-input" size={13} /><span>Import conversation</span></button>
                  <button onclick={() => startNewThread(snapshot?.mode)}><Icon name="git-branch" size={13} /><span>Continue in New Chat</span></button>
                  <div class="menu-sep"></div>
                  <button onclick={() => openWorkspaceView("feedback")}><Icon name="flag" size={13} /><span>Report an Issue</span></button>
                  <div class="menu-sep"></div>
                  <button class="danger" onclick={() => { const id = activeThread()?.id; if (id) sendCommand("deleteThread", { threadId: id }); closeMenus(); }}><Icon name="trash-2" size={13} /><span>Delete thread</span></button>
                </div>
              {/if}
            </div>
            <button class="icon-button compact" title="Settings" onclick={() => openSettings()}><Icon name="settings-2" size={14} /></button>
          </div>
        </header>

        <div class="repository-strip multi-repo">
          <button class="chip" title="Open Git workspace" onclick={() => openWorkspaceView("git")}><Icon name="git-branch" size={12} /><span>{snapshot.projectName}</span></button>
          <button class="chip accent" title="Repository guidelines" onclick={() => openSettings("Rules & Guidelines")}><Icon name="book-open" size={12} /><span>Guidelines</span></button>
          <span class="repository-spacer"></span>
          <button class="chip index-state {snapshot.context.state}" title={snapshot.context.label} onclick={updateContext}>
            <Icon name="database" size={12} /><span>{snapshot.context.state === "ready" ? "Indexed" : snapshot.context.label}</span>
          </button>
        </div>

        <div class="conversation" style={`font-size: calc(10.5px * var(--chat-zoom))`}>
          {#if snapshot.messages.length === 0}
            <div class="empty-state">
              <span class="empty-logo"><Icon name="plugin-icon" size={18} /></span>
              <h1>Start a new task</h1>
              <p>Ask about the project or let Agent make an approved change.</p>
              <button onclick={() => prompt = "Explain how this project is structured"}><Icon name="file-text" size={14} />Explain this project</button>
              <button onclick={() => prompt = "Find a bug and fix it with a regression test"}><Icon name="circle-alert" size={14} />Fix a bug with tests</button>
              <button onclick={() => prompt = "Run the most relevant tests and investigate failures"}><Icon name="play" size={14} />Check the build</button>
            </div>
          {:else}
            <div class="message-list">
              {#each conversationTimeline() as item (item.kind === "user" || item.kind === "assistant" ? item.message.id : item.kind === "tools" ? `tools-${item.turnIndex}` : item.kind)}
                {#if item.kind === "user"}
                  <article class="user-message">
                    <header><span>You</span><time>{formatTime(item.message.createdAt)}</time><Icon name="user-round" size={14} /></header>
                    <div>{item.message.content}</div>
                  </article>
                {:else if item.kind === "assistant"}
                  {#if item.lead}
                    <header class="agent-meta agent-meta-standalone">
                      <span class="agent-avatar"><Icon name="plugin-icon" size={12} /></span>
                      <strong>CodeAgent</strong>
                      <span>{formatTime(item.message.createdAt)}</span>
                      <div class="message-actions">
                        <button title="Copy response" onclick={() => { void navigator.clipboard.writeText(item.message.content); notice = "Response copied"; }}><Icon name="copy" size={13} /></button>
                      </div>
                    </header>
                  {/if}
                  {#if item.message.content}
                    <div class="assistant-message" class:post-tool={!item.lead}>{item.message.content}</div>
                  {:else if isBusy() && item.lead}
                    <div class="thinking"><Icon name="bot" size={13} />Thinking…</div>
                  {/if}
                {:else if item.kind === "tools"}
                  <section class="agent-turn tools-turn">
                    <div class="pass-summary">
                      <Icon name="square-terminal" size={14} />
                      <strong>Agent tool pass</strong>
                      <span>{item.tools.filter((tool) => tool.status === "completed").length} / {item.tools.length}</span>
                      <button onclick={() => setAllTools(true)}>Expand all</button>
                      <button onclick={() => setAllTools(false)}>Collapse all</button>
                    </div>
                    {#if isBusy()}<div class="thinking"><Icon name="bot" size={13} />Running tools — model continues after results</div>{/if}
                    <div class="tool-list">
                      {#each item.tools as tool}
                        <section class="tool-card {tool.status}">
                          <button class="tool-header" onclick={() => toggleTool(tool.id)} aria-expanded={toolsExpanded.has(tool.id)}>
                            <span class="tool-icon"><Icon name={toolIcon(tool)} size={14} /></span>
                            <span class="tool-copy"><strong>{toolTitle(tool)}</strong><small>{tool.summary}</small></span>
                            <span class="tool-status">{statusLabel(tool.status)}</span>
                            {#if toolsExpanded.has(tool.id)}<Icon name="chevron-down" size={14} />{:else}<Icon name="chevron-right" size={14} />{/if}
                          </button>
                          {#if toolsExpanded.has(tool.id)}
                            <div class="tool-detail">
                              <span class="detail-label">Details</span>
                              {#if tool.name === "render_mermaid" && tool.detail}
                                <MermaidCanvas source={tool.detail} compact />
                                <button class="secondary-action" onclick={() => openMermaid(tool)}><Icon name="maximize-2" size={13} />Open Canvas</button>
                              {:else if tool.detail}<pre>{tool.detail}</pre>{:else}<p>No additional output.</p>{/if}
                              {#if tool.changePath}
                                <div class="file-actions">
                                  <span>{tool.changePath}</span>
                                  <button onclick={() => sendCommand("openDiff", { toolId: tool.id })}><Icon name="file-diff" size={13} />View Diff</button>
                                  {#if tool.canRevert}<button onclick={() => sendCommand("revertChange", { toolId: tool.id })}><Icon name="undo-2" size={13} />Undo</button>{/if}
                                </div>
                              {/if}
                              {#if tool.name === "run_terminal"}
                                <button class="secondary-action" onclick={() => sendCommand("openTerminal")}><Icon name="square-terminal" size={13} />Open Terminal</button>
                              {/if}
                            </div>
                          {/if}
                          {#if tool.status === "approval"}
                            <div class="approval">
                              <Icon name="circle-alert" size={14} />
                              <span>Approval required</span>
                              <button onclick={() => sendCommand("resolveApproval", { toolId: tool.id, approved: false })}>Skip</button>
                              <button class="approve" onclick={() => sendCommand("resolveApproval", { toolId: tool.id, approved: true })}>Approve</button>
                            </div>
                          {/if}
                        </section>
                      {/each}
                    </div>
                  </section>
                {:else if item.kind === "tasks"}
                  <section class="task-panel">
                    <button class="task-panel-header" onclick={() => tasksOpen = !tasksOpen} aria-expanded={tasksOpen}>
                      <Icon name="list-checks" size={14} />
                      <strong>Task List</strong>
                      <span>{snapshot.tasks.filter((task) => task.state === "completed").length}/{snapshot.tasks.length}</span>
                      {#if tasksOpen}<Icon name="chevron-down" size={14} />{:else}<Icon name="chevron-right" size={14} />{/if}
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
                      <footer>
                        <button onclick={() => openWorkspaceView("tasks")}>Open Tasks</button>
                        <button onclick={() => sendCommand("clearCompletedTasks")}>Clear completed</button>
                      </footer>
                    {/if}
                  </section>
                {/if}
              {/each}
            </div>
          {/if}
        </div>

        {#if error}<div class="error-banner"><Icon name="circle-alert" size={14} /><span>{error}</span><button title="Dismiss" onclick={() => error = ""}><Icon name="x" size={13} /></button></div>{/if}
        {#if notice}<div class="notice-banner"><Icon name="circle-check" size={13} /><span>{notice}</span><button title="Dismiss" onclick={() => notice = ""}><Icon name="x" size={13} /></button></div>{/if}

        <footer class="composer-wrap cw">
          {#if changeTools().length > 0}
            <div class="change-summary edits-bar">
              <span><Icon name="file-diff" size={13} />{changeTools().length} {changeTools().length === 1 ? "file" : "files"} changed</span>
              <button onclick={() => sendCommand("reviewChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Review</button>
              <button onclick={() => sendCommand("keepChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Keep All</button>
              <button class="discard" onclick={() => sendCommand("discardChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Discard All</button>
            </div>
          {/if}
          {#if snapshot.attachments.length > 0}
            <div class="context-chips chips">
              {#each snapshot.attachments as item}
                <span class="chip accent"><Icon name="file" size={12} /><b title={item.path}>{item.label}</b><button title="Remove" onclick={() => sendCommand("removeContext", { id: item.id })}><Icon name="x" size={11} /></button></span>
              {/each}
              <button class="chip" onclick={() => sendCommand("pickContext")}><Icon name="plus" size={12} /> context</button>
            </div>
          {:else}
            <div class="context-chips chips">
              <button class="chip" onclick={() => sendCommand("pickContext")}><Icon name="plus" size={12} /> context</button>
            </div>
          {/if}
          {#if snapshot.messageQueue.length > 0}
            <section class="message-queue">
              <header><Icon name="list-checks" size={12} /><strong>Message Queue</strong><span>{snapshot.messageQueue.length}</span></header>
              {#each snapshot.messageQueue as item, index}
                <div><i>{index + 1}</i><span>{item.text}</span><b>{item.mode}</b><button title="Remove queued message" onclick={() => sendCommand("removeQueuedMessage", { messageId: item.id })}><Icon name="x" size={11} /></button></div>
              {/each}
            </section>
          {/if}
          <div class="composer comp" class:busy={isBusy()}>
            {#if snapshot.mode === "ask"}
              <div class="ask-badge"><Icon name="message-circle" size={12} /> Ask Mode (read-only)<button class="icon-button compact" title="Switch to Agent" onclick={() => setMode("agent")}><Icon name="x" size={11} /></button></div>
            {/if}
            {#if slashOpen}
              <div class="composer-popup slash-menu">
                <header><strong>Slash commands</strong><button class="icon-button compact" title="Close" onclick={() => slashOpen = false}><Icon name="x" size={12} /></button></header>
                {#each filteredSlash() as item}
                  <button onclick={() => insertSlash(item.command)}><strong>{item.command}</strong><small>{item.description}</small></button>
                {:else}
                  <p>No matching commands</p>
                {/each}
              </div>
            {/if}
            {#if atOpen}
              <div class="composer-popup at-menu">
                <header><strong>@ mentions</strong><button class="icon-button compact" title="Close" onclick={() => atOpen = false}><Icon name="x" size={12} /></button></header>
                {#each MENTION_KINDS as item}
                  <button onclick={() => insertMention(item.id)}><Icon name={item.icon} size={13} /><span><strong>{item.label}</strong></span></button>
                {/each}
              </div>
            {/if}
            <textarea
              bind:value={prompt}
              placeholder="Type a message or command..."
              oninput={() => {
                if (prompt.startsWith("/")) {
                  slashOpen = true;
                  atOpen = false;
                } else if (slashOpen && !prompt.startsWith("/")) {
                  slashOpen = false;
                }
              }}
              onkeydown={(event) => {
                if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); submit(); }
                if (event.key === "Escape") { slashOpen = false; atOpen = false; }
              }}
            ></textarea>
            <div class="composer-toolbar abar">
              <div class="mode-control">
                <button class="mode-button dd-btn" onclick={() => { modeMenuOpen = !modeMenuOpen; modelMenuOpen = false; skillsOpen = false; slashOpen = false; atOpen = false; }}>
                  <span class="tag {snapshot.mode}">{modeLabel()}</span>
                  <Icon name="chevron-down" size={12} />
                </button>
                {#if modeMenuOpen}
                  <div class="mode-menu drop">
                    <button class:active={snapshot.mode === "agent"} onclick={() => setMode("agent")}>
                      <span class="tag agent">Agent</span>
                      <span><strong>Agent Mode</strong><small>Edits files and executes workspace workflows autonomously.</small></span>
                      {#if snapshot.mode === "agent"}<Icon name="check" size={13} />{/if}
                    </button>
                    <button class:active={snapshot.mode === "chat"} onclick={() => setMode("chat")}>
                      <span class="tag chat">Chat</span>
                      <span><strong>Chat Mode</strong><small>Ask questions, plan implementations, and chat interactively.</small></span>
                      {#if snapshot.mode === "chat"}<Icon name="check" size={13} />{/if}
                    </button>
                    <div class="menu-sep"></div>
                    <button class:active={snapshot.mode === "ask"} onclick={() => setMode("ask")}>
                      <span class="tag ask">Ask</span>
                      <span><strong>Toggle Ask Mode</strong><small>Locks Agent to read-only exploration mode.</small></span>
                      {#if snapshot.mode === "ask"}<Icon name="check" size={13} />{/if}
                    </button>
                  </div>
                {/if}
              </div>
              <div class="model-control">
                <button
                  class="model-select model-btn"
                  title={snapshot.models.selectedModel ?? snapshot.models.defaultModel ?? snapshot.models.label}
                  disabled={isBusy() || snapshot.models.state !== "ready" || snapshot.models.options.length === 0}
                  onclick={() => { modelMenuOpen = !modelMenuOpen; modeMenuOpen = false; skillsOpen = false; slashOpen = false; atOpen = false; }}
                ><span>{snapshot.models.selectedModel ?? snapshot.models.defaultModel ?? "auto-detect"}</span><Icon name="chevron-down" size={11} /></button>
                {#if modelMenuOpen}
                  <div class="model-menu drop">
                    <header><span>{snapshot.models.provider}</span><small>{snapshot.models.options.length} models</small></header>
                    {#each snapshot.models.options as model}
                      <button class:active={(snapshot.models.selectedModel ?? snapshot.models.defaultModel) === model.id} onclick={() => selectModel(model.id)}>
                        <span><strong>{model.id}</strong>{#if model.ownedBy}<small>{model.ownedBy}</small>{/if}</span>
                        {#if (snapshot.models.selectedModel ?? snapshot.models.defaultModel) === model.id}<Icon name="check" size={13} />{/if}
                      </button>
                    {/each}
                  </div>
                {/if}
              </div>
              <button title="Context Canvas" onclick={() => openWorkspaceView("images")}><Icon name="layers-2" size={14} /></button>
              <button title="@ mention" onclick={seedMention}><Icon name="at-sign" size={14} /></button>
              <button title="Slash commands" onclick={seedSlash}><Icon name="square-terminal" size={14} /></button>
              <button title="Attach file/image" onclick={() => sendCommand("pickContext")}><Icon name="file-input" size={14} /></button>
              <button title="Prompt Enhancer is not connected" disabled><Icon name="sparkles" size={14} /></button>
              <span class="toolbar-spacer sp"></span>
              <div class="skill-control">
                <button class:active={skillsOpen} title="Skills" onclick={() => { skillsOpen = !skillsOpen; modeMenuOpen = false; modelMenuOpen = false; }}>
                  <Icon name="wand-sparkles" size={14} />
                  {#if selectedSkillCount() > 0}<i>{selectedSkillCount()}</i>{/if}
                </button>
                {#if skillsOpen}
                  <div class="skills-popover">
                    <header>
                      <div><strong>Skills</strong><small>{snapshot.customization.rules.length} active rules</small></div>
                      <button title="Refresh" onclick={() => sendCommand("refreshCustomization")}><Icon name="refresh-ccw" size={13} /></button>
                    </header>
                    {#each snapshot.customization.skills as skill}
                      <label>
                        <input type="checkbox" checked={skill.selected} disabled={isBusy() || (!skill.selected && selectedSkillCount() >= snapshot.customization.maxSelectedSkills)} onchange={() => toggleSkill(skill)} />
                        <span><strong>{skill.name}</strong><small>{skill.description}</small></span>
                      </label>
                    {:else}
                      <p>No repository skills found.</p>
                    {/each}
                  </div>
                {/if}
              </div>
            </div>
            <div class="send-row">
              <button class="auto-toggle" class:active={autoApproveReadOnly} title={autoApproveReadOnly ? "Auto ON — read-only tools run without approval" : "Auto OFF — require approval for most commands"} onclick={toggleAutoRun}>Auto {autoApproveReadOnly ? "ON" : "OFF"}</button>
              {#if isBusy()}
                <button class="send-button queue-send" title="Queue message" disabled={!prompt.trim()} onclick={submit}><Icon name="send-horizontal" size={13} /></button>
                <button class="send-button stop" title="Stop" onclick={() => sendCommand("cancelRun")}><Icon name="square" size={13} /></button>
              {:else}
                <button class="send-button" title="Send" disabled={!prompt.trim()} onclick={submit}><Icon name="send-horizontal" size={15} /></button>
              {/if}
            </div>
          </div>
        </footer>
      </section>
    {:else if currentView === "settings"}
      <section class="settings-view">
        <header class="settings-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <strong>Settings</strong>
          <span></span>
          <button class="audit-button" disabled title="Audit is not connected">Audit</button>
        </header>
        <div class="settings-layout" class:navigation-open={settingsNavigationOpen}>
          <nav class="settings-navigation">
            {#each settingsGroups as group}
              {#if group.label}<div class="settings-group-label">{group.label}</div>{/if}
              {#each group.items as item}
                <button class:active={settingsSection === item.id} onclick={() => chooseSettingsSection(item.id)}>
                  <Icon name={item.icon} size={14} />
                  <span>{item.label}</span>
                  {#if item.badge}<i>{item.badge}</i>{/if}
                </button>
              {/each}
            {/each}
          </nav>

          <div class="settings-content">
            <button class="settings-nav-toggle" onclick={() => settingsNavigationOpen = true}><Icon name="menu" size={14} />All settings</button>
            <div class="breadcrumb">Home <span>/</span> {settingsSection}</div>
            {#if settingsSection === "Home"}
              <h1>Project Home</h1>
              <p class="settings-lead">CodeAgent for {snapshot.projectName}</p>
              <div class="settings-stats">
                <article><strong>{snapshot.context.files ?? "--"}</strong><span><Icon name="folders" size={12} /> Files indexed</span></article>
                <article><strong>{snapshot.threads.length}</strong><span><Icon name="wand-sparkles" size={12} /> Threads</span></article>
              </div>
              <section class="settings-block">
                <header><strong>Codebase Indexing</strong><button onclick={updateContext}>Rebuild Index</button></header>
                <p>{snapshot.context.label}</p>
                <div class="progress"><span class:ready={snapshot.context.state === "ready"}></span></div>
              </section>
              <section class="settings-block">
                <header>
                  <span class="service-status {snapshot.backendHealth.state}"></span>
                  <strong>Agent Backend</strong>
                  <i class="backend-state">{snapshot.backendHealth.state}</i>
                  <button onclick={() => sendCommand("checkBackend")}><Icon name="refresh-ccw" size={12} />Check</button>
                  <button onclick={() => chooseSettingsSection("Services")}>Configure</button>
                </header>
                <p>{snapshot.backendHealth.label} · {backendUrl}</p>
              </section>
              <section class="settings-block">
                <header><strong>Workspace</strong></header>
                <div class="workspace-row"><Icon name="folder" size={15} /><span>{snapshot.projectName}</span><i>active</i></div>
              </section>
            {:else if settingsSection === "Services" || settingsSection === "API Keys"}
              <h1>{settingsSection === "API Keys" ? "API Keys" : "Services"}</h1>
              <p class="settings-lead">
                {#if settingsSection === "API Keys"}
                  Backend model credentials stay on the deployed Agent service. Use this page for the IDE gateway token only.
                {:else}
                  Connect this IDE capability gateway to the deployed Agent backend.
                {/if}
              </p>
              <section class="settings-form settings-block">
                <label><span>Backend URL</span><input bind:value={backendUrl} /></label>
                <label><span>Backend token</span><input type="password" bind:value={backendToken} placeholder={snapshot.settings.backendTokenConfigured ? "Configured" : "Not configured"} /></label>
                <label><span>Node.js executable</span><input bind:value={nodePath} /></label>
                <label class="toggle-row">
                  <input type="checkbox" bind:checked={autoApproveReadOnly} />
                  <span><strong>Auto-run read-only tools</strong><small>Context retrieval, search, and file reads</small></span>
                </label>
                <div class="backend-health-row">
                  <span class="service-status {snapshot.backendHealth.state}"></span>
                  <span><strong>{snapshot.backendHealth.state}</strong><small>{snapshot.backendHealth.label}</small></span>
                  <button onclick={() => sendCommand("checkBackend")}><Icon name="refresh-ccw" size={12} />Test connection</button>
                </div>
                <footer><button class="primary" onclick={saveSettings}>Save settings</button></footer>
              </section>
            {:else if settingsSection === "Rules & Guidelines"}
              {#if ruleEditorOpen}
                <div class="rule-editor-title">
                  <button class="icon-button compact" title="Back to rules" onclick={() => ruleEditorOpen = false}><Icon name="chevron-left" size={14} /></button>
                  <div><h1>Rule: {ruleFileName}</h1><p class="settings-lead">Markdown guidance stored in the current repository.</p></div>
                </div>
                <section class="settings-block rule-editor">
                  <label><span>File name</span><input bind:value={ruleFileName} disabled={editingExistingRule} /></label>
                  <label><span>Description</span><input bind:value={ruleDescription} maxlength="240" placeholder="When should the Agent use this rule?" /></label>
                  <fieldset>
                    <legend>Trigger</legend>
                    <div class="trigger-control">
                      <button class:active={ruleTrigger === "always"} onclick={() => ruleTrigger = "always"}>Always</button>
                      <button class:active={ruleTrigger === "manual"} onclick={() => ruleTrigger = "manual"}>Manual</button>
                      <button class:active={ruleTrigger === "agent"} onclick={() => ruleTrigger = "agent"}>Agent</button>
                    </div>
                  </fieldset>
                  <label><span>Rule content</span><textarea bind:value={ruleContent} spellcheck="false"></textarea></label>
                  <footer>
                    <button onclick={() => ruleEditorOpen = false}>Cancel</button>
                    <button class="primary" disabled={!ruleFileName.trim() || !ruleContent.trim()} onclick={saveRule}>Save rule</button>
                  </footer>
                </section>
              {:else}
                <div class="section-title">
                  <div><h1>Rules & Guidelines</h1><p class="settings-lead">Repository instructions validated by the IDEA capability gateway.</p></div>
                  <button onclick={() => editRule()}><Icon name="plus" size={13} />New Rule</button>
                </div>
                <section class="settings-block rule-list">
                  {#each snapshot.customization.rules as rule}
                    <div>
                      <Icon name="book-open" size={14} />
                      <button class="rule-copy" onclick={() => editRule(rule)}><strong>{rule.name}</strong><small>{rule.description || rule.path}</small></button>
                      <i>{rule.trigger}</i>
                      {#if rule.trigger === "manual"}
                        <label title="Enable for this thread"><input type="checkbox" checked={rule.selected} onchange={() => sendCommand("toggleRule", { ruleId: rule.id, selected: !rule.selected })} /></label>
                      {/if}
                      <button class="icon-button compact" title="Edit rule" onclick={() => editRule(rule)}><Icon name="file-pen" size={13} /></button>
                    </div>
                  {:else}
                    <p>No repository rules found.</p>
                  {/each}
                </section>
              {/if}
            {:else if settingsSection === "Skills"}
              <h1>Skills <em>Beta</em></h1>
              <p class="settings-lead">Select task methods from the message composer.</p>
              <section class="settings-block list-block">
                {#each snapshot.customization.skills as skill}
                  <div><Icon name="wand-sparkles" size={14} /><span><strong>{skill.name}</strong><small>{skill.description}</small></span><i>{skill.selected ? "On" : "Off"}</i></div>
                {:else}
                  <p>No repository skills found.</p>
                {/each}
              </section>
            {:else if settingsSection === "User Experience"}
              <h1>User Experience</h1>
              <p class="settings-lead">Local panel presentation. Sound, desktop notifications, and timestamps stay unavailable until host hooks exist.</p>
              <section class="settings-block">
                <header><strong>Chat zoom</strong><span>{chatZoom}%</span></header>
                <div class="image-settings" style="margin:8px 0 0">
                  <label>Zoom <input type="range" min="85" max="140" bind:value={chatZoom} /><span>{chatZoom}%</span></label>
                </div>
              </section>
              <section class="settings-block unavailable">
                <Icon name="bell" size={18} />
                <strong>Notifications & sound</strong>
                <p>Not connected in this build.</p>
              </section>
            {:else if settingsSection === "MCP Servers"}
              <h1>MCP Servers</h1>
              <p class="settings-lead">Prototype surface for Easy MCP / remote MCP. No server lifecycle is connected.</p>
              <section class="settings-block unavailable">
                <Icon name="mcp" size={20} />
                <strong>Not connected</strong>
                <p>Install/Add/Import actions are hidden until a real MCP gateway exists.</p>
              </section>
            {:else}
              <h1>{settingsSection}{#if settingsGroups.some((group) => group.items.some((item) => item.id === settingsSection && item.badge))} <em>Beta</em>{/if}</h1>
              <p class="settings-lead">This surface is unavailable until its backend capability is connected.</p>
              <section class="settings-block unavailable">
                <Icon name="plug" size={20} />
                <strong>Not connected</strong>
                <p>No operation will be simulated from this page.</p>
              </section>
            {/if}
          </div>
        </div>
      </section>
    {:else if currentView === "git"}
      <section class="workspace-view">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <Icon name="git-branch" size={14} />
          <strong>Git Changes</strong>
          <button class="btn sm" disabled={gitLoading || gitSnapshot.unstaged.length === 0} onclick={() => sendCommand("stageGit", { paths: gitPaths(gitSnapshot.unstaged) })}>Stage All</button>
          <button class="btn sm primary" disabled={gitLoading || gitSnapshot.staged.length === 0 || !commitMessage.trim()} onclick={() => sendCommand("commitGit", { message: commitMessage })}>Commit</button>
          <button class="icon-button compact" title="Refresh Git status" onclick={() => { gitLoading = true; sendCommand("refreshGit"); }}><Icon name="refresh-ccw" size={13} /></button>
        </header>
        <div class="workspace-body">
          {#if gitLoading}
            <div class="workspace-empty"><Icon name="refresh-ccw" size={19} /><strong>Reading repository status</strong></div>
          {:else if gitSnapshot.error || !gitSnapshot.available}
            <div class="workspace-empty"><Icon name="git-branch" size={20} /><strong>Git unavailable</strong><p>{gitSnapshot.error ?? "This project is not a Git repository."}</p></div>
          {:else}
            <div class="workspace-context"><span><Icon name="git-branch" size={13} />{gitSnapshot.branch}</span><small>repo: {gitSnapshot.repository}</small></div>
            <section class="workspace-section">
              <header><div><strong>Unstaged</strong><small>{gitSnapshot.unstaged.length} files</small></div><button disabled={gitSnapshot.unstaged.length === 0} onclick={() => sendCommand("stageGit", { paths: gitPaths(gitSnapshot.unstaged) })}>Stage All</button></header>
              <div class="workspace-list">
                {#each gitSnapshot.unstaged as file}
                  <div class="workspace-file">
                    <Icon name="file" size={14} />
                    <span><strong>{file.path}</strong><small>{file.status}</small></span>
                    <button onclick={() => sendCommand("stageGit", { paths: [file.path] })}>Stage</button>
                    <button class="icon-button compact" title="Open working tree Diff" onclick={() => sendCommand("openGitDiff", { path: file.path, staged: false })}><Icon name="file-diff" size={13} /></button>
                  </div>
                {:else}
                  <div class="workspace-list-empty">No unstaged changes</div>
                {/each}
              </div>
            </section>
            <section class="workspace-section">
              <header><div><strong>Reviewed and Approved</strong><small>{gitSnapshot.staged.length} files</small></div></header>
              <div class="workspace-list">
                {#each gitSnapshot.staged as file}
                  <div class="workspace-file">
                    <Icon name="file" size={14} />
                    <span><strong>{file.path}</strong><small>{file.status}</small></span>
                    <button onclick={() => sendCommand("unstageGit", { paths: [file.path] })}>Unstage</button>
                    <button class="icon-button compact" title="Open staged Diff" onclick={() => sendCommand("openGitDiff", { path: file.path, staged: true })}><Icon name="file-diff" size={13} /></button>
                  </div>
                {:else}
                  <div class="workspace-list-empty">No staged files to commit</div>
                {/each}
              </div>
            </section>
            <section class="workspace-section commit-section">
              <header><div><strong>Commit message</strong><small>Commits only the staged files above</small></div></header>
              <textarea bind:value={commitMessage} placeholder="Enter commit message..." maxlength="4000"></textarea>
              <footer>
                <button disabled={gitSnapshot.staged.length === 0} onclick={() => sendCommand("suggestCommitMessage", { files: gitSnapshot.staged })}><Icon name="sparkles" size={13} />Generate message</button>
                <button class="primary" disabled={gitSnapshot.staged.length === 0 || !commitMessage.trim()} onclick={() => sendCommand("commitGit", { message: commitMessage })}><Icon name="git-commit-horizontal" size={13} />Commit</button>
              </footer>
            </section>
          {/if}
        </div>
      </section>
    {:else if currentView === "tasks"}
      <section class="workspace-view">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <Icon name="list-checks" size={14} />
          <strong>Active Tasklist</strong>
          <span class="workspace-count">{snapshot.tasks.filter((task) => task.state === "completed").length}/{snapshot.tasks.length}</span>
          <button class="btn sm" onclick={() => { newTaskName = newTaskName || "New task"; addTask(); }}>Add</button>
          <button class="btn sm primary" disabled={isBusy() || snapshot.tasks.every((task) => task.state === "completed" || task.state === "cancelled")} onclick={() => sendCommand("runAllTasks")}>Run All</button>
        </header>
        <div class="workspace-body task-workspace">
          <div class="workspace-actions">
            <div class="segmented-control">
              <button class:active={taskFilter === "all"} onclick={() => taskFilter = "all"}>All</button>
              <button class:active={taskFilter === "pending"} onclick={() => taskFilter = "pending"}>Pending</button>
              <button class:active={taskFilter === "running"} onclick={() => taskFilter = "running"}>Running</button>
              <button class:active={taskFilter === "done"} onclick={() => taskFilter = "done"}>Done</button>
            </div>
          </div>
          <div class="task-import-actions">
            <button onclick={() => sendCommand("exportTasks")} disabled={snapshot.tasks.length === 0}><Icon name="upload" size={12} />Export</button>
            <button onclick={() => sendCommand("importTasks")}><Icon name="download" size={12} />Import</button>
            <button onclick={() => sendCommand("clearCompletedTasks")} disabled={!snapshot.tasks.some((task) => task.state === "completed" || task.state === "cancelled")}>Clear Completed</button>
            <button class="danger" onclick={() => sendCommand("clearTasks")} disabled={snapshot.tasks.length === 0}><Icon name="trash-2" size={12} />Clear All</button>
          </div>
          <form class="task-add" onsubmit={(event) => { event.preventDefault(); addTask(); }}>
            <input bind:value={newTaskName} maxlength="240" placeholder="Add a new task" />
            <button class="primary" disabled={!newTaskName.trim()}><Icon name="plus" size={13} />Add New</button>
          </form>
          <div class="task-workspace-list">
            {#each filteredTasks() as task, index}
              <div class="task-workspace-row" class:completed={task.state === "completed"} class:running={task.state === "in_progress"}>
                <button class="task-state" title="Toggle complete" onclick={() => sendCommand("setTaskState", { taskId: task.id, state: task.state === "completed" ? "not_started" : "completed" })}>
                  {#if task.state === "completed"}<Icon name="circle-check" size={15} />{:else}<span></span>{/if}
                </button>
                <i>{index + 1}</i>
                <span><strong>{task.name}</strong><small>{task.state.replaceAll("_", " ")}</small></span>
                <button class="icon-button compact" title="Run task" disabled={isBusy() || task.state === "completed"} onclick={() => sendCommand("runTask", { taskId: task.id })}><Icon name="play" size={13} /></button>
                <button class="icon-button compact danger" title="Delete task" onclick={() => sendCommand("deleteTask", { taskId: task.id })}><Icon name="trash-2" size={13} /></button>
              </div>
            {:else}
              <div class="workspace-empty"><Icon name="list-checks" size={20} /><strong>Get Started with Tasks</strong><p>Break Agent work into runnable steps.</p></div>
            {/each}
          </div>
        </div>
      </section>
    {:else if currentView === "images"}
      <section class="workspace-view image-workspace">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <Icon name="layers-2" size={14} />
          <strong>Context Canvas</strong>
          <button class="icon-button compact" title="Refresh images" onclick={() => sendCommand("refreshImageCanvas")}><Icon name="refresh-ccw" size={13} /></button>
        </header>
        <div class="workspace-body">
          <div class="image-toolbar">
            <span><Icon name="folder" size={13} /><b>{imageCanvas.directory || "No directory selected"}</b></span>
            <button onclick={() => sendCommand("browseImageDirectory")}>Browse</button>
            <button class:active={imageSettingsOpen} title="View settings" onclick={() => imageSettingsOpen = !imageSettingsOpen}><Icon name="settings-2" size={13} /></button>
          </div>
          {#if imageSettingsOpen}
            <div class="image-settings">
              <label>Columns <input type="number" min="1" max="5" bind:value={imageColumns} /></label>
              <label>Zoom <input type="range" min="50" max="200" bind:value={imageZoom} /><span>{imageZoom}%</span></label>
            </div>
          {/if}
          {#if imageCanvas.error}
            <div class="workspace-empty"><Icon name="circle-alert" size={20} /><strong>Image Canvas unavailable</strong><p>{imageCanvas.error}</p></div>
          {:else if imageCanvas.images.length === 0}
            <div class="workspace-empty">
              <Icon name="image-plus" size={20} />
              <strong>No images found</strong>
              <p>Choose a project directory containing PNG, JPEG, GIF, or WebP files.</p>
              <button class="primary" onclick={() => sendCommand("browseImageDirectory")}>Choose Directory</button>
            </div>
          {:else}
            <div class="image-grid" style={`--image-columns:${imageColumns};--image-zoom:${imageZoom / 100}`}>
              {#each imageCanvas.images as image}
                <article class="image-item">
                  <div class="image-thumb"><img src={image.dataUrl} alt={image.name} /></div>
                  <div class="image-meta">
                    <strong title={image.path}>{image.name}</strong>
                    <small>{Math.max(1, Math.round(image.sizeBytes / 1024))} KB</small>
                    <div>
                      <button class="primary" onclick={() => sendCommand("attachImage", { path: image.path })}><Icon name="at-sign" size={12} />Mention</button>
                      <button onclick={() => sendCommand("openImage", { path: image.path })}><Icon name="external-link" size={12} />Open</button>
                    </div>
                  </div>
                </article>
              {/each}
            </div>
            {#if imageCanvas.truncated}<p class="image-limit">Showing the first images within the 10 MB preview budget.</p>{/if}
          {/if}
        </div>
      </section>
    {:else if currentView === "tools"}
      <section class="workspace-view">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <Icon name="wrench" size={14} />
          <strong>Insert Tool Call</strong>
        </header>
        <div class="workspace-body">
          <label class="thread-search"><Icon name="search" size={13} /><input bind:value={toolFilter} placeholder="Filter tools…" /></label>
          <div class="catalog-list">
            {#each filteredTools() as tool}
              <button class="catalog-card" class:connected={tool.connected} onclick={() => insertToolSeed(tool.id)}>
                <span class="tool-icon"><Icon name={tool.icon} size={16} /></span>
                <span>
                  <strong>{tool.name}</strong>
                  <small>{tool.desc}</small>
                </span>
                <i>{tool.connected ? "connected" : "unavailable"}</i>
              </button>
            {:else}
              <div class="workspace-list-empty">No tools match this filter</div>
            {/each}
          </div>
        </div>
      </section>
    {:else if currentView === "icons"}
      <section class="workspace-view">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <Icon name="sparkles" size={14} />
          <strong>Icon Registry Viewer</strong>
        </header>
        <div class="workspace-body">
          <label class="thread-search"><Icon name="search" size={13} /><input bind:value={iconFilter} placeholder="Filter icons…" /></label>
          <div class="icon-grid">
            {#each filteredIcons() as name}
              <button class="icon-tile" title={name} onclick={async () => { try { await navigator.clipboard.writeText(name); notice = `Copied ${name}`; } catch { notice = name; } }}>
                <Icon name={name} size={18} />
                <span>{name}</span>
              </button>
            {/each}
          </div>
        </div>
      </section>
    {:else if currentView === "edits"}
      <section class="workspace-view">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <Icon name="file-diff" size={14} />
          <strong>Agent Edits</strong>
          <button class="btn sm" disabled={changeTools().length === 0} onclick={() => sendCommand("keepChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Keep all</button>
          <button class="btn sm danger" disabled={changeTools().length === 0} onclick={() => sendCommand("discardChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Discard all</button>
        </header>
        <div class="workspace-body">
          {#if changeTools().length === 0}
            <div class="workspace-empty"><Icon name="file-diff" size={20} /><strong>No pending agent edits</strong><p>File tools with Diff/undo will appear here after Agent changes files.</p></div>
          {:else}
            <div class="workspace-list">
              {#each changeTools() as tool}
                <div class="workspace-file">
                  <Icon name="file" size={14} />
                  <span><strong>{tool.changePath}</strong><small>{toolTitle(tool)} · {statusLabel(tool.status)}</small></span>
                  <button onclick={() => sendCommand("openDiff", { toolId: tool.id })}>Diff</button>
                  {#if tool.canRevert}<button onclick={() => sendCommand("revertChange", { toolId: tool.id })}>Undo</button>{/if}
                </div>
              {/each}
            </div>
          {/if}
        </div>
      </section>
    {:else if currentView === "feedback"}
      <section class="workspace-view">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <Icon name="flag" size={14} />
          <strong>Report Issue / Feedback</strong>
        </header>
        <div class="workspace-body">
          <section class="settings-block rule-editor">
            <label><span>Describe the issue</span><textarea bind:value={feedbackText} placeholder="What went wrong or what should improve?" spellcheck="true"></textarea></label>
            <footer>
              <button onclick={() => { copyThread(); notice = "Thread copied for support context"; }}>Export Logs / Thread</button>
              <button class="primary" disabled={!feedbackText.trim()} onclick={() => { notice = "Feedback noted locally. No remote report endpoint is connected."; feedbackText = ""; }}>Submit</button>
            </footer>
          </section>
          <p class="settings-lead">No remote feedback service is connected. Submit only stores a local notice so the UI stays honest.</p>
        </div>
      </section>
    {:else if currentView === "mermaid"}
      <section class="mermaid-view">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <strong>{mermaidTitle}</strong>
          <div class="canvas-tabs">
            <button class:active={mermaidMode === "diagram"} onclick={() => mermaidMode = "diagram"}>Diagram</button>
            <button class:active={mermaidMode === "code"} onclick={() => mermaidMode = "code"}>Code</button>
          </div>
          <button class="icon-button compact" title="Zoom out" disabled={mermaidMode === "code"} onclick={() => mermaidScale = Math.max(.5, mermaidScale - .1)}><Icon name="minus" size={14} /></button>
          <span class="zoom-value">{Math.round(mermaidScale * 100)}%</span>
          <button class="icon-button compact" title="Zoom in" disabled={mermaidMode === "code"} onclick={() => mermaidScale = Math.min(2, mermaidScale + .1)}><Icon name="plus" size={14} /></button>
          <button class="fit-button" disabled={mermaidMode === "code"} onclick={() => mermaidScale = 1}><Icon name="maximize-2" size={13} />Fit</button>
          <button class="icon-button compact" title="Open Mermaid source in IDE editor" onclick={() => sendCommand("openMermaidEditor", { title: mermaidTitle, code: mermaidSource })}><Icon name="external-link" size={13} /></button>
        </header>
        <div class="canvas-body">
          {#if mermaidMode === "diagram"}
            <MermaidCanvas source={mermaidSource} scale={mermaidScale} />
          {:else}
            <pre>{mermaidSource}</pre>
          {/if}
        </div>
      </section>
    {/if}

    {#if currentView !== "chat" && error}
      <div class="global-banner error-banner"><Icon name="circle-alert" size={14} /><span>{error}</span><button title="Dismiss" onclick={() => error = ""}><Icon name="x" size={13} /></button></div>
    {/if}
    {#if currentView !== "chat" && notice}
      <div class="global-banner notice-banner"><Icon name="circle-check" size={13} /><span>{notice}</span><button title="Dismiss" onclick={() => notice = ""}><Icon name="x" size={13} /></button></div>
    {/if}

    {#if threadDrawerOpen}
      <button class="drawer-backdrop" aria-label="Close threads" onclick={() => threadDrawerOpen = false}></button>
      <aside class="thread-drawer drawer">
        <header class="drawer-head">
          <strong>Threads</strong>
          <div class="new-thread-control">
            <button class="new-thread" onclick={() => startNewThread()}><Icon name="plus" size={14} /> New {modeLabel()}</button>
          </div>
          <button class="icon-button" title="Close" onclick={() => threadDrawerOpen = false}><Icon name="x" size={15} /></button>
        </header>
        <div class="new-thread-modes">
          <button onclick={() => startNewThread("agent")}><Icon name="bot" size={13} />New Agent</button>
          <button onclick={() => startNewThread("chat")}><Icon name="message-circle" size={13} />New Chat</button>
          <button onclick={() => startNewThread("ask")}><Icon name="search" size={13} />New Ask</button>
        </div>
        <label class="thread-search"><Icon name="search" size={13} /><input bind:value={threadSearch} placeholder="Search threads…" /></label>
        <div class="thread-list">
          {#each visibleThreads() as thread}
            <div class="thread-row" class:active={thread.active}>
              <button class="thread-select" onclick={() => selectThread(thread.id)}>
                <span><strong>{thread.title}</strong><small>{formatTime(thread.updatedAt)}</small></span>
                <i class="tag {thread.mode}">{modeLabel(thread.mode)}</i>
              </button>
              <button class="icon-button compact" class:pinned={thread.pinned} title={thread.pinned ? "Unpin thread" : "Pin thread"} onclick={() => sendCommand("toggleThreadPinned", { threadId: thread.id })}><Icon name="pin" size={12} /></button>
              <button class="icon-button compact delete-thread" title="Delete thread" onclick={() => sendCommand("deleteThread", { threadId: thread.id })}><Icon name="trash-2" size={12} /></button>
            </div>
          {/each}
        </div>
        <footer>
          <button onclick={() => sendCommand("importThread")}><Icon name="download" size={13} />Import</button>
          <button onclick={copyThread}><Icon name="upload" size={13} />Export</button>
        </footer>
      </aside>
    {/if}
  </main>
{/if}
