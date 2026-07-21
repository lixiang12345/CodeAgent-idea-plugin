import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import path from "node:path";
import { Readable, Writable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

export interface AcpAgentConfiguration {
  id: string;
  name: string;
  description?: string | null;
  enabled: boolean;
  command: string;
  args?: string[];
  cwd?: string | null;
  requiredEnvironment?: string[];
  authMethodId?: string | null;
  timeoutSeconds?: number;
}

export interface AcpSessionSnapshot {
  sessionId: string;
  updatedAt: string;
}

export interface AcpAgentSnapshot {
  id: string;
  name: string;
  enabled: boolean;
  state: "disabled" | "stopped" | "starting" | "ready" | "error";
  label: string;
  protocolVersion: number | null;
  agentName: string | null;
  agentVersion: string | null;
  loadSession: boolean;
  authMethods: Array<{ id: string; name: string }>;
  sessions: AcpSessionSnapshot[];
  pid: number | null;
  lastError: string | null;
}

export interface AcpRuntimeSnapshot {
  state: "idle" | "ready" | "degraded";
  label: string;
  agents: AcpAgentSnapshot[];
}

export interface AcpPromptResult {
  agentId: string;
  sessionId: string;
  stopReason: string;
  text: string;
  updates: unknown[];
}

interface AcpAgentRuntime {
  config: AcpAgentConfiguration;
  state: AcpAgentSnapshot["state"];
  label: string;
  child: ChildProcessWithoutNullStreams | null;
  connection: acp.ClientConnection | null;
  protocolVersion: number | null;
  agentName: string | null;
  agentVersion: string | null;
  loadSession: boolean;
  authMethods: Array<{ id: string; name: string }>;
  sessions: Map<string, AcpSessionSnapshot>;
  updateCollectors: Map<string, unknown[]>;
  lastError: string | null;
  stderr: string;
  intentionalClose: boolean;
}

export class AcpRuntimeManager {
  private readonly agents = new Map<string, AcpAgentRuntime>();
  private closed = false;

  constructor(private readonly root: string) {}

  async reconcile(configurations: AcpAgentConfiguration[]): Promise<AcpRuntimeSnapshot> {
    this.ensureOpen();
    const normalized = configurations.map((configuration) => normalizeConfiguration(this.root, configuration));
    const desired = new Map(normalized.map((configuration) => [configuration.id, configuration]));
    for (const [id, runtime] of this.agents) {
      if (!desired.has(id)) {
        await this.stopRuntime(runtime, "Configuration removed");
        this.agents.delete(id);
      }
    }
    for (const configuration of normalized) {
      let runtime = this.agents.get(configuration.id);
      if (runtime && configurationKey(runtime.config) !== configurationKey(configuration)) {
        await this.stopRuntime(runtime, "Configuration changed");
        this.agents.delete(configuration.id);
        runtime = undefined;
      }
      if (!runtime) {
        runtime = createRuntime(configuration);
        this.agents.set(configuration.id, runtime);
      } else {
        runtime.config = configuration;
      }
      if (!configuration.enabled) await this.stopRuntime(runtime, "Disabled", "disabled");
    }
    await Promise.allSettled(
      [...this.agents.values()]
        .filter((runtime) => runtime.config.enabled && runtime.state !== "ready" && runtime.state !== "starting")
        .map((runtime) => this.startRuntime(runtime)),
    );
    return this.snapshot();
  }

  status(): AcpRuntimeSnapshot {
    this.ensureOpen();
    return this.snapshot();
  }

  async start(id: string): Promise<AcpRuntimeSnapshot> {
    const runtime = this.requiredRuntime(id);
    if (!runtime.config.enabled) throw new Error(`ACP agent '${id}' is disabled`);
    await this.startRuntime(runtime);
    return this.snapshot();
  }

  async stop(id: string): Promise<AcpRuntimeSnapshot> {
    await this.stopRuntime(this.requiredRuntime(id), "Stopped");
    return this.snapshot();
  }

  async restart(id: string): Promise<AcpRuntimeSnapshot> {
    const runtime = this.requiredRuntime(id);
    await this.stopRuntime(runtime, "Restarting");
    await this.startRuntime(runtime);
    return this.snapshot();
  }

  async prompt(id: string, prompt: string, sessionId?: string | null): Promise<AcpPromptResult> {
    const runtime = this.requiredRuntime(id);
    if (runtime.state !== "ready" || !runtime.connection) await this.startRuntime(runtime);
    const connection = runtime.connection;
    if (!connection) throw new Error(`ACP agent '${id}' is not connected`);
    const text = requireText(prompt, "prompt");
    const activeSessionId = sessionId?.trim() || await this.newSession(runtime);
    if (!runtime.sessions.has(activeSessionId)) {
      if (!runtime.loadSession) throw new Error(`ACP agent '${id}' cannot load session '${activeSessionId}'`);
      await withTimeout(
        runtime.config,
        connection.agent.request(acp.methods.agent.session.load, {
          sessionId: activeSessionId,
          cwd: resolveWorkingDirectory(this.root, runtime.config.cwd),
          mcpServers: [],
        }),
      );
      runtime.sessions.set(activeSessionId, { sessionId: activeSessionId, updatedAt: new Date().toISOString() });
    }
    const updates: unknown[] = [];
    runtime.updateCollectors.set(activeSessionId, updates);
    try {
      const response = await withTimeout(
        runtime.config,
        connection.agent.request(acp.methods.agent.session.prompt, {
          sessionId: activeSessionId,
          prompt: [{ type: "text", text }],
        }),
        () => connection.agent.notify(acp.methods.agent.session.cancel, { sessionId: activeSessionId }),
      );
      runtime.sessions.set(activeSessionId, { sessionId: activeSessionId, updatedAt: new Date().toISOString() });
      runtime.lastError = null;
      runtime.label = `${runtime.sessions.size} session${runtime.sessions.size === 1 ? "" : "s"}`;
      return {
        agentId: id,
        sessionId: activeSessionId,
        stopReason: response.stopReason,
        text: updates.map(updateText).filter(Boolean).join(""),
        updates,
      };
    } finally {
      runtime.updateCollectors.delete(activeSessionId);
    }
  }

  async cancel(id: string, sessionId: string): Promise<AcpRuntimeSnapshot> {
    const runtime = this.requiredRuntime(id);
    if (runtime.connection) {
      await runtime.connection.agent.notify(acp.methods.agent.session.cancel, {
        sessionId: requireText(sessionId, "sessionId"),
      });
    }
    return this.snapshot();
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    await Promise.allSettled([...this.agents.values()].map((runtime) => this.stopRuntime(runtime, "Gateway stopped")));
    this.agents.clear();
  }

  private async startRuntime(runtime: AcpAgentRuntime): Promise<void> {
    if (runtime.state === "starting") return;
    await this.closeConnection(runtime);
    runtime.state = "starting";
    runtime.label = "Connecting";
    runtime.lastError = null;
    runtime.stderr = "";
    try {
      const env = allowedEnvironment(runtime.config);
      const child = spawn(runtime.config.command, runtime.config.args ?? [], {
        cwd: resolveWorkingDirectory(this.root, runtime.config.cwd),
        env,
        stdio: ["pipe", "pipe", "pipe"],
      });
      runtime.child = child;
      child.stderr.on("data", (chunk) => {
        runtime.stderr = `${runtime.stderr}${String(chunk)}`.slice(-4_000);
      });
      child.on("exit", (code, signal) => {
        if (!runtime.intentionalClose) {
          runtime.connection = null;
          runtime.child = null;
          runtime.state = "error";
          runtime.lastError = `ACP process exited (${signal ?? code ?? "unknown"})`;
          runtime.label = runtime.lastError;
        }
      });
      const app = acp.client({ name: "CodeAgent" })
        .onRequest(acp.methods.client.session.requestPermission, ({ params }) => ({
          outcome: permissionRejection(params.options),
        }))
        .onNotification(acp.methods.client.session.update, ({ params }) => {
          runtime.updateCollectors.get(params.sessionId)?.push(params.update);
        });
      const stream = acp.ndJsonStream(
        Writable.toWeb(child.stdin) as WritableStream<Uint8Array>,
        Readable.toWeb(child.stdout) as ReadableStream<Uint8Array>,
      );
      const connection = app.connect(stream);
      runtime.connection = connection;
      const initialized = await withTimeout(runtime.config, connection.agent.request(acp.methods.agent.initialize, {
        protocolVersion: acp.PROTOCOL_VERSION,
        clientCapabilities: {
          fs: { readTextFile: false, writeTextFile: false },
          terminal: false,
        },
        clientInfo: { name: "CodeAgent", title: "CodeAgent for JetBrains", version: "0.7.26" },
      }));
      if (initialized.protocolVersion !== acp.PROTOCOL_VERSION) {
        throw new Error(`Unsupported ACP protocol version ${initialized.protocolVersion}; expected ${acp.PROTOCOL_VERSION}`);
      }
      runtime.protocolVersion = initialized.protocolVersion;
      runtime.agentName = initialized.agentInfo?.title ?? initialized.agentInfo?.name ?? null;
      runtime.agentVersion = initialized.agentInfo?.version ?? null;
      runtime.loadSession = initialized.agentCapabilities?.loadSession === true;
      runtime.authMethods = (initialized.authMethods ?? []).map((method) => ({ id: method.id, name: method.name }));
      const authMethodId = runtime.config.authMethodId?.trim();
      if (authMethodId) {
        if (!runtime.authMethods.some((method) => method.id === authMethodId)) {
          throw new Error(`ACP authentication method '${authMethodId}' was not advertised by the agent`);
        }
        await withTimeout(runtime.config, connection.agent.request(acp.methods.agent.authenticate, { methodId: authMethodId }));
      }
      runtime.state = "ready";
      runtime.label = runtime.agentName ? `Connected · ${runtime.agentName}` : "Connected";
      connection.closed.then(() => {
        if (!runtime.intentionalClose && runtime.state === "ready") {
          runtime.connection = null;
          runtime.child = null;
          runtime.state = "error";
          runtime.lastError = "ACP connection closed";
          runtime.label = runtime.lastError;
        }
      });
    } catch (error) {
      const stderr = runtime.stderr.trim();
      runtime.lastError = stderr ? `${errorMessage(error)} · ${stderr.slice(-800)}` : errorMessage(error);
      runtime.state = "error";
      runtime.label = runtime.lastError;
      await this.closeConnection(runtime);
      throw error;
    }
  }

  private async newSession(runtime: AcpAgentRuntime): Promise<string> {
    const connection = runtime.connection;
    if (!connection) throw new Error(`ACP agent '${runtime.config.id}' is not connected`);
    const response = await withTimeout(runtime.config, connection.agent.request(acp.methods.agent.session.new, {
      cwd: resolveWorkingDirectory(this.root, runtime.config.cwd),
      mcpServers: [],
    }));
    runtime.sessions.set(response.sessionId, { sessionId: response.sessionId, updatedAt: new Date().toISOString() });
    return response.sessionId;
  }

  private async stopRuntime(
    runtime: AcpAgentRuntime,
    label: string,
    state: AcpAgentSnapshot["state"] = "stopped",
  ): Promise<void> {
    await this.closeConnection(runtime);
    runtime.state = state;
    runtime.label = label;
    runtime.sessions.clear();
    runtime.updateCollectors.clear();
  }

  private async closeConnection(runtime: AcpAgentRuntime): Promise<void> {
    runtime.intentionalClose = true;
    const connection = runtime.connection;
    const child = runtime.child;
    runtime.connection = null;
    runtime.child = null;
    connection?.close();
    if (child && child.exitCode === null && child.signalCode === null) child.kill("SIGTERM");
    if (child) await Promise.race([new Promise<void>((resolve) => child.once("exit", () => resolve())), delay(1_000)]);
    if (child && child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
    runtime.intentionalClose = false;
  }

  private requiredRuntime(id: string): AcpAgentRuntime {
    this.ensureOpen();
    return this.agents.get(id) ?? (() => { throw new Error(`Unknown ACP agent '${id}'`); })();
  }

  private ensureOpen(): void {
    if (this.closed) throw new Error("ACP runtime is closed");
  }

  private snapshot(): AcpRuntimeSnapshot {
    const agents = [...this.agents.values()]
      .sort((left, right) => left.config.name.localeCompare(right.config.name))
      .map(toSnapshot);
    const unhealthy = agents.filter((agent) => agent.enabled && agent.state !== "ready");
    return {
      state: unhealthy.length > 0 ? "degraded" : agents.some((agent) => agent.state === "ready") ? "ready" : "idle",
      label: agents.length === 0
        ? "No ACP agents configured"
        : unhealthy.length > 0
          ? `${unhealthy.length} of ${agents.length} agents need attention`
          : `${agents.length} ACP agent${agents.length === 1 ? "" : "s"} ready`,
      agents,
    };
  }
}

function normalizeConfiguration(root: string, value: AcpAgentConfiguration): AcpAgentConfiguration {
  const id = requireText(value.id, "id");
  const name = requireText(value.name, "name");
  if (!/^[A-Za-z0-9._-]{1,120}$/.test(id)) throw new Error(`Invalid ACP agent ID '${id}'`);
  const timeoutSeconds = Number.isInteger(value.timeoutSeconds) ? Number(value.timeoutSeconds) : 300;
  if (timeoutSeconds < 1 || timeoutSeconds > 1_800) throw new Error("ACP timeout must be between 1 and 1800 seconds");
  resolveWorkingDirectory(root, value.cwd);
  for (const variable of value.requiredEnvironment ?? []) {
    if (!/^[A-Z][A-Z0-9_]{0,127}$/.test(variable)) throw new Error(`Invalid environment variable name '${variable}'`);
    if (!process.env[variable]) throw new Error(`Required environment variable '${variable}' is not set`);
  }
  return {
    ...value,
    id,
    name,
    command: requireText(value.command, "command"),
    enabled: value.enabled !== false,
    args: Array.isArray(value.args) ? value.args.map(String).slice(0, 128) : [],
    requiredEnvironment: [...new Set(value.requiredEnvironment ?? [])].slice(0, 64),
    authMethodId: value.authMethodId?.trim() || null,
    timeoutSeconds,
  };
}

function createRuntime(config: AcpAgentConfiguration): AcpAgentRuntime {
  return {
    config,
    state: config.enabled ? "stopped" : "disabled",
    label: config.enabled ? "Stopped" : "Disabled",
    child: null,
    connection: null,
    protocolVersion: null,
    agentName: null,
    agentVersion: null,
    loadSession: false,
    authMethods: [],
    sessions: new Map(),
    updateCollectors: new Map(),
    lastError: null,
    stderr: "",
    intentionalClose: false,
  };
}

function toSnapshot(runtime: AcpAgentRuntime): AcpAgentSnapshot {
  return {
    id: runtime.config.id,
    name: runtime.config.name,
    enabled: runtime.config.enabled,
    state: runtime.state,
    label: runtime.label,
    protocolVersion: runtime.protocolVersion,
    agentName: runtime.agentName,
    agentVersion: runtime.agentVersion,
    loadSession: runtime.loadSession,
    authMethods: runtime.authMethods,
    sessions: [...runtime.sessions.values()].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)),
    pid: runtime.child?.pid ?? null,
    lastError: runtime.lastError,
  };
}

function permissionRejection(options: acp.PermissionOption[]): acp.RequestPermissionOutcome {
  const rejection = options.find((option) => option.kind === "reject_once")
    ?? options.find((option) => option.kind === "reject_always");
  return rejection ? { outcome: "selected", optionId: rejection.optionId } : { outcome: "cancelled" };
}

function updateText(value: unknown): string {
  if (!value || typeof value !== "object") return "";
  const update = value as { sessionUpdate?: unknown; content?: { type?: unknown; text?: unknown } };
  return update.sessionUpdate === "agent_message_chunk" && update.content?.type === "text" && typeof update.content.text === "string"
    ? update.content.text
    : "";
}

function allowedEnvironment(configuration: AcpAgentConfiguration): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = {};
  for (const name of ["PATH", "HOME", "USER", "TMPDIR", "TEMP", "TMP", ...(configuration.requiredEnvironment ?? [])]) {
    if (process.env[name] !== undefined) env[name] = process.env[name];
  }
  return env;
}

function resolveWorkingDirectory(rootValue: string, configured?: string | null): string {
  const root = path.resolve(rootValue);
  const candidate = path.resolve(root, configured?.trim() || ".");
  const relative = path.relative(root, candidate);
  if (relative === ".." || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    throw new Error("ACP working directory must stay inside the current project");
  }
  return candidate;
}

function configurationKey(value: AcpAgentConfiguration): string {
  return JSON.stringify(value);
}

async function withTimeout<T>(
  configuration: AcpAgentConfiguration,
  promise: Promise<T>,
  onTimeout?: () => Promise<void>,
): Promise<T> {
  const timeoutMs = (configuration.timeoutSeconds ?? 300) * 1_000;
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      void onTimeout?.();
      reject(new Error(`ACP request timed out after ${configuration.timeoutSeconds ?? 300} seconds`));
    }, timeoutMs);
    timer.unref();
  });
  try {
    return await Promise.race([promise, timeout]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function requireText(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) throw new Error(`ACP ${field} is required`);
  return value.trim();
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
