export const PROTOCOL_VERSION = 1;

export type Mode = "agent" | "chat" | "ask";
export type MessageRole = "user" | "assistant" | "system";
export type RunState = "idle" | "starting" | "running" | "awaiting_approval" | "failed";
export type AgentRunPhase =
  | "idle"
  | "starting"
  | "thinking"
  | "streaming"
  | "tools"
  | "processing"
  | "retrying"
  | "verifying"
  | "approval"
  | "compacting"
  | "failed";

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  createdAt: number;
  timelineSequence?: number;
  turnIndex?: number;
  runId?: string;
}

export interface ToolRun {
  id: string;
  name: string;
  summary: string;
  status: "running" | "approval" | "completed" | "failed" | "rejected";
  detail?: string;
  changePath?: string;
  canRevert: boolean;
  turnIndex?: number;
  runId?: string;
  createdAt?: number;
  updatedAt?: number;
  timelineSequence?: number;
}

export interface AgentRunTelemetry {
  phase: AgentRunPhase;
  turnIndex: number;
  estimatedInputTokens: number;
  targetInputTokens: number;
  contextWindowTokens: number;
  reservedOutputTokens: number;
  retrievalBudgetTokens: number;
  toolDefinitionTokens: number;
  compactedToolResults: number;
  truncatedMessages: number;
  compactionApplied: boolean;
  overBudget: boolean;
  activeToolNames: string[];
  activeToolCount: number;
  catalogToolCount: number;
  discoverableToolCount: number;
  activatedToolNames: string[];
  toolBatchTotal: number;
  toolBatchCompleted: number;
  toolBatchExecution?: "sequential" | "parallel";
  retryAttempt: number;
  retryMaxAttempts: number;
  retryMessage?: string;
  verificationState: "idle" | "required" | "verified";
  verificationMessage?: string;
  verificationToolName?: string;
}

export interface MessageDelta {
  id: string;
  delta: string;
  turnIndex: number;
}

export interface ThreadSummary {
  id: string;
  title: string;
  updatedAt: number;
  active: boolean;
  mode: Mode;
  pinned: boolean;
}

export interface TaskItem {
  id: string;
  name: string;
  state: "not_started" | "in_progress" | "completed" | "cancelled";
}

export interface QueuedMessage {
  id: string;
  text: string;
  mode: Mode;
}

export interface ContextItem {
  id: string;
  label: string;
  path: string;
  kind?: "file" | "image" | "ide_state";
}

export interface GitFile {
  path: string;
  status: string;
}

export interface GitSnapshot {
  available: boolean;
  branch: string;
  repository: string;
  unstaged: GitFile[];
  staged: GitFile[];
  error?: string;
}

export interface ImageItem {
  id: string;
  name: string;
  path: string;
  dataUrl: string;
  sizeBytes: number;
}

export interface ImageCanvasSnapshot {
  directory: string;
  images: ImageItem[];
  truncated: boolean;
  error?: string;
}

export interface SettingsSnapshot {
  backendUrl: string;
  nodePath: string;
  backendTokenConfigured: boolean;
  autoApproveReadOnly: boolean;
  chatZoom: number;
  showTimestamps: boolean;
  showRunTelemetry: boolean;
  desktopNotifications: boolean;
  autoDismissNotifications: boolean;
  contextMode: "remote-http" | "lexical" | "private-semantic";
  contextHttpBaseUrl: string;
  contextHttpTokenConfigured: boolean;
  contextEmbeddingBaseUrl: string;
  contextEmbeddingModel: string;
  contextEmbeddingTokenConfigured: boolean;
  contextNeuralRerank: boolean;
  contextRerankBaseUrl: string;
  contextRerankModel: string;
}

export interface SettingsSaved {
  requestId: string;
  backendTokenConfigured: boolean;
  contextHttpTokenConfigured: boolean;
  contextEmbeddingTokenConfigured: boolean;
}

export interface AccountUsage {
  kind: string;
  units: number;
}

export interface AccountSnapshot {
  state: "checking" | "signed_out" | "signing_in" | "signed_in" | "signing_out" | "error";
  mode: "unknown" | "local" | "shared-token" | "oidc";
  userId?: string;
  displayName?: string;
  email?: string;
  usage: AccountUsage[];
  label: string;
}

export interface ModelOption {
  id: string;
  ownedBy?: string;
}

export interface ModelRegistry {
  state: "unknown" | "loading" | "ready" | "error";
  provider: string;
  defaultModel?: string;
  selectedModel?: string;
  options: ModelOption[];
  label: string;
}

export interface BackendToolCapability {
  name: string;
  catalogId: string;
  available: boolean;
  unavailableReason?: string;
  requiredEnvironment: string[];
}

export type ConfigurationKind = "mcp" | "hooks" | "commands" | "agents" | "plugins" | "tool-permissions";

export interface ProductConfiguration {
  id: string;
  kind: ConfigurationKind;
  value: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

export interface ConfigurationSnapshot {
  state: "unavailable" | "loading" | "ready" | "error";
  label: string;
  items: Partial<Record<ConfigurationKind, ProductConfiguration[]>>;
}

export interface HookExecution {
  id: string;
  hookId: string;
  hookName: string;
  event: "before-run" | "after-run" | "before-tool" | "after-tool" | "on-error";
  status: "completed" | "failed" | "timeout";
  exitCode?: number;
  startedAt: string;
  durationMs: number;
  summary: string;
  detail?: string;
}

export interface HookRuntimeSnapshot {
  state: "idle" | "ready" | "degraded";
  label: string;
  configured: number;
  automatic: number;
  recent: HookExecution[];
}

export interface PluginCommand {
  id: string;
  pluginId: string;
  pluginVersion: string;
  name: string;
  description?: string;
  argumentHint?: string;
  mode: "inherit" | "agent" | "chat" | "ask";
  agentProfileId?: string;
}

export interface PluginPrompt {
  id: string;
  pluginId: string;
  pluginVersion: string;
  name: string;
  description?: string;
  argumentHint?: string;
}

export interface PluginRuntimeItem {
  id: string;
  name: string;
  description?: string;
  source: string;
  state: "available" | "disabled" | "ready" | "update-available" | "error";
  label: string;
  configuredVersion?: string;
  installedVersion?: string;
  latestVersion?: string;
  integrity?: string;
  grantedCapabilities: string[];
  declaredCapabilities: string[];
  commandCount: number;
  promptCount: number;
  ruleCount: number;
  skillCount: number;
  installedAt?: string;
  lastCheckedAt?: string;
  lastError?: string;
}

export interface PluginRuntimeSnapshot {
  state: "idle" | "ready" | "degraded";
  label: string;
  items: PluginRuntimeItem[];
  commands: PluginCommand[];
  prompts: PluginPrompt[];
}

export type McpRuntimeState = "disabled" | "stopped" | "starting" | "ready" | "degraded" | "error" | "stopping";

export interface McpTool {
  id: string;
  serverId: string;
  name: string;
  title?: string;
  description: string;
  parameters: Record<string, unknown>;
  risk: "read_only" | "mutating";
}

export interface McpServerRuntime {
  id: string;
  name: string;
  enabled: boolean;
  transport: "stdio" | "streamable-http" | "sse";
  state: McpRuntimeState;
  label: string;
  serverName?: string;
  serverVersion?: string;
  protocolVersion?: string;
  capabilities: string[];
  tools: McpTool[];
  pid?: number;
  latencyMs?: number;
  restartCount: number;
  lastConnectedAt?: string;
  lastHealthyAt?: string;
  lastError?: string;
}

export interface McpRuntimeSnapshot {
  state: "idle" | "ready" | "degraded";
  label: string;
  servers: McpServerRuntime[];
  tools: McpTool[];
}

export type ProductJobStatus = "queued" | "running" | "completed" | "failed" | "cancelled";

export interface ProductJob {
  id: string;
  type: "subagent" | "history-summary";
  status: ProductJobStatus;
  prompt: string;
  role?: "research" | "review" | "test" | "security" | "planner";
  context?: string;
  expectedOutput?: string;
  maxOutputTokens?: number;
  model?: string;
  output?: string;
  outputPartial?: boolean;
  error?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProductJobSnapshot {
  state: "unavailable" | "loading" | "ready" | "error";
  label: string;
  items: ProductJob[];
}

export interface WorkspaceRule {
  id: string;
  name: string;
  path: string;
  content: string;
  trigger: "always" | "manual" | "agent";
  selected: boolean;
  description: string;
  source?: string;
}

export interface WorkspaceSkill {
  id: string;
  name: string;
  description: string;
  path: string;
  selected: boolean;
  source?: string;
}

export interface AppSnapshot {
  projectName: string;
  mode: Mode;
  selectedAgentProfileId?: string;
  runState: RunState;
  agentRun: AgentRunTelemetry;
  messages: ChatMessage[];
  tools: ToolRun[];
  threads: ThreadSummary[];
  tasks: TaskItem[];
  messageQueue: QueuedMessage[];
  attachments: ContextItem[];
  settings: SettingsSnapshot;
  account: AccountSnapshot;
  context: {
    state: "unavailable" | "not_indexed" | "indexing" | "checking" | "ready" | "error";
    label: string;
    files?: number;
    chunks?: number;
    roots?: number;
    watchedRoots?: number;
    watching?: boolean;
    hasEmbeddings?: boolean;
    lastIndexedAt?: string;
    pendingChanges?: number;
    automaticIndexRuns?: number;
    lastAutomaticIndexAt?: string;
    watchError?: string;
  };
  backendHealth: {
    state: "unknown" | "checking" | "online" | "offline" | "incompatible";
    label: string;
    protocolVersion?: number;
    provider?: string;
    defaultModel?: string;
  };
  models: ModelRegistry;
  backendTools: BackendToolCapability[];
  configurations: ConfigurationSnapshot;
  mcpRuntime: McpRuntimeSnapshot;
  hookRuntime: HookRuntimeSnapshot;
  pluginRuntime: PluginRuntimeSnapshot;
  jobs: ProductJobSnapshot;
  customization: {
    rules: WorkspaceRule[];
    skills: WorkspaceSkill[];
    maxSelectedSkills: number;
  };
}

export interface CommandEnvelope {
  version: number;
  id: string;
  type: string;
  payload?: unknown;
}

export interface EventEnvelope {
  version: number;
  sequence?: number;
  type: string;
  payload?: unknown;
}

declare global {
  interface Window {
    codeAgentPost?: (json: string) => void;
    CodeAgent?: { receive: (json: string) => void };
  }
}

const listeners = new Set<(event: EventEnvelope) => void>();
let lastHostEventSequence = 0;
let developmentSnapshot: AppSnapshot | undefined;
let developmentJobSequence = 0;
let developmentRunGeneration = 0;

function emitDevelopmentEvent(type: string, payload?: unknown): void {
  queueMicrotask(() => dispatchHostEvent({ version: PROTOCOL_VERSION, type, payload }));
}

function emitDevelopmentSnapshot(): void {
  if (developmentSnapshot) emitDevelopmentEvent("snapshot", developmentSnapshot);
}

function updateDevelopmentSnapshot(update: (snapshot: AppSnapshot) => AppSnapshot): void {
  if (!developmentSnapshot) return;
  developmentSnapshot = update(developmentSnapshot);
  emitDevelopmentSnapshot();
}

function startDevelopmentIndex(): void {
  const indexedAt = new Date().toISOString();
  updateDevelopmentSnapshot((snapshot) => ({
    ...snapshot,
    context: {
      state: "ready",
      label: "248 files indexed · Auto-sync on · Semantic search",
      files: 248,
      chunks: 1_376,
      roots: 1,
      watchedRoots: 1,
      watching: true,
      hasEmbeddings: true,
      pendingChanges: 0,
      automaticIndexRuns: 1,
      lastIndexedAt: indexedAt,
      lastAutomaticIndexAt: indexedAt,
    },
  }));
}

function nextDevelopmentTimelineSequence(snapshot: AppSnapshot): number {
  return Math.max(
    0,
    ...snapshot.messages.map((message) => message.timelineSequence ?? 0),
    ...snapshot.tools.map((tool) => tool.timelineSequence ?? 0),
  ) + 1;
}

type DevelopmentMessageRequest = {
  text?: string;
  mode?: Mode;
  clientMessageId?: string;
};

function startDevelopmentMessage(request: DevelopmentMessageRequest): void {
  const text = String(request.text ?? "").trim();
  if (!text || !developmentSnapshot) return;
  const simulateCompaction = /\b(?:simulate[- ]compaction|compact context)\b|模拟上下文压缩/i.test(text);
  const userMessageId = request.clientMessageId || crypto.randomUUID();
  const toolId = crypto.randomUUID();
  const assistantMessageId = crypto.randomUUID();
  const presentationRunId = crypto.randomUUID();
  const sequence = nextDevelopmentTimelineSequence(developmentSnapshot);
  const generation = ++developmentRunGeneration;
  updateDevelopmentSnapshot((snapshot) => ({
    ...snapshot,
    runState: "running",
    agentRun: {
      ...snapshot.agentRun,
      phase: "tools",
      turnIndex: snapshot.agentRun.turnIndex + 1,
      estimatedInputTokens: simulateCompaction
        ? 196_000
        : Math.min(200_000, 12_288 + snapshot.messages.length * 384 + Math.ceil(text.length / 4)),
      targetInputTokens: 203_776,
      contextWindowTokens: 256_000,
      reservedOutputTokens: 8_192,
      retrievalBudgetTokens: 17_408,
      toolDefinitionTokens: 1_024,
      compactedToolResults: simulateCompaction ? 3 : 0,
      truncatedMessages: simulateCompaction ? 6 : 0,
      compactionApplied: simulateCompaction,
      overBudget: false,
      activeToolNames: ["codebase_retrieval"],
      activeToolCount: 1,
      catalogToolCount: 12,
      discoverableToolCount: 11,
      activatedToolNames: ["codebase_retrieval"],
      toolBatchTotal: 1,
      toolBatchCompleted: 0,
      toolBatchExecution: "sequential",
      retryAttempt: 0,
      retryMaxAttempts: 0,
      retryMessage: undefined,
      verificationState: "idle",
    },
    messages: [...snapshot.messages, {
      id: userMessageId,
      role: "user",
      content: text,
      createdAt: Date.now(),
      timelineSequence: sequence,
      runId: presentationRunId,
    }],
    tools: [...snapshot.tools, {
      id: toolId,
      name: "codebase_retrieval",
      summary: "Retrieving from: Codebase",
      status: "running",
      detail: `Query\n${text}`,
      canRevert: false,
      runId: presentationRunId,
      turnIndex: 0,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      timelineSequence: sequence + 1,
    }],
    threads: snapshot.threads.map((thread) => thread.active
      ? { ...thread, title: text.slice(0, 64), updatedAt: Date.now(), mode: request.mode ?? snapshot.mode }
      : thread),
  }));
  window.setTimeout(() => {
    if (generation !== developmentRunGeneration) return;
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      agentRun: {
        ...snapshot.agentRun,
        phase: "processing",
        toolBatchCompleted: 1,
      },
      tools: snapshot.tools.map((tool) => tool.id === toolId
        ? {
            ...tool,
            status: "completed",
            updatedAt: Date.now(),
            detail: `Query\n${text}\n\nSources\nfrontend/src/App.svelte\nfrontend/src/lib/protocol.ts`,
          }
        : tool),
    }));
  }, 850);
  window.setTimeout(() => {
    if (generation !== developmentRunGeneration) return;
    let queuedMessage: QueuedMessage | undefined;
    updateDevelopmentSnapshot((snapshot) => {
      queuedMessage = snapshot.messageQueue[0];
      return {
        ...snapshot,
        runState: "idle",
        agentRun: {
          ...snapshot.agentRun,
          phase: "idle",
          activeToolNames: [],
          activeToolCount: 0,
        },
        messageQueue: queuedMessage ? snapshot.messageQueue.slice(1) : snapshot.messageQueue,
        tools: snapshot.tools,
        messages: [...snapshot.messages, {
          id: assistantMessageId,
          role: "assistant",
          content: `Development host completed the interaction for: ${text}`,
          createdAt: Date.now(),
          runId: presentationRunId,
          turnIndex: 1,
          timelineSequence: sequence + 2,
        }],
      };
    });
    if (queuedMessage) {
      startDevelopmentMessage({ text: queuedMessage.text, mode: queuedMessage.mode, clientMessageId: queuedMessage.id });
    }
  }, 5_000);
}

type DevelopmentJobRequest = {
  prompt?: string;
  role?: NonNullable<ProductJob["role"]>;
  context?: string | null;
  expectedOutput?: string | null;
  maxOutputTokens?: number;
  model?: string | null;
};

function developmentJobLabel(items: ProductJob[]): string {
  return items.length === 0 ? "No durable jobs" : `${items.length} durable job${items.length === 1 ? "" : "s"}`;
}

function startDevelopmentJob(request: DevelopmentJobRequest): void {
  const prompt = String(request.prompt ?? "").trim();
  if (!prompt) {
    emitDevelopmentEvent("error", { message: "Job task is required" });
    return;
  }
  const role = request.role ?? "research";
  const now = new Date().toISOString();
  const jobId = `dev-job-${++developmentJobSequence}`;
  const job: ProductJob = {
    id: jobId,
    type: "subagent",
    status: "queued",
    prompt,
    role,
    context: request.context?.trim() || undefined,
    expectedOutput: request.expectedOutput?.trim() || undefined,
    maxOutputTokens: request.maxOutputTokens ?? 4096,
    model: request.model?.trim() || developmentSnapshot?.models.selectedModel || developmentSnapshot?.models.defaultModel,
    createdAt: now,
    updatedAt: now,
  };
  updateDevelopmentSnapshot((snapshot) => {
    const items = [job, ...snapshot.jobs.items];
    return { ...snapshot, jobs: { state: "ready", label: developmentJobLabel(items), items } };
  });
  window.setTimeout(() => updateDevelopmentSnapshot((snapshot) => ({
    ...snapshot,
    jobs: {
      state: "ready",
      label: snapshot.jobs.label,
      items: snapshot.jobs.items.map((item) => item.id === jobId && item.status === "queued"
        ? { ...item, status: "running", output: `Running ${role} analysis...`, outputPartial: true, updatedAt: new Date().toISOString() }
        : item),
    },
  })), 180);
  window.setTimeout(() => updateDevelopmentSnapshot((snapshot) => {
    const items = snapshot.jobs.items.map((item) => item.id === jobId && item.status !== "cancelled"
      ? {
          ...item,
          status: "completed" as const,
          output: `${role[0].toUpperCase()}${role.slice(1)} subagent completed the delegated task.\n\nTask: ${prompt}\n\nResult: No blocking issue was found in the development-host simulation.`,
          outputPartial: false,
          updatedAt: new Date().toISOString(),
        }
      : item);
    return { ...snapshot, jobs: { state: "ready", label: developmentJobLabel(items), items } };
  }), 620);
}

export function onHostEvent(listener: (event: EventEnvelope) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function sendCommand(type: string, payload?: unknown): void {
  const command: CommandEnvelope = {
    version: PROTOCOL_VERSION,
    id: crypto.randomUUID(),
    type,
    payload,
  };

  if (window.codeAgentPost) {
    window.codeAgentPost(JSON.stringify(command));
    return;
  }

  handleDevelopmentCommand(command);
}

function dispatchHostEvent(event: EventEnvelope): void {
  const sequence = Number(event.sequence ?? 0);
  if (sequence > 0 && sequence <= lastHostEventSequence) {
    acknowledgeHostEvent(event);
    return;
  }
  if (listeners.size === 0) return;
  try {
    listeners.forEach((listener) => listener(event));
    if (sequence > 0) {
      lastHostEventSequence = sequence;
      acknowledgeHostEvent(event);
    }
  } catch (error) {
    console.error("CodeAgent host event failed", error);
  }
}

window.CodeAgent = {
  receive(json: string) {
    dispatchHostEvent(JSON.parse(json) as EventEnvelope);
  },
};

function acknowledgeHostEvent(event: EventEnvelope): void {
  if (!window.codeAgentPost) return;
  const sequence = Number(event.sequence ?? 0);
  if (sequence <= 0) return;
  const snapshot = event.type === "snapshot" && event.payload && typeof event.payload === "object"
    ? event.payload as { messages?: Array<{ id?: unknown }> }
    : undefined;
  const messages = Array.isArray(snapshot?.messages) ? snapshot.messages : undefined;
  const lastMessageId = messages?.at(-1)?.id;
  const command: CommandEnvelope = {
    version: PROTOCOL_VERSION,
    id: crypto.randomUUID(),
    type: "ackEvent",
    payload: {
      sequence,
      eventType: event.type,
      snapshotMessageCount: messages?.length,
      lastMessageId: typeof lastMessageId === "string" ? lastMessageId : undefined,
    },
  };
  window.codeAgentPost(JSON.stringify(command));
}

function handleDevelopmentCommand(command: CommandEnvelope): void {
  if (command.type === "saveSettings") {
    const payload = command.payload as Partial<SettingsSaved> & {
      backendToken?: string;
      clearBackendToken?: boolean;
      contextHttpApiKey?: string;
      clearContextHttpApiKey?: boolean;
      contextEmbeddingApiKey?: string;
      clearContextEmbeddingApiKey?: boolean;
    };
    if (payload.requestId) {
      const current = developmentSnapshot?.settings;
      const backendTokenConfigured = payload.clearBackendToken
        ? false
        : Boolean(payload.backendToken?.trim()) || current?.backendTokenConfigured || false;
      const contextHttpTokenConfigured = payload.clearContextHttpApiKey
        ? false
        : Boolean(payload.contextHttpApiKey?.trim()) || current?.contextHttpTokenConfigured || false;
      const contextEmbeddingTokenConfigured = payload.clearContextEmbeddingApiKey
        ? false
        : Boolean(payload.contextEmbeddingApiKey?.trim()) || current?.contextEmbeddingTokenConfigured || false;
      const saved: SettingsSaved = {
        requestId: payload.requestId,
        backendTokenConfigured,
        contextHttpTokenConfigured,
        contextEmbeddingTokenConfigured,
      };
      if (developmentSnapshot) {
        developmentSnapshot = {
          ...developmentSnapshot,
          settings: {
            ...developmentSnapshot.settings,
            backendTokenConfigured,
            contextHttpTokenConfigured,
            contextEmbeddingTokenConfigured,
          },
        };
      }
      queueMicrotask(() => window.CodeAgent?.receive(JSON.stringify({ version: 1, type: "settingsSaved", payload: saved })));
    }
    return;
  }
  if (command.type === "refreshGit") {
    const payload: GitSnapshot = {
      available: true,
      branch: "main",
      repository: "sample-project",
      unstaged: [
        { path: "src/main/java/com/example/auth/AuthController.java", status: "modified" },
        { path: "src/test/java/com/example/auth/AuthControllerTest.java", status: "untracked" },
      ],
      staged: [{ path: "src/main/java/com/example/auth/TokenService.java", status: "modified" }],
    };
    queueMicrotask(() => window.CodeAgent?.receive(JSON.stringify({ version: 1, type: "gitSnapshot", payload })));
    return;
  }
  if (command.type === "suggestCommitMessage") {
    queueMicrotask(() => window.CodeAgent?.receive(JSON.stringify({ version: 1, type: "gitCommitSuggested", payload: { message: "feat(auth): update JWT login flow" } })));
    return;
  }
  if (command.type === "refreshImageCanvas") {
    const payload: ImageCanvasSnapshot = { directory: "docs/screenshots", images: [], truncated: false };
    queueMicrotask(() => window.CodeAgent?.receive(JSON.stringify({ version: 1, type: "imageCanvas", payload })));
    return;
  }
  if (command.type === "setMode") {
    const mode = (command.payload as { mode?: Mode } | undefined)?.mode;
    if (mode === "agent" || mode === "chat" || mode === "ask") {
      updateDevelopmentSnapshot((snapshot) => ({
        ...snapshot,
        mode,
        threads: snapshot.threads.map((thread) => thread.active ? { ...thread, mode } : thread),
      }));
    }
    return;
  }
  if (command.type === "selectModel") {
    const modelId = String((command.payload as { modelId?: string } | undefined)?.modelId ?? "").trim();
    updateDevelopmentSnapshot((snapshot) => snapshot.models.options.some((model) => model.id === modelId)
      ? { ...snapshot, models: { ...snapshot.models, selectedModel: modelId } }
      : snapshot);
    return;
  }
  if (command.type === "getContextStatus") {
    emitDevelopmentSnapshot();
    return;
  }
  if (command.type === "checkContextEngine") {
    const payload = command.payload as {
      requestId?: string;
      contextMode?: string;
      contextHttpBaseUrl?: string;
      contextHttpApiKey?: string;
    } | undefined;
    if (payload?.contextMode !== "remote-http") {
      queueMicrotask(() => window.CodeAgent?.receive(JSON.stringify({
        version: 1,
        type: "contextConnectionChecked",
        payload: { requestId: payload?.requestId, ok: true, label: "ContextEngine runtime verified" },
      })));
      return;
    }
    const baseUrl = String(payload.contextHttpBaseUrl ?? "").trim().replace(/\/+$/, "");
    const token = String(payload.contextHttpApiKey ?? "").trim();
    const headers = token ? { Authorization: `Bearer ${token}` } : undefined;
    const endpoint = baseUrl === "http://127.0.0.1:8790"
      ? "/__contextengine/v1/workspaces"
      : `${baseUrl}/v1/workspaces`;
    void fetch(endpoint, { headers, signal: AbortSignal.timeout(10_000) })
      .then((response) => {
        const checked = response.ok
          ? { ok: true, label: "Connection and token verified. Save settings to apply." }
          : response.status === 401
            ? { ok: false, label: "ContextEngine token is invalid" }
            : response.status === 403
              ? { ok: false, label: "ContextEngine token is not authorized" }
              : { ok: false, label: `ContextEngine returned HTTP ${response.status}` };
        window.CodeAgent?.receive(JSON.stringify({
          version: 1,
          type: "contextConnectionChecked",
          payload: { requestId: payload?.requestId, ...checked },
        }));
      })
      .catch((error) => window.CodeAgent?.receive(JSON.stringify({
        version: 1,
        type: "contextConnectionChecked",
        payload: {
          requestId: payload?.requestId,
          ok: false,
          label: error instanceof Error ? error.message : "ContextEngine connection failed",
        },
      })));
    return;
  }
  if (command.type === "refreshContextIndex") {
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      context: { ...snapshot.context, state: "checking", label: "Checking automatic sync" },
    }));
    window.setTimeout(() => {
      startDevelopmentIndex();
      emitDevelopmentEvent("notice", { message: "Automatic sync is active; the index is current" });
    }, 260);
    return;
  }
  if (command.type === "indexWorkspace") {
    startDevelopmentIndex();
    return;
  }
  if (command.type === "checkBackend") {
    const payload = command.payload as { requestId?: string; backendUrl?: string; backendToken?: string } | undefined;
    if (payload?.requestId) {
      window.setTimeout(() => emitDevelopmentEvent("backendConnectionChecked", {
        requestId: payload.requestId,
        ok: true,
        label: `Connection verified: ${payload.backendUrl || "codeagent-backend"}`,
      }), 220);
      return;
    }
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      backendHealth: { state: "checking", label: "Checking backend" },
    }));
    window.setTimeout(() => updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      backendHealth: { state: "online", label: "Connected to codeagent-backend", protocolVersion: 1 },
    })), 220);
    return;
  }
  if (command.type === "refreshJobs") {
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      jobs: { ...snapshot.jobs, state: "ready", label: developmentJobLabel(snapshot.jobs.items) },
    }));
    return;
  }
  if (command.type === "createJob") {
    startDevelopmentJob((command.payload ?? {}) as DevelopmentJobRequest);
    return;
  }
  if (command.type === "cancelJob") {
    const jobId = String((command.payload as { jobId?: string } | undefined)?.jobId ?? "");
    updateDevelopmentSnapshot((snapshot) => {
      const items = snapshot.jobs.items.map((job) => job.id === jobId && (job.status === "queued" || job.status === "running")
        ? { ...job, status: "cancelled" as const, outputPartial: false, error: "Cancelled by user", updatedAt: new Date().toISOString() }
        : job);
      return { ...snapshot, jobs: { state: "ready", label: developmentJobLabel(items), items } };
    });
    return;
  }
  if (command.type === "retryJob") {
    const jobId = String((command.payload as { jobId?: string } | undefined)?.jobId ?? "");
    const job = developmentSnapshot?.jobs.items.find((item) => item.id === jobId);
    if (!job) {
      emitDevelopmentEvent("error", { message: "Job no longer exists" });
      return;
    }
    startDevelopmentJob({
      prompt: job.prompt,
      role: job.role,
      context: job.context,
      expectedOutput: job.expectedOutput,
      maxOutputTokens: job.maxOutputTokens,
      model: job.model,
    });
    return;
  }
  if (command.type === "openJobResult") {
    const jobId = String((command.payload as { jobId?: string } | undefined)?.jobId ?? "");
    const job = developmentSnapshot?.jobs.items.find((item) => item.id === jobId);
    emitDevelopmentEvent(job?.output ? "notice" : "error", {
      message: job?.output ? "Job result is available in the durable-jobs panel; the IDE build opens it in an editor tab" : "Job result is not available yet",
    });
    return;
  }
  if (command.type === "enhancePrompt") {
    const text = String((command.payload as { text?: string } | undefined)?.text ?? "").trim();
    emitDevelopmentEvent(text ? "promptEnhanced" : "error", text
      ? { text: `${text}\n\nInclude concrete file evidence, implementation constraints, and verification steps.` }
      : { message: "Prompt text is required" });
    return;
  }
  if (command.type === "queueMessage") {
    const request = command.payload as { text?: string; mode?: Mode; clientMessageId?: string } | undefined;
    const text = String(request?.text ?? "").trim();
    if (text) updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      messageQueue: [...snapshot.messageQueue, { id: request?.clientMessageId || crypto.randomUUID(), text, mode: request?.mode ?? snapshot.mode }],
    }));
    return;
  }
  if (command.type === "removeQueuedMessage") {
    const messageId = String((command.payload as { messageId?: string } | undefined)?.messageId ?? "");
    updateDevelopmentSnapshot((snapshot) => ({ ...snapshot, messageQueue: snapshot.messageQueue.filter((message) => message.id !== messageId) }));
    return;
  }
  if (command.type === "cancelRun") {
    developmentRunGeneration += 1;
    let queuedMessage: QueuedMessage | undefined;
    updateDevelopmentSnapshot((snapshot) => {
      queuedMessage = snapshot.messageQueue[0];
      return {
        ...snapshot,
        runState: "idle",
        messageQueue: queuedMessage ? snapshot.messageQueue.slice(1) : snapshot.messageQueue,
        tools: snapshot.tools.map((tool) => tool.status === "running" || tool.status === "approval" ? { ...tool, status: "rejected" } : tool),
        tasks: snapshot.tasks.map((task) => task.state === "in_progress" ? { ...task, state: "cancelled" } : task),
      };
    });
    if (queuedMessage) {
      startDevelopmentMessage({ text: queuedMessage.text, mode: queuedMessage.mode, clientMessageId: queuedMessage.id });
    }
    return;
  }
  if (command.type === "attachActiveEditor") {
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      attachments: [...snapshot.attachments, { id: crypto.randomUUID(), label: "ActiveEditor.java", path: "src/main/java/com/example/ActiveEditor.java", kind: "ide_state" }],
    }));
    return;
  }
  if (command.type === "pickContext") {
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      attachments: [...snapshot.attachments, { id: crypto.randomUUID(), label: "SelectedContext.java", path: "src/main/java/com/example/SelectedContext.java", kind: "file" }],
    }));
    return;
  }
  if (command.type === "attachImage") {
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      attachments: [...snapshot.attachments, { id: crypto.randomUUID(), label: "architecture.png", path: "docs/screenshots/architecture.png", kind: "image" }],
    }));
    return;
  }
  if (command.type === "resolveApproval") {
    const request = command.payload as { toolId?: string; approved?: boolean } | undefined;
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      runState: "idle",
      tools: snapshot.tools.map((tool) => tool.id === request?.toolId
        ? { ...tool, status: request.approved ? "completed" : "rejected", detail: `${tool.detail ?? ""}\n\n${request.approved ? "Approved" : "Rejected"} in the development host.`.trim() }
        : tool),
    }));
    return;
  }
  if (command.type === "revertChange") {
    const toolId = String((command.payload as { toolId?: string } | undefined)?.toolId ?? "");
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      tools: snapshot.tools.map((tool) => tool.id === toolId ? { ...tool, status: "rejected", canRevert: false } : tool),
    }));
    return;
  }
  if (command.type === "keepChanges" || command.type === "discardChanges") {
    const toolIds = new Set((command.payload as { toolIds?: string[] } | undefined)?.toolIds ?? []);
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      tools: snapshot.tools.map((tool) => toolIds.has(tool.id)
        ? { ...tool, status: command.type === "discardChanges" ? "rejected" : tool.status, canRevert: false }
        : tool),
    }));
    return;
  }
  if (command.type === "signIn") {
    updateDevelopmentSnapshot((snapshot) => ({ ...snapshot, account: { ...snapshot.account, state: "signing_in", label: "Waiting for browser sign-in" } }));
    window.setTimeout(() => updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      account: { state: "signed_in", mode: "oidc", userId: "dev-user", displayName: "CodeAgent Developer", email: "developer@example.com", usage: snapshot.account.usage, label: "Signed in as CodeAgent Developer" },
    })), 260);
    return;
  }
  if (command.type === "signOut") {
    updateDevelopmentSnapshot((snapshot) => ({ ...snapshot, account: { ...snapshot.account, state: "signing_out", label: "Signing out" } }));
    window.setTimeout(() => updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      account: { state: "signed_out", mode: "oidc", usage: [], label: "Signed out" },
    })), 180);
    return;
  }
  if (command.type === "refreshConfigurations" || command.type === "refreshCustomization") {
    emitDevelopmentSnapshot();
    return;
  }
  if (command.type === "listCheckpoints") {
    emitDevelopmentEvent("checkpoints", [{ id: "dev-checkpoint", label: "Development snapshot", createdAt: new Date().toISOString() }]);
    return;
  }
  if (command.type === "createCheckpoint" || command.type === "restoreCheckpoint") {
    emitDevelopmentEvent("notice", { message: command.type === "createCheckpoint" ? "Development checkpoint created" : "Development checkpoint restored" });
    return;
  }
  if (command.type === "saveRule") {
    emitDevelopmentEvent("notice", { message: "Rule saved in the development host; the JetBrains build writes the workspace rule file" });
    emitDevelopmentSnapshot();
    return;
  }
  if (
    command.type === "browseImageDirectory"
    || command.type === "commitGit"
    || command.type === "copyText"
    || command.type === "copyThread"
    || command.type === "discardChanges"
    || command.type === "exportTasks"
    || command.type === "exportThread"
    || command.type === "importTasks"
    || command.type === "importThread"
    || command.type === "openDiff"
    || command.type === "openGitDiff"
    || command.type === "openImage"
    || command.type === "openMermaidEditor"
    || command.type === "openTerminal"
    || command.type === "reviewChanges"
    || command.type === "stageGit"
    || command.type === "unstageGit"
  ) {
    emitDevelopmentEvent("notice", { message: `${command.type} is implemented by the JetBrains host; the browser development host acknowledged the action without changing local IDE state` });
    return;
  }
  if (command.type === "sendMessage") {
    startDevelopmentMessage((command.payload ?? {}) as DevelopmentMessageRequest);
    return;
  }
  if (command.type === "newThread") {
    const mode = (command.payload as { mode?: Mode } | undefined)?.mode ?? "agent";
    const threadId = crypto.randomUUID();
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      mode,
      messages: [],
      tools: [],
      tasks: [],
      messageQueue: [],
      attachments: [],
      threads: [
        { id: threadId, title: "New thread", updatedAt: Date.now(), active: true, mode, pinned: false },
        ...snapshot.threads.map((thread) => ({ ...thread, active: false })),
      ],
    }));
    return;
  }
  if (command.type === "selectThread") {
    const threadId = String((command.payload as { threadId?: string } | undefined)?.threadId ?? "");
    updateDevelopmentSnapshot((snapshot) => {
      const selected = snapshot.threads.find((thread) => thread.id === threadId);
      return selected ? {
        ...snapshot,
        mode: selected.mode,
        threads: snapshot.threads.map((thread) => ({ ...thread, active: thread.id === threadId })),
      } : snapshot;
    });
    return;
  }
  if (command.type === "toggleThreadPinned") {
    const request = command.payload as { threadId?: string; pinned?: boolean } | undefined;
    const threadId = String(request?.threadId ?? "");
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      threads: snapshot.threads.map((thread) => thread.id === threadId
        ? { ...thread, pinned: request?.pinned ?? !thread.pinned }
        : thread),
    }));
    return;
  }
  if (command.type === "renameThread") {
    const request = command.payload as { threadId?: string; title?: string } | undefined;
    const title = String(request?.title ?? "").trim();
    if (title) updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      threads: snapshot.threads.map((thread) => thread.id === request?.threadId ? { ...thread, title, updatedAt: Date.now() } : thread),
    }));
    return;
  }
  if (command.type === "deleteThread") {
    const threadId = String((command.payload as { threadId?: string } | undefined)?.threadId ?? "");
    updateDevelopmentSnapshot((snapshot) => {
      const remaining = snapshot.threads.filter((thread) => thread.id !== threadId);
      if (remaining.length === 0) return snapshot;
      const hadActive = snapshot.threads.some((thread) => thread.id === threadId && thread.active);
      return { ...snapshot, threads: remaining.map((thread, index) => ({ ...thread, active: hadActive ? index === 0 : thread.active })) };
    });
    return;
  }
  if (command.type === "setTaskState") {
    const request = command.payload as { taskId?: string; state?: TaskItem["state"] } | undefined;
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      tasks: snapshot.tasks.map((task) => task.id === request?.taskId && request.state
        ? { ...task, state: request.state }
        : task),
    }));
    return;
  }
  if (command.type === "addTask") {
    const name = String((command.payload as { name?: string } | undefined)?.name ?? "").trim();
    if (name) updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      tasks: [...snapshot.tasks, { id: crypto.randomUUID(), name, state: "not_started" }],
    }));
    return;
  }
  if (command.type === "deleteTask") {
    const taskId = String((command.payload as { taskId?: string } | undefined)?.taskId ?? "");
    updateDevelopmentSnapshot((snapshot) => ({ ...snapshot, tasks: snapshot.tasks.filter((task) => task.id !== taskId) }));
    return;
  }
  if (command.type === "clearCompletedTasks") {
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      tasks: snapshot.tasks.filter((task) => task.state !== "completed" && task.state !== "cancelled"),
    }));
    return;
  }
  if (command.type === "clearTasks") {
    updateDevelopmentSnapshot((snapshot) => ({ ...snapshot, tasks: [] }));
    return;
  }
  if (command.type === "runTask") {
    const taskId = String((command.payload as { taskId?: string } | undefined)?.taskId ?? "");
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      runState: "running",
      tasks: snapshot.tasks.map((task) => task.id === taskId ? { ...task, state: "in_progress" } : task),
    }));
    window.setTimeout(() => updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      runState: "idle",
      tasks: snapshot.tasks.map((task) => task.id === taskId && task.state === "in_progress" ? { ...task, state: "completed" } : task),
    })), 360);
    return;
  }
  if (command.type === "runAllTasks") {
    const taskIds = new Set(developmentSnapshot?.tasks.filter((task) => task.state !== "completed" && task.state !== "cancelled").map((task) => task.id) ?? []);
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      runState: "running",
      tasks: snapshot.tasks.map((task) => taskIds.has(task.id) ? { ...task, state: "in_progress" } : task),
    }));
    window.setTimeout(() => updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      runState: "idle",
      tasks: snapshot.tasks.map((task) => taskIds.has(task.id) && task.state === "in_progress" ? { ...task, state: "completed" } : task),
    })), 520);
    return;
  }
  if (command.type === "removeContext") {
    const id = String((command.payload as { id?: string } | undefined)?.id ?? "");
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      attachments: snapshot.attachments.filter((attachment) => attachment.id !== id),
    }));
    return;
  }
  if (command.type === "toggleSkill") {
    const request = command.payload as { skillId?: string; selected?: boolean } | undefined;
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      customization: {
        ...snapshot.customization,
        skills: snapshot.customization.skills.map((skill) => skill.id === request?.skillId
          ? { ...skill, selected: Boolean(request.selected) }
          : skill),
      },
    }));
    return;
  }
  if (command.type === "toggleRule") {
    const request = command.payload as { ruleId?: string; selected?: boolean } | undefined;
    updateDevelopmentSnapshot((snapshot) => ({
      ...snapshot,
      customization: {
        ...snapshot.customization,
        rules: snapshot.customization.rules.map((rule) => rule.id === request?.ruleId
          ? { ...rule, selected: Boolean(request.selected) }
          : rule),
      },
    }));
    return;
  }
  if (command.type !== "bootstrap") {
    emitDevelopmentEvent("error", { message: `Development host has no handler for ${command.type}` });
    return;
  }
  const snapshot: AppSnapshot = {
    projectName: "sample-project",
    mode: "agent",
    runState: "idle",
    agentRun: {
      phase: "idle",
      turnIndex: 0,
      estimatedInputTokens: 0,
      targetInputTokens: 0,
      contextWindowTokens: 0,
      reservedOutputTokens: 0,
      retrievalBudgetTokens: 0,
      toolDefinitionTokens: 0,
      compactedToolResults: 0,
      truncatedMessages: 0,
      compactionApplied: false,
      overBudget: false,
      activeToolNames: [],
      activeToolCount: 0,
      catalogToolCount: 0,
      discoverableToolCount: 0,
      activatedToolNames: [],
      toolBatchTotal: 0,
      toolBatchCompleted: 0,
      retryAttempt: 0,
      retryMaxAttempts: 0,
      verificationState: "idle",
    },
    messages: [
      {
        id: "user-1",
        role: "user",
        content: "Implement JWT login end-to-end, update the tests, and verify the integration.",
        createdAt: Date.now() - 42_000,
        timelineSequence: 1,
      },
      {
        id: "assistant-1",
        role: "assistant",
        content: "JWT login is implemented and the focused tests pass. The edit remains available for native Diff review.",
        createdAt: Date.now() - 14_000,
        timelineSequence: 10,
      },
    ],
    tools: [
      {
        id: "tool-context",
        name: "codebase_retrieval",
        summary: "Retrieving from: Codebase",
        status: "completed",
        detail: "Query\nJWT login controller and security configuration\n\nSources\nAuthController.java\nSecurityConfig.java\nTokenService.java",
        canRevert: false,
        timelineSequence: 2,
      },
      {
        id: "tool-read",
        name: "read_file",
        summary: "AuthController.java",
        status: "completed",
        detail: "src/main/java/com/example/auth/AuthController.java:1-94",
        canRevert: false,
        timelineSequence: 3,
      },
      {
        id: "tool-search",
        name: "search_text",
        summary: "pattern: issue(",
        status: "completed",
        detail: "TokenService.java:41: return jwtIssuer.issue(user);",
        canRevert: false,
        timelineSequence: 4,
      },
      {
        id: "tool-edit",
        name: "replace_text",
        summary: "AuthController.java",
        status: "completed",
        detail: "+ return tokenService.issue(request);\n- return null;",
        changePath: "src/main/java/com/example/auth/AuthController.java",
        canRevert: true,
        timelineSequence: 5,
      },
      {
        id: "tool-create",
        name: "write_file",
        summary: "AuthControllerTest.java",
        status: "completed",
        detail: "Created focused login and invalid-credential tests.",
        changePath: "src/test/java/com/example/auth/AuthControllerTest.java",
        canRevert: true,
        timelineSequence: 6,
      },
      {
        id: "tool-terminal",
        name: "run_terminal",
        summary: "./gradlew test --tests AuthControllerTest",
        status: "completed",
        detail: "BUILD SUCCESSFUL in 4s\n14 tests completed",
        canRevert: false,
        timelineSequence: 7,
      },
      {
        id: "tool-open",
        name: "open_file",
        summary: "SecurityConfig.java",
        status: "completed",
        detail: "Opened in the active IDE editor.",
        canRevert: false,
        timelineSequence: 8,
      },
      {
        id: "tool-mermaid",
        name: "render_mermaid",
        summary: "JWT login flow",
        status: "completed",
        detail: "flowchart LR\n  Client[Client] --> Controller[AuthController]\n  Controller --> Service[TokenService]\n  Service --> JWT[Signed JWT]",
        canRevert: false,
        timelineSequence: 9,
      },
    ],
    threads: [
      { id: "dev", title: "Implement login flow with JWT", updatedAt: Date.now(), active: true, mode: "agent", pinned: true },
      { id: "dev-2", title: "Review repository architecture", updatedAt: Date.now() - 3_600_000, active: false, mode: "ask", pinned: false },
      { id: "dev-3", title: "Investigate flaky integration test", updatedAt: Date.now() - 86_400_000, active: false, mode: "chat", pinned: false },
    ],
    tasks: [
      { id: "task-1", name: "Inspect the existing authentication flow", state: "completed" },
      { id: "task-2", name: "Implement JWT token issuance", state: "completed" },
      { id: "task-3", name: "Add invalid-credential regression coverage", state: "in_progress" },
      { id: "task-4", name: "Run the complete integration suite", state: "not_started" },
    ],
    messageQueue: [],
    attachments: [{ id: "attachment-1", label: "UserRepository.java", path: "src/main/java/com/example/UserRepository.java", kind: "file" }],
    settings: {
      backendUrl: "http://127.0.0.1:8788",
      nodePath: "node",
      backendTokenConfigured: false,
      autoApproveReadOnly: true,
      chatZoom: 100,
      showTimestamps: true,
      showRunTelemetry: true,
      desktopNotifications: false,
      autoDismissNotifications: true,
      contextMode: "remote-http",
      contextHttpBaseUrl: "http://127.0.0.1:8790",
      contextHttpTokenConfigured: false,
      contextEmbeddingBaseUrl: "",
      contextEmbeddingModel: "",
      contextEmbeddingTokenConfigured: false,
      contextNeuralRerank: false,
      contextRerankBaseUrl: "",
      contextRerankModel: "",
    },
    account: {
      state: "signed_in",
      mode: "oidc",
      userId: "dev-user",
      displayName: "CodeAgent Developer",
      email: "developer@example.com",
      usage: [
        { kind: "agent-run", units: 42 },
        { kind: "completion", units: 318 },
      ],
      label: "Signed in as CodeAgent Developer",
    },

    context: { state: "not_indexed", label: "Preparing automatic project index" },
    backendHealth: { state: "online", label: "Connected to codeagent-backend", protocolVersion: 1 },
    models: {
      state: "ready",
      provider: "unified-native",
      defaultModel: "gpt-5.6-sol",
      selectedModel: undefined,
      options: [
        { id: "gpt-5.6-sol", ownedBy: "openai" },
        { id: "claude-fable-5", ownedBy: "anthropic" },
        { id: "claude-opus-4-8", ownedBy: "anthropic" },
        { id: "claude-sonnet-5", ownedBy: "anthropic" },
        { id: "grok-4.5", ownedBy: "grok" },
      ],
      label: "5 models",
    },
    backendTools: [
      {
        name: "web_search",
        catalogId: "web",
        available: false,
        unavailableReason: "Set WEB_SEARCH_ENDPOINT",
        requiredEnvironment: ["WEB_SEARCH_ENDPOINT"],
      },
      {
        name: "subagent",
        catalogId: "subagent",
        available: true,
        requiredEnvironment: ["MODEL"],
      },
    ],
    configurations: {
      state: "ready",
      label: "3 configurations",
      items: {
        commands: [
          {
            id: "review",
            kind: "commands",
            value: {
              name: "Review changes",
              description: "Review the current diff and run focused verification.",
              enabled: true,
              prompt: "Inspect the current changes for bugs, regressions, and missing tests.",
              argumentHint: "[scope]",
            },
          },
        ],
        hooks: [
          {
            id: "verify-run",
            kind: "hooks",
            value: {
              name: "Verify run",
              description: "Runs the focused verification script after an Agent run.",
              enabled: true,
              event: "after-run",
              command: "./gradlew test",
              timeoutSeconds: 120,
              runPolicy: "manual",
              failurePolicy: "continue",
              requiredEnvironment: [],
            },
          },
        ],
        plugins: [
          {
            id: "review-pack",
            kind: "plugins",
            value: {
              name: "Review pack",
              description: "Shared declarative review workflows.",
              enabled: true,
              source: "https://plugins.example.test/review-pack.json",
              version: null,
              integrity: null,
              capabilities: ["commands"],
            },
          },
        ],
        agents: [
          {
            id: "context-researcher",
            kind: "agents",
            value: {
              name: "Context researcher",
              description: "Builds an evidence-backed repository context pack.",
              enabled: true,
              agentType: "context",
              systemPrompt: "Retrieve the smallest complete set of repository evidence needed to answer the task.",
              model: "gpt-5.6-sol",
              allowedTools: ["codebase_retrieval", "read_file", "search_text"],
              maxTurns: 10,
              contextWindowTokens: 256000,
              reservedOutputTokens: 8192,
            },
          },
        ],
        mcp: [
          {
            id: "local-context",
            kind: "mcp",
            value: {
              name: "Local Context MCP",
              description: "Saved local development endpoint.",
              enabled: true,
              transport: "streamable-http",
              command: null,
              args: [],
              cwd: null,
              url: "http://127.0.0.1:3939/mcp",
              authMode: "none",
              tokenEnvironment: null,
              requiredEnvironment: [],
              timeoutSeconds: 60,
            },
          },
        ],
      },
    },
    mcpRuntime: {
      state: "ready",
      label: "1 server · 1 tool",
      servers: [
        {
          id: "local-context",
          name: "Local Context MCP",
          enabled: true,
          transport: "streamable-http",
          state: "ready",
          label: "1 tool · 18 ms",
          serverName: "context-engine",
          serverVersion: "1.0.0",
          protocolVersion: "2025-11-25",
          capabilities: ["tools"],
          tools: [
            {
              id: "mcp__local-context_d9a3a824__search_24193290",
              serverId: "local-context",
              name: "search",
              title: "Search context",
              description: "Search the connected context service.",
              parameters: {
                type: "object",
                properties: { query: { type: "string" } },
                required: ["query"],
                additionalProperties: false,
              },
              risk: "read_only",
            },
          ],
          latencyMs: 18,
          restartCount: 0,
          lastConnectedAt: new Date(Date.now() - 30_000).toISOString(),
          lastHealthyAt: new Date(Date.now() - 5_000).toISOString(),
        },
      ],
      tools: [
        {
          id: "mcp__local-context_d9a3a824__search_24193290",
          serverId: "local-context",
          name: "search",
          title: "Search context",
          description: "Search the connected context service.",
          parameters: {
            type: "object",
            properties: { query: { type: "string" } },
            required: ["query"],
            additionalProperties: false,
          },
          risk: "read_only",
        },
      ],
    },
    hookRuntime: {
      state: "ready",
      label: "1 hook · manual execution",
      configured: 1,
      automatic: 0,
      recent: [
        {
          id: "hook-run-1",
          hookId: "verify-run",
          hookName: "Verify run",
          event: "after-run",
          status: "completed",
          exitCode: 0,
          startedAt: new Date(Date.now() - 45_000).toISOString(),
          durationMs: 4218,
          summary: "Completed",
          detail: "BUILD SUCCESSFUL in 4s",
        },
      ],
    },
    pluginRuntime: {
      state: "ready",
      label: "1 configured · 1 installed · 1 active",
      items: [
        {
          id: "review-pack",
          name: "Review pack",
          description: "Shared declarative review workflows.",
          source: "https://plugins.example.test/review-pack.json",
          state: "ready",
          label: "Installed and active",
          installedVersion: "1.0.0",
          latestVersion: "1.0.0",
          grantedCapabilities: ["commands", "prompts"],
          declaredCapabilities: ["commands", "prompts"],
          commandCount: 1,
          promptCount: 1,
          ruleCount: 0,
          skillCount: 0,
          installedAt: new Date(Date.now() - 86_400_000).toISOString(),
          lastCheckedAt: new Date(Date.now() - 60_000).toISOString(),
        },
      ],
      commands: [
        {
          id: "review-pack.review",
          pluginId: "review-pack",
          pluginVersion: "1.0.0",
          name: "Plugin review",
          description: "Review the requested scope",
          argumentHint: "[scope]",
          mode: "ask",
          agentProfileId: "loop",
        },
      ],
      prompts: [
        {
          id: "review-pack.security-review",
          pluginId: "review-pack",
          pluginVersion: "1.0.0",
          name: "Security review",
          description: "Review a scope for security regressions",
          argumentHint: "[scope]",
        },
      ],
    },
    jobs: {
      state: "ready",
      label: "2 durable jobs",
      items: [
        {
          id: "job-review",
          type: "subagent",
          status: "completed",
          prompt: "Review the authentication change for regressions and missing tests.",
          role: "review",
          expectedOutput: "Prioritized findings with file evidence.",
          maxOutputTokens: 4096,
          model: "gpt-5.6-sol",
          output: "No blocking regressions found. Add one expired-token integration test.",
          createdAt: new Date(Date.now() - 120_000).toISOString(),
          updatedAt: new Date(Date.now() - 90_000).toISOString(),
        },
        {
          id: "job-security",
          type: "subagent",
          status: "running",
          prompt: "Inspect JWT trust boundaries and secret handling.",
          role: "security",
          maxOutputTokens: 4096,
          model: "claude-fable-5",
          createdAt: new Date(Date.now() - 20_000).toISOString(),
          updatedAt: new Date(Date.now() - 5_000).toISOString(),
        },
      ],
    },
    customization: {
      rules: [
        {
          id: ".codeagent/rules/testing.md",
          name: "Testing",
          path: ".codeagent/rules/testing.md",
          content: "# Testing\n\nAdd focused regression tests for every behavior change.",
          trigger: "always",
          selected: false,
          description: "Regression coverage requirements",
        },
        {
          id: ".codeagent/rules/review.md",
          name: "Review",
          path: ".codeagent/rules/review.md",
          content: "# Review\n\nInspect the final diff for regressions and missing coverage.",
          trigger: "manual",
          selected: true,
          description: "Final diff review guidance",
        },
      ],
      skills: [
        {
          id: ".codeagent/skills/release/SKILL.md",
          name: "Release workflow",
          description: "Verify tests, compatibility, and artifacts before tagging a release.",
          path: ".codeagent/skills/release/SKILL.md",
          selected: true,
        },
        {
          id: ".agents/skills/review/SKILL.md",
          name: "Review changes",
          description: "Inspect behavior, regressions, and missing coverage in the current diff.",
          path: ".agents/skills/review/SKILL.md",
          selected: false,
        },
      ],
      maxSelectedSkills: 8,
    },
  };
  developmentSnapshot = snapshot;
  developmentJobSequence = 0;
  emitDevelopmentSnapshot();
  startDevelopmentIndex();
}
