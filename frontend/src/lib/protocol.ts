export const PROTOCOL_VERSION = 1;

export type Mode = "agent" | "chat" | "ask";
export type MessageRole = "user" | "assistant" | "system";
export type RunState = "idle" | "running" | "awaiting_approval" | "failed";

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  createdAt: number;
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
}

export interface AgentRunTelemetry {
  turnIndex: number;
  estimatedInputTokens: number;
  targetInputTokens: number;
  contextWindowTokens: number;
  reservedOutputTokens: number;
  toolDefinitionTokens: number;
  compactedToolResults: number;
  truncatedMessages: number;
  overBudget: boolean;
  activeToolNames: string[];
  activeToolCount: number;
  catalogToolCount: number;
  discoverableToolCount: number;
  activatedToolNames: string[];
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
  contextMode: "lexical" | "private-semantic";
  contextEmbeddingBaseUrl: string;
  contextEmbeddingModel: string;
  contextEmbeddingTokenConfigured: boolean;
  contextNeuralRerank: boolean;
  contextRerankBaseUrl: string;
  contextRerankModel: string;
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
  installedAt?: string;
  lastCheckedAt?: string;
  lastError?: string;
}

export interface PluginRuntimeSnapshot {
  state: "idle" | "ready" | "degraded";
  label: string;
  items: PluginRuntimeItem[];
  commands: PluginCommand[];
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
}

export interface WorkspaceSkill {
  id: string;
  name: string;
  description: string;
  path: string;
  selected: boolean;
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
    state: "unavailable" | "not_indexed" | "indexing" | "ready" | "error";
    label: string;
    files?: number;
    chunks?: number;
    watching?: boolean;
    hasEmbeddings?: boolean;
    lastIndexedAt?: string;
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

window.CodeAgent = {
  receive(json: string) {
    const event = JSON.parse(json) as EventEnvelope;
    listeners.forEach((listener) => listener(event));
  },
};

function handleDevelopmentCommand(command: CommandEnvelope): void {
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
  if (command.type !== "bootstrap") return;
  const snapshot: AppSnapshot = {
    projectName: "sample-project",
    mode: "agent",
    runState: "idle",
    agentRun: {
      turnIndex: 0,
      estimatedInputTokens: 0,
      targetInputTokens: 0,
      contextWindowTokens: 0,
      reservedOutputTokens: 0,
      toolDefinitionTokens: 0,
      compactedToolResults: 0,
      truncatedMessages: 0,
      overBudget: false,
      activeToolNames: [],
      activeToolCount: 0,
      catalogToolCount: 0,
      discoverableToolCount: 0,
      activatedToolNames: [],
      verificationState: "idle",
    },
    messages: [
      {
        id: "user-1",
        role: "user",
        content: "Implement JWT login end-to-end, update the tests, and verify the integration.",
        createdAt: Date.now() - 42_000,
      },
      {
        id: "assistant-1",
        role: "assistant",
        content: "JWT login is implemented and the focused tests pass. The edit remains available for native Diff review.",
        createdAt: Date.now() - 14_000,
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
      },
      {
        id: "tool-read",
        name: "read_file",
        summary: "AuthController.java",
        status: "completed",
        detail: "src/main/java/com/example/auth/AuthController.java:1-94",
        canRevert: false,
      },
      {
        id: "tool-search",
        name: "search_text",
        summary: "pattern: issue(",
        status: "completed",
        detail: "TokenService.java:41: return jwtIssuer.issue(user);",
        canRevert: false,
      },
      {
        id: "tool-edit",
        name: "replace_text",
        summary: "AuthController.java",
        status: "completed",
        detail: "+ return tokenService.issue(request);\n- return null;",
        changePath: "src/main/java/com/example/auth/AuthController.java",
        canRevert: true,
      },
      {
        id: "tool-create",
        name: "write_file",
        summary: "AuthControllerTest.java",
        status: "completed",
        detail: "Created focused login and invalid-credential tests.",
        changePath: "src/test/java/com/example/auth/AuthControllerTest.java",
        canRevert: true,
      },
      {
        id: "tool-terminal",
        name: "run_terminal",
        summary: "./gradlew test --tests AuthControllerTest",
        status: "completed",
        detail: "BUILD SUCCESSFUL in 4s\n14 tests completed",
        canRevert: false,
      },
      {
        id: "tool-open",
        name: "open_file",
        summary: "SecurityConfig.java",
        status: "completed",
        detail: "Opened in the active IDE editor.",
        canRevert: false,
      },
      {
        id: "tool-mermaid",
        name: "render_mermaid",
        summary: "JWT login flow",
        status: "completed",
        detail: "flowchart LR\n  Client[Client] --> Controller[AuthController]\n  Controller --> Service[TokenService]\n  Service --> JWT[Signed JWT]",
        canRevert: false,
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
    attachments: [{ id: "attachment-1", label: "UserRepository.java", path: "src/main/java/com/example/UserRepository.java" }],
    settings: {
      backendUrl: "http://127.0.0.1:8788",
      nodePath: "node",
      backendTokenConfigured: false,
      autoApproveReadOnly: true,
      contextMode: "lexical",
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

    context: { state: "not_indexed", label: "Index project" },
    backendHealth: { state: "online", label: "Connected to codeagent-backend", protocolVersion: 1 },
    models: {
      state: "ready",
      provider: "unified-native",
      defaultModel: "gpt-5.6-sol",
      selectedModel: "gpt-5.6-sol",
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
              contextWindowTokens: 64000,
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
          grantedCapabilities: ["commands"],
          declaredCapabilities: ["commands"],
          commandCount: 1,
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
  queueMicrotask(() => window.CodeAgent?.receive(JSON.stringify({ version: 1, type: "snapshot", payload: snapshot })));
}
