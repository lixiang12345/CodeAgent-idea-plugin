<script lang="ts">
  import { onMount, tick } from "svelte";
  import Icon from "./lib/Icon.svelte";
  import ConfigurationSettings from "./lib/ConfigurationSettings.svelte";
  import MarkdownMessage from "./lib/MarkdownMessage.svelte";
  import MermaidCanvas from "./lib/MermaidCanvas.svelte";
  import { ICON_NAMES } from "./lib/icons";
  import { MENTION_KINDS, SLASH_COMMANDS, TOOL_CATALOG } from "./lib/tools-catalog";
  import {
    PROTOCOL_VERSION,
    onHostEvent,
    sendCommand,
    type AppSnapshot,
    type AgentRunPhase,
    type ChatMessage,
    type EventEnvelope,
    type GitFile,
    type GitSnapshot,
    type ImageCanvasSnapshot,
    type MessageDelta,
    type Mode,
    type ProductJob,
    type SettingsSaved,
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
        { id: "ACP Agents", label: "ACP Agents", icon: "bot" },
      ],
    },
    {
      label: "Preferences",
      items: [
        { id: "Rules & Guidelines", label: "Rules & Guidelines", icon: "pencil-ruler" },
        { id: "API Keys", label: "API Keys", icon: "key-round" },
        { id: "Memories", label: "Memories", icon: "brain" },
        { id: "Commands", label: "Commands", icon: "square-terminal", badge: "Beta" },
        { id: "Skills", label: "Skills", icon: "wand-sparkles", badge: "Beta" },
        { id: "Hooks", label: "Hooks", icon: "workflow" },
        { id: "Agents", label: "Agents", icon: "bot", badge: "Beta" },
        { id: "Plugins", label: "Plugins", icon: "layers" },
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
    conversation_retrieval: "augment-logo",
    read_file: "file",
    list_files: "folder",
    search_text: "search",
    write_file: "file-plus",
    replace_text: "file-pen",
    remove_files: "file-minus",
    apply_patch: "file-diff",
    run_terminal: "square-terminal",
    launch_process: "square-terminal",
    list_processes: "square-terminal",
    read_process: "square-terminal",
    write_process: "square-terminal",
    wait_process: "square-terminal",
    kill_process: "square-terminal",
    open_file: "external-link",
    open_browser: "external-link",
    web_fetch: "globe",
    web_search: "globe",
    github_search: "github",
    github_manage: "github",
    linear_search: "linear",
    notion_search: "notion",
    jira_search: "jira",
    confluence_search: "confluence",
    glean_search: "glean",
    supabase_query: "supabase",
    subagent: "bot",
    diagnostics: "circle-alert",
    git_history: "git-commit-horizontal",
    view_tasks: "list-checks",
    add_tasks: "list-checks",
    update_tasks: "list-checks",
    reorg_tasks: "list-checks",
    render_mermaid: "workflow",
    ask_user: "message-circle",
  };
  const managedProcessToolNames = new Set(["launch_process", "read_process", "write_process", "wait_process", "kill_process"]);

  const builtInAgentProfiles = [
    { id: "general", name: "General Agent", description: "Balanced implementation and verification", agentType: "general", source: "Built in" },
    { id: "search", name: "Search Agent", description: "Evidence-first repository and service research", agentType: "search", source: "Built in" },
    { id: "context", name: "Context Agent", description: "Focused repository context collection", agentType: "context", source: "Built in" },
    { id: "prompt", name: "Prompt Engineer", description: "Task and prompt refinement", agentType: "prompt", source: "Built in" },
    { id: "loop", name: "Loop Agent", description: "Bounded execution, verification, and iteration", agentType: "loop", source: "Built in" },
  ];

  let snapshot: AppSnapshot | null = null;
  let prompt = "";
  type WorkspaceView = "chat" | "settings" | "mermaid" | "git" | "tasks" | "jobs" | "images" | "tools" | "icons" | "edits" | "feedback";
  let currentView: WorkspaceView = "chat";
  let settingsSection = "Home";
  let settingsNavigationOpen = false;
  let threadDrawerOpen = false;
  let modeMenuOpen = false;
  let agentMenuOpen = false;
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
  let resolvingApprovalIds = new Set<string>();
  let backendUrl = "";
  let nodePath = "";
  let inlineCompletionsEnabled = true;
  let backendToken = "";
  let autoApproveReadOnly = true;
  let contextMode: "remote-http" | "lexical" | "private-semantic" = "remote-http";
  let contextHttpBaseUrl = "http://127.0.0.1:8790";
  let contextHttpApiKey = "";
  let contextEmbeddingBaseUrl = "http://127.0.0.1:8000/v1";
  let contextEmbeddingModel = "Qwen/Qwen3-Embedding-0.6B";
  let contextEmbeddingApiKey = "";
  let contextNeuralRerank = false;
  let contextRerankBaseUrl = "";
  let contextRerankModel = "Qwen/Qwen3-Reranker-0.6B";
  type ServiceTestState = "idle" | "checking" | "success" | "error";
  let backendTestState: ServiceTestState = "idle";
  let backendTestLabel = "";
  let backendTestRequestId: string | null = null;
  let contextTestState: ServiceTestState = "idle";
  let contextTestLabel = "";
  let contextTestRequestId: string | null = null;
  type SettingsSaveState = "idle" | "saving" | "saved";
  type PendingSettingsSave = {
    requestId: string;
    backendToken: string;
    contextHttpApiKey: string;
    contextEmbeddingApiKey: string;
    sentBackendToken: boolean;
    sentContextHttpApiKey: boolean;
    sentContextEmbeddingApiKey: boolean;
    clearBackendToken: boolean;
    clearContextHttpApiKey: boolean;
    clearContextEmbeddingApiKey: boolean;
  };
  type SettingsUpdateOptions = {
    requestId?: string;
    includeSecrets?: boolean;
    clearBackendToken?: boolean;
    clearContextHttpApiKey?: boolean;
    clearContextEmbeddingApiKey?: boolean;
  };
  let settingsSaveState: SettingsSaveState = "idle";
  let pendingSettingsSave: PendingSettingsSave | null = null;
  let settingsDirtyWhileSaving = false;
  let settingsHydrated = false;
  let error = "";
  let notice = "";
  let threadSearch = "";
  let moreMenuOpen = false;
  let threadOptOpen = false;
  let taskFilter: "all" | "pending" | "running" | "done" = "all";
  let newTaskName = "";
  let jobFilter: "all" | "active" | "completed" | "failed" = "all";
  let jobPrompt = "";
  let jobRole: NonNullable<ProductJob["role"]> = "research";
  let jobContext = "";
  let jobExpectedOutput = "";
  let jobMaxOutputTokens = 4096;
  let jobModel = "";
  let creatingJob = false;
  let commitMessage = "";
  let gitLoading = false;
  let gitSnapshot: GitSnapshot = { available: false, branch: "", repository: "", unstaged: [], staged: [] };
  let imageCanvas: ImageCanvasSnapshot = { directory: "", images: [], truncated: false };
  let imageSettingsOpen = false;
  let imageColumns = 3;
  let imageZoom = 100;
  const CHAT_ZOOM_MIN = 85;
  const CHAT_ZOOM_MAX = 140;
  const CHAT_ZOOM_DEFAULT = 100;
  const CHAT_ZOOM_STEP = 10;
  let chatZoom = CHAT_ZOOM_DEFAULT;
  let showTimestamps = true;
  let showRunTelemetry = true;
  let desktopNotifications = false;
  let autoDismissNotifications = true;
  let slashOpen = false;
  let atOpen = false;
  let iconFilter = "";
  let toolFilter = "";
  let feedbackText = "";
  let renaming = false;
  let renameTitle = "";
  let enhancing = false;
  let checkpoints: Array<{ id: string; label: string; createdAt: number; changeCount: number; paths: string[] }> = [];
  let noticeTimer: number | undefined;
  let errorTimer: number | undefined;
  type ContextCompactionState = "idle" | "running" | "complete";
  let contextCompactionState: ContextCompactionState = "idle";
  let contextCompactionProgress = 0;
  let contextCompactionMessages = 0;
  let contextCompactionTools = 0;
  let contextCompactionSignature = "";
  let contextCompactionAdvanceTimer: number | undefined;
  let contextCompactionCompleteTimer: number | undefined;
  let contextCompactionHideTimer: number | undefined;
  let scheduledNotice = "";
  let scheduledError = "";
  let conversationElement: HTMLDivElement | undefined;
  let followConversation = true;
  let forceConversationFollow = true;
  let visibleThreadId: string | undefined;
  let pendingUserMessages: ChatMessage[] = [];
  let pendingThreadDeletes = new Set<string>();
  let pendingThreadPins: Record<string, boolean> = {};

  onMount(() => {
    const unsubscribe = onHostEvent(handleEvent);
    sendCommand("bootstrap");
    const jobPoller = window.setInterval(() => {
      const hasActiveJobs = snapshot?.jobs.items.some((job) => job.status === "queued" || job.status === "running");
      if (currentView === "jobs" && hasActiveJobs) sendCommand("refreshJobs");
    }, 2000);
    const contextPoller = window.setInterval(() => {
      if (currentView === "chat" && snapshot?.context.state === "ready") sendCommand("getContextStatus");
    }, 5000);
    return () => {
      window.clearInterval(jobPoller);
      window.clearInterval(contextPoller);
      if (noticeTimer !== undefined) window.clearTimeout(noticeTimer);
      if (errorTimer !== undefined) window.clearTimeout(errorTimer);
      clearContextCompactionTimers();
      unsubscribe();
    };
  });

  $: reconcileToastTimers(notice, error, autoDismissNotifications);

  function handleEvent(event: EventEnvelope) {
    if (event.type === "snapshot") {
      const nextSnapshot = event.payload as AppSnapshot;
      const nextThreadId = nextSnapshot.threads.find((thread) => thread.active)?.id;
      const threadChanged = visibleThreadId !== undefined && nextThreadId !== visibleThreadId;
      const forceFollow = forceConversationFollow || nextThreadId !== visibleThreadId;
      if (threadChanged) {
        pendingUserMessages = [];
        hideContextCompaction();
      } else {
        reconcilePendingUserMessages(nextSnapshot.messages);
      }
      snapshot = nextSnapshot;
      pendingThreadDeletes = new Set();
      pendingThreadPins = Object.fromEntries(
        Object.entries(pendingThreadPins).filter(([threadId, pinned]) => {
          const thread = nextSnapshot.threads.find((item) => item.id === threadId);
          return thread !== undefined && thread.pinned !== pinned;
        }),
      );
      reconcileContextCompaction(nextSnapshot, nextThreadId);
      visibleThreadId = nextThreadId;
      forceConversationFollow = false;
      resolvingApprovalIds = new Set(
        [...resolvingApprovalIds].filter((toolId) =>
          nextSnapshot.tools.some((tool) => tool.id === toolId && tool.status === "approval")
        )
      );
      if (!settingsHydrated) {
        hydrateSettings(nextSnapshot);
        settingsHydrated = true;
      }
      if (snapshot.jobs.state !== "loading") creatingJob = false;
      scrollConversationToBottom(forceFollow);
      return;
    }
    if (event.type === "settingsSaved") {
      const saved = event.payload as SettingsSaved;
      if (!pendingSettingsSave || saved.requestId !== pendingSettingsSave.requestId) return;
      if ((pendingSettingsSave.sentBackendToken || pendingSettingsSave.clearBackendToken) && backendToken === pendingSettingsSave.backendToken) backendToken = "";
      if ((pendingSettingsSave.sentContextHttpApiKey || pendingSettingsSave.clearContextHttpApiKey) && contextHttpApiKey === pendingSettingsSave.contextHttpApiKey) contextHttpApiKey = "";
      if ((pendingSettingsSave.sentContextEmbeddingApiKey || pendingSettingsSave.clearContextEmbeddingApiKey) && contextEmbeddingApiKey === pendingSettingsSave.contextEmbeddingApiKey) contextEmbeddingApiKey = "";
      if (snapshot) {
        snapshot = {
          ...snapshot,
          settings: {
            ...snapshot.settings,
            backendTokenConfigured: saved.backendTokenConfigured,
            contextHttpTokenConfigured: saved.contextHttpTokenConfigured,
            contextEmbeddingTokenConfigured: saved.contextEmbeddingTokenConfigured,
          },
        };
      }
      settingsSaveState = settingsDirtyWhileSaving ? "idle" : "saved";
      settingsDirtyWhileSaving = false;
      pendingSettingsSave = null;
      return;
    }
    if (event.type === "navigateSettings") {
      const section = String((event.payload as { section?: string })?.section ?? "Home");
      openSettings(section);
      return;
    }
    if (event.type === "backendConnectionChecked") {
      const checked = event.payload as { requestId?: string; ok?: boolean; label?: string };
      if (!backendTestRequestId || checked.requestId !== backendTestRequestId) return;
      backendTestRequestId = null;
      backendTestState = checked.ok ? "success" : "error";
      backendTestLabel = String(checked.label ?? (checked.ok ? "Backend connection verified" : "Backend connection failed"));
      return;
    }
    if (event.type === "contextConnectionChecked") {
      const checked = event.payload as { requestId?: string; ok?: boolean; label?: string };
      if (!contextTestRequestId || checked.requestId !== contextTestRequestId) return;
      contextTestRequestId = null;
      contextTestState = checked.ok ? "success" : "error";
      contextTestLabel = String(checked.label ?? (checked.ok ? "ContextEngine connection verified" : "ContextEngine connection failed"));
      return;
    }
    if (event.type === "error") {
      enhancing = false;
      pendingThreadDeletes = new Set();
      pendingThreadPins = {};
      if (pendingSettingsSave) {
        pendingSettingsSave = null;
        settingsSaveState = "idle";
        settingsDirtyWhileSaving = false;
      }
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
    if (event.type === "promptEnhanced") {
      enhancing = false;
      const text = String((event.payload as { text?: string })?.text ?? "").trim();
      if (text) {
        prompt = text;
        notice = "Prompt enhanced";
      } else {
        error = "Enhancer returned empty text";
      }
      return;
    }
    if (event.type === "checkpoints") {
      checkpoints = Array.isArray(event.payload) ? event.payload as typeof checkpoints : [];
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
      scrollConversationToBottom();
      return;
    }
    if (event.type === "stateChanged" && snapshot) {
      snapshot = { ...snapshot, ...(event.payload as Partial<AppSnapshot>) };
      reconcilePendingUserMessages(snapshot.messages);
      scrollConversationToBottom();
    }
  }

  function reconcilePendingUserMessages(messages: ChatMessage[]) {
    if (pendingUserMessages.length === 0) return;
    const persistedIds = new Set(messages.map((message) => message.id));
    pendingUserMessages = pendingUserMessages.filter((message) => !persistedIds.has(message.id));
  }

  function updateConversationFollow() {
    if (!conversationElement) return;
    const distanceFromBottom = conversationElement.scrollHeight - conversationElement.scrollTop - conversationElement.clientHeight;
    followConversation = distanceFromBottom <= 48;
  }

  function scrollConversationToBottom(force = false) {
    if (!force && !followConversation) return;
    void tick().then(() => {
      if (!conversationElement || (!force && !followConversation)) return;
      conversationElement.scrollTop = conversationElement.scrollHeight;
      followConversation = true;
    });
  }

  function reconcileToastTimers(currentNotice: string, currentError: string, autoDismiss: boolean) {
    if (!autoDismiss || !currentNotice) {
      if (noticeTimer !== undefined) window.clearTimeout(noticeTimer);
      noticeTimer = undefined;
      scheduledNotice = "";
    } else if (scheduledNotice !== currentNotice) {
      if (noticeTimer !== undefined) window.clearTimeout(noticeTimer);
      scheduledNotice = currentNotice;
      noticeTimer = window.setTimeout(() => {
        if (notice === currentNotice) notice = "";
        scheduledNotice = "";
        noticeTimer = undefined;
      }, 4_000);
    }
    if (!autoDismiss || !currentError) {
      if (errorTimer !== undefined) window.clearTimeout(errorTimer);
      errorTimer = undefined;
      scheduledError = "";
    } else if (scheduledError !== currentError) {
      if (errorTimer !== undefined) window.clearTimeout(errorTimer);
      scheduledError = currentError;
      errorTimer = window.setTimeout(() => {
        if (error === currentError) error = "";
        scheduledError = "";
        errorTimer = undefined;
      }, 8_000);
    }
  }

  function closeMenus() {
    moreMenuOpen = false;
    threadOptOpen = false;
    modeMenuOpen = false;
    agentMenuOpen = false;
    modelMenuOpen = false;
    skillsOpen = false;
    slashOpen = false;
    atOpen = false;
  }

  function submit() {
    const text = prompt.trim();
    if (!text || !snapshot) return;
    if (runLocalSlash(text)) {
      prompt = "";
      return;
    }
    const busy = isBusy(snapshot);
    const clientMessageId = crypto.randomUUID();
    if (!busy) {
      const optimisticMessage: ChatMessage = {
        id: clientMessageId,
        role: "user",
        content: text,
        createdAt: Date.now(),
        timelineSequence: nextConversationTimelineSequence(snapshot),
      };
      pendingUserMessages = [...pendingUserMessages, optimisticMessage];
    }
    prompt = "";
    closeMenus();
    forceConversationFollow = true;
    scrollConversationToBottom(true);
    sendCommand(busy ? "queueMessage" : "sendMessage", { text, mode: snapshot.mode, clientMessageId });
  }

  function resolveApproval(toolId: string, approved: boolean) {
    if (resolvingApprovalIds.has(toolId)) return;
    resolvingApprovalIds = new Set(resolvingApprovalIds).add(toolId);
    sendCommand("resolveApproval", { toolId, approved });
  }

  function enhancePrompt() {
    const text = prompt.trim();
    if (!text || !snapshot || enhancing || isBusy(snapshot)) return;
    enhancing = true;
    error = "";
    sendCommand("enhancePrompt", { text, mode: snapshot.mode });
  }

  function setMode(mode: Mode) {
    if (!snapshot) return;
    snapshot = { ...snapshot, mode };
    modeMenuOpen = false;
    sendCommand("setMode", { mode });
  }

  function availableAgentProfiles(currentSnapshot: AppSnapshot) {
    const configured = (currentSnapshot.configurations.items.agents ?? [])
      .filter((profile) => profile.value.enabled !== false)
      .map((profile) => ({
        id: profile.id,
        name: typeof profile.value.name === "string" ? profile.value.name : profile.id,
        description: typeof profile.value.description === "string" ? profile.value.description : "Account Agent profile",
        agentType: typeof profile.value.agentType === "string" ? profile.value.agentType : "general",
        source: "Account",
      }));
    const plugins = currentSnapshot.pluginRuntime.agents.map((profile) => ({
      id: profile.id,
      name: profile.name,
      description: profile.description ?? `Agent profile from ${profile.pluginId}`,
      agentType: profile.agentType,
      source: profile.pluginId,
    }));
    const unique = new Map<string, (typeof builtInAgentProfiles)[number]>();
    [...builtInAgentProfiles, ...configured, ...plugins].forEach((profile) => {
      if (!unique.has(profile.id)) unique.set(profile.id, profile);
    });
    return [...unique.values()];
  }

  function activeAgentProfile(currentSnapshot: AppSnapshot) {
    return availableAgentProfiles(currentSnapshot).find((profile) => profile.id === currentSnapshot.selectedAgentProfileId)
      ?? builtInAgentProfiles[0];
  }

  function selectAgentProfile(agentProfileId: string) {
    if (!snapshot || isBusy(snapshot) || snapshot.selectedAgentProfileId === agentProfileId) return;
    snapshot = { ...snapshot, selectedAgentProfileId: agentProfileId };
    agentMenuOpen = false;
    sendCommand("selectAgentProfile", { agentProfileId });
  }

  function toggleAgentMenu(event: MouseEvent) {
    event.stopPropagation();
    if (!snapshot || isBusy(snapshot)) return;
    const open = !agentMenuOpen;
    closeMenus();
    agentMenuOpen = open;
  }

  function selectModel(modelId: string) {
    if (!snapshot) return;
    const normalized = modelId.trim();
    if (!normalized) return;
    modelMenuOpen = false;
    if (activeModelId(snapshot.models) === normalized) return;
    snapshot = {
      ...snapshot,
      models: { ...snapshot.models, selectedModel: normalized },
    };
    sendCommand("selectModel", { modelId: normalized });
  }

  function toggleModelMenu(event: MouseEvent) {
    event.stopPropagation();
    if (!snapshot || snapshot.models.state !== "ready" || snapshot.models.options.length === 0) return;
    const open = !modelMenuOpen;
    closeMenus();
    modelMenuOpen = open;
  }

  function availableModels(models: AppSnapshot["models"]) {
    return models.options;
  }

  function activeModelId(modelSnapshot: AppSnapshot["models"]) {
    const models = availableModels(modelSnapshot);
    const selected = modelSnapshot.selectedModel;
    if (selected && models.some((model) => model.id === selected)) return selected;
    const defaultModel = modelSnapshot.defaultModel;
    if (defaultModel && models.some((model) => model.id === defaultModel)) return defaultModel;
    return models[0]?.id ?? selected ?? defaultModel;
  }

  function modelLabel(models: AppSnapshot["models"]) {
    return activeModelId(models) ?? "Select model";
  }

  function modelTitle(models: AppSnapshot["models"]) {
    const modelId = activeModelId(models);
    return modelId ? `Model: ${modelId}` : "Select a model";
  }

  function modelVendorName(model: AppSnapshot["models"]["options"][number]) {
    if (model.ownedBy === "openai") return "OpenAI";
    if (model.ownedBy === "anthropic") return "Anthropic";
    if (model.ownedBy === "xai") return "xAI";
    return model.ownedBy ?? "Model provider";
  }

  function modelVendorIcon(model: AppSnapshot["models"]["options"][number]) {
    if (model.ownedBy === "openai") return "openai-brand";
    if (model.ownedBy === "anthropic") return "anthropic-brand";
    if (model.ownedBy === "xai") return "xai-brand";
    return "settings-2";
  }

  function modelContextUsage(currentSnapshot: AppSnapshot | null) {
    if (!currentSnapshot) return null;
    const windowTokens = currentSnapshot.agentRun.contextWindowTokens;
    const usedTokens = currentSnapshot.agentRun.estimatedInputTokens + currentSnapshot.agentRun.toolDefinitionTokens;
    if (windowTokens <= 0 || usedTokens <= 0) return null;
    return {
      usedTokens,
      contextWindowTokens: windowTokens,
      compactionTokens: currentSnapshot.agentRun.targetInputTokens + currentSnapshot.agentRun.toolDefinitionTokens,
      percent: Math.min(100, Math.max(0, Math.round((usedTokens / windowTokens) * 100))),
    };
  }

  function contextMeterText(currentSnapshot: AppSnapshot | null) {
    const usage = modelContextUsage(currentSnapshot);
    return usage ? `${usage.percent}%` : "--";
  }

  function contextMeterTitle(currentSnapshot: AppSnapshot | null) {
    const usage = modelContextUsage(currentSnapshot);
    if (!usage) return "Model context usage appears after the first model turn";
    return `${usage.usedTokens.toLocaleString()} of ${usage.contextWindowTokens.toLocaleString()} model-context tokens used · automatic compaction at ${usage.compactionTokens.toLocaleString()} (80%)`;
  }

  function clearContextCompactionTimers() {
    if (contextCompactionAdvanceTimer !== undefined) window.clearTimeout(contextCompactionAdvanceTimer);
    if (contextCompactionCompleteTimer !== undefined) window.clearTimeout(contextCompactionCompleteTimer);
    if (contextCompactionHideTimer !== undefined) window.clearTimeout(contextCompactionHideTimer);
    contextCompactionAdvanceTimer = undefined;
    contextCompactionCompleteTimer = undefined;
    contextCompactionHideTimer = undefined;
  }

  function hideContextCompaction() {
    clearContextCompactionTimers();
    contextCompactionState = "idle";
    contextCompactionProgress = 0;
  }

  function reconcileContextCompaction(nextSnapshot: AppSnapshot, threadId?: string) {
    const compactedMessages = nextSnapshot.agentRun.truncatedMessages;
    const compactedTools = nextSnapshot.agentRun.compactedToolResults;
    if (compactedMessages <= 0 && compactedTools <= 0) return;
    const signature = [
      threadId ?? "",
      nextSnapshot.agentRun.turnIndex,
      nextSnapshot.agentRun.estimatedInputTokens,
      compactedMessages,
      compactedTools,
    ].join(":");
    if (signature === contextCompactionSignature) return;

    clearContextCompactionTimers();
    contextCompactionSignature = signature;
    contextCompactionMessages = compactedMessages;
    contextCompactionTools = compactedTools;
    contextCompactionState = "running";
    contextCompactionProgress = 14;
    contextCompactionAdvanceTimer = window.setTimeout(() => {
      contextCompactionProgress = 72;
    }, 40);
    contextCompactionCompleteTimer = window.setTimeout(() => {
      contextCompactionState = "complete";
      contextCompactionProgress = 100;
    }, 680);
    contextCompactionHideTimer = window.setTimeout(() => {
      contextCompactionState = "idle";
      contextCompactionProgress = 0;
    }, 2_700);
  }

  function contextCompactionDetail() {
    const details: string[] = [];
    if (contextCompactionMessages > 0) {
      details.push(`${contextCompactionMessages} message${contextCompactionMessages === 1 ? "" : "s"}`);
    }
    if (contextCompactionTools > 0) {
      details.push(`${contextCompactionTools} tool output${contextCompactionTools === 1 ? "" : "s"}`);
    }
    return details.length > 0 ? details.join(" · ") : "Conversation history";
  }

  function toggleTool(id: string) {
    const next = new Set(toolsExpanded);
    next.has(id) ? next.delete(id) : next.add(id);
    toolsExpanded = next;
  }

  function setAllTools(expanded: boolean) {
    toolsExpanded = expanded && snapshot ? new Set(snapshot.tools.map((tool) => tool.id)) : new Set();
  }

  function sendSettingsUpdate(options: SettingsUpdateOptions = {}) {
    const includeSecrets = options.includeSecrets ?? false;
    sendCommand("saveSettings", {
      requestId: options.requestId,
      backendUrl,
      nodePath,
      inlineCompletionsEnabled,
      backendToken: includeSecrets ? backendToken : "",
      clearBackendToken: options.clearBackendToken ?? false,
      autoApproveReadOnly,
      chatZoom,
      showTimestamps,
      showRunTelemetry,
      desktopNotifications,
      autoDismissNotifications,
      contextMode,
      contextHttpBaseUrl,
      contextHttpApiKey: includeSecrets ? contextHttpApiKey : "",
      clearContextHttpApiKey: options.clearContextHttpApiKey ?? false,
      contextEmbeddingBaseUrl,
      contextEmbeddingModel,
      contextEmbeddingApiKey: includeSecrets ? contextEmbeddingApiKey : "",
      clearContextEmbeddingApiKey: options.clearContextEmbeddingApiKey ?? false,
      contextNeuralRerank,
      contextRerankBaseUrl,
      contextRerankModel,
    });
  }

  function beginSettingsSave(options: SettingsUpdateOptions = {}) {
    if (settingsSaveState === "saving") return;
    const requestId = crypto.randomUUID();
    const includeSecrets = options.includeSecrets ?? false;
    pendingSettingsSave = {
      requestId,
      backendToken,
      contextHttpApiKey,
      contextEmbeddingApiKey,
      sentBackendToken: includeSecrets,
      sentContextHttpApiKey: includeSecrets,
      sentContextEmbeddingApiKey: includeSecrets,
      clearBackendToken: options.clearBackendToken ?? false,
      clearContextHttpApiKey: options.clearContextHttpApiKey ?? false,
      clearContextEmbeddingApiKey: options.clearContextEmbeddingApiKey ?? false,
    };
    settingsSaveState = "saving";
    settingsDirtyWhileSaving = false;
    error = "";
    sendSettingsUpdate({ ...options, requestId });
  }

  function saveSettings() {
    beginSettingsSave({ includeSecrets: true });
  }

  function clearBackendTokenSetting() {
    if (settingsSaveState === "saving") return;
    backendToken = "";
    beginSettingsSave({ clearBackendToken: true });
  }

  function clearContextHttpApiKeySetting() {
    if (settingsSaveState === "saving") return;
    contextHttpApiKey = "";
    beginSettingsSave({ clearContextHttpApiKey: true });
  }

  function clearContextEmbeddingApiKeySetting() {
    if (settingsSaveState === "saving") return;
    contextEmbeddingApiKey = "";
    beginSettingsSave({ clearContextEmbeddingApiKey: true });
  }

  function markSettingsDirty() {
    backendTestRequestId = null;
    backendTestState = "idle";
    backendTestLabel = "";
    contextTestRequestId = null;
    contextTestState = "idle";
    contextTestLabel = "";
    if (settingsSaveState === "saving") {
      settingsDirtyWhileSaving = true;
    } else {
      settingsSaveState = "idle";
    }
  }

  function hydrateSettings(currentSnapshot: AppSnapshot) {
    backendUrl = currentSnapshot.settings.backendUrl;
    nodePath = currentSnapshot.settings.nodePath;
    inlineCompletionsEnabled = currentSnapshot.settings.inlineCompletionsEnabled;
    autoApproveReadOnly = currentSnapshot.settings.autoApproveReadOnly;
    chatZoom = currentSnapshot.settings.chatZoom;
    showTimestamps = currentSnapshot.settings.showTimestamps;
    showRunTelemetry = currentSnapshot.settings.showRunTelemetry;
    desktopNotifications = currentSnapshot.settings.desktopNotifications;
    autoDismissNotifications = currentSnapshot.settings.autoDismissNotifications;
    contextMode = currentSnapshot.settings.contextMode;
    contextHttpBaseUrl = currentSnapshot.settings.contextHttpBaseUrl;
    contextEmbeddingBaseUrl = currentSnapshot.settings.contextEmbeddingBaseUrl;
    contextEmbeddingModel = currentSnapshot.settings.contextEmbeddingModel;
    contextNeuralRerank = currentSnapshot.settings.contextNeuralRerank;
    contextRerankBaseUrl = currentSnapshot.settings.contextRerankBaseUrl;
    contextRerankModel = currentSnapshot.settings.contextRerankModel;
  }

  function testBackend() {
    const requestId = crypto.randomUUID();
    backendTestRequestId = requestId;
    backendTestState = "checking";
    backendTestLabel = "Checking the current URL and token";
    sendCommand("checkBackend", { requestId, backendUrl, backendToken });
  }

  function testContextEngine() {
    const requestId = crypto.randomUUID();
    contextTestRequestId = requestId;
    contextTestState = "checking";
    contextTestLabel = "Checking the current URL and token";
    sendCommand("checkContextEngine", {
      requestId,
      contextMode,
      contextHttpBaseUrl,
      contextHttpApiKey,
    });
  }

  function saveUserExperience() {
    sendSettingsUpdate();
  }

  function signIn() {
    error = "";
    sendCommand("signIn");
  }

  function signOut() {
    error = "";
    sendCommand("signOut");
  }

  function accountInitials(name?: string) {
    return (name ?? "CA").split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase()).join("") || "CA";
  }

  function usageLabel(kind: string) {
    return kind === "agent-run" ? "Agent runs" : kind === "completion" ? "Completions" : kind.replaceAll("-", " ");
  }

  function copyThread() {
    sendCommand("copyThread");
  }

  function copyText(text: string, successMessage: string) {
    if (window.codeAgentPost) {
      sendCommand("copyText", { text });
      notice = successMessage;
      return;
    }
    void navigator.clipboard.writeText(text)
      .then(() => {
        notice = successMessage;
      })
      .catch((cause) => {
        error = `Could not copy text: ${cause instanceof Error ? cause.message : String(cause)}`;
      });
  }

  function buildAuditReport(includeConversation = false) {
    if (!snapshot) return "CodeAgent audit unavailable: application state is not ready.";
    const active = snapshot.threads.find((thread) => thread.active);
    const lines = [
      "# CodeAgent runtime audit",
      "",
      `Generated: ${new Date().toISOString()}`,
      `Bridge protocol: ${PROTOCOL_VERSION}`,
      `Project: ${snapshot.projectName}`,
      `Thread: ${active?.id ?? "none"} (${snapshot.mode})`,
      `Run state: ${snapshot.runState}`,
      "",
      "## Backend",
      `State: ${snapshot.backendHealth.state}`,
      `Label: ${snapshot.backendHealth.label}`,
      `Protocol: ${snapshot.backendHealth.protocolVersion ?? "unknown"}`,
      `Provider: ${snapshot.backendHealth.provider ?? snapshot.models.provider ?? "unknown"}`,
      `Models: ${snapshot.models.state} (${snapshot.models.options.length})`,
      `Backend tools: ${snapshot.backendTools.filter((tool) => tool.available).length}/${snapshot.backendTools.length} available`,
      "",
      "## ContextEngine",
      `State: ${snapshot.context.state}`,
      `Label: ${snapshot.context.label}`,
      `Files/chunks: ${snapshot.context.files ?? 0}/${snapshot.context.chunks ?? 0}`,
      `Watched roots: ${snapshot.context.watchedRoots ?? 0}/${snapshot.context.roots ?? 0}`,
      `Incremental watcher: ${snapshot.context.watching ? "active" : "inactive"}`,
      `Retrieval: ${snapshot.context.hasEmbeddings ? "hybrid semantic" : "lexical and symbol"}`,
      `Last indexed: ${snapshot.context.lastIndexedAt ?? "unknown"}`,
      "",
      "## Managed runtimes",
      `Account: ${snapshot.account.state} (${snapshot.account.mode})`,
      `Configurations: ${snapshot.configurations.state} (${Object.values(snapshot.configurations.items).flat().length})`,
      `MCP: ${snapshot.mcpRuntime.state} (${snapshot.mcpRuntime.servers.length} servers, ${snapshot.mcpRuntime.tools.length} tools)`,
      `Hooks: ${snapshot.hookRuntime.state} (${snapshot.hookRuntime.configured} configured, ${snapshot.hookRuntime.recent.length} recent)`,
      `Plugins: ${snapshot.pluginRuntime.state} (${snapshot.pluginRuntime.items.length} configured, ${snapshot.pluginRuntime.commands.length} commands)`,
      `Jobs: ${snapshot.jobs.state} (${snapshot.jobs.items.length} retained)`,
      "",
      "## Conversation state",
      `Messages: ${snapshot.messages.length}`,
      `Tool cards: ${snapshot.tools.length}`,
      `Tasks: ${snapshot.tasks.length}`,
      `Queued messages: ${snapshot.messageQueue.length}`,
      `Attachments: ${snapshot.attachments.length}`,
      `Agent profile: ${snapshot.selectedAgentProfileId ?? "general"}`,
      `Agent turn: ${snapshot.agentRun.turnIndex + 1}`,
      `Verification: ${snapshot.agentRun.verificationState}`,
      "",
      "## Recent tool activity",
      ...snapshot.tools.slice(-40).map((tool) =>
        `- ${tool.name}: ${tool.status} - ${tool.summary.slice(0, 500).replaceAll("\n", " ")}`
      ),
    ];
    if (includeConversation) {
      lines.push(
        "",
        "## Recent conversation",
        "This section may contain repository or user-provided content because support export was explicitly requested.",
        ...snapshot.messages.slice(-40).flatMap((message) => [
          "",
          `### ${message.role.toUpperCase()} ${new Date(message.createdAt).toISOString()}`,
          message.content.slice(0, 8_000),
        ]),
      );
    }
    lines.push("", "Secrets, tokens, endpoint credentials, tool arguments, and tool result details are intentionally excluded.");
    return lines.join("\n");
  }

  function copyAuditReport(includeConversation = false) {
    copyText(
      buildAuditReport(includeConversation),
      includeConversation ? "Support bundle copied" : "Runtime audit copied",
    );
  }

  function refreshAudit() {
    sendCommand("checkBackend");
    sendCommand("getContextStatus");
    sendCommand("refreshConfigurations");
    sendCommand("refreshJobs");
    notice = "Runtime audit refresh requested";
  }

  function exportThread() {
    sendCommand("exportThread");
  }

  function toggleAutoRun() {
    autoApproveReadOnly = !autoApproveReadOnly;
    sendSettingsUpdate();
  }

  function updateContext() {
    const state = snapshot?.context.state;
    sendCommand(state === "not_indexed" || state === "error" || state === "unavailable" ? "indexWorkspace" : "refreshContextIndex");
  }

  function contextIndexState(currentSnapshot: AppSnapshot | null) {
    if (!currentSnapshot) return "unavailable";
    if (currentSnapshot.context.watchError) return "error";
    if ((currentSnapshot.context.pendingChanges ?? 0) > 0) return "indexing";
    return currentSnapshot.context.state;
  }

  function contextIndexLabel(currentSnapshot: AppSnapshot | null) {
    if (!currentSnapshot) return "Context unavailable";
    if (currentSnapshot.context.watchError) return "Sync error";
    const pending = currentSnapshot.context.pendingChanges ?? 0;
    if (pending > 0) return pending === 1 ? "Syncing 1 change" : `Syncing ${pending} changes`;
    if (currentSnapshot.context.state === "ready") return currentSnapshot.context.watching ? "Auto-synced" : "Indexed";
    return currentSnapshot.context.label;
  }

  function contextIndexTitle(currentSnapshot: AppSnapshot | null) {
    if (!currentSnapshot) return "Context unavailable";
    const details = [currentSnapshot.context.label];
    if (currentSnapshot.context.lastAutomaticIndexAt) {
      const timestamp = Date.parse(currentSnapshot.context.lastAutomaticIndexAt);
      if (Number.isFinite(timestamp)) details.push(`Last automatic sync ${formatTime(timestamp)}`);
    }
    if ((currentSnapshot.context.automaticIndexRuns ?? 0) > 0) {
      details.push(`${currentSnapshot.context.automaticIndexRuns} automatic sync runs`);
    }
    return details.join(" · ");
  }

  function backendConnectionState(currentSnapshot: AppSnapshot | null, testState: ServiceTestState) {
    if (testState === "checking") return "checking";
    if (testState === "success") return "online";
    if (testState === "error") return "offline";
    return currentSnapshot?.backendHealth.state ?? "unknown";
  }

  function backendConnectionLabel(currentSnapshot: AppSnapshot | null, testState: ServiceTestState, testLabel: string) {
    if (testState !== "idle") return testLabel;
    return currentSnapshot ? `Runtime · ${currentSnapshot.backendHealth.label}` : "Backend status unavailable";
  }

  function contextConnectionState(currentSnapshot: AppSnapshot | null, testState: ServiceTestState) {
    if (testState === "checking") return "checking";
    if (testState === "success") return "online";
    if (testState === "error") return "offline";
    if (!currentSnapshot) return "offline";
    if (currentSnapshot.context.watchError) return "offline";
    if (currentSnapshot.context.state === "checking" || currentSnapshot.context.state === "indexing") return "checking";
    if (currentSnapshot.context.state === "error" || currentSnapshot.context.state === "unavailable") return "offline";
    return "online";
  }

  function contextConnectionLabel(currentSnapshot: AppSnapshot | null, testState: ServiceTestState, testLabel: string) {
    if (testState !== "idle") return testLabel;
    if (!currentSnapshot) return "ContextEngine status unavailable";
    if (currentSnapshot.context.watchError) return `Sync error · ${currentSnapshot.context.watchError}`;
    if (currentSnapshot.context.state === "not_indexed") return "Runtime · Connected · Project not indexed";
    if (currentSnapshot.context.state === "ready" && currentSnapshot.context.hasEmbeddings) {
      return "Runtime · Connected · Model embeddings and semantic search ready";
    }
    if (currentSnapshot.context.state === "ready") return "Runtime · Connected · Index ready, embeddings unavailable";
    return `Runtime · ${currentSnapshot.context.label}`;
  }

  function toggleSkill(skill: WorkspaceSkill) {
    if (!isBusy(snapshot)) sendCommand("toggleSkill", { skillId: skill.id, selected: !skill.selected });
  }

  function selectedSkillCount() {
    return snapshot?.customization.skills.filter((skill) => skill.selected).length ?? 0;
  }

  function isBusy(currentSnapshot: AppSnapshot | null) {
    return currentSnapshot?.runState === "starting" || currentSnapshot?.runState === "running" || currentSnapshot?.runState === "awaiting_approval";
  }

  function compactTokenCount(value: number) {
    if (value < 1000) return String(value);
    return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)}k`;
  }

  function runActivityLabel(currentSnapshot: AppSnapshot) {
    const telemetry = currentSnapshot.agentRun;
    const phase = currentSnapshot.runState === "starting"
      ? "Starting queued message"
      : telemetry.verificationState === "required" || telemetry.phase === "verifying"
        ? "Verifying changes"
        : telemetry.overBudget || telemetry.phase === "compacting"
          ? "Compacting context"
          : telemetry.phase === "retrying"
            ? `Connection interrupted · retrying ${telemetry.retryAttempt}/${telemetry.retryMaxAttempts}`
            : telemetry.phase === "tools"
              ? telemetry.toolBatchTotal > 0
                ? `Running tool ${Math.min(telemetry.toolBatchCompleted + 1, telemetry.toolBatchTotal)}/${telemetry.toolBatchTotal}`
                : "Running tools"
              : telemetry.phase === "processing"
                ? telemetry.toolBatchTotal > 0 && telemetry.toolBatchCompleted >= telemetry.toolBatchTotal
                  ? "Tools complete · waiting for model"
                  : "Processing results"
                : telemetry.phase === "approval" || currentSnapshot.runState === "awaiting_approval"
                  ? "Waiting for approval"
                  : telemetry.phase === "thinking"
                    ? "Thinking"
                    : telemetry.phase === "starting"
                      ? "Starting agent"
                      : "Generating response";
    const details: string[] = [];
    if (telemetry.phase === "tools" && telemetry.toolBatchExecution === "sequential") {
      details.push("sequential execution");
    } else if (telemetry.catalogToolCount > 0) {
      details.push(`${telemetry.activeToolCount} tools ready · ${telemetry.catalogToolCount} catalog`);
    }
    if (telemetry.targetInputTokens > 0) {
      details.push(`${compactTokenCount(telemetry.estimatedInputTokens + telemetry.toolDefinitionTokens)}/${compactTokenCount(telemetry.contextWindowTokens)} context`);
    }
    return details.length > 0 ? `${phase} · ${details.join(" · ")}` : phase;
  }

  function runPhaseLabel(phase: AgentRunPhase) {
    const labels: Record<AgentRunPhase, string> = {
      idle: "idle",
      starting: "starting",
      thinking: "thinking",
      streaming: "streaming",
      tools: "tools",
      processing: "processing",
      retrying: "retrying",
      verifying: "verifying",
      approval: "approval",
      compacting: "compacting",
      failed: "failed",
    };
    return labels[phase];
  }

  function visibleThreads(
    threads: AppSnapshot["threads"],
    queryValue: string,
    pinOverrides: Record<string, boolean>,
  ) {
    const query = queryValue.trim().toLowerCase();
    const filtered = query
      ? threads.filter((thread) => thread.title.toLowerCase().includes(query))
      : threads;
    return filtered.map((thread) => pinOverrides[thread.id] === undefined
      ? thread
      : { ...thread, pinned: pinOverrides[thread.id] });
  }

  function toggleThreadPinned(threadId: string, pinned: boolean) {
    if (pendingThreadPins[threadId] !== undefined) return;
    pendingThreadPins = { ...pendingThreadPins, [threadId]: !pinned };
    sendCommand("toggleThreadPinned", { threadId, pinned: !pinned });
  }

  function deleteThread(threadId: string) {
    if (pendingThreadDeletes.has(threadId)) return;
    pendingThreadDeletes = new Set(pendingThreadDeletes).add(threadId);
    sendCommand("deleteThread", { threadId });
  }

  function formatTime(timestamp: number) {
    if (!Number.isFinite(timestamp) || timestamp <= 0) return "";
    return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(timestamp);
  }

  function formatDuration(durationMs: number) {
    if (!Number.isFinite(durationMs) || durationMs <= 0) return "";
    if (durationMs < 1_000) return `${Math.max(1, Math.round(durationMs))} ms`;
    if (durationMs < 60_000) return `${(durationMs / 1_000).toFixed(durationMs < 10_000 ? 1 : 0)} s`;
    const minutes = Math.floor(durationMs / 60_000);
    const seconds = Math.round((durationMs % 60_000) / 1_000);
    return `${minutes}m ${seconds}s`;
  }

  function toolTimeline(tool: ToolRun) {
    const createdAt = tool.createdAt ?? 0;
    const updatedAt = tool.updatedAt ?? createdAt;
    return [showTimestamps ? formatTime(createdAt) : "", updatedAt > createdAt ? formatDuration(updatedAt - createdAt) : ""]
      .filter(Boolean)
      .join(" · ");
  }

  function toolPassMeta(turnIndex: number, tools: ToolRun[]) {
    const created = tools.map((tool) => tool.createdAt ?? 0).filter((value) => value > 0);
    const updated = tools.map((tool) => tool.updatedAt ?? tool.createdAt ?? 0).filter((value) => value > 0);
    const startedAt = created.length > 0 ? Math.min(...created) : 0;
    const endedAt = updated.length > 0 ? Math.max(...updated) : startedAt;
    return [
      `Turn ${turnIndex + 1}`,
      showTimestamps ? formatTime(startedAt) : "",
      endedAt > startedAt ? formatDuration(endedAt - startedAt) : "",
    ].filter(Boolean).join(" · ");
  }

  function toolPassStats(tools: ToolRun[]) {
    const passed = tools.filter((tool) => tool.status === "completed").length;
    const failed = tools.filter((tool) => tool.status === "failed" || tool.status === "rejected").length;
    return {
      passed,
      failed,
      active: Math.max(0, tools.length - passed - failed),
      total: tools.length,
    };
  }

  function toolPassStatus(tools: ToolRun[]) {
    const stats = toolPassStats(tools);
    if (stats.failed > 0) return `${stats.passed}/${stats.total} passed · ${stats.failed} failed`;
    if (stats.active > 0) return `${stats.passed}/${stats.total} passed · ${stats.active} active`;
    return `${stats.passed}/${stats.total} passed`;
  }

  function setToolPassExpanded(tools: ToolRun[], expanded: boolean) {
    const next = new Set(toolsExpanded);
    tools.forEach((tool) => expanded ? next.add(tool.id) : next.delete(tool.id));
    toolsExpanded = next;
  }

  function hasRunTelemetry(currentSnapshot: AppSnapshot | null) {
    if (!currentSnapshot) return false;
    const telemetry = currentSnapshot.agentRun;
    return telemetry.turnIndex > 0
      || telemetry.phase !== "idle"
      || telemetry.estimatedInputTokens > 0
      || telemetry.catalogToolCount > 0
      || telemetry.toolBatchTotal > 0
      || telemetry.retryAttempt > 0
      || telemetry.verificationState !== "idle";
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
    settingsNavigationOpen = false;
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
    if (view === "edits") sendCommand("listCheckpoints");
    if (view === "jobs") sendCommand("refreshJobs");
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

  type SlashCommandOption = { command: string; description: string; argumentHint?: string };

  const localSlashActions: Record<string, () => void> = {
    "/commit": () => openWorkspaceView("git"),
    "/tasks": () => openWorkspaceView("tasks"),
    "/rules": () => openSettings("Rules & Guidelines"),
  };

  function runLocalSlash(text: string): boolean {
    const action = localSlashActions[text.toLowerCase()];
    if (!action) return false;
    action();
    return true;
  }

  function availableSlashCommands(): SlashCommandOption[] {
    const commands = new Map<string, SlashCommandOption>(
      SLASH_COMMANDS.map((item) => [item.command, { ...item }]),
    );
    for (const configuration of snapshot?.configurations.items.commands ?? []) {
      if (configuration.value.enabled === false) continue;
      const command = `/${configuration.id}`;
      if (localSlashActions[command]) continue;
      const name = typeof configuration.value.name === "string" ? configuration.value.name : configuration.id;
      const description = typeof configuration.value.description === "string"
        ? configuration.value.description
        : `Run ${name}`;
      const argumentHint = typeof configuration.value.argumentHint === "string"
        ? configuration.value.argumentHint
        : undefined;
      commands.set(command, { command, description, argumentHint });
    }
    for (const contribution of snapshot?.pluginRuntime.commands ?? []) {
      const command = `/${contribution.id}`;
      if (localSlashActions[command]) continue;
      commands.set(command, {
        command,
        description: contribution.description ?? `${contribution.name} · ${contribution.pluginId}@${contribution.pluginVersion}`,
        argumentHint: contribution.argumentHint,
      });
    }
    for (const contribution of snapshot?.pluginRuntime.prompts ?? []) {
      const command = `/${contribution.id}`;
      if (localSlashActions[command]) continue;
      commands.set(command, {
        command,
        description: contribution.description ?? `${contribution.name} · ${contribution.pluginId}@${contribution.pluginVersion}`,
        argumentHint: contribution.argumentHint,
      });
    }
    return [...commands.values()].sort((left, right) => left.command.localeCompare(right.command));
  }

  function insertMention(kind: string) {
    atOpen = false;
    if (kind === "file") {
      sendCommand("pickContext");
      return;
    }
    if (kind === "editor") {
      sendCommand("attachActiveEditor");
      return;
    }
    if (kind === "rule") {
      openSettings("Rules & Guidelines");
      return;
    }
    prompt = `${prompt}${prompt.endsWith(" ") || !prompt ? "" : " "}@`;
  }

  function filteredSlash() {
    const q = prompt.startsWith("/") ? prompt.slice(1).split(/\s+/, 1)[0].toLowerCase() : "";
    return availableSlashCommands().filter((item) => !q || item.command.slice(1).includes(q) || item.description.toLowerCase().includes(q));
  }

  function filteredTools() {
    const q = toolFilter.trim().toLowerCase();
    return TOOL_CATALOG.filter((tool) => !q || tool.id.includes(q) || tool.name.toLowerCase().includes(q) || tool.desc.toLowerCase().includes(q));
  }

  function attachmentIcon(item: { kind?: string }) {
    return item.kind === "image" ? "image" : item.kind === "ide_state" ? "code" : "file";
  }

  function filteredIcons() {
    const q = iconFilter.trim().toLowerCase();
    return ICON_NAMES.filter((name) => !q || name.includes(q)).slice(0, 240);
  }

  function toolConnected(entry: (typeof TOOL_CATALOG)[number]) {
    return entry.connected || Boolean(
      entry.pluginTool && snapshot?.backendTools.some((tool) => tool.name === entry.pluginTool && tool.available),
    );
  }

  function toolConnectionTitle(entry: (typeof TOOL_CATALOG)[number]) {
    if (toolConnected(entry)) return "Connected";
    return snapshot?.backendTools.find((tool) => tool.catalogId === entry.id)?.unavailableReason ?? "Not connected in this build";
  }

  function catalogName(catalogId: string) {
    return TOOL_CATALOG.find((entry) => entry.id === catalogId)?.name ?? catalogId;
  }

  function insertToolSeed(toolId: string) {
    const entry = TOOL_CATALOG.find((tool) => tool.id === toolId);
    if (!entry) return;
    prompt = toolConnected(entry)
      ? `Use the ${entry.name} tool to help with: `
      : `[${entry.name}] is not connected in this build. `;
    currentView = "chat";
  }

  function filteredTasks(currentSnapshot: AppSnapshot | null, filter: typeof taskFilter) {
    if (!currentSnapshot || filter === "all") return currentSnapshot?.tasks ?? [];
    if (filter === "pending") return currentSnapshot.tasks.filter((task) => task.state === "not_started");
    if (filter === "running") return currentSnapshot.tasks.filter((task) => task.state === "in_progress");
    return currentSnapshot.tasks.filter((task) => task.state === "completed" || task.state === "cancelled");
  }

  function filteredJobs(currentSnapshot: AppSnapshot | null, filter: typeof jobFilter) {
    if (!currentSnapshot || filter === "all") return currentSnapshot?.jobs.items ?? [];
    if (filter === "active") return currentSnapshot.jobs.items.filter((job) => job.status === "queued" || job.status === "running");
    if (filter === "completed") return currentSnapshot.jobs.items.filter((job) => job.status === "completed");
    return currentSnapshot.jobs.items.filter((job) => job.status === "failed" || job.status === "cancelled");
  }

  function createJob() {
    const task = jobPrompt.trim();
    if (!task || creatingJob) return;
    creatingJob = true;
    sendCommand("createJob", {
      prompt: task,
      role: jobRole,
      context: jobContext.trim() || null,
      expectedOutput: jobExpectedOutput.trim() || null,
      maxOutputTokens: jobMaxOutputTokens,
      model: jobModel || null,
    });
    jobPrompt = "";
    jobContext = "";
    jobExpectedOutput = "";
  }

  function formatJobTime(value?: string) {
    if (!value) return "time unavailable";
    const timestamp = Date.parse(value);
    return Number.isFinite(timestamp) ? new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "short" }).format(timestamp) : value;
  }

  function useJobOutput(job: ProductJob) {
    if (!job.output) return;
    prompt = job.output;
    currentView = "chat";
    notice = "Job result added to the composer";
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
      launch_process: "Launch Process",
      list_processes: "Managed Processes",
      read_process: "Read Process Output",
      write_process: "Write Process Input",
      wait_process: "Wait for Process",
      kill_process: "Stop Process",
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

  function terminalCommand(tool: ToolRun) {
    return tool.summary.replace(/\s+\(exit -?\d+\)$/, "").trim() || "Command";
  }

  function terminalOutput(tool: ToolRun) {
    return (tool.detail ?? "").replace(/^exit=-?\d+(?: timeout=true)?\n?/, "").trimEnd();
  }

  function terminalExitCode(tool: ToolRun) {
    const match = tool.detail?.match(/^exit=(-?\d+)/);
    return match ? Number(match[1]) : null;
  }

  function terminalTimedOut(tool: ToolRun) {
    return /^exit=-?\d+ timeout=true/.test(tool.detail ?? "");
  }

  function terminalFailed(tool: ToolRun) {
    const exitCode = terminalExitCode(tool);
    return terminalTimedOut(tool) || (exitCode !== null && exitCode !== 0);
  }

  function terminalStatus(tool: ToolRun) {
    if (terminalTimedOut(tool)) return "timed out";
    const exitCode = terminalExitCode(tool);
    return exitCode === null ? statusLabel(tool.status) : `exit ${exitCode}`;
  }

  function isManagedProcessTool(tool: ToolRun) {
    return managedProcessToolNames.has(tool.name);
  }

  function managedProcessMetadata(tool: ToolRun) {
    return (tool.detail ?? "").split("\n\n", 1)[0].split("\n").filter((line) => !line.startsWith("command=")).join("\n");
  }

  function managedProcessCommand(tool: ToolRun) {
    return tool.detail?.match(/^command=(.*)$/m)?.[1] ?? "";
  }

  function managedProcessOutput(tool: ToolRun) {
    const separator = tool.detail?.indexOf("\n\n") ?? -1;
    return separator >= 0 ? tool.detail?.slice(separator + 2).trimEnd() ?? "" : "";
  }

  function managedProcessState(tool: ToolRun) {
    if (/^waiting_for_input=true$/m.test(tool.detail ?? "")) return "waiting for input";
    return tool.detail?.match(/^state=(.*)$/m)?.[1] ?? statusLabel(tool.status);
  }

  function changeTools() {
    return snapshot?.tools.filter((tool) => Boolean(tool.changePath)) ?? [];
  }

  function openMermaid(tool: ToolRun) {
    if (!tool.detail) return;
    openMermaidSource(tool.detail, tool.summary || "Mermaid diagram");
  }

  function openMermaidSource(source: string, title = "Mermaid diagram") {
    mermaidSource = source;
    mermaidTitle = title;
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

  function modeLabel(mode: Mode | string) {
    if (mode === "ask") return "Ask";
    if (mode === "chat") return "Chat";
    return "Agent";
  }

  function setChatZoom(next: number, persist = true) {
    const clamped = Math.min(CHAT_ZOOM_MAX, Math.max(CHAT_ZOOM_MIN, next));
    if (clamped === chatZoom) return;
    chatZoom = clamped;
    if (persist) saveUserExperience();
  }

  function adjustChatZoom(delta: number) {
    setChatZoom(chatZoom + delta);
  }

  function resetChatZoom() {
    setChatZoom(CHAT_ZOOM_DEFAULT);
  }

  function handleChatZoomShortcut(event: KeyboardEvent): boolean {
    const primary = event.metaKey || event.ctrlKey;
    if (!primary || event.altKey) return false;

    const increase = event.code === "Equal" || event.key === "+" || event.key === "=";
    const decrease = event.code === "Minus" || event.key === "-" || event.key === "_";
    const reset = event.code === "Digit0" || event.key === "0";
    if (!increase && !decrease && !reset) return false;

    if (increase) adjustChatZoom(CHAT_ZOOM_STEP);
    else if (decrease) adjustChatZoom(-CHAT_ZOOM_STEP);
    else resetChatZoom();

    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    return true;
  }

  function seedSlash() {
    if (!prompt.startsWith("/")) prompt = "/";
    slashOpen = true;
    atOpen = false;
    modeMenuOpen = false;
    agentMenuOpen = false;
    skillsOpen = false;
  }

  function seedMention() {
    atOpen = true;
    slashOpen = false;
    modeMenuOpen = false;
    agentMenuOpen = false;
    skillsOpen = false;
  }

  type TimelineMessage = AppSnapshot["messages"][number];

  type TimelineItem =
    | { kind: "user"; message: TimelineMessage }
    | { kind: "assistant"; message: TimelineMessage }
    | { kind: "queued"; message: AppSnapshot["messageQueue"][number]; position: number }
    | { kind: "tools"; runId?: string; turnIndex: number; tools: ToolRun[] }
    | { kind: "activity"; label: string; phase: AgentRunPhase }
    | { kind: "tasks" };

  function nextConversationTimelineSequence(currentSnapshot: AppSnapshot) {
    return Math.max(
      0,
      ...currentSnapshot.messages.map((message) => message.timelineSequence ?? 0),
      ...currentSnapshot.tools.map((tool) => tool.timelineSequence ?? 0),
    ) + 1;
  }

  function conversationTimeline(currentSnapshot: AppSnapshot, optimisticMessages: ChatMessage[]): TimelineItem[] {
    const persistedMessageIds = new Set(currentSnapshot.messages.map((message) => message.id));
    const messages = [
      ...currentSnapshot.messages,
      ...optimisticMessages.filter((message) => !persistedMessageIds.has(message.id)),
    ];
    const tools = currentSnapshot.tools;
    const toolGroups = new Map<string, { runId?: string; turnIndex: number; tools: ToolRun[] }>();
    tools.forEach((tool) => {
      const turnIndex = tool.turnIndex ?? 0;
      const key = `${tool.runId ?? "legacy"}:${turnIndex}`;
      const group = toolGroups.get(key) ?? { runId: tool.runId, turnIndex, tools: [] };
      group.tools.push(tool);
      toolGroups.set(key, group);
    });
    const ordered = [
      ...messages
        .filter((message) => message.role === "user" || message.role === "assistant")
        .map((message, index) => ({
          item: { kind: message.role, message } as TimelineItem,
          sequence: message.timelineSequence ?? 0,
          createdAt: message.createdAt,
          fallback: index * 2,
        })),
      ...[...toolGroups.values()].map((group, index) => {
        const sortedTools = [...group.tools].sort((left, right) =>
          (left.timelineSequence ?? 0) - (right.timelineSequence ?? 0) ||
          (left.createdAt ?? 0) - (right.createdAt ?? 0),
        );
        return {
          item: { kind: "tools", runId: group.runId, turnIndex: group.turnIndex, tools: sortedTools } as TimelineItem,
          sequence: Math.min(...sortedTools.map((tool) => tool.timelineSequence ?? Number.MAX_SAFE_INTEGER)),
          createdAt: Math.min(...sortedTools.map((tool) => tool.createdAt ?? 0)),
          fallback: messages.length * 2 + index,
        };
      }),
    ].sort((left, right) => {
      const leftHasSequence = left.sequence > 0 && left.sequence < Number.MAX_SAFE_INTEGER;
      const rightHasSequence = right.sequence > 0 && right.sequence < Number.MAX_SAFE_INTEGER;
      if (leftHasSequence && rightHasSequence) return left.sequence - right.sequence;
      return left.createdAt - right.createdAt || left.fallback - right.fallback;
    });
    const items = ordered.map((entry) => entry.item);

    if (isBusy(currentSnapshot)) {
      items.push({
        kind: "activity",
        label: runActivityLabel(currentSnapshot),
        phase: currentSnapshot.agentRun.phase,
      });
    }
    currentSnapshot.messageQueue.forEach((message, index) => items.push({ kind: "queued", message, position: index + 1 }));
    if (currentSnapshot.tasks.length > 0) items.push({ kind: "tasks" });
    return items;
  }
</script>

<svelte:window onkeydown={(event) => {
  if (handleChatZoomShortcut(event)) return;
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") submit();
  if (event.key === "Escape") {
    const settingsDrawerWasOpen = currentView === "settings" && settingsNavigationOpen;
    threadDrawerOpen = false;
    closeMenus();
    if (settingsDrawerWasOpen) {
      settingsNavigationOpen = false;
      event.preventDefault();
    }
  }
}} />

{#if !snapshot}
  <main class="loading"><Icon name="plugin-icon" size={18} /><span>Starting CodeAgent</span></main>
{:else}
  <main
    class="shell"
    class:settings-active={currentView === "settings"}
    class:canvas-active={currentView === "mermaid" || currentView === "jobs" || currentView === "images" || currentView === "tools" || currentView === "icons" || currentView === "edits" || currentView === "feedback"}
    style={`--chat-zoom:${chatZoom / 100}`}
  >
    <header class="app-header tw-head">
      <div class="app-title">
        <span class="app-logo"><Icon name="plugin-icon" size={18} /></span>
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
                <button onclick={() => { const thread = activeThread(); if (thread) toggleThreadPinned(thread.id, thread.pinned); closeMenus(); }}><Icon name="pin" size={14} /><span>Pin / Unpin</span></button>
                <button class="danger" onclick={() => { const id = activeThread()?.id; if (id) deleteThread(id); closeMenus(); }}><Icon name="trash" size={14} /><span>Delete</span></button>
                <div class="menu-sep"></div>
                <button onclick={() => openWorkspaceView("tasks")}><Icon name="list-checks" size={14} /><span>Agent Tasklist</span></button>
                <button onclick={() => openWorkspaceView("jobs")}><Icon name="bot" size={14} /><span>Durable Jobs</span></button>
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
          <div class="ch-actions">
            <span class="context-meter" title={contextMeterTitle(snapshot)}><Icon name="gauge" size={12} /> Context <b>{contextMeterText(snapshot)}</b></span>
            <div class="chat-zoom-controls" role="group" aria-label="Chat zoom">
              <button class="icon-button compact" title="Decrease chat zoom (Cmd/Ctrl -)" aria-label="Decrease chat zoom" disabled={chatZoom <= CHAT_ZOOM_MIN} onclick={() => adjustChatZoom(-CHAT_ZOOM_STEP)}><Icon name="minus" size={13} /></button>
              <button class="chat-zoom-value" title="Reset chat zoom (Cmd/Ctrl 0)" aria-label={`Reset chat zoom, currently ${chatZoom}%`} onclick={resetChatZoom}>{chatZoom}%</button>
              <button class="icon-button compact" title="Increase chat zoom (Cmd/Ctrl +)" aria-label="Increase chat zoom" disabled={chatZoom >= CHAT_ZOOM_MAX} onclick={() => adjustChatZoom(CHAT_ZOOM_STEP)}><Icon name="plus" size={13} /></button>
            </div>
            <button class="icon-button compact" title="Share link to session" onclick={copyThread}><Icon name="share-2" size={13} /></button>
            <div class="more-control">
              <button class="icon-button compact" class:active={threadOptOpen} title="Thread options" onclick={() => { threadOptOpen = !threadOptOpen; moreMenuOpen = false; }}><Icon name="ellipsis" size={14} /></button>
              {#if threadOptOpen}
                <div class="workspace-menu menu thread-opt-menu">
                  <button onclick={() => beginRename()}><Icon name="square-pen" size={13} /><span>Rename thread</span></button>
                  <button onclick={() => { const thread = activeThread(); if (thread) toggleThreadPinned(thread.id, thread.pinned); closeMenus(); }}><Icon name="pin" size={13} /><span>Pin / Unpin</span></button>
                  <button onclick={() => { copyThread(); closeMenus(); }}><Icon name="share-2" size={13} /><span>Share link to session</span></button>
                  <button onclick={() => { exportThread(); closeMenus(); }}><Icon name="upload" size={13} /><span>Export conversation</span></button>
                  <button onclick={() => { sendCommand("importThread"); closeMenus(); }}><Icon name="file-input" size={13} /><span>Import conversation</span></button>
                  <button onclick={() => startNewThread(snapshot?.mode)}><Icon name="git-branch" size={13} /><span>Continue in New Chat</span></button>
                  <div class="menu-sep"></div>
                  <button onclick={() => openWorkspaceView("feedback")}><Icon name="flag" size={13} /><span>Report an Issue</span></button>
                  <div class="menu-sep"></div>
                  <button class="danger" onclick={() => { const id = activeThread()?.id; if (id) deleteThread(id); closeMenus(); }}><Icon name="trash-2" size={13} /><span>Delete thread</span></button>
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
          <button class="chip index-state {contextIndexState(snapshot)}" title={`${contextIndexTitle(snapshot)} · Click to check sync now`} disabled={contextIndexState(snapshot) === "indexing" || contextIndexState(snapshot) === "checking"} onclick={updateContext}>
            <Icon name={snapshot.context.watching ? "refresh-cw" : "database"} size={12} /><span>{contextIndexLabel(snapshot)}</span>
          </button>
        </div>

        {#if showRunTelemetry && hasRunTelemetry(snapshot)}
          <div class="run-telemetry" title={snapshot.agentRun.retryMessage ?? snapshot.agentRun.verificationMessage ?? snapshot.agentRun.activeToolNames.join(", ")}>
            <span><Icon name="activity" size={11} />Turn {snapshot.agentRun.turnIndex + 1}</span>
            {#if isBusy(snapshot)}<span class="run-phase {snapshot.agentRun.phase}">{runPhaseLabel(snapshot.agentRun.phase)}</span>{/if}
            {#if snapshot.agentRun.targetInputTokens > 0}<span>{compactTokenCount(snapshot.agentRun.estimatedInputTokens + snapshot.agentRun.toolDefinitionTokens)} / {compactTokenCount(snapshot.agentRun.contextWindowTokens)} context · compact at {compactTokenCount(snapshot.agentRun.targetInputTokens + snapshot.agentRun.toolDefinitionTokens)}</span>{/if}
            {#if snapshot.agentRun.catalogToolCount > 0}<span title="Tool definitions available to the model, not executed calls">{snapshot.agentRun.activeToolCount} tools ready · {snapshot.agentRun.catalogToolCount} catalog</span>{/if}
            {#if snapshot.agentRun.toolBatchTotal > 0}<span>{snapshot.agentRun.toolBatchCompleted}/{snapshot.agentRun.toolBatchTotal} tools · {snapshot.agentRun.toolBatchExecution ?? "sequential"}</span>{/if}
            {#if snapshot.agentRun.retryAttempt > 0}<i class="retrying">retry {snapshot.agentRun.retryAttempt}/{snapshot.agentRun.retryMaxAttempts}</i>{/if}
            {#if snapshot.agentRun.verificationState !== "idle"}<i class={snapshot.agentRun.verificationState}>{snapshot.agentRun.verificationState}</i>{/if}
          </div>
        {/if}

        {#if contextCompactionState !== "idle"}
          <div class="context-compaction-strip {contextCompactionState}" role="status" aria-live="polite">
            <Icon name={contextCompactionState === "complete" ? "circle-check" : "refresh-cw"} size={12} />
            <span>
              <strong>{contextCompactionState === "complete" ? "Context compacted" : "Compacting context"}</strong>
              <small>{contextCompactionDetail()}</small>
            </span>
            <b>{contextCompactionProgress}%</b>
            <i class="context-compaction-track" aria-hidden="true"><i style={`width: ${contextCompactionProgress}%`}></i></i>
          </div>
        {/if}

        <div class="conversation" bind:this={conversationElement} onscroll={updateConversationFollow}>
          {#if snapshot.messages.length === 0 && pendingUserMessages.length === 0}
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
              {#each conversationTimeline(snapshot, pendingUserMessages) as item (item.kind === "user" || item.kind === "assistant" || item.kind === "queued" ? item.message.id : item.kind === "tools" ? `tools-${item.runId ?? "legacy"}-${item.turnIndex}` : item.kind)}
                {#if item.kind === "user"}
                  <article class="user-message">
                    {#if showTimestamps}<time>{formatTime(item.message.createdAt)}</time>{/if}
                    <div class="user-message-content">{item.message.content}</div>
                  </article>
                {:else if item.kind === "assistant"}
                  {#if item.message.content}
                    <section class="assistant-turn">
                      <div class="assistant-message">
                        <MarkdownMessage
                          content={item.message.content}
                          onOpenMermaid={(source) => openMermaidSource(source)}
                        />
                      </div>
                      {#if showTimestamps || (showRunTelemetry && item.message.turnIndex !== undefined)}
                        <div class="assistant-meta">
                          {#if showTimestamps}<time>{formatTime(item.message.createdAt)}</time>{/if}
                          {#if showRunTelemetry && item.message.turnIndex !== undefined}<span>Turn {item.message.turnIndex + 1}</span>{/if}
                        </div>
                      {/if}
                      <div class="assistant-actions">
                        <button title="Copy response" aria-label="Copy response" onclick={() => copyText(item.message.content, "Response copied")}><Icon name="copy" size={13} /></button>
                      </div>
                    </section>
                  {/if}
                {:else if item.kind === "queued"}
                  <article class="user-message queued-message">
                    <header><span>Queued</span><span class="queued-position">#{item.position} · sends after the current run</span><Icon name="clock" size={14} /></header>
                    <div>{item.message.text}</div>
                    <footer><button title="Remove queued message" onclick={() => sendCommand("removeQueuedMessage", { messageId: item.message.id })}><Icon name="x" size={12} />Remove</button></footer>
                  </article>
                {:else if item.kind === "activity"}
                  <div class="generation-status {item.phase}" role="status" aria-live="polite" title={snapshot.agentRun.retryMessage ?? snapshot.agentRun.activeToolNames.join(", ")}><Icon name="circle-dashed" size={13} /><span>{item.label}</span></div>
                {:else if item.kind === "tools"}
                  <section class="agent-turn tools-turn">
                    <header class="tool-pass-header">
                      <span class="tool-pass-title"><Icon name="wrench" size={12} /><strong>Agent tool pass</strong></span>
                      <span class="tool-pass-copy">
                        <b class:failed={toolPassStats(item.tools).failed > 0}>{toolPassStatus(item.tools)}</b>
                        <small>{toolPassMeta(item.turnIndex, item.tools)}</small>
                      </span>
                      <span class="tool-pass-actions">
                        <button title="Expand this tool pass" aria-label="Expand this tool pass" onclick={() => setToolPassExpanded(item.tools, true)}><Icon name="chevrons-down" size={12} /></button>
                        <button title="Collapse this tool pass" aria-label="Collapse this tool pass" onclick={() => setToolPassExpanded(item.tools, false)}><Icon name="chevrons-down-up" size={12} /></button>
                      </span>
                    </header>
                    <div class="tool-list">
                      {#each item.tools as tool (tool.id)}
                        <section class="tool-card {tool.status}">
                          <button class="tool-header" onclick={() => toggleTool(tool.id)} aria-expanded={toolsExpanded.has(tool.id)}>
                            <span class="tool-icon"><Icon name={toolIcon(tool)} size={14} /></span>
                            <span class="tool-copy"><strong>{toolTitle(tool)}</strong><small>{tool.summary}</small></span>
                            <span class="tool-state-copy"><span class="tool-status">{#if tool.status === "running"}<Icon name="circle-dashed" size={10} />{/if}{statusLabel(tool.status)}</span>{#if showTimestamps && toolTimeline(tool)}<time>{toolTimeline(tool)}</time>{/if}</span>
                            {#if toolsExpanded.has(tool.id)}<Icon name="chevron-down" size={14} />{:else}<Icon name="chevron-right" size={14} />{/if}
                          </button>
                          {#if toolsExpanded.has(tool.id)}
                            <div class="tool-detail">
                              {#if tool.name === "run_terminal"}
                                <div class="shell-details">
                                  <section>
                                    <header>
                                      <strong>Command</strong>
                                      <button title="Copy command" aria-label="Copy command" onclick={() => copyText(terminalCommand(tool), "Command copied")}><Icon name="copy" size={12} /></button>
                                    </header>
                                    <pre><span>$</span> {terminalCommand(tool)}</pre>
                                  </section>
                                  {#if tool.detail}
                                    <section class:error={terminalFailed(tool)}>
                                      <header>
                                        <strong>{terminalFailed(tool) ? "Error" : "Output"}</strong>
                                        <i>{terminalStatus(tool)}</i>
                                        <button title="Copy output" aria-label="Copy output" onclick={() => copyText(terminalOutput(tool), "Terminal output copied")}><Icon name="copy" size={12} /></button>
                                      </header>
                                      <pre>{terminalOutput(tool) || "Command completed without output."}</pre>
                                    </section>
                                  {/if}
                                  <button class="secondary-action" onclick={() => sendCommand("openTerminal")}><Icon name="square-terminal" size={13} />Show Terminal</button>
                                </div>
                              {:else if isManagedProcessTool(tool)}
                                <div class="shell-details">
                                  {#if managedProcessCommand(tool)}
                                    <section>
                                      <header>
                                        <strong>Command</strong>
                                        <button title="Copy command" aria-label="Copy command" onclick={() => copyText(managedProcessCommand(tool), "Command copied")}><Icon name="copy" size={12} /></button>
                                      </header>
                                      <pre><span>$</span> {managedProcessCommand(tool)}</pre>
                                    </section>
                                  {/if}
                                  <section>
                                    <header>
                                      <strong>Process</strong>
                                      <i>{managedProcessState(tool)}</i>
                                      <button title="Copy process details" aria-label="Copy process details" onclick={() => copyText(managedProcessMetadata(tool), "Process details copied")}><Icon name="copy" size={12} /></button>
                                    </header>
                                    <pre>{managedProcessMetadata(tool)}</pre>
                                  </section>
                                  {#if managedProcessOutput(tool)}
                                    <section>
                                      <header>
                                        <strong>Output</strong>
                                        <button title="Copy output" aria-label="Copy output" onclick={() => copyText(managedProcessOutput(tool), "Process output copied")}><Icon name="copy" size={12} /></button>
                                      </header>
                                      <pre>{managedProcessOutput(tool)}</pre>
                                    </section>
                                  {/if}
                                </div>
                              {:else if tool.name === "render_mermaid" && tool.detail}
                                <span class="detail-label">Details</span>
                                <MermaidCanvas source={tool.detail} compact />
                                <button class="secondary-action" onclick={() => openMermaid(tool)}><Icon name="maximize-2" size={13} />Open Canvas</button>
                              {:else}
                                <span class="detail-label">Details</span>
                                {#if tool.detail}<pre>{tool.detail}</pre>{:else}<p>No additional output.</p>{/if}
                              {/if}
                              {#if tool.changePath}
                                <div class="file-actions">
                                  <span>{tool.changePath}</span>
                                  <button onclick={() => sendCommand("openDiff", { toolId: tool.id })}><Icon name="file-diff" size={13} />View Diff</button>
                                  {#if tool.canRevert}<button onclick={() => sendCommand("revertChange", { toolId: tool.id })}><Icon name="undo-2" size={13} />Undo</button>{/if}
                                </div>
                              {/if}
                            </div>
                          {/if}
                          {#if tool.status === "approval"}
                            <div class="approval" role="status" aria-live="polite">
                              <Icon name="circle-alert" size={14} />
                              <span>Waiting for user input</span>
                              <button disabled={resolvingApprovalIds.has(tool.id)} onclick={() => resolveApproval(tool.id, false)}>Skip</button>
                              <button class="approve" disabled={resolvingApprovalIds.has(tool.id)} onclick={() => resolveApproval(tool.id, true)}><Icon name="circle-play" size={12} />{resolvingApprovalIds.has(tool.id) ? "Approving..." : "Approve"}</button>
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

        {#if error || notice}
          <div class="chat-toast-stack">
            {#if error}<div class="error-banner"><Icon name="circle-alert" size={14} /><span>{error}</span><button title="Dismiss" onclick={() => error = ""}><Icon name="x" size={13} /></button></div>{/if}
            {#if notice}<div class="notice-banner"><Icon name="circle-check" size={13} /><span>{notice}</span><button title="Dismiss" onclick={() => notice = ""}><Icon name="x" size={13} /></button></div>{/if}
          </div>
        {/if}

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
                <span class="chip accent"><Icon name={attachmentIcon(item)} size={12} /><b title={item.path}>{item.label}</b><button title="Remove" onclick={() => sendCommand("removeContext", { id: item.id })}><Icon name="x" size={11} /></button></span>
              {/each}
              <button class="chip" onclick={() => sendCommand("pickContext")}><Icon name="plus" size={12} /> context</button>
            </div>
          {:else}
            <div class="context-chips chips">
              <button class="chip" onclick={() => sendCommand("pickContext")}><Icon name="plus" size={12} /> context</button>
            </div>
          {/if}
          <div class="composer comp" class:busy={isBusy(snapshot)}>
            {#if snapshot.mode === "ask"}
              <div class="ask-badge"><Icon name="message-circle" size={12} /> Ask Mode (read-only)<button class="icon-button compact" title="Switch to Agent" onclick={() => setMode("agent")}><Icon name="x" size={11} /></button></div>
            {/if}
            {#if slashOpen}
              <div class="composer-popup slash-menu">
                <header><strong>Slash commands</strong><button class="icon-button compact" title="Close" onclick={() => slashOpen = false}><Icon name="x" size={12} /></button></header>
                {#each filteredSlash() as item}
                  <button onclick={() => insertSlash(item.command)}><strong>{item.command}</strong><small>{item.description}{item.argumentHint ? ` · ${item.argumentHint}` : ""}</small></button>
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
            {#if modelMenuOpen}
              <div class="composer-popup model-menu" role="listbox" aria-label="Available models">
                {#each availableModels(snapshot.models) as model}
                  <button type="button" class:active={activeModelId(snapshot.models) === model.id} role="option" aria-selected={activeModelId(snapshot.models) === model.id} onclick={() => selectModel(model.id)}>
                    <span class="model-vendor-mark {model.ownedBy ?? "unknown"}"><Icon name={modelVendorIcon(model)} size={14} /></span>
                    <span class="model-option-copy"><strong>{model.id}</strong><small>{modelVendorName(model)} · Applies to this thread</small></span>
                    {#if activeModelId(snapshot.models) === model.id}<Icon name="check" size={13} />{/if}
                  </button>
                {/each}
              </div>
            {/if}
            {#if agentMenuOpen}
              <div class="composer-popup agent-menu" role="listbox" aria-label="Available Agent profiles">
                {#each availableAgentProfiles(snapshot) as profile}
                  <button type="button" class:active={snapshot.selectedAgentProfileId === profile.id} role="option" aria-selected={snapshot.selectedAgentProfileId === profile.id} onclick={() => selectAgentProfile(profile.id)}>
                    <span class="agent-profile-mark"><Icon name={profile.agentType === "search" ? "search" : profile.agentType === "context" ? "layers" : profile.agentType === "prompt" ? "sparkles" : "bot"} size={14} /></span>
                    <span class="agent-option-copy"><strong>{profile.name}</strong><small>{profile.description} · {profile.source}</small></span>
                    {#if snapshot.selectedAgentProfileId === profile.id}<Icon name="check" size={13} />{/if}
                  </button>
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
                <button class="mode-button dd-btn" onclick={() => { modeMenuOpen = !modeMenuOpen; agentMenuOpen = false; modelMenuOpen = false; skillsOpen = false; slashOpen = false; atOpen = false; }}>
                  <span class="tag {snapshot.mode}">{modeLabel(snapshot.mode)}</span>
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
              <div class="agent-control" title={`Agent profile: ${activeAgentProfile(snapshot).name}`}>
                <button
                  type="button"
                  class="agent-select"
                  class:active={agentMenuOpen}
                  disabled={isBusy(snapshot)}
                  aria-label="Agent profile selection"
                  aria-haspopup="listbox"
                  aria-expanded={agentMenuOpen}
                  onclick={toggleAgentMenu}
                >
                  <Icon name="bot" size={12} />
                  <span>{activeAgentProfile(snapshot).name}</span>
                </button>
              </div>
              <div class="model-control" title={modelTitle(snapshot.models)}>
                <button
                  type="button"
                  class="model-select"
                  class:active={modelMenuOpen}
                  disabled={snapshot.models.state !== "ready" || snapshot.models.options.length === 0}
                  aria-label="Model selection"
                  aria-haspopup="listbox"
                  aria-expanded={modelMenuOpen}
                  onclick={toggleModelMenu}
                >
                  <Icon name="settings-2" size={12} />
                  <span>{modelLabel(snapshot.models)}</span>
                </button>
              </div>
              <button title="Context Canvas" onclick={() => openWorkspaceView("images")}><Icon name="layers-2" size={14} /></button>
              <button title="@ mention" onclick={seedMention}><Icon name="at-sign" size={14} /></button>
              <button title="Slash commands" onclick={seedSlash}><Icon name="square-terminal" size={14} /></button>
              <button title="Attach file/image" onclick={() => sendCommand("pickContext")}><Icon name="file-input" size={14} /></button>
              <button title={enhancing ? "Enhancing…" : "Enhance prompt"} disabled={!prompt.trim() || enhancing || isBusy(snapshot)} onclick={enhancePrompt}><Icon name="sparkles" size={14} /></button>
              <div class="skill-control">
                <button class:active={skillsOpen} title="Skills" onclick={() => { skillsOpen = !skillsOpen; modeMenuOpen = false; agentMenuOpen = false; modelMenuOpen = false; }}>
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
                        <input type="checkbox" checked={skill.selected} disabled={isBusy(snapshot) || (!skill.selected && selectedSkillCount() >= snapshot.customization.maxSelectedSkills)} onchange={() => toggleSkill(skill)} />
                        <span><strong>{skill.name}</strong><small>{skill.description}</small></span>
                      </label>
                    {:else}
                      <p>No repository skills found.</p>
                    {/each}
                  </div>
                {/if}
              </div>
              <span class="toolbar-spacer sp"></span>
              <button class="auto-toggle" class:active={autoApproveReadOnly} title={autoApproveReadOnly ? "Auto ON — read-only tools run without approval" : "Auto OFF — require approval for most commands"} onclick={toggleAutoRun}><span class="auto-label">Auto </span>{autoApproveReadOnly ? "ON" : "OFF"}</button>
              {#if isBusy(snapshot)}
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
          <button class="audit-button" class:active={settingsSection === "Audit"} title="Open runtime audit" onclick={() => chooseSettingsSection("Audit")}><Icon name="scan-search" size={12} />Audit</button>
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
                {#if snapshot.context.state === "ready"}
                  <div class="context-signals">
                    <span><Icon name={snapshot.context.watching ? "refresh-cw" : "pause"} size={11} />{snapshot.context.watching ? "File changes sync automatically" : "Automatic sync unavailable"}</span>
                    <span><Icon name="folders" size={11} />{snapshot.context.watchedRoots ?? 0}/{snapshot.context.roots ?? 1} roots watched</span>
                    <span><Icon name={snapshot.context.hasEmbeddings ? "sparkles" : "search"} size={11} />{snapshot.context.hasEmbeddings ? "Hybrid semantic retrieval" : "Lexical and symbol retrieval"}</span>
                  </div>
                {/if}
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
            {:else if settingsSection === "Audit"}
              <div class="section-title">
                <div>
                  <h1>System Audit</h1>
                  <p class="settings-lead">Live capability and lifecycle state collected from the current IDE session.</p>
                </div>
                <button onclick={refreshAudit}><Icon name="refresh-ccw" size={12} />Refresh</button>
              </div>
              <section class="settings-block capability-list">
                <div>
                  <Icon name="server" size={14} />
                  <span class="capability-copy"><strong>Agent backend</strong><small>{snapshot.backendHealth.label} · {snapshot.models.options.length} models · {snapshot.backendTools.filter((tool) => tool.available).length}/{snapshot.backendTools.length} backend tools</small></span>
                  <i class:ready={snapshot.backendHealth.state === "online"}>{snapshot.backendHealth.state}</i>
                </div>
                <div>
                  <Icon name="database-zap" size={14} />
                  <span class="capability-copy"><strong>ContextEngine</strong><small>{snapshot.context.files ?? 0} files · {snapshot.context.chunks ?? 0} chunks · {snapshot.context.watchedRoots ?? 0}/{snapshot.context.roots ?? 0} roots watched · {snapshot.context.hasEmbeddings ? "hybrid" : "no-model lexical"}</small></span>
                  <i class:ready={snapshot.context.state === "ready" && !!snapshot.context.watching}>{snapshot.context.state}</i>
                </div>
                <div>
                  <Icon name="mcp" size={14} />
                  <span class="capability-copy"><strong>MCP gateway</strong><small>{snapshot.mcpRuntime.servers.length} servers · {snapshot.mcpRuntime.tools.length} namespaced tools · approval policy enforced by the IDE</small></span>
                  <i class:ready={snapshot.mcpRuntime.state === "ready"}>{snapshot.mcpRuntime.state}</i>
                </div>
                <div>
                  <Icon name="workflow" size={14} />
                  <span class="capability-copy"><strong>Hooks</strong><small>{snapshot.hookRuntime.configured} configured · {snapshot.hookRuntime.automatic} automatic · {snapshot.hookRuntime.recent.length} retained executions</small></span>
                  <i class:ready={snapshot.hookRuntime.state === "ready"}>{snapshot.hookRuntime.state}</i>
                </div>
                <div>
                  <Icon name="layers" size={14} />
                  <span class="capability-copy"><strong>Declarative plugins</strong><small>{snapshot.pluginRuntime.items.length} configured · {snapshot.pluginRuntime.commands.length + snapshot.pluginRuntime.prompts.length} templates · {snapshot.pluginRuntime.agents.length} agents · {snapshot.pluginRuntime.hooks.length} hooks · {snapshot.pluginRuntime.mcpServers.length} MCP · {snapshot.pluginRuntime.tools.length} tools</small></span>
                  <i class:ready={snapshot.pluginRuntime.state === "ready"}>{snapshot.pluginRuntime.state}</i>
                </div>
                <div>
                  <Icon name="bot" size={14} />
                  <span class="capability-copy"><strong>Durable jobs</strong><small>{snapshot.jobs.items.filter((job) => job.status === "queued" || job.status === "running").length} active · {snapshot.jobs.items.length} retained · account {snapshot.account.state}</small></span>
                  <i class:ready={snapshot.jobs.state === "ready"}>{snapshot.jobs.state}</i>
                </div>
              </section>
              <section class="settings-block audit-report">
                <header><Icon name="circle-check" size={14} /><strong>Redacted support report</strong></header>
                <p>Copies runtime states, counts, lifecycle labels, and recent tool-card summaries. Credentials, endpoints, tool arguments, and tool result details are excluded.</p>
                <footer>
                  <button onclick={() => copyAuditReport(false)}><Icon name="copy" size={12} />Copy audit report</button>
                  <button onclick={() => openWorkspaceView("feedback")}><Icon name="flag" size={12} />Report issue</button>
                </footer>
              </section>
            {:else if settingsSection === "Services" || settingsSection === "API Keys"}
              <h1>{settingsSection === "API Keys" ? "API Keys" : "Services"}</h1>
              <p class="settings-lead">
                {#if settingsSection === "API Keys"}
                  Model credentials stay in JetBrains Password Safe and are sent only for model discovery, completion, enhancement, and Agent-run requests to HTTPS or the local loopback backend.
                {:else}
                  Connect this IDE capability gateway to the deployed Agent backend.
                {/if}
              </p>
              {#if settingsSection === "API Keys"}
                <section class="settings-block list-block byok-provider-list">
                  <header><strong>Bring your own model provider</strong><span>{snapshot.byok.activeProvider ?? "Backend default"}</span></header>
                  <div>
                    <Icon name="sparkles" size={14} />
                    <span><strong>OpenAI Responses</strong><small>{snapshot.byok.openAiConfigured ? (snapshot.byok.activeProvider === "openai" ? "Configured and active" : "Configured") : "Not configured"}</small></span>
                    <button onclick={() => sendCommand("configureByok", { provider: "openai" })}>{snapshot.byok.openAiConfigured ? "Replace" : "Set key"}</button>
                    {#if snapshot.byok.openAiConfigured}<button class="danger" title="Remove OpenAI key" onclick={() => sendCommand("clearByok", { provider: "openai" })}>Clear</button>{/if}
                  </div>
                  <div>
                    <Icon name="bot" size={14} />
                    <span><strong>Anthropic Messages</strong><small>{snapshot.byok.anthropicConfigured ? (snapshot.byok.activeProvider === "anthropic" ? "Configured and active" : "Configured") : "Not configured"}</small></span>
                    <button onclick={() => sendCommand("configureByok", { provider: "anthropic" })}>{snapshot.byok.anthropicConfigured ? "Replace" : "Set key"}</button>
                    {#if snapshot.byok.anthropicConfigured}<button class="danger" title="Remove Anthropic key" onclick={() => sendCommand("clearByok", { provider: "anthropic" })}>Clear</button>{/if}
                  </div>
                  <div>
                    <Icon name="cloud" size={14} />
                    <span><strong>AWS Bedrock Converse</strong><small>{snapshot.byok.bedrockConfigured ? (snapshot.byok.activeProvider === "aws-bedrock" ? "Configured and active · SigV4" : "Configured · SigV4") : "Access key, secret, region, and model required"}</small></span>
                    <button onclick={() => sendCommand("configureByok", { provider: "aws-bedrock" })}>{snapshot.byok.bedrockConfigured ? "Replace" : "Set credentials"}</button>
                    {#if snapshot.byok.bedrockConfigured}<button class="danger" title="Remove AWS credentials" onclick={() => sendCommand("clearByok", { provider: "aws-bedrock" })}>Clear</button>{/if}
                  </div>
                  <p>Provider secrets are never written to project files, product configuration, backend storage, or logs. Durable background jobs continue to use the deployed backend credential because BYOK secrets are intentionally not persisted server-side.</p>
                </section>
              {/if}
              <section class="settings-form settings-block" oninput={markSettingsDirty}>
                <label><span>Backend URL</span><input bind:value={backendUrl} /></label>
                <label class="secret-field">
                  <span>Backend token</span>
                  <div class="secret-input-row">
                    <input type="password" bind:value={backendToken} placeholder={snapshot.settings.backendTokenConfigured ? "Configured" : "Not configured"} />
                    {#if snapshot.settings.backendTokenConfigured}
                      <button type="button" title="Clear backend token" disabled={settingsSaveState === "saving"} onclick={clearBackendTokenSetting}><Icon name="trash-2" size={12} />Clear</button>
                    {/if}
                  </div>
                </label>
                <label><span>Node.js executable</span><input bind:value={nodePath} /></label>
                <label><span>Context retrieval</span><select bind:value={contextMode}><option value="remote-http">ContextEngine HTTP deployment</option><option value="lexical">Local lexical and symbol index</option><option value="private-semantic">Local index with private embeddings</option></select></label>
                {#if contextMode === "remote-http"}
                  <label><span>ContextEngine URL</span><input bind:value={contextHttpBaseUrl} placeholder="http://127.0.0.1:8790" /></label>
                  <label class="secret-field">
                    <span>ContextEngine token</span>
                    <div class="secret-input-row">
                      <input type="password" bind:value={contextHttpApiKey} placeholder={snapshot.settings.contextHttpTokenConfigured ? "Configured" : "Required unless unauthenticated mode is enabled"} />
                      {#if snapshot.settings.contextHttpTokenConfigured}
                        <button type="button" title="Clear ContextEngine token" disabled={settingsSaveState === "saving"} onclick={clearContextHttpApiKeySetting}><Icon name="trash-2" size={12} />Clear</button>
                      {/if}
                    </div>
                  </label>
                {/if}
                {#if contextMode === "private-semantic"}
                  <label><span>Embedding API URL</span><input bind:value={contextEmbeddingBaseUrl} /></label>
                  <label><span>Embedding model</span><input bind:value={contextEmbeddingModel} /></label>
                  <label class="secret-field">
                    <span>Embedding token</span>
                    <div class="secret-input-row">
                      <input type="password" bind:value={contextEmbeddingApiKey} placeholder={snapshot.settings.contextEmbeddingTokenConfigured ? "Configured" : "Optional for local endpoints"} />
                      {#if snapshot.settings.contextEmbeddingTokenConfigured}
                        <button type="button" title="Clear embedding token" disabled={settingsSaveState === "saving"} onclick={clearContextEmbeddingApiKeySetting}><Icon name="trash-2" size={12} />Clear</button>
                      {/if}
                    </div>
                  </label>
                  <label class="toggle-row">
                    <input type="checkbox" bind:checked={contextNeuralRerank} />
                    <span><strong>Neural rerank</strong><small>Apply an optional cross-encoder to the top retrieval candidates</small></span>
                  </label>
                  {#if contextNeuralRerank}
                    <label><span>Rerank API URL</span><input bind:value={contextRerankBaseUrl} placeholder="Use embedding endpoint" /></label>
                    <label><span>Rerank model</span><input bind:value={contextRerankModel} /></label>
                  {/if}
                {/if}
                <label class="toggle-row">
                  <input type="checkbox" bind:checked={autoApproveReadOnly} />
                  <span><strong>Auto-run read-only tools</strong><small>Context retrieval, search, and file reads</small></span>
                </label>
                <label class="toggle-row">
                  <input type="checkbox" bind:checked={inlineCompletionsEnabled} />
                  <span><strong>Inline completions</strong><small>Offer CodeAgent suggestions as you type in the editor</small></span>
                </label>
                <div class="service-health-stack">
                  <div class="service-health-row">
                    <span class="service-status {backendConnectionState(snapshot, backendTestState)}"></span>
                    <span><strong>Agent Backend</strong><small>{backendConnectionLabel(snapshot, backendTestState, backendTestLabel)}</small></span>
                    <button disabled={backendTestState === "checking"} onclick={testBackend}><Icon name="refresh-ccw" size={12} />{backendTestState === "checking" ? "Checking..." : "Test connection"}</button>
                  </div>
                  {#if contextMode !== "lexical"}
                    <div class="service-health-row">
                      <span class="service-status {contextConnectionState(snapshot, contextTestState)}"></span>
                      <span><strong>ContextEngine</strong><small>{contextConnectionLabel(snapshot, contextTestState, contextTestLabel)}</small></span>
                      <button disabled={contextTestState === "checking" || snapshot.context.state === "indexing"} onclick={testContextEngine}><Icon name="refresh-ccw" size={12} />{contextTestState === "checking" ? "Checking..." : "Test connection"}</button>
                    </div>
                  {/if}
                </div>
                <footer class="settings-save-footer">
                  {#if settingsSaveState !== "idle"}
                    <span class:saved={settingsSaveState === "saved"}><Icon name={settingsSaveState === "saving" ? "circle-dashed" : "circle-check"} size={12} />{settingsSaveState === "saving" ? "Saving securely..." : "Saved securely"}</span>
                  {/if}
                  <button class="primary" disabled={settingsSaveState === "saving"} onclick={saveSettings}>{settingsSaveState === "saving" ? "Saving..." : "Save settings"}</button>
                </footer>
              </section>
              {#if settingsSection === "Services"}
                <section class="settings-block list-block">
                  <header><strong>Backend tools</strong><span>{snapshot.backendTools.filter((tool) => tool.available).length} connected</span></header>
                  {#each snapshot.backendTools as tool}
                    <div>
                      <Icon name={TOOL_CATALOG.find((entry) => entry.id === tool.catalogId)?.icon ?? "plug"} size={14} />
                      <span>
                        <strong>{catalogName(tool.catalogId)}</strong>
                        <small>{tool.available ? tool.name : tool.unavailableReason ?? "Not configured"}</small>
                      </span>
                      <i>{tool.available ? "On" : "Off"}</i>
                    </div>
                  {:else}
                    <p>No backend tool capabilities reported.</p>
                  {/each}
                </section>
              {/if}
            {:else if settingsSection === "Memories"}
              <h1>Memories</h1>
              <p class="settings-lead">Conversation continuity is derived from compact, factual summaries and stays subordinate to the current request and workspace rules.</p>
              <section class="settings-block capability-list">
                <div><Icon name="brain" size={14} /><span class="capability-copy"><strong>Thread summaries</strong><small>Long conversations are compacted into bounded continuity context when the model budget reaches its threshold.</small></span><i class:ready={snapshot.threads.length > 0}>{snapshot.threads.length} threads</i></div>
                <div><Icon name="shield" size={14} /><span class="capability-copy"><strong>Instruction safety</strong><small>Remembered text is treated as historical evidence, never as higher-priority instruction.</small></span><i class="ready">Enforced</i></div>
                <div><Icon name="cloud" size={14} /><span class="capability-copy"><strong>Cloud recovery</strong><small>Signed-in accounts can restore conversation summaries together with the durable thread state.</small></span><i class:ready={snapshot.account.state === "signed_in"}>{snapshot.account.state === "signed_in" ? "Available" : "Sign in"}</i></div>
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
                      <button class="rule-copy" disabled={rule.source !== undefined && rule.source !== "workspace"} title={rule.source !== undefined && rule.source !== "workspace" ? "Plugin rules are read-only" : undefined} onclick={() => editRule(rule)}><strong>{rule.name}</strong><small>{rule.description || rule.path}</small></button>
                      <i>{rule.trigger}</i>
                      {#if rule.trigger === "manual"}
                        <label title="Enable for this thread"><input type="checkbox" checked={rule.selected} onchange={() => sendCommand("toggleRule", { ruleId: rule.id, selected: !rule.selected })} /></label>
                      {/if}
                      {#if rule.source === undefined || rule.source === "workspace"}
                        <button class="icon-button compact" title="Edit rule" onclick={() => editRule(rule)}><Icon name="file-pen" size={13} /></button>
                      {/if}
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
            {:else if settingsSection === "Account"}
              <h1>Account</h1>
              <p class="settings-lead">Your identity anchors cloud conversations, durable tasks, and usage across IDEs.</p>
              {#if snapshot.account.state === "signed_in" || snapshot.account.state === "signing_out"}
                <section class="settings-block account-card">
                  <div class="account-session-rail" aria-hidden="true"><span></span><span></span><span></span></div>
                  <header class="account-identity">
                    <span class="account-avatar">{accountInitials(snapshot.account.displayName)}</span>
                    <span class="account-copy">
                      <strong>{snapshot.account.displayName ?? "CodeAgent user"}</strong>
                      <small>{snapshot.account.email ?? snapshot.account.userId ?? snapshot.account.label}</small>
                    </span>
                    <i class="account-mode">{snapshot.account.mode}</i>
                  </header>
                  <div class="account-session">
                    <span class="service-status online"></span>
                    <span><strong>Session active</strong><small>{snapshot.account.label}</small></span>
                  </div>
                  <div class="account-usage">
                    {#each snapshot.account.usage as item}
                      <article><span>{usageLabel(item.kind)}</span><strong>{item.units.toLocaleString()}</strong></article>
                    {:else}
                      <p>Usage appears after your first Agent run.</p>
                    {/each}
                  </div>
                  <footer class="account-actions">
                    <span>{snapshot.account.mode === "local" ? "Local development session" : "Tokens are stored in JetBrains Password Safe"}</span>
                    {#if snapshot.account.mode === "oidc"}
                      <button disabled={snapshot.account.state === "signing_out"} onclick={signOut}>{snapshot.account.state === "signing_out" ? "Signing out…" : "Sign out"}</button>
                    {/if}
                  </footer>
                </section>
              {:else}
                <section class="settings-block account-empty-card" class:error={snapshot.account.state === "error"}>
                  <div class="account-empty-mark"><Icon name={snapshot.account.state === "error" ? "circle-alert" : "user-round"} size={21} /></div>
                  <strong>{snapshot.account.mode === "oidc" ? "Connect your CodeAgent account" : snapshot.account.mode === "shared-token" ? "Backend token required" : "Account unavailable"}</strong>
                  <p>{snapshot.account.label}</p>
                  {#if snapshot.account.mode === "oidc"}
                    <button class="primary" disabled={snapshot.account.state === "signing_in" || snapshot.account.state === "checking"} onclick={signIn}>
                      <Icon name="external-link" size={12} />
                      {snapshot.account.state === "signing_in" ? "Waiting for browser…" : "Sign in with browser"}
                    </button>
                  {:else if snapshot.account.mode === "shared-token"}
                    <button onclick={() => chooseSettingsSection("API Keys")}>Configure backend token</button>
                  {:else}
                    <button disabled={snapshot.account.state === "checking"} onclick={() => sendCommand("checkBackend")}><Icon name="refresh-ccw" size={12} />Check account</button>
                  {/if}
                </section>
              {/if}

            {:else if settingsSection === "User Experience"}
              <h1>User Experience</h1>
              <p class="settings-lead">Conversation presentation and IDE-owned notification delivery.</p>
              <section class="settings-form settings-block">
                <header><strong>Appearance</strong><span>{chatZoom}%</span></header>
                <div class="chat-zoom-setting">
                  <span><strong>Chat Zoom</strong><small>Increase or decrease the zoom level of the chat</small></span>
                  <div class="chat-zoom-controls" role="group" aria-label="Chat zoom">
                    <button class="icon-button compact" title="Decrease chat zoom" aria-label="Decrease chat zoom" disabled={chatZoom <= CHAT_ZOOM_MIN} onclick={() => adjustChatZoom(-CHAT_ZOOM_STEP)}><Icon name="minus" size={13} /></button>
                    <span class="chat-zoom-setting-value">{chatZoom}%</span>
                    <button class="icon-button compact" title="Increase chat zoom" aria-label="Increase chat zoom" disabled={chatZoom >= CHAT_ZOOM_MAX} onclick={() => adjustChatZoom(CHAT_ZOOM_STEP)}><Icon name="plus" size={13} /></button>
                  </div>
                </div>
                <label class="toggle-row">
                  <input type="checkbox" bind:checked={showTimestamps} />
                  <span><strong>Timeline timestamps</strong><small>Show message, tool start, and tool duration metadata</small></span>
                </label>
                <label class="toggle-row">
                  <input type="checkbox" bind:checked={showRunTelemetry} />
                  <span><strong>Run telemetry</strong><small>Show turn, context-budget, tool-catalog, and verification state</small></span>
                </label>
              </section>
              <section class="settings-form settings-block">
                <header><strong>Notifications</strong><Icon name="bell" size={14} /></header>
                <label class="toggle-row">
                  <input type="checkbox" bind:checked={desktopNotifications} />
                  <span><strong>IDE notifications</strong><small>Run completion, failures, and approval requests</small></span>
                </label>
                <label class="toggle-row">
                  <input type="checkbox" bind:checked={autoDismissNotifications} />
                  <span><strong>Auto-dismiss in-panel notices</strong><small>Success after 4 seconds and errors after 8 seconds</small></span>
                </label>
                <p>Notification sound and delivery style follow the IDE notification settings.</p>
                <footer><button class="primary" onclick={saveUserExperience}>Save preferences</button></footer>
              </section>
            {:else if ["MCP Servers", "ACP Agents", "Commands", "Hooks", "Agents", "Plugins"].includes(settingsSection)}
              <ConfigurationSettings
                section={settingsSection}
                configurationSnapshot={snapshot.configurations}
                mcpRuntime={snapshot.mcpRuntime}
                acpRuntime={snapshot.acpRuntime}
                hookRuntime={snapshot.hookRuntime}
                pluginRuntime={snapshot.pluginRuntime}
                models={snapshot.models.options}
              />
            {:else if settingsSection === "Feature Flags"}
              <h1>Feature Flags</h1>
              <p class="settings-lead">Runtime capability report derived from the current plugin, backend, and ContextEngine state.</p>
              <section class="settings-block capability-list">
                <div><Icon name="database-zap" size={14} /><span class="capability-copy"><strong>Incremental context index</strong><small>Project changes are synchronized into the local index.</small></span><i class:ready={snapshot.context.watching}>{snapshot.context.watching ? "Active" : "Inactive"}</i></div>
                <div><Icon name="scan-search" size={14} /><span class="capability-copy"><strong>Semantic retrieval</strong><small>Embedding-backed search is available only when the local ContextEngine reports vectors.</small></span><i class:ready={snapshot.context.hasEmbeddings}>{snapshot.context.hasEmbeddings ? "Active" : "Lexical"}</i></div>
                <div><Icon name="cloud" size={14} /><span class="capability-copy"><strong>Cloud sessions</strong><small>Account-scoped conversations and durable jobs require a signed-in backend session.</small></span><i class:ready={snapshot.account.state === "signed_in"}>{snapshot.account.state === "signed_in" ? "Active" : "Inactive"}</i></div>
                <div><Icon name="server" size={14} /><span class="capability-copy"><strong>Backend agent runtime</strong><small>Prompt enhancement, model routing, and bounded agent runs use the deployed backend.</small></span><i class:ready={snapshot.backendHealth.state === "online"}>{snapshot.backendHealth.state === "online" ? "Active" : "Offline"}</i></div>
                <div><Icon name="mcp" size={14} /><span class="capability-copy"><strong>MCP runtime</strong><small>Local stdio and remote HTTP/SSE servers expose namespaced, approval-controlled Agent tools.</small></span><i class:ready={snapshot.mcpRuntime.state === "ready"}>{snapshot.mcpRuntime.state === "ready" ? `${snapshot.mcpRuntime.tools.length} tools` : snapshot.mcpRuntime.state}</i></div>
                <div><Icon name="bot" size={14} /><span class="capability-copy"><strong>ACP v1 runtime</strong><small>External ACP agents negotiate capabilities, preserve sessions, stream updates, and remain behind IDE approval.</small></span><i class:ready={snapshot.acpRuntime.state === "ready"}>{snapshot.acpRuntime.state === "ready" ? `${snapshot.acpRuntime.agents.length} agents` : snapshot.acpRuntime.state}</i></div>
              </section>
            {:else if settingsSection === "Beta"}
              <h1>Beta <em>Beta</em></h1>
              <p class="settings-lead">Connected previews with explicit maturity and execution boundaries.</p>
              <section class="settings-block capability-list">
                <div><Icon name="bot" size={14} /><span class="capability-copy"><strong>Specialized Agent profiles</strong><small>Search, context, prompt, loop, and general profiles can be stored with model and tool budgets.</small></span><i class:ready={snapshot.configurations.state === "ready"}>{snapshot.configurations.state === "ready" ? "Active" : "Unavailable"}</i></div>
                <div><Icon name="square-terminal" size={14} /><span class="capability-copy"><strong>Reusable commands</strong><small>Account commands are discovered in the composer and expanded by the IDE runtime.</small></span><i class:ready={snapshot.configurations.state === "ready"}>{snapshot.configurations.state === "ready" ? "Active" : "Unavailable"}</i></div>
                <div><Icon name="list-checks" size={14} /><span class="capability-copy"><strong>Durable subagent jobs</strong><small>Backend jobs survive IDE reconnects and can be restored through the account session.</small></span><i class:ready={snapshot.account.state === "signed_in"}>{snapshot.account.state === "signed_in" ? "Preview" : "Sign in"}</i></div>
                <div><Icon name="wand-sparkles" size={14} /><span class="capability-copy"><strong>Repository skills</strong><small>Skills are loaded from repository instructions and selected per conversation.</small></span><i class:ready={snapshot.customization.skills.length > 0}>{snapshot.customization.skills.length > 0 ? "Preview" : "None found"}</i></div>
              </section>
            {:else if settingsSection === "Subscription"}
              <h1>Subscription</h1>
              <p class="settings-lead">Current identity and metered usage reported by the connected backend.</p>
              {#if snapshot.account.state === "signed_in"}
                <section class="settings-block subscription-summary">
                  <header><span><strong>{snapshot.account.displayName ?? "CodeAgent user"}</strong><small>{snapshot.account.email ?? snapshot.account.userId ?? "Signed-in account"}</small></span><i>{snapshot.account.mode}</i></header>
                  <div class="usage-grid">
                    {#each snapshot.account.usage as item}
                      <article><span>{usageLabel(item.kind)}</span><strong>{item.units.toLocaleString()}</strong></article>
                    {:else}
                      <p>No metered usage has been reported.</p>
                    {/each}
                  </div>
                  <p>Plan names, quotas, invoices, and payment management are shown only when the backend exposes a billing contract.</p>
                </section>
              {:else}
                <section class="settings-block unavailable">
                  <Icon name="credit-card" size={20} />
                  <strong>No subscription session</strong>
                  <p>Sign in to load account usage. Billing actions are not simulated.</p>
                  <button onclick={() => chooseSettingsSection("Account")}>Open Account</button>
                </section>
              {/if}
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
          <button class="btn sm primary" disabled={isBusy(snapshot) || snapshot.tasks.every((task) => task.state === "completed" || task.state === "cancelled")} onclick={() => sendCommand("runAllTasks")}>Run All</button>
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
            {#each filteredTasks(snapshot, taskFilter) as task, index}
              <div class="task-workspace-row" class:completed={task.state === "completed"} class:running={task.state === "in_progress"}>
                <button class="task-state" title="Toggle complete" onclick={() => sendCommand("setTaskState", { taskId: task.id, state: task.state === "completed" ? "not_started" : "completed" })}>
                  {#if task.state === "completed"}<Icon name="circle-check" size={15} />{:else}<span></span>{/if}
                </button>
                <i>{index + 1}</i>
                <span><strong>{task.name}</strong><small>{task.state.replaceAll("_", " ")}</small></span>
                <button class="icon-button compact" title="Run task" disabled={isBusy(snapshot) || task.state === "completed"} onclick={() => sendCommand("runTask", { taskId: task.id })}><Icon name="play" size={13} /></button>
                <button class="icon-button compact danger" title="Delete task" onclick={() => sendCommand("deleteTask", { taskId: task.id })}><Icon name="trash-2" size={13} /></button>
              </div>
            {:else}
              <div class="workspace-empty"><Icon name="list-checks" size={20} /><strong>Get Started with Tasks</strong><p>Break Agent work into runnable steps.</p></div>
            {/each}
          </div>
        </div>
      </section>
    {:else if currentView === "jobs"}
      <section class="workspace-view">
        <header class="canvas-header ov-h">
          <button class="icon-button compact" title="Back" onclick={() => currentView = "chat"}><Icon name="chevron-left" size={15} /></button>
          <Icon name="bot" size={14} />
          <strong>Durable Jobs</strong>
          <span class="workspace-count">{snapshot.jobs.items.filter((job) => job.status === "queued" || job.status === "running").length} active</span>
          <button class="icon-button compact" title="Refresh durable jobs" onclick={() => sendCommand("refreshJobs")}><Icon name="refresh-ccw" size={13} /></button>
        </header>
        <div class="workspace-body jobs-workspace">
          <section class="workspace-section job-compose">
            <header><div><strong>Delegate bounded work</strong><small>Runs independently and survives IDE or backend restarts.</small></div></header>
            <form onsubmit={(event) => { event.preventDefault(); createJob(); }}>
              <div class="job-form-grid">
                <label><span>Specialist role</span><select bind:value={jobRole}><option value="research">Research</option><option value="review">Review</option><option value="test">Test</option><option value="security">Security</option><option value="planner">Planner</option></select></label>
                <label><span>Model</span><select bind:value={jobModel}><option value="">Backend default</option>{#each snapshot.models.options as model}<option value={model.id}>{model.id}</option>{/each}</select></label>
                <label class="wide"><span>Task</span><textarea bind:value={jobPrompt} maxlength="100000" placeholder="Describe one self-contained delegated task" spellcheck="true"></textarea></label>
                <label class="wide"><span>Context</span><textarea bind:value={jobContext} maxlength="30000" placeholder="Optional evidence or constraints the specialist needs" spellcheck="true"></textarea></label>
                <label class="wide"><span>Expected output</span><input bind:value={jobExpectedOutput} maxlength="4000" placeholder="For example: prioritized findings with file evidence" /></label>
                <label><span>Output token limit</span><input type="number" min="1024" max="16000" step="512" bind:value={jobMaxOutputTokens} /></label>
              </div>
              <footer><button class="primary" disabled={creatingJob || !jobPrompt.trim() || jobMaxOutputTokens < 1024 || jobMaxOutputTokens > 16000}><Icon name="play" size={12} />{creatingJob ? "Starting" : "Start job"}</button></footer>
            </form>
          </section>
          <div class="workspace-actions jobs-actions">
            <div class="segmented-control">
              <button class:active={jobFilter === "all"} onclick={() => jobFilter = "all"}>All</button>
              <button class:active={jobFilter === "active"} onclick={() => jobFilter = "active"}>Active</button>
              <button class:active={jobFilter === "completed"} onclick={() => jobFilter = "completed"}>Completed</button>
              <button class:active={jobFilter === "failed"} onclick={() => jobFilter = "failed"}>Failed</button>
            </div>
            <span class:error={snapshot.jobs.state === "error"}>{snapshot.jobs.label}</span>
          </div>
          {#if (snapshot.jobs.state === "unavailable" || snapshot.jobs.state === "error") && snapshot.jobs.items.length === 0}
            <div class="workspace-empty"><Icon name="circle-alert" size={20} /><strong>Durable jobs unavailable</strong><p>{snapshot.jobs.label}</p></div>
          {:else}
            <div class="job-list">
              {#each filteredJobs(snapshot, jobFilter) as job (job.id)}
                <article class="job-row {job.status}">
                  <header>
                    <span class="job-status-icon"><Icon name={job.status === "completed" ? "circle-check" : job.status === "failed" || job.status === "cancelled" ? "circle-alert" : "circle-dashed"} size={14} /></span>
                    <strong>{job.type === "history-summary" ? "History summary" : `${job.role ?? "research"} subagent`}</strong>
                    <i>{job.status}</i>
                    <time>{formatJobTime(job.updatedAt ?? job.createdAt)}</time>
                  </header>
                  <p>{job.prompt}</p>
                  <div class="job-meta">
                    {#if job.model}<span><Icon name="cpu" size={11} />{job.model}</span>{/if}
                    {#if job.maxOutputTokens}<span>{job.maxOutputTokens} max tokens</span>{/if}
                    <span>{job.id.slice(0, 8)}</span>
                  </div>
                  {#if job.context || job.expectedOutput}
                    <details>
                      <summary>Delegation details</summary>
                      {#if job.context}<div><strong>Context</strong><p>{job.context}</p></div>{/if}
                      {#if job.expectedOutput}<div><strong>Expected output</strong><p>{job.expectedOutput}</p></div>{/if}
                    </details>
                  {/if}
                  {#if job.output}
                    <div class="job-output-label"><Icon name={job.outputPartial ? "activity" : "file-text"} size={11} />{job.outputPartial ? "Live output" : "Result"}</div>
                    <pre>{job.output}</pre>
                  {/if}
                  {#if job.error}<div class="job-error"><Icon name="circle-alert" size={12} /><span>{job.error}</span></div>{/if}
                  <footer>
                    {#if job.status === "queued" || job.status === "running"}<button class="danger" onclick={() => sendCommand("cancelJob", { jobId: job.id })}><Icon name="square" size={11} />Cancel</button>{/if}
                    {#if job.status === "completed" || job.status === "failed" || job.status === "cancelled"}<button onclick={() => sendCommand("retryJob", { jobId: job.id })}><Icon name="refresh-ccw" size={11} />Retry</button>{/if}
                    {#if job.output}<button onclick={() => sendCommand("openJobResult", { jobId: job.id })}><Icon name="external-link" size={11} />Open result</button>{/if}
                    {#if job.output}<button class="primary" onclick={() => useJobOutput(job)}><Icon name="copy" size={11} />Use result</button>{/if}
                  </footer>
                </article>
              {:else}
                <div class="workspace-empty"><Icon name="bot" size={20} /><strong>No matching durable jobs</strong><p>Create a bounded specialist task or change the status filter.</p></div>
              {/each}
            </div>
          {/if}
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
              <button
                class="catalog-card"
                class:connected={toolConnected(tool)}
                title={toolConnectionTitle(tool)}
                onclick={() => insertToolSeed(tool.id)}
              >
                <span class="tool-icon"><Icon name={tool.icon} size={16} /></span>
                <span>
                  <strong>{tool.name}</strong>
                  <small>{tool.desc}</small>
                </span>
                <i>{toolConnected(tool) ? "connected" : "unavailable"}</i>
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
              <button class="icon-tile" title={name} onclick={() => copyText(name, `Copied ${name}`)}>
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
          <button class="btn sm" disabled={changeTools().length === 0} onclick={() => sendCommand("createCheckpoint", { label: "Agent checkpoint" })}>Checkpoint</button>
          <button class="btn sm" disabled={changeTools().length === 0} onclick={() => sendCommand("keepChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Keep all</button>
          <button class="btn sm danger" disabled={changeTools().length === 0} onclick={() => sendCommand("discardChanges", { toolIds: changeTools().map((tool) => tool.id) })}>Discard all</button>
        </header>
        <div class="workspace-body">
          {#if changeTools().length === 0 && checkpoints.length === 0}
            <div class="workspace-empty"><Icon name="file-diff" size={20} /><strong>No pending agent edits</strong><p>File tools with Diff/undo will appear here after Agent changes files.</p></div>
          {:else}
            {#if changeTools().length > 0}
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
            {#if checkpoints.length > 0}
              <section class="workspace-section" style="margin-top:12px">
                <header><strong>Checkpoints</strong><button class="btn sm" onclick={() => sendCommand("listCheckpoints")}>Refresh</button></header>
                <div class="workspace-list">
                  {#each checkpoints as checkpoint}
                    <div class="workspace-file">
                      <Icon name="history" size={14} />
                      <span><strong>{checkpoint.label}</strong><small>{checkpoint.changeCount} files · {formatTime(checkpoint.createdAt)}</small></span>
                      <button onclick={() => sendCommand("restoreCheckpoint", { checkpointId: checkpoint.id })}>Restore</button>
                    </div>
                  {/each}
                </div>
              </section>
            {/if}
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
              <button onclick={() => copyAuditReport(true)}>Copy Support Bundle</button>
              <button class="primary" disabled={!feedbackText.trim()} onclick={() => { notice = "Feedback noted locally. No remote report endpoint is connected."; feedbackText = ""; }}>Submit</button>
            </footer>
          </section>
          <p class="settings-lead">The support bundle contains a redacted runtime audit plus the latest bounded conversation context. No remote feedback service is connected; Submit only stores a local notice.</p>
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
            <button class="new-thread" onclick={() => startNewThread()}><Icon name="plus" size={14} /> New {modeLabel(snapshot.mode)}</button>
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
          {#each visibleThreads(snapshot?.threads ?? [], threadSearch, pendingThreadPins) as thread (thread.id)}
            <div class="thread-row" class:active={thread.active}>
              <button class="thread-select" onclick={() => selectThread(thread.id)}>
                <span><strong>{thread.title}</strong><small>{formatTime(thread.updatedAt)}</small></span>
                <i class="tag {thread.mode}">{modeLabel(thread.mode)}</i>
              </button>
              <button class="icon-button compact" class:pinned={thread.pinned} title={pendingThreadPins[thread.id] !== undefined ? "Updating pin" : thread.pinned ? "Unpin thread" : "Pin thread"} disabled={pendingThreadPins[thread.id] !== undefined} onclick={() => toggleThreadPinned(thread.id, thread.pinned)}><Icon name="pin" size={12} /></button>
              <button class="icon-button compact delete-thread" title={pendingThreadDeletes.has(thread.id) ? "Deleting thread" : "Delete thread"} disabled={pendingThreadDeletes.has(thread.id)} onclick={() => deleteThread(thread.id)}><Icon name="trash-2" size={12} /></button>
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
