export const PROTOCOL_VERSION = 1;

export type Mode = "agent" | "ask";
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
}

export interface ContextItem {
  id: string;
  label: string;
  path: string;
}

export interface SettingsSnapshot {
  endpoint: string;
  model: string;
  nodePath: string;
  apiKeyConfigured: boolean;
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
    messages: [],
    tools: [],
    threads: [{ id: "dev", title: "New task", updatedAt: Date.now(), active: true }],
    attachments: [],
    settings: {
      endpoint: "https://api.openai.com/v1",
      model: "gpt-5.2",
      nodePath: "node",
      apiKeyConfigured: false,
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
