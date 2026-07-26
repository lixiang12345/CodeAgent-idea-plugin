import { createHash } from "node:crypto";
import path from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js";
import {
  getDefaultEnvironment,
  StdioClientTransport,
} from "@modelcontextprotocol/sdk/client/stdio.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { ToolListChangedNotificationSchema } from "@modelcontextprotocol/sdk/types.js";

export type McpTransportKind = "stdio" | "streamable-http" | "sse";
export type McpRuntimeState =
  | "disabled"
  | "stopped"
  | "starting"
  | "ready"
  | "degraded"
  | "error"
  | "stopping";

export interface McpServerConfiguration {
  id: string;
  name: string;
  description?: string | null;
  enabled: boolean;
  transport: McpTransportKind;
  command?: string | null;
  args?: string[];
  cwd?: string | null;
  url?: string | null;
  authMode?: "none" | "bearer-environment" | "oauth";
  tokenEnvironment?: string | null;
  accessToken?: string | null;
  requiredEnvironment?: string[];
  timeoutSeconds?: number;
  /** Literal environment entries; values may reference ${VAR} or ${VAR:-default}. */
  env?: Record<string, string>;
  /** Extra headers for http/sse transports; values support the same expansion. */
  headers?: Record<string, string>;
}

const MAX_ENV_ENTRIES = 64;
const MAX_HEADER_ENTRIES = 32;
const MAX_ENV_VALUE_CHARS = 4_096;
const MAX_IMPORTED_SERVERS = 64;

/**
 * Expands `${VAR}` and `${VAR:-default}` references against the process
 * environment, matching the original plugin's MCP configuration contract.
 * `\${` escapes an expansion. Unset variables without a default expand to "".
 */
export function expandEnvironmentReferences(
  value: string,
  environment: NodeJS.ProcessEnv = process.env,
): string {
  if (value.length > MAX_ENV_VALUE_CHARS) {
    throw new Error(`MCP environment value exceeds ${MAX_ENV_VALUE_CHARS} characters`);
  }
  return value.replace(
    /\\\$\{|\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}/g,
    (match, name?: string, fallback?: string) => {
      if (match === "\\${") return "${";
      const resolved = environment[name as string];
      if (resolved !== undefined && resolved !== "") return resolved;
      return fallback ?? "";
    },
  );
}

/**
 * Accepts the container shapes the original plugin's MCP configuration files
 * use — a bare array, `{servers: [...]}`, `{mcpServers: [...]}`, or a
 * name-keyed object under either key — and returns configurations ready for
 * `reconcile`. Server names become IDs when no explicit ID is present.
 */
export function importMcpServerConfigurations(source: unknown): McpServerConfiguration[] {
  const container = source && typeof source === "object" && !Array.isArray(source)
    ? (source as Record<string, unknown>)
    : undefined;
  const raw = Array.isArray(source)
    ? source
    : container?.servers ?? container?.mcpServers ?? undefined;
  if (raw === undefined) throw new Error("MCP configuration must contain servers or mcpServers");

  const entries: Array<[string | undefined, Record<string, unknown>]> = Array.isArray(raw)
    ? raw.map((value) => {
      if (!value || typeof value !== "object") throw new Error("Each MCP server entry must be an object");
      return [undefined, value as Record<string, unknown>];
    })
    : Object.entries(raw as Record<string, unknown>).map(([key, value]) => {
      if (!value || typeof value !== "object") throw new Error(`MCP server '${key}' must be an object`);
      return [key, value as Record<string, unknown>];
    });

  if (entries.length > MAX_IMPORTED_SERVERS) {
    throw new Error(`MCP configuration accepts at most ${MAX_IMPORTED_SERVERS} servers`);
  }

  return entries.map(([key, entry]) => {
    const name = String(entry.name ?? entry.title ?? key ?? "").trim();
    if (!name) throw new Error("Each MCP server needs a name");
    const id = String(entry.id ?? key ?? name).trim().replace(/[^A-Za-z0-9._-]/g, "-").slice(0, 120);
    const transport = entry.transport ?? entry.type ?? (entry.command ? "stdio" : "streamable-http");
    return normalizeConfiguration({
      ...(entry as unknown as McpServerConfiguration),
      id,
      name,
      transport: transport as McpTransportKind,
      enabled: entry.enabled !== false && entry.disabled !== true,
    });
  });
}

function normalizeStringMap(
  value: unknown,
  label: string,
  maxEntries: number,
  keyPattern: RegExp,
): Record<string, string> | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`MCP ${label} must be an object`);
  }
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length > maxEntries) throw new Error(`MCP ${label} accepts at most ${maxEntries} entries`);
  const result: Record<string, string> = {};
  for (const [key, raw] of entries) {
    if (!keyPattern.test(key)) throw new Error(`Invalid MCP ${label} name '${key}'`);
    if (typeof raw !== "string") throw new Error(`MCP ${label} value for '${key}' must be a string`);
    result[key] = expandEnvironmentReferences(raw);
  }
  return result;
}

export interface McpToolSnapshot {
  id: string;
  serverId: string;
  name: string;
  title: string | null;
  description: string;
  parameters: Record<string, unknown>;
  risk: "read_only" | "mutating";
}

export interface McpServerSnapshot {
  id: string;
  name: string;
  enabled: boolean;
  transport: McpTransportKind;
  state: McpRuntimeState;
  label: string;
  serverName: string | null;
  serverVersion: string | null;
  protocolVersion: string | null;
  capabilities: string[];
  tools: McpToolSnapshot[];
  pid: number | null;
  latencyMs: number | null;
  restartCount: number;
  lastConnectedAt: string | null;
  lastHealthyAt: string | null;
  lastError: string | null;
}

export interface McpRuntimeSnapshot {
  state: "idle" | "ready" | "degraded";
  label: string;
  servers: McpServerSnapshot[];
  tools: McpToolSnapshot[];
}

export interface McpToolCallResult {
  output: string;
  summary: string;
  detail: string;
}

type McpTransport = StdioClientTransport | StreamableHTTPClientTransport | SSEClientTransport;
type ListedTool = Awaited<ReturnType<Client["listTools"]>>["tools"][number];

interface ServerRuntime {
  config: McpServerConfiguration;
  state: McpRuntimeState;
  label: string;
  client: Client | null;
  transport: McpTransport | null;
  tools: McpToolSnapshot[];
  toolNames: Map<string, string>;
  serverName: string | null;
  serverVersion: string | null;
  protocolVersion: string | null;
  capabilities: string[];
  latencyMs: number | null;
  restartCount: number;
  lastConnectedAt: string | null;
  lastHealthyAt: string | null;
  lastError: string | null;
  stderr: string;
  generation: number;
  intentionalClose: boolean;
  manualStop: boolean;
  reconnectTimer: NodeJS.Timeout | null;
}

export class McpRuntimeManager {
  private readonly servers = new Map<string, ServerRuntime>();
  private readonly healthTimer: NodeJS.Timeout;
  private closed = false;

  constructor(
    private readonly root: string,
    healthIntervalMs = 30_000,
  ) {
    this.healthTimer = setInterval(() => void this.healthTick(), Math.max(1_000, healthIntervalMs));
    this.healthTimer.unref();
  }

  async reconcile(configurations: McpServerConfiguration[]): Promise<McpRuntimeSnapshot> {
    this.ensureOpen();
    const normalized = configurations.map(normalizeConfiguration);
    const desired = new Map(normalized.map((configuration) => [configuration.id, configuration]));

    for (const [id, runtime] of this.servers) {
      if (!desired.has(id)) {
        await this.stopRuntime(runtime, "stopped", "Configuration removed");
        this.servers.delete(id);
      }
    }

    for (const configuration of normalized) {
      let runtime = this.servers.get(configuration.id);
      if (runtime && configurationKey(runtime.config) !== configurationKey(configuration)) {
        await this.stopRuntime(runtime, "stopped", "Configuration changed");
        this.servers.delete(configuration.id);
        runtime = undefined;
      }
      if (!runtime) {
        runtime = createRuntime(configuration);
        this.servers.set(configuration.id, runtime);
      } else {
        runtime.config = configuration;
      }

      if (!configuration.enabled) {
        await this.stopRuntime(runtime, "disabled", "Disabled");
      }
    }

    await Promise.allSettled(
      [...this.servers.values()]
        .filter((runtime) => runtime.config.enabled && !runtime.manualStop && runtime.state !== "ready" && runtime.state !== "starting")
        .map((runtime) => this.startRuntime(runtime)),
    );
    return this.snapshot();
  }

  status(): McpRuntimeSnapshot {
    this.ensureOpen();
    return this.snapshot();
  }

  async start(id: string): Promise<McpRuntimeSnapshot> {
    const runtime = this.requiredRuntime(id);
    if (!runtime.config.enabled) throw new Error(`MCP server '${id}' is disabled`);
    runtime.manualStop = false;
    await this.startRuntime(runtime);
    return this.snapshot();
  }

  async stop(id: string): Promise<McpRuntimeSnapshot> {
    const runtime = this.requiredRuntime(id);
    runtime.manualStop = true;
    await this.stopRuntime(runtime, runtime.config.enabled ? "stopped" : "disabled", "Stopped");
    return this.snapshot();
  }

  async restart(id: string): Promise<McpRuntimeSnapshot> {
    const runtime = this.requiredRuntime(id);
    if (!runtime.config.enabled) throw new Error(`MCP server '${id}' is disabled`);
    runtime.manualStop = false;
    runtime.restartCount += 1;
    await this.stopRuntime(runtime, "stopped", "Restarting");
    await this.startRuntime(runtime);
    return this.snapshot();
  }

  async test(id: string): Promise<McpRuntimeSnapshot> {
    const runtime = this.requiredRuntime(id);
    if (!runtime.config.enabled) throw new Error(`MCP server '${id}' is disabled`);
    runtime.manualStop = false;
    if (runtime.state !== "ready" || !runtime.client) await this.startRuntime(runtime);
    const client = runtime.client;
    if (!client) return this.snapshot();
    const startedAt = Date.now();
    await client.ping(requestOptions(runtime.config));
    runtime.latencyMs = Date.now() - startedAt;
    runtime.lastHealthyAt = new Date().toISOString();
    runtime.state = "ready";
    runtime.label = `${runtime.tools.length} tools · ${runtime.latencyMs} ms`;
    runtime.lastError = null;
    return this.snapshot();
  }

  async call(toolId: string, args: Record<string, unknown>): Promise<McpToolCallResult> {
    this.ensureOpen();
    const runtime = [...this.servers.values()].find((candidate) => candidate.toolNames.has(toolId));
    if (!runtime || runtime.state !== "ready" || !runtime.client) {
      throw new Error(`MCP tool '${toolId}' is not connected`);
    }
    const tool = runtime.tools.find((candidate) => candidate.id === toolId);
    const originalName = runtime.toolNames.get(toolId);
    if (!tool || !originalName) throw new Error(`MCP tool '${toolId}' is no longer available`);

    const result = await runtime.client.callTool(
      { name: originalName, arguments: args },
      undefined,
      {
        ...requestOptions(runtime.config),
        resetTimeoutOnProgress: true,
        maxTotalTimeout: timeoutMs(runtime.config) * 4,
      },
    );
    const output = formatToolResult(result);
    if (result.isError) throw new Error(output || `MCP tool '${originalName}' failed`);
    runtime.lastHealthyAt = new Date().toISOString();
    runtime.lastError = null;
    return {
      output,
      summary: `${runtime.config.name}: ${tool.title ?? tool.name}`,
      detail: output.slice(0, 8_000),
    };
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.healthTimer);
    await Promise.allSettled(
      [...this.servers.values()].map((runtime) => this.stopRuntime(runtime, "stopped", "Gateway stopped")),
    );
    this.servers.clear();
  }

  private async startRuntime(runtime: ServerRuntime): Promise<void> {
    if (this.closed || runtime.state === "starting") return;
    clearReconnect(runtime);
    await this.closeConnection(runtime);
    const generation = ++runtime.generation;
    runtime.state = "starting";
    runtime.label = "Connecting";
    runtime.lastError = null;
    runtime.stderr = "";

    try {
      validateEnvironment(runtime.config);
      const transport = this.createTransport(runtime);
      const client = new Client(
        { name: "CodeAgent", version: "0.7.30" },
        { capabilities: {} },
      );
      runtime.transport = transport;
      runtime.client = client;
      client.onerror = (error) => {
        if (runtime.generation === generation && !runtime.intentionalClose) {
          runtime.lastError = error.message;
          runtime.state = "degraded";
          runtime.label = error.message;
        }
      };
      client.onclose = () => {
        if (runtime.generation === generation && !runtime.intentionalClose && runtime.config.enabled) {
          runtime.client = null;
          runtime.transport = null;
          runtime.state = "degraded";
          runtime.label = runtime.lastError ?? "Connection closed";
          this.scheduleReconnect(runtime);
        }
      };
      client.setNotificationHandler(ToolListChangedNotificationSchema, async () => {
        if (runtime.generation !== generation || runtime.state !== "ready") return;
        try {
          await this.refreshTools(runtime, client);
        } catch (error) {
          runtime.lastError = errorMessage(error);
          runtime.state = "degraded";
          runtime.label = `Tool refresh failed: ${runtime.lastError}`;
        }
      });

      await client.connect(transport, requestOptions(runtime.config));
      if (runtime.generation !== generation) {
        await client.close();
        return;
      }
      const connectedAt = new Date().toISOString();
      runtime.serverName = client.getServerVersion()?.name ?? null;
      runtime.serverVersion = client.getServerVersion()?.version ?? null;
      runtime.capabilities = Object.keys(client.getServerCapabilities() ?? {}).sort();
      runtime.protocolVersion = transport instanceof StreamableHTTPClientTransport
        ? transport.protocolVersion ?? null
        : null;
      await this.refreshTools(runtime, client);
      const startedAt = Date.now();
      await client.ping(requestOptions(runtime.config));
      runtime.latencyMs = Date.now() - startedAt;
      runtime.lastConnectedAt = connectedAt;
      runtime.lastHealthyAt = new Date().toISOString();
      runtime.lastError = null;
      runtime.state = "ready";
      runtime.label = `${runtime.tools.length} tools · ${runtime.latencyMs} ms`;
    } catch (error) {
      if (runtime.generation !== generation) return;
      const stderr = runtime.stderr.trim();
      runtime.lastError = stderr ? `${errorMessage(error)} · ${stderr.slice(-800)}` : errorMessage(error);
      runtime.state = "error";
      runtime.label = runtime.lastError;
      await this.closeConnection(runtime);
      this.scheduleReconnect(runtime);
    }
  }

  private createTransport(runtime: ServerRuntime): McpTransport {
    const configuration = runtime.config;
    if (configuration.transport === "stdio") {
      const cwd = resolveWorkingDirectory(this.root, configuration.cwd);
      const env = getDefaultEnvironment();
      for (const name of requestedEnvironment(configuration)) {
        const value = process.env[name];
        if (value !== undefined) env[name] = value;
      }
      // Literal env entries win over allowlisted pass-through values.
      for (const [name, value] of Object.entries(configuration.env ?? {})) env[name] = value;
      const transport = new StdioClientTransport({
        command: requireText(configuration.command, "command"),
        args: configuration.args ?? [],
        cwd,
        env,
        stderr: "pipe",
      });
      transport.stderr?.on("data", (chunk) => {
        runtime.stderr = `${runtime.stderr}${String(chunk)}`.slice(-4_000);
      });
      return transport;
    }

    const url = new URL(requireText(configuration.url, "url"));
    const token = configuration.authMode === "bearer-environment"
      ? process.env[requireText(configuration.tokenEnvironment, "tokenEnvironment")]
      : configuration.authMode === "oauth"
        ? requireText(configuration.accessToken, "OAuth access token")
        : undefined;
    const configuredHeaders = configuration.headers ?? {};
    const headers: Record<string, string> = {
      ...configuredHeaders,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    };
    const hasHeaders = Object.keys(headers).length > 0;
    const authenticatedFetch = token
      ? bearerFetch(token, configuredHeaders)
      : Object.keys(configuredHeaders).length > 0
        ? headerFetch(configuredHeaders)
        : fetch;
    if (configuration.transport === "sse") {
      return new SSEClientTransport(url, {
        fetch: authenticatedFetch,
        requestInit: { headers: hasHeaders ? headers : undefined },
      });
    }
    return new StreamableHTTPClientTransport(url, {
      fetch: authenticatedFetch,
      requestInit: { headers: hasHeaders ? headers : undefined },
      reconnectionOptions: {
        initialReconnectionDelay: 1_000,
        maxReconnectionDelay: 30_000,
        reconnectionDelayGrowFactor: 1.8,
        maxRetries: 5,
      },
    });
  }

  private async refreshTools(runtime: ServerRuntime, client: Client): Promise<void> {
    const listed: ListedTool[] = [];
    let cursor: string | undefined;
    do {
      const response = await client.listTools(cursor ? { cursor } : undefined, requestOptions(runtime.config));
      listed.push(...response.tools);
      cursor = response.nextCursor;
    } while (cursor);
    runtime.tools = listed.map((tool) => toToolSnapshot(runtime.config, tool));
    runtime.toolNames = new Map(runtime.tools.map((tool, index) => [tool.id, listed[index].name]));
    if (runtime.state === "ready") runtime.label = `${runtime.tools.length} tools`;
  }

  private async healthTick(): Promise<void> {
    if (this.closed) return;
    const candidates = [...this.servers.values()].filter((runtime) => runtime.state === "ready" && runtime.client);
    await Promise.allSettled(candidates.map(async (runtime) => {
      const client = runtime.client;
      if (!client) return;
      const startedAt = Date.now();
      try {
        await client.ping({ timeout: Math.min(5_000, timeoutMs(runtime.config)) });
        runtime.latencyMs = Date.now() - startedAt;
        runtime.lastHealthyAt = new Date().toISOString();
        runtime.lastError = null;
        runtime.label = `${runtime.tools.length} tools · ${runtime.latencyMs} ms`;
      } catch (error) {
        runtime.lastError = errorMessage(error);
        runtime.state = "degraded";
        runtime.label = `Health check failed: ${runtime.lastError}`;
        await this.closeConnection(runtime);
        this.scheduleReconnect(runtime);
      }
    }));
  }

  private scheduleReconnect(runtime: ServerRuntime): void {
    if (this.closed || !runtime.config.enabled || runtime.manualStop || runtime.intentionalClose || runtime.reconnectTimer) return;
    const delay = Math.min(30_000, 1_000 * 2 ** Math.min(runtime.restartCount, 5));
    runtime.restartCount += 1;
    runtime.reconnectTimer = setTimeout(() => {
      runtime.reconnectTimer = null;
      void this.startRuntime(runtime);
    }, delay);
    runtime.reconnectTimer.unref();
  }

  private async stopRuntime(
    runtime: ServerRuntime,
    finalState: McpRuntimeState,
    label: string,
  ): Promise<void> {
    clearReconnect(runtime);
    runtime.state = "stopping";
    runtime.label = "Stopping";
    runtime.generation += 1;
    await this.closeConnection(runtime);
    runtime.tools = [];
    runtime.toolNames.clear();
    runtime.state = finalState;
    runtime.label = label;
    runtime.latencyMs = null;
  }

  private async closeConnection(runtime: ServerRuntime): Promise<void> {
    const client = runtime.client;
    runtime.client = null;
    runtime.transport = null;
    if (!client) return;
    runtime.intentionalClose = true;
    try {
      await client.close();
    } catch {
      // The transport may already be closed after an error.
    } finally {
      runtime.intentionalClose = false;
    }
  }

  private requiredRuntime(id: string): ServerRuntime {
    this.ensureOpen();
    return this.servers.get(id) ?? (() => { throw new Error(`Unknown MCP server '${id}'`); })();
  }

  private ensureOpen(): void {
    if (this.closed) throw new Error("MCP runtime is closed");
  }

  private snapshot(): McpRuntimeSnapshot {
    const servers = [...this.servers.values()]
      .sort((left, right) => left.config.name.localeCompare(right.config.name))
      .map(toServerSnapshot);
    const tools = servers.flatMap((server) => server.tools);
    const unhealthy = servers.filter((server) => server.enabled && server.state !== "ready");
    const state = unhealthy.length > 0 ? "degraded" : tools.length > 0 ? "ready" : "idle";
    const label = servers.length === 0
      ? "No MCP servers configured"
      : unhealthy.length > 0
        ? `${unhealthy.length} of ${servers.length} servers need attention`
        : `${servers.length} servers · ${tools.length} tools`;
    return { state, label, servers, tools };
  }
}

function createRuntime(config: McpServerConfiguration): ServerRuntime {
  return {
    config,
    state: config.enabled ? "stopped" : "disabled",
    label: config.enabled ? "Stopped" : "Disabled",
    client: null,
    transport: null,
    tools: [],
    toolNames: new Map(),
    serverName: null,
    serverVersion: null,
    protocolVersion: null,
    capabilities: [],
    latencyMs: null,
    restartCount: 0,
    lastConnectedAt: null,
    lastHealthyAt: null,
    lastError: null,
    stderr: "",
    generation: 0,
    intentionalClose: false,
    manualStop: false,
    reconnectTimer: null,
  };
}

/** Accepts the original plugin's `http` alias for the Streamable HTTP transport. */
function normalizeTransport(value: unknown): McpTransportKind {
  const raw = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (raw === "http") return "streamable-http";
  if (raw === "stdio" || raw === "streamable-http" || raw === "sse") return raw;
  throw new Error(`Unsupported MCP transport '${String(value)}'`);
}

function normalizeConfiguration(value: McpServerConfiguration): McpServerConfiguration {
  if (!value || typeof value !== "object") throw new Error("MCP configuration must be an object");
  const id = requireText(value.id, "id");
  const name = requireText(value.name, "name");
  if (!/^[A-Za-z0-9._-]{1,120}$/.test(id)) throw new Error(`Invalid MCP server ID '${id}'`);
  const transport = normalizeTransport(value.transport);
  if (!["none", "bearer-environment", "oauth"].includes(value.authMode ?? "none")) {
    throw new Error(`Unsupported MCP authentication mode '${String(value.authMode)}'`);
  }
  const timeoutSeconds = Number.isInteger(value.timeoutSeconds) ? Number(value.timeoutSeconds) : 60;
  if (timeoutSeconds < 1 || timeoutSeconds > 600) throw new Error("MCP timeout must be between 1 and 600 seconds");
  const env = normalizeStringMap(value.env, "env", MAX_ENV_ENTRIES, /^[A-Za-z_][A-Za-z0-9_]{0,127}$/);
  const headers = normalizeStringMap(value.headers, "headers", MAX_HEADER_ENTRIES, /^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$/);
  // The original plugin uses `disabled`; CodeAgent persists `enabled`.
  const disabled = (value as { disabled?: unknown }).disabled === true;
  return {
    ...value,
    id,
    name,
    transport,
    enabled: value.enabled !== false && !disabled,
    args: Array.isArray(value.args) ? value.args.map(String).slice(0, 128) : [],
    requiredEnvironment: Array.isArray(value.requiredEnvironment)
      ? [...new Set(value.requiredEnvironment.map(String))].slice(0, 64)
      : [],
    timeoutSeconds,
    ...(env ? { env } : {}),
    ...(headers ? { headers } : {}),
  };
}

function validateEnvironment(configuration: McpServerConfiguration): void {
  for (const name of requestedEnvironment(configuration)) {
    if (!/^[A-Z][A-Z0-9_]{0,127}$/.test(name)) throw new Error(`Invalid environment variable name '${name}'`);
    if (!process.env[name]) throw new Error(`Required environment variable '${name}' is not set`);
  }
}

function requestedEnvironment(configuration: McpServerConfiguration): string[] {
  return [...new Set([
    ...(configuration.requiredEnvironment ?? []),
    ...(configuration.authMode === "bearer-environment" && configuration.tokenEnvironment
      ? [configuration.tokenEnvironment]
      : []),
  ])];
}

function resolveWorkingDirectory(rootValue: string, configured?: string | null): string {
  const root = path.resolve(rootValue);
  const candidate = path.resolve(root, configured?.trim() || ".");
  const relative = path.relative(root, candidate);
  if (relative === ".." || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    throw new Error("MCP working directory must stay inside the current project");
  }
  return candidate;
}

export function bearerFetch(token: string, extraHeaders: Record<string, string> = {}): typeof fetch {
  return async (input, init = {}) => {
    const headers = new Headers(init.headers);
    for (const [name, value] of Object.entries(extraHeaders)) headers.set(name, value);
    headers.set("Authorization", `Bearer ${token}`);
    return fetch(input, { ...init, headers });
  };
}

export function headerFetch(extraHeaders: Record<string, string>): typeof fetch {
  return async (input, init = {}) => {
    const headers = new Headers(init.headers);
    for (const [name, value] of Object.entries(extraHeaders)) headers.set(name, value);
    return fetch(input, { ...init, headers });
  };
}

function requestOptions(configuration: McpServerConfiguration): { timeout: number } {
  return { timeout: timeoutMs(configuration) };
}

function timeoutMs(configuration: McpServerConfiguration): number {
  return (configuration.timeoutSeconds ?? 60) * 1_000;
}

function toToolSnapshot(configuration: McpServerConfiguration, tool: ListedTool): McpToolSnapshot {
  const readOnly = tool.annotations?.readOnlyHint === true && tool.annotations?.destructiveHint !== true;
  return {
    id: namespacedToolId(configuration.id, tool.name),
    serverId: configuration.id,
    name: tool.name,
    title: tool.title ?? tool.annotations?.title ?? null,
    description: tool.description?.trim() || `Tool provided by ${configuration.name}`,
    parameters: tool.inputSchema as Record<string, unknown>,
    risk: readOnly ? "read_only" : "mutating",
  };
}

function namespacedToolId(serverId: string, toolName: string): string {
  const server = slug(serverId, 28);
  const tool = slug(toolName, 42);
  return `mcp__${server}_${digest(serverId)}__${tool}_${digest(toolName)}`;
}

function slug(value: string, maxLength: number): string {
  return value.replace(/[^A-Za-z0-9_-]+/g, "_").replace(/^_+|_+$/g, "").slice(0, maxLength) || "tool";
}

function digest(value: string): string {
  return createHash("sha256").update(value).digest("hex").slice(0, 8);
}

function toServerSnapshot(runtime: ServerRuntime): McpServerSnapshot {
  return {
    id: runtime.config.id,
    name: runtime.config.name,
    enabled: runtime.config.enabled,
    transport: runtime.config.transport,
    state: runtime.state,
    label: runtime.label,
    serverName: runtime.serverName,
    serverVersion: runtime.serverVersion,
    protocolVersion: runtime.protocolVersion,
    capabilities: runtime.capabilities,
    tools: runtime.tools,
    pid: runtime.transport instanceof StdioClientTransport ? runtime.transport.pid : null,
    latencyMs: runtime.latencyMs,
    restartCount: runtime.restartCount,
    lastConnectedAt: runtime.lastConnectedAt,
    lastHealthyAt: runtime.lastHealthyAt,
    lastError: runtime.lastError,
  };
}

function formatToolResult(result: Awaited<ReturnType<Client["callTool"]>>): string {
  const parts: string[] = [];
  for (const item of result.content ?? []) {
    if (item.type === "text") {
      parts.push(item.text);
    } else if (item.type === "resource") {
      parts.push(JSON.stringify(item.resource, null, 2));
    } else if (item.type === "resource_link") {
      parts.push(`${item.name}: ${item.uri}`);
    } else if ("mimeType" in item) {
      parts.push(`[${item.type}: ${item.mimeType}]`);
    } else {
      parts.push(JSON.stringify(item));
    }
  }
  if (result.structuredContent && Object.keys(result.structuredContent).length > 0) {
    parts.push(JSON.stringify(result.structuredContent, null, 2));
  }
  return parts.join("\n\n").trim().slice(0, 100_000);
}

function configurationKey(configuration: McpServerConfiguration): string {
  return JSON.stringify(configuration);
}

function clearReconnect(runtime: ServerRuntime): void {
  if (runtime.reconnectTimer) clearTimeout(runtime.reconnectTimer);
  runtime.reconnectTimer = null;
}

function requireText(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${field} is required`);
  return value.trim();
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
