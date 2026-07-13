export const PROTOCOL_VERSION = 1;

export type Mode = "agent" | "chat" | "ask";
export type MessageRole = "user" | "assistant" | "system";
export type RunState = "idle" | "running" | "awaiting_approval" | "failed";

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  createdAt: number;
}

export interface ToolRun {
  id: string;
  name: string;
  summary: string;
  status: "running" | "approval" | "completed" | "failed" | "rejected";
  detail?: string;
  changePath?: string;
  canRevert: boolean;
}

export interface ThreadSummary {
  id: string;
  title: string;
  updatedAt: number;
  active: boolean;
  mode: Mode;
}

export interface ContextItem {
  id: string;
  label: string;
  path: string;
}

export interface SettingsSnapshot {
  backendUrl: string;
  nodePath: string;
  backendTokenConfigured: boolean;
  autoApproveReadOnly: boolean;
}

export interface WorkspaceRule {
  id: string;
  name: string;
  path: string;
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
  runState: RunState;
  messages: ChatMessage[];
  tools: ToolRun[];
  threads: ThreadSummary[];
  attachments: ContextItem[];
  settings: SettingsSnapshot;
  context: {
    state: "unavailable" | "not_indexed" | "indexing" | "ready" | "error";
    label: string;
    files?: number;
    chunks?: number;
  };
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
  if (command.type !== "bootstrap") return;
  const snapshot: AppSnapshot = {
    projectName: "sample-project",
    mode: "agent",
    runState: "idle",
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
    ],
    threads: [
      { id: "dev", title: "Implement login flow with JWT", updatedAt: Date.now(), active: true, mode: "agent" },
      { id: "dev-2", title: "Review repository architecture", updatedAt: Date.now() - 3_600_000, active: false, mode: "ask" },
      { id: "dev-3", title: "Investigate flaky integration test", updatedAt: Date.now() - 86_400_000, active: false, mode: "chat" },
    ],
    attachments: [{ id: "attachment-1", label: "UserRepository.java", path: "src/main/java/com/example/UserRepository.java" }],
    settings: {
      backendUrl: "http://127.0.0.1:8787",
      nodePath: "node",
      backendTokenConfigured: false,
      autoApproveReadOnly: true,
    },
    context: { state: "not_indexed", label: "Index project" },
    customization: {
      rules: [{ id: ".codeagent/rules/testing.md", name: "Testing", path: ".codeagent/rules/testing.md" }],
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
