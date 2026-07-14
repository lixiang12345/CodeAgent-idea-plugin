import { randomUUID } from "node:crypto";
import pg from "pg";

const { Pool } = pg;

export function createProductStoreFromEnv(env = process.env) {
  const connectionString = env.DATABASE_URL?.trim();
  if (!connectionString) return new MemoryProductStore();
  return new PostgresProductStore({
    connectionString,
    ssl: parseSsl(env.DATABASE_SSL),
  });
}

export class PostgresProductStore {
  constructor({ connectionString, ssl = false }) {
    this.pool = new Pool({ connectionString, ssl });
    this.kind = "postgres";
  }

  async initialize() {
    await this.pool.query(SCHEMA_SQL);
  }

  async close() {
    await this.pool.end();
  }

  async upsertUser(principal) {
    const { rows } = await this.pool.query(
      `INSERT INTO codeagent_users (id, email, display_name, claims)
       VALUES ($1, $2, $3, $4::jsonb)
       ON CONFLICT (id) DO UPDATE SET
         email = EXCLUDED.email,
         display_name = EXCLUDED.display_name,
         claims = EXCLUDED.claims,
         updated_at = now()
       RETURNING id, email, display_name AS "displayName", created_at AS "createdAt", updated_at AS "updatedAt"`,
      [principal.id, principal.email, principal.displayName, JSON.stringify(principal.claims || {})],
    );
    return rows[0];
  }

  async createAuthFlow(flow) {
    await this.pool.query(
      `INSERT INTO codeagent_auth_flows
         (id, redirect_uri, client_state, client_code_challenge, provider_code_verifier, nonce, expires_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [flow.id, flow.redirectUri, flow.clientState, flow.clientCodeChallenge, flow.providerCodeVerifier, flow.nonce, flow.expiresAt],
    );
  }

  async consumeAuthFlow(id) {
    const { rows } = await this.pool.query(
      `DELETE FROM codeagent_auth_flows WHERE id = $1 AND expires_at > now()
       RETURNING id, redirect_uri AS "redirectUri", client_state AS "clientState",
         client_code_challenge AS "clientCodeChallenge", provider_code_verifier AS "providerCodeVerifier",
         nonce, expires_at AS "expiresAt"`,
      [id],
    );
    return rows[0] || null;
  }

  async createAuthCode(code) {
    await this.pool.query(
      `INSERT INTO codeagent_auth_codes (code_hash, user_id, redirect_uri, code_challenge, expires_at)
       VALUES ($1, $2, $3, $4, $5)`,
      [code.codeHash, code.userId, code.redirectUri, code.codeChallenge, code.expiresAt],
    );
  }

  async consumeAuthCode(codeHash) {
    const { rows } = await this.pool.query(
      `DELETE FROM codeagent_auth_codes WHERE code_hash = $1 AND expires_at > now()
       RETURNING user_id AS "userId", redirect_uri AS "redirectUri", code_challenge AS "codeChallenge",
         expires_at AS "expiresAt"`,
      [codeHash],
    );
    return rows[0] || null;
  }

  async createSession(session) {
    const { rows } = await this.pool.query(
      `INSERT INTO codeagent_sessions (id, user_id, refresh_token_hash, refresh_expires_at)
       VALUES ($1, $2, $3, $4)
       RETURNING id::text, user_id AS "userId", refresh_expires_at AS "refreshExpiresAt",
         created_at AS "createdAt", updated_at AS "updatedAt"`,
      [session.id, session.userId, session.refreshTokenHash, session.refreshExpiresAt],
    );
    return rows[0];
  }

  async getSession(id, userId) {
    const { rows } = await this.pool.query(
      `SELECT id::text, user_id AS "userId", refresh_expires_at AS "refreshExpiresAt",
         created_at AS "createdAt", updated_at AS "updatedAt"
       FROM codeagent_sessions
       WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL AND refresh_expires_at > now()`,
      [id, userId],
    );
    return rows[0] || null;
  }

  async rotateSession(refreshTokenHash, replacementHash, refreshExpiresAt) {
    const { rows } = await this.pool.query(
      `UPDATE codeagent_sessions SET refresh_token_hash = $2, refresh_expires_at = $3, updated_at = now()
       WHERE refresh_token_hash = $1 AND revoked_at IS NULL AND refresh_expires_at > now()
       RETURNING id::text, user_id AS "userId", refresh_expires_at AS "refreshExpiresAt",
         created_at AS "createdAt", updated_at AS "updatedAt"`,
      [refreshTokenHash, replacementHash, refreshExpiresAt],
    );
    return rows[0] || null;
  }

  async revokeSession(id, userId) {
    const result = await this.pool.query(
      `UPDATE codeagent_sessions SET revoked_at = now(), updated_at = now()
       WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL`,
      [id, userId],
    );
    return result.rowCount > 0;
  }

  async getUser(userId) {
    const { rows } = await this.pool.query(
      `SELECT id, email, display_name AS "displayName", created_at AS "createdAt", updated_at AS "updatedAt"
       FROM codeagent_users WHERE id = $1`,
      [userId],
    );
    return rows[0] || null;
  }

  async listConversations(userId) {
    const { rows } = await this.pool.query(
      `SELECT id, title, mode, pinned, summary, version::int AS version,
         client_updated_at::float8 AS "updatedAt", created_at AS "createdAt", updated_at AS "syncedAt",
         (SELECT COUNT(*)::int FROM codeagent_messages WHERE user_id = $1 AND conversation_id = codeagent_conversations.id) AS "messageCount",
         (SELECT COUNT(*)::int FROM codeagent_tasks WHERE user_id = $1 AND conversation_id = codeagent_conversations.id) AS "taskCount"
       FROM codeagent_conversations WHERE user_id = $1 ORDER BY updated_at DESC`,
      [userId],
    );
    return rows;
  }

  async getConversation(userId, id) {
    const { rows } = await this.pool.query(
      `SELECT id, title, mode, selected_model_id AS "selectedModelId",
         selected_skill_ids AS "selectedSkillIds", selected_rule_ids AS "selectedRuleIds",
         pinned, summary, version::int AS version, client_updated_at::float8 AS "updatedAt",
         created_at AS "createdAt", updated_at AS "syncedAt",
         COALESCE((
           SELECT jsonb_agg(jsonb_build_object(
             'id', message.id, 'role', message.role, 'content', message.content, 'createdAt', message.created_at_ms
           ) ORDER BY message.created_at_ms, message.id)
           FROM codeagent_messages message
           WHERE message.user_id = $1 AND message.conversation_id = codeagent_conversations.id
         ), '[]'::jsonb) AS messages,
         COALESCE((
           SELECT jsonb_agg(jsonb_build_object(
             'id', task.id, 'name', task.name, 'state', task.state
           ) ORDER BY task.position, task.id)
           FROM codeagent_tasks task
           WHERE task.user_id = $1 AND task.conversation_id = codeagent_conversations.id
         ), '[]'::jsonb) AS tasks
       FROM codeagent_conversations WHERE user_id = $1 AND id = $2`,
      [userId, id],
    );
    return rows[0] || null;
  }

  async putConversation(userId, conversation, expectedVersion = null) {
    validateConversation(conversation);
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const existing = await client.query(
        `SELECT version FROM codeagent_conversations WHERE user_id = $1 AND id = $2 FOR UPDATE`,
        [userId, conversation.id],
      );
      const currentVersion = Number(existing.rows[0]?.version ?? 0);
      if (expectedVersion !== null && currentVersion !== expectedVersion) throw conflict("Conversation version changed");
      const nextVersion = currentVersion + 1;
      const { rows } = await client.query(
        `INSERT INTO codeagent_conversations
           (user_id, id, title, mode, selected_model_id, selected_skill_ids, selected_rule_ids,
            pinned, summary, client_updated_at, version)
         VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7::jsonb, $8, $9, $10, $11)
         ON CONFLICT (user_id, id) DO UPDATE SET
           title = EXCLUDED.title,
           mode = EXCLUDED.mode,
           selected_model_id = EXCLUDED.selected_model_id,
           selected_skill_ids = EXCLUDED.selected_skill_ids,
           selected_rule_ids = EXCLUDED.selected_rule_ids,
           pinned = EXCLUDED.pinned,
           summary = EXCLUDED.summary,
           client_updated_at = EXCLUDED.client_updated_at,
           version = EXCLUDED.version,
           updated_at = now()
         RETURNING id, title, mode, selected_model_id AS "selectedModelId",
           selected_skill_ids AS "selectedSkillIds", selected_rule_ids AS "selectedRuleIds",
           pinned, summary, version::int AS version, client_updated_at::float8 AS "updatedAt",
           created_at AS "createdAt", updated_at AS "syncedAt"`,
        [
          userId,
          conversation.id,
          conversation.title,
          conversation.mode,
          conversation.selectedModelId,
          JSON.stringify(conversation.selectedSkillIds),
          JSON.stringify(conversation.selectedRuleIds),
          conversation.pinned,
          conversation.summary,
          conversation.updatedAt,
          nextVersion,
        ],
      );
      await client.query(`DELETE FROM codeagent_messages WHERE user_id = $1 AND conversation_id = $2`, [userId, conversation.id]);
      await client.query(
        `INSERT INTO codeagent_messages (user_id, conversation_id, id, role, content, created_at_ms)
         SELECT $1, $2, message.id, message.role, message.content, message."createdAt"
         FROM jsonb_to_recordset($3::jsonb) AS message(id text, role text, content text, "createdAt" bigint)`,
        [userId, conversation.id, JSON.stringify(conversation.messages)],
      );
      await client.query(`DELETE FROM codeagent_tasks WHERE user_id = $1 AND conversation_id = $2`, [userId, conversation.id]);
      await client.query(
        `INSERT INTO codeagent_tasks (user_id, conversation_id, id, name, state, position)
         SELECT $1, $2, task.value->>'id', task.value->>'name', task.value->>'state', task.position::int - 1
         FROM jsonb_array_elements($3::jsonb) WITH ORDINALITY AS task(value, position)`,
        [userId, conversation.id, JSON.stringify(conversation.tasks)],
      );
      await client.query("COMMIT");
      return { ...rows[0], messages: clone(conversation.messages), tasks: clone(conversation.tasks) };
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  async deleteConversation(userId, id) {
    const result = await this.pool.query(
      `DELETE FROM codeagent_conversations WHERE user_id = $1 AND id = $2`,
      [userId, id],
    );
    return result.rowCount > 0;
  }

  async listConfigurations(userId, kind) {
    const { rows } = await this.pool.query(
      `SELECT id, kind, value, created_at AS "createdAt", updated_at AS "updatedAt"
       FROM codeagent_configurations WHERE user_id = $1 AND kind = $2 ORDER BY id`,
      [userId, kind],
    );
    return rows;
  }

  async putConfiguration(userId, kind, id, value) {
    const { rows } = await this.pool.query(
      `INSERT INTO codeagent_configurations (user_id, kind, id, value)
       VALUES ($1, $2, $3, $4::jsonb)
       ON CONFLICT (user_id, kind, id) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
       RETURNING id, kind, value, created_at AS "createdAt", updated_at AS "updatedAt"`,
      [userId, kind, id, JSON.stringify(value)],
    );
    return rows[0];
  }

  async deleteConfiguration(userId, kind, id) {
    const result = await this.pool.query(
      `DELETE FROM codeagent_configurations WHERE user_id = $1 AND kind = $2 AND id = $3`,
      [userId, kind, id],
    );
    return result.rowCount > 0;
  }

  async createJob(userId, { type, input }) {
    const id = randomUUID();
    const { rows } = await this.pool.query(
      `INSERT INTO codeagent_jobs (user_id, id, type, status, input)
       VALUES ($1, $2, $3, 'queued', $4::jsonb)
       RETURNING id, type, status, input, output, error, created_at AS "createdAt", updated_at AS "updatedAt"`,
      [userId, id, type, JSON.stringify(input || {})],
    );
    return rows[0];
  }

  async updateJob(userId, id, patch) {
    const { rows } = await this.pool.query(
      `UPDATE codeagent_jobs SET
         status = COALESCE($3, status),
         output = CASE WHEN $4 THEN $5::jsonb ELSE output END,
         error = CASE WHEN $6 THEN $7 ELSE error END,
         updated_at = now()
       WHERE user_id = $1 AND id = $2
       RETURNING id, type, status, input, output, error, created_at AS "createdAt", updated_at AS "updatedAt"`,
      [userId, id, patch.status || null, "output" in patch, patch.output === undefined ? null : JSON.stringify(patch.output), "error" in patch, patch.error ?? null],
    );
    return rows[0] || null;
  }

  async getJob(userId, id) {
    const { rows } = await this.pool.query(
      `SELECT id, type, status, input, output, error, created_at AS "createdAt", updated_at AS "updatedAt"
       FROM codeagent_jobs WHERE user_id = $1 AND id = $2`,
      [userId, id],
    );
    return rows[0] || null;
  }

  async listJobs(userId, limit = 50) {
    const { rows } = await this.pool.query(
      `SELECT id, type, status, input, output, error, created_at AS "createdAt", updated_at AS "updatedAt"
       FROM codeagent_jobs WHERE user_id = $1 ORDER BY created_at DESC LIMIT $2`,
      [userId, limit],
    );
    return rows;
  }

  async listRunnableJobs() {
    const { rows } = await this.pool.query(
      `SELECT user_id AS "userId", id, type, status, input
       FROM codeagent_jobs WHERE status IN ('queued', 'running') ORDER BY created_at ASC`,
    );
    return rows;
  }

  async recordUsage(userId, event) {
    await this.pool.query(
      `INSERT INTO codeagent_usage_events (user_id, kind, units, metadata)
       VALUES ($1, $2, $3, $4::jsonb)`,
      [userId, event.kind, event.units ?? 1, JSON.stringify(event.metadata || {})],
    );
  }

  async getUsage(userId) {
    const { rows } = await this.pool.query(
      `SELECT kind, SUM(units)::bigint AS units
       FROM codeagent_usage_events WHERE user_id = $1 GROUP BY kind ORDER BY kind`,
      [userId],
    );
    return rows.map((row) => ({ kind: row.kind, units: Number(row.units) }));
  }
}

export class MemoryProductStore {
  constructor() {
    this.kind = "memory";
    this.users = new Map();
    this.conversations = new Map();
    this.configurations = new Map();
    this.jobs = new Map();
    this.usage = new Map();
    this.authFlows = new Map();
    this.authCodes = new Map();
    this.sessions = new Map();
    this.sessionByRefreshHash = new Map();
  }

  async initialize() {}
  async close() {}

  async upsertUser(principal) {
    const now = new Date().toISOString();
    const previous = this.users.get(principal.id);
    const user = { id: principal.id, email: principal.email, displayName: principal.displayName, createdAt: previous?.createdAt || now, updatedAt: now };
    this.users.set(principal.id, user);
    return clone(user);
  }

  async createAuthFlow(flow) {
    this.authFlows.set(flow.id, clone(flow));
  }

  async consumeAuthFlow(id) {
    const flow = this.authFlows.get(id);
    this.authFlows.delete(id);
    return flow && Date.parse(flow.expiresAt) > Date.now() ? clone(flow) : null;
  }

  async createAuthCode(code) {
    this.authCodes.set(code.codeHash, clone(code));
  }

  async consumeAuthCode(codeHash) {
    const code = this.authCodes.get(codeHash);
    this.authCodes.delete(codeHash);
    return code && Date.parse(code.expiresAt) > Date.now() ? clone(code) : null;
  }

  async createSession(session) {
    const now = new Date().toISOString();
    const stored = { ...clone(session), createdAt: now, updatedAt: now, revokedAt: null };
    this.sessions.set(stored.id, stored);
    this.sessionByRefreshHash.set(stored.refreshTokenHash, stored.id);
    return publicSession(stored);
  }

  async getSession(id, userId) {
    const session = this.sessions.get(id);
    if (!activeSession(session) || session.userId !== userId) return null;
    return publicSession(session);
  }

  async rotateSession(refreshTokenHash, replacementHash, refreshExpiresAt) {
    const id = this.sessionByRefreshHash.get(refreshTokenHash);
    const session = id ? this.sessions.get(id) : null;
    if (!activeSession(session)) return null;
    this.sessionByRefreshHash.delete(refreshTokenHash);
    session.refreshTokenHash = replacementHash;
    session.refreshExpiresAt = refreshExpiresAt;
    session.updatedAt = new Date().toISOString();
    this.sessionByRefreshHash.set(replacementHash, session.id);
    return publicSession(session);
  }

  async revokeSession(id, userId) {
    const session = this.sessions.get(id);
    if (!session || session.userId !== userId || session.revokedAt) return false;
    session.revokedAt = new Date().toISOString();
    session.updatedAt = session.revokedAt;
    this.sessionByRefreshHash.delete(session.refreshTokenHash);
    return true;
  }

  async getUser(userId) { return clone(this.users.get(userId) || null); }

  async listConversations(userId) {
    return [...this.#userMap(this.conversations, userId).values()]
      .map(({ messages, tasks, ...conversation }) => ({
        ...conversation,
        messageCount: messages.length,
        taskCount: tasks.length,
      }))
      .sort((a, b) => String(b.syncedAt).localeCompare(String(a.syncedAt)));
  }

  async getConversation(userId, id) { return clone(this.#userMap(this.conversations, userId).get(id) || null); }

  async putConversation(userId, conversation, expectedVersion = null) {
    validateConversation(conversation);
    const map = this.#userMap(this.conversations, userId);
    const previous = map.get(conversation.id);
    const currentVersion = previous?.version || 0;
    if (expectedVersion !== null && currentVersion !== expectedVersion) throw conflict("Conversation version changed");
    const now = new Date().toISOString();
    const stored = {
      ...clone(conversation),
      version: currentVersion + 1,
      createdAt: previous?.createdAt || now,
      syncedAt: now,
    };
    map.set(stored.id, stored);
    return clone(stored);
  }

  async deleteConversation(userId, id) { return this.#userMap(this.conversations, userId).delete(id); }

  async listConfigurations(userId, kind) {
    const map = this.#userMap(this.configurations, userId);
    return [...map.values()].filter((item) => item.kind === kind).sort((a, b) => a.id.localeCompare(b.id)).map(clone);
  }

  async putConfiguration(userId, kind, id, value) {
    const map = this.#userMap(this.configurations, userId);
    const key = `${kind}:${id}`;
    const previous = map.get(key);
    const now = new Date().toISOString();
    const stored = { id, kind, value: clone(value), createdAt: previous?.createdAt || now, updatedAt: now };
    map.set(key, stored);
    return clone(stored);
  }

  async deleteConfiguration(userId, kind, id) { return this.#userMap(this.configurations, userId).delete(`${kind}:${id}`); }

  async createJob(userId, { type, input }) {
    const now = new Date().toISOString();
    const job = { id: randomUUID(), type, status: "queued", input: clone(input || {}), output: null, error: null, createdAt: now, updatedAt: now };
    this.#userMap(this.jobs, userId).set(job.id, job);
    return clone(job);
  }

  async updateJob(userId, id, patch) {
    const map = this.#userMap(this.jobs, userId);
    const job = map.get(id);
    if (!job) return null;
    Object.assign(job, clone(patch), { updatedAt: new Date().toISOString() });
    return clone(job);
  }

  async getJob(userId, id) { return clone(this.#userMap(this.jobs, userId).get(id) || null); }

  async listJobs(userId, limit = 50) {
    return [...this.#userMap(this.jobs, userId).values()]
      .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).slice(0, limit).map(clone);
  }

  async listRunnableJobs() {
    const result = [];
    for (const [userId, jobs] of this.jobs) {
      for (const job of jobs.values()) if (["queued", "running"].includes(job.status)) result.push({ userId, ...clone(job) });
    }
    return result;
  }

  async recordUsage(userId, event) {
    const map = this.#userMap(this.usage, userId);
    map.set(event.kind, (map.get(event.kind) || 0) + (event.units ?? 1));
  }

  async getUsage(userId) {
    return [...this.#userMap(this.usage, userId)].map(([kind, units]) => ({ kind, units })).sort((a, b) => a.kind.localeCompare(b.kind));
  }

  #userMap(collection, userId) {
    let map = collection.get(userId);
    if (!map) {
      map = new Map();
      collection.set(userId, map);
    }
    return map;
  }
}

const SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS codeagent_users (
  id text PRIMARY KEY,
  email text,
  display_name text NOT NULL,
  claims jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS codeagent_auth_flows (
  id text PRIMARY KEY,
  redirect_uri text NOT NULL,
  client_state text NOT NULL,
  client_code_challenge text NOT NULL,
  provider_code_verifier text NOT NULL,
  nonce text NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS codeagent_auth_flows_expiry_idx ON codeagent_auth_flows(expires_at);
CREATE TABLE IF NOT EXISTS codeagent_auth_codes (
  code_hash text PRIMARY KEY,
  user_id text NOT NULL REFERENCES codeagent_users(id) ON DELETE CASCADE,
  redirect_uri text NOT NULL,
  code_challenge text NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS codeagent_auth_codes_expiry_idx ON codeagent_auth_codes(expires_at);
CREATE TABLE IF NOT EXISTS codeagent_sessions (
  id uuid PRIMARY KEY,
  user_id text NOT NULL REFERENCES codeagent_users(id) ON DELETE CASCADE,
  refresh_token_hash text NOT NULL UNIQUE,
  refresh_expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS codeagent_sessions_user_idx ON codeagent_sessions(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS codeagent_sessions_expiry_idx ON codeagent_sessions(refresh_expires_at);
CREATE TABLE IF NOT EXISTS codeagent_conversations (
  user_id text NOT NULL REFERENCES codeagent_users(id) ON DELETE CASCADE,
  id text NOT NULL,
  title text NOT NULL,
  mode text NOT NULL CHECK (mode IN ('agent', 'chat', 'ask')),
  snapshot jsonb,
  selected_model_id text,
  selected_skill_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  selected_rule_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  pinned boolean NOT NULL DEFAULT false,
  summary text,
  client_updated_at bigint NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, id)
);
ALTER TABLE codeagent_conversations ALTER COLUMN snapshot DROP NOT NULL;
ALTER TABLE codeagent_conversations ADD COLUMN IF NOT EXISTS selected_model_id text;
ALTER TABLE codeagent_conversations ADD COLUMN IF NOT EXISTS selected_skill_ids jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE codeagent_conversations ADD COLUMN IF NOT EXISTS selected_rule_ids jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE codeagent_conversations ADD COLUMN IF NOT EXISTS pinned boolean NOT NULL DEFAULT false;
ALTER TABLE codeagent_conversations ADD COLUMN IF NOT EXISTS summary text;
ALTER TABLE codeagent_conversations ADD COLUMN IF NOT EXISTS client_updated_at bigint NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS codeagent_conversations_updated_idx ON codeagent_conversations(user_id, updated_at DESC);
CREATE TABLE IF NOT EXISTS codeagent_messages (
  user_id text NOT NULL,
  conversation_id text NOT NULL,
  id text NOT NULL,
  role text NOT NULL CHECK (role IN ('user', 'assistant')),
  content text NOT NULL,
  created_at_ms bigint NOT NULL,
  PRIMARY KEY (user_id, conversation_id, id),
  FOREIGN KEY (user_id, conversation_id) REFERENCES codeagent_conversations(user_id, id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS codeagent_messages_order_idx ON codeagent_messages(user_id, conversation_id, created_at_ms, id);
CREATE TABLE IF NOT EXISTS codeagent_tasks (
  user_id text NOT NULL,
  conversation_id text NOT NULL,
  id text NOT NULL,
  name text NOT NULL,
  state text NOT NULL CHECK (state IN ('not_started', 'in_progress', 'completed', 'cancelled')),
  position integer NOT NULL,
  PRIMARY KEY (user_id, conversation_id, id),
  FOREIGN KEY (user_id, conversation_id) REFERENCES codeagent_conversations(user_id, id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS codeagent_tasks_order_idx ON codeagent_tasks(user_id, conversation_id, position, id);
CREATE TABLE IF NOT EXISTS codeagent_configurations (
  user_id text NOT NULL REFERENCES codeagent_users(id) ON DELETE CASCADE,
  kind text NOT NULL,
  id text NOT NULL,
  value jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, kind, id)
);
CREATE TABLE IF NOT EXISTS codeagent_jobs (
  user_id text NOT NULL REFERENCES codeagent_users(id) ON DELETE CASCADE,
  id text NOT NULL,
  type text NOT NULL,
  status text NOT NULL CHECK (status IN ('queued', 'running', 'completed', 'failed', 'cancelled')),
  input jsonb NOT NULL DEFAULT '{}'::jsonb,
  output jsonb,
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS codeagent_jobs_status_idx ON codeagent_jobs(status, created_at);
CREATE TABLE IF NOT EXISTS codeagent_usage_events (
  id bigserial PRIMARY KEY,
  user_id text NOT NULL REFERENCES codeagent_users(id) ON DELETE CASCADE,
  kind text NOT NULL,
  units bigint NOT NULL DEFAULT 1,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS codeagent_usage_user_idx ON codeagent_usage_events(user_id, created_at DESC);
`;

function validateConversation(conversation) {
  if (!conversation || typeof conversation !== "object" || Array.isArray(conversation)) throw badRequest("Conversation is required");
  if (typeof conversation.id !== "string" || !conversation.id.trim()) throw badRequest("Conversation id is required");
  if (typeof conversation.title !== "string" || !conversation.title.trim()) throw badRequest("Conversation title is required");
  if (!["agent", "chat", "ask"].includes(conversation.mode)) throw badRequest("Unsupported conversation mode");
  if (!Number.isSafeInteger(conversation.updatedAt) || conversation.updatedAt < 0) throw badRequest("Conversation updatedAt is invalid");
  if (!Array.isArray(conversation.messages) || !Array.isArray(conversation.tasks)) throw badRequest("Conversation messages and tasks are required");
}


function parseSsl(value) {
  if (!value || value === "false" || value === "0") return false;
  if (value === "no-verify") return { rejectUnauthorized: false };
  return { rejectUnauthorized: true };
}

function clone(value) {
  return value === undefined ? undefined : structuredClone(value);
}

function activeSession(session) {
  return Boolean(session && !session.revokedAt && Date.parse(session.refreshExpiresAt) > Date.now());
}

function publicSession(session) {
  return clone({
    id: session.id,
    userId: session.userId,
    refreshExpiresAt: session.refreshExpiresAt,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
  });
}

function badRequest(message) {
  const error = new Error(message);
  error.statusCode = 400;
  return error;
}

function conflict(message) {
  const error = new Error(message);
  error.statusCode = 409;
  return error;
}
