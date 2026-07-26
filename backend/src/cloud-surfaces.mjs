/**
 * Deployment-owned cloud surfaces: shareable session links, operator-published
 * notifications, and subscription quota state.
 *
 * Every value here comes from deployment configuration or from usage the
 * backend actually counted. When a deployment has not configured a surface the
 * resolver reports an explicit unavailable state with a reason instead of
 * inventing a plan, a quota, or an announcement.
 */

const NOTIFICATION_LEVELS = ["info", "warning", "error"];
const IDENTIFIER = /^[A-Za-z0-9._-]{1,120}$/;

export function createCloudSurfacesFromEnv(env = process.env) {
  return {
    notifications: parseNotificationCatalog(env.NOTIFICATIONS_JSON),
    subscription: parseSubscriptionPolicy(env),
    share: parseSharePolicy(env),
  };
}

export function parseSharePolicy(env = {}) {
  const baseUrl = env.SHARE_BASE_URL?.trim();
  const ttl = Number.parseInt(env.SHARE_LINK_TTL_SECONDS || "604800", 10);
  if (!Number.isInteger(ttl) || ttl < 60 || ttl > 31_536_000) {
    throw new Error("SHARE_LINK_TTL_SECONDS must be between 60 and 31536000");
  }
  return { baseUrl: baseUrl ? shareBaseUrl(baseUrl) : null, defaultTtlSeconds: ttl };
}

function shareBaseUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("SHARE_BASE_URL must be a valid absolute URL");
  }
  const loopback = ["localhost", "127.0.0.1", "::1"].includes(url.hostname);
  if (url.protocol !== "https:" && !(url.protocol === "http:" && loopback)) {
    throw new Error("SHARE_BASE_URL must use https; loopback http is allowed for local development");
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error("SHARE_BASE_URL must not contain credentials, a query, or a fragment");
  }
  return url.toString().replace(/\/+$/, "");
}

export function parseNotificationCatalog(raw) {
  const text = typeof raw === "string" ? raw.trim() : "";
  if (!text) return [];
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error("NOTIFICATIONS_JSON must be valid JSON");
  }
  if (!Array.isArray(parsed)) throw new Error("NOTIFICATIONS_JSON must be an array of notifications");
  if (parsed.length > 20) throw new Error("NOTIFICATIONS_JSON must contain at most 20 notifications");
  const seen = new Set();
  return parsed.map((entry) => {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) throw new Error("Notification must be an object");
    const id = String(entry.id ?? "");
    if (!IDENTIFIER.test(id)) {
      throw new Error("Notification id must use 1-120 letters, numbers, dots, underscores, or hyphens");
    }
    if (seen.has(id)) throw new Error(`Duplicate notification id: ${id}`);
    seen.add(id);
    const message = typeof entry.message === "string" ? entry.message.trim() : "";
    if (!message) throw new Error(`Notification ${id} requires a message`);
    if (message.length > 2_000) throw new Error(`Notification ${id} message is too long`);
    const level = entry.level === undefined || entry.level === null ? "info" : entry.level;
    if (!NOTIFICATION_LEVELS.includes(level)) {
      throw new Error(`Notification ${id} level must be one of: ${NOTIFICATION_LEVELS.join(", ")}`);
    }
    return {
      id,
      level,
      message,
      actionItems: parseActionItems(entry.actionItems, id),
      expiresAt: parseExpiry(entry.expiresAt, id),
      userIds: parseAudience(entry.userIds, id),
    };
  });
}

function parseActionItems(value, id) {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.length > 4) {
    throw new Error(`Notification ${id} must declare at most 4 action items`);
  }
  return value.map((item) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      throw new Error(`Notification ${id} action item must be an object`);
    }
    const title = typeof item.title === "string" ? item.title.trim() : "";
    if (!title || title.length > 80) throw new Error(`Notification ${id} action item requires a title of at most 80 characters`);
    let url;
    try {
      url = new URL(String(item.url ?? ""));
    } catch {
      throw new Error(`Notification ${id} action item requires an absolute URL`);
    }
    const loopback = ["localhost", "127.0.0.1", "::1"].includes(url.hostname);
    if (url.protocol !== "https:" && !(url.protocol === "http:" && loopback)) {
      throw new Error(`Notification ${id} action item URL must use https`);
    }
    if (url.username || url.password) throw new Error(`Notification ${id} action item URL must not contain credentials`);
    return { title, url: url.toString() };
  });
}

function parseExpiry(value, id) {
  if (value === undefined || value === null || value === "") return null;
  const timestamp = typeof value === "number" ? value : Date.parse(String(value));
  if (!Number.isFinite(timestamp) || timestamp < 0) throw new Error(`Notification ${id} expiresAt is invalid`);
  return Math.trunc(timestamp);
}

function parseAudience(value, id) {
  if (value === undefined || value === null) return null;
  if (!Array.isArray(value) || value.length > 200) throw new Error(`Notification ${id} must target at most 200 users`);
  return value.map((userId) => {
    const normalized = String(userId ?? "").trim();
    if (!normalized || normalized.length > 200) throw new Error(`Notification ${id} contains an invalid user ID`);
    return normalized;
  });
}

/** Filters the operator catalog down to what this principal has not dismissed. */
export function resolveNotifications({ catalog = [], principal, dismissals = [], now = Date.now() } = {}) {
  const dismissed = new Set(dismissals.map((entry) => entry.notificationId));
  return catalog
    .filter((notification) => !dismissed.has(notification.id))
    .filter((notification) => notification.expiresAt === null || notification.expiresAt > now)
    .filter((notification) => !notification.userIds || notification.userIds.includes(principal?.id))
    .map(({ userIds, ...notification }) => ({ ...notification, actionItems: notification.actionItems.map((item) => ({ ...item })) }));
}

export function parseSubscriptionPolicy(env = {}) {
  const warnRatio = Number.parseFloat(env.SUBSCRIPTION_WARN_RATIO || "0.8");
  if (!Number.isFinite(warnRatio) || warnRatio < 0.5 || warnRatio > 0.99) {
    throw new Error("SUBSCRIPTION_WARN_RATIO must be between 0.5 and 0.99");
  }
  const planClaim = env.SUBSCRIPTION_PLAN_CLAIM?.trim() || "codeagent_plan";
  const defaultPlan = env.SUBSCRIPTION_DEFAULT_PLAN?.trim() || null;
  const plans = parsePlans(env.SUBSCRIPTION_PLANS_JSON);
  if (defaultPlan && plans.size && !plans.has(defaultPlan)) {
    throw new Error(`SUBSCRIPTION_DEFAULT_PLAN "${defaultPlan}" is not present in SUBSCRIPTION_PLANS_JSON`);
  }
  return { planClaim, defaultPlan, warnRatio, plans };
}

function parsePlans(raw) {
  const text = typeof raw === "string" ? raw.trim() : "";
  if (!text) return new Map();
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error("SUBSCRIPTION_PLANS_JSON must be valid JSON");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("SUBSCRIPTION_PLANS_JSON must be an object keyed by plan ID");
  }
  const entries = Object.entries(parsed);
  if (entries.length > 20) throw new Error("SUBSCRIPTION_PLANS_JSON must declare at most 20 plans");
  return new Map(entries.map(([plan, definition]) => {
    if (!IDENTIFIER.test(plan)) throw new Error(`Subscription plan ID "${plan}" is invalid`);
    if (!definition || typeof definition !== "object" || Array.isArray(definition)) {
      throw new Error(`Subscription plan ${plan} must be an object`);
    }
    return [plan, {
      label: typeof definition.label === "string" && definition.label.trim() ? definition.label.trim().slice(0, 80) : plan,
      manageUrl: parseManageUrl(definition.manageUrl, plan),
      quotas: parseQuotas(definition.quotas, plan),
    }];
  }));
}

function parseManageUrl(value, plan) {
  if (value === undefined || value === null || value === "") return null;
  let url;
  try {
    url = new URL(String(value));
  } catch {
    throw new Error(`Subscription plan ${plan} manageUrl must be an absolute URL`);
  }
  const loopback = ["localhost", "127.0.0.1", "::1"].includes(url.hostname);
  if (url.protocol !== "https:" && !(url.protocol === "http:" && loopback)) {
    throw new Error(`Subscription plan ${plan} manageUrl must use https`);
  }
  if (url.username || url.password) throw new Error(`Subscription plan ${plan} manageUrl must not contain credentials`);
  return url.toString();
}

function parseQuotas(value, plan) {
  if (value === undefined || value === null) return new Map();
  if (typeof value !== "object" || Array.isArray(value)) throw new Error(`Subscription plan ${plan} quotas must be an object`);
  const entries = Object.entries(value);
  if (entries.length > 20) throw new Error(`Subscription plan ${plan} must declare at most 20 quotas`);
  return new Map(entries.map(([kind, limit]) => {
    if (!IDENTIFIER.test(kind)) throw new Error(`Subscription plan ${plan} quota "${kind}" is invalid`);
    if (!Number.isInteger(limit) || limit < 0 || limit > Number.MAX_SAFE_INTEGER) {
      throw new Error(`Subscription plan ${plan} quota ${kind} must be a non-negative integer`);
    }
    return [kind, limit];
  }));
}

/**
 * Reports the plan and quota position from counted usage. Anything the
 * deployment has not configured is reported as `unknown` with a reason, so the
 * panel can show an unavailable row rather than an unwarranted "all good".
 */
export function resolveSubscription({ policy, principal, usage = [] } = {}) {
  const resolvedPolicy = policy || { planClaim: "codeagent_plan", defaultPlan: null, warnRatio: 0.8, plans: new Map() };
  const claimed = principal?.claims?.[resolvedPolicy.planClaim];
  const plan = typeof claimed === "string" && claimed.trim() ? claimed.trim().slice(0, 120) : resolvedPolicy.defaultPlan;
  const base = { plan: plan || null, label: null, manageUrl: null, quotas: [], warning: null };

  if (!resolvedPolicy.plans.size) {
    return { ...base, state: "unknown", reason: "Subscription plans are not configured on this deployment" };
  }
  const definition = plan ? resolvedPolicy.plans.get(plan) : null;
  if (!definition) {
    return {
      ...base,
      state: "unknown",
      reason: plan
        ? `Plan "${plan}" is not configured on this deployment`
        : "No subscription plan is assigned to this account",
    };
  }
  if (!definition.quotas.size) {
    return {
      ...base,
      label: definition.label,
      manageUrl: definition.manageUrl,
      state: "unknown",
      reason: `Plan "${plan}" does not declare usage quotas`,
    };
  }

  const used = new Map(usage.map((entry) => [entry.kind, Number(entry.units) || 0]));
  const quotas = [...definition.quotas].map(([kind, limit]) => {
    const consumed = used.get(kind) || 0;
    const ratio = limit === 0 ? 1 : consumed / limit;
    return {
      kind,
      used: consumed,
      limit,
      remaining: Math.max(0, limit - consumed),
      ratio: Math.round(ratio * 1000) / 1000,
      state: ratio >= 1 ? "exhausted" : ratio >= resolvedPolicy.warnRatio ? "approaching" : "ok",
    };
  });

  const worst = quotas.reduce((current, quota) => (severity(quota.state) > severity(current.state) ? quota : current), quotas[0]);
  return {
    plan,
    label: definition.label,
    manageUrl: definition.manageUrl,
    state: worst.state,
    quotas,
    warning: worst.state === "ok" ? null : {
      level: worst.state === "exhausted" ? "error" : "warning",
      kind: worst.kind,
      message: worst.state === "exhausted"
        ? `You have used all ${worst.limit} ${worst.kind} units included in the ${definition.label} plan.`
        : `You have used ${worst.used} of ${worst.limit} ${worst.kind} units included in the ${definition.label} plan.`,
    },
  };
}

function severity(state) {
  return state === "exhausted" ? 2 : state === "approaching" ? 1 : 0;
}
