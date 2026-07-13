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
}

export interface ThreadSummary {
  id: string;
  title: string;
  updatedAt: number;
  active: boolean;
}

export interface SettingsSnapshot {
  endpoint: string;
  model: string;
  nodePath: string;
  apiKeyConfigured: boolean;
  autoApproveReadOnly: boolean;
}

export interface AppSnapshot {
  projectName: string;
  mode: Mode;
  runState: RunState;
  messages: ChatMessage[];
  tools: ToolRun[];
  threads: ThreadSummary[];
  settings: SettingsSnapshot;
  context: {
    state: "unavailable" | "not_indexed" | "indexing" | "ready" | "error";
    label: string;
    files?: number;
    chunks?: number;
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
    settings: {
      endpoint: "https://api.openai.com/v1",
      model: "gpt-5.2",
      nodePath: "node",
      apiKeyConfigured: false,
      autoApproveReadOnly: true,
    },
    context: { state: "not_indexed", label: "Index project" },
  };
  queueMicrotask(() => window.CodeAgent?.receive(JSON.stringify({ version: 1, type: "snapshot", payload: snapshot })));
}
