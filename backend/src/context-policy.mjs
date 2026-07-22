export const DEFAULT_CONTEXT_WINDOW_TOKENS = 256_000;
export const DEFAULT_RESERVED_OUTPUT_TOKENS = 8_192;
export const DEFAULT_COMPACTION_TRIGGER_RATIO = 0.8;
export const MIN_CONTEXT_WINDOW_TOKENS = 32_768;
export const MAX_CONTEXT_WINDOW_TOKENS = 2_000_000;
export const MIN_RESERVED_OUTPUT_TOKENS = 1_024;
export const MAX_RESERVED_OUTPUT_TOKENS = 65_536;

const COMPACTED_TOOL_EXCERPT_TOKENS = 256;
const COMPACTED_MESSAGE_TOKENS = 192;
const RECENT_TOOL_RESULTS = 3;
const RECENT_MESSAGES = 6;
const BASE_RETRIEVAL_CONTEXT_WINDOW_TOKENS = 64_000;
const BASE_INPUT_TOKENS = BASE_RETRIEVAL_CONTEXT_WINDOW_TOKENS - DEFAULT_RESERVED_OUTPUT_TOKENS;
const BASE_RETRIEVAL_TOKENS = 8_192;
const MIN_RETRIEVAL_TOKENS = 4_096;
const MAX_RETRIEVAL_TOKENS = 24_576;
const RETRIEVAL_BUDGET_STEP = 512;

export function contextBudgetFor(agentProfile = {}, tools = []) {
  const configuredContextWindowTokens = integerInRange(
    agentProfile.contextWindowTokens,
    MIN_CONTEXT_WINDOW_TOKENS,
    MAX_CONTEXT_WINDOW_TOKENS,
    DEFAULT_CONTEXT_WINDOW_TOKENS,
  );
  const contextWindowTokens = configuredContextWindowTokens;
  const requestedOutputTokens = integerInRange(
    agentProfile.reservedOutputTokens,
    MIN_RESERVED_OUTPUT_TOKENS,
    MAX_RESERVED_OUTPUT_TOKENS,
    DEFAULT_RESERVED_OUTPUT_TOKENS,
  );
  const reservedOutputTokens = Math.min(
    requestedOutputTokens,
    Math.max(MIN_RESERVED_OUTPUT_TOKENS, contextWindowTokens - 8_192),
  );
  const inputBudgetTokens = contextWindowTokens - reservedOutputTokens;
  const toolDefinitionTokens = estimateTokens(JSON.stringify(tools || []));
  const retrievalBudgetTokens = retrievalBudgetForContextWindow(
    contextWindowTokens,
    reservedOutputTokens,
  );
  const compactionTriggerTokens = Math.min(
    inputBudgetTokens,
    Math.floor(contextWindowTokens * DEFAULT_COMPACTION_TRIGGER_RATIO),
  );

  return {
    contextWindowTokens,
    reservedOutputTokens,
    inputBudgetTokens,
    compactionTriggerTokens,
    systemPromptTokens: Math.max(4_096, Math.min(24_000, Math.floor(inputBudgetTokens * 0.36))),
    maxMessageTokens: Math.max(2_048, Math.min(16_000, Math.floor(inputBudgetTokens * 0.24))),
    maxToolResultTokens: retrievalBudgetTokens,
    retrievalBudgetTokens,
    toolDefinitionTokens,
  };
}

export function retrievalBudgetForContextWindow(
  contextWindowTokens,
  reservedOutputTokens = DEFAULT_RESERVED_OUTPUT_TOKENS,
) {
  const availableInputTokens = Math.max(
    1_024,
    contextWindowTokens - reservedOutputTokens,
  );
  const scaled =
    BASE_RETRIEVAL_TOKENS *
    Math.sqrt(availableInputTokens / BASE_INPUT_TOKENS);
  const stepped =
    Math.round(scaled / RETRIEVAL_BUDGET_STEP) * RETRIEVAL_BUDGET_STEP;
  return Math.min(
    MAX_RETRIEVAL_TOKENS,
    Math.max(MIN_RETRIEVAL_TOKENS, stepped),
  );
}

export function prepareModelMessages(messages, budget) {
  const prepared = messages.map(cloneMessage);
  const latestUserIndex = findLastIndex(prepared, (message) => message.role === "user");
  const toolIndexes = prepared
    .map((message, index) => message.role === "tool" ? index : -1)
    .filter((index) => index >= 0);
  const recentToolIndexes = new Set(toolIndexes.slice(-RECENT_TOOL_RESULTS));
  let compactedToolResults = 0;
  let truncatedMessages = 0;
  let compactionApplied = false;

  for (let index = 0; index < prepared.length; index += 1) {
    const message = prepared[index];
    if (typeof message.content !== "string" || !message.content) continue;
    const limit = message.role === "system"
      ? budget.systemPromptTokens
      : message.role === "tool"
        ? budget.maxToolResultTokens
        : budget.maxMessageTokens;
    const truncated = truncateToTokens(message.content, limit);
    if (truncated !== message.content) {
      message.content = truncated;
      truncatedMessages += 1;
    }
    if (message.role === "tool" &&
        !recentToolIndexes.has(index) &&
        estimateTokens(message.content) > COMPACTED_TOOL_EXCERPT_TOKENS * 2) {
      message.content = compactToolResult(message);
      compactedToolResults += 1;
    }
  }

  const targetInputTokens = Math.max(
    4_096,
    budget.compactionTriggerTokens - budget.toolDefinitionTokens,
  );
  let estimatedInputTokens = estimateMessages(prepared);
  const recentStart = Math.max(0, prepared.length - RECENT_MESSAGES);
  const protectedIndexes = new Set([
    0,
    ...(latestUserIndex >= 0 ? [latestUserIndex] : []),
    ...prepared.slice(recentStart).map((_, offset) => recentStart + offset),
  ]);

  if (estimatedInputTokens > targetInputTokens) {
    for (let index = 1; index < prepared.length && estimatedInputTokens > targetInputTokens; index += 1) {
      if (protectedIndexes.has(index)) continue;
      if (compactMessageContent(prepared[index], COMPACTED_MESSAGE_TOKENS)) {
        compactionApplied = true;
        truncatedMessages += 1;
        estimatedInputTokens = estimateMessages(prepared);
      }
    }
  }

  if (estimatedInputTokens > targetInputTokens) {
    for (let index = 1; index < prepared.length && estimatedInputTokens > targetInputTokens; index += 1) {
      if (index === latestUserIndex) continue;
      if (compactMessageContent(prepared[index], 96)) {
        compactionApplied = true;
        truncatedMessages += 1;
        estimatedInputTokens = estimateMessages(prepared);
      }
    }
  }

  if (estimatedInputTokens > targetInputTokens && latestUserIndex >= 0) {
    if (compactMessageContent(prepared[latestUserIndex], Math.max(1_024, Math.floor(targetInputTokens * 0.3)))) {
      compactionApplied = true;
      truncatedMessages += 1;
      estimatedInputTokens = estimateMessages(prepared);
    }
  }

  return {
    messages: prepared,
    stats: {
      estimatedInputTokens,
      targetInputTokens,
      contextWindowTokens: budget.contextWindowTokens,
      reservedOutputTokens: budget.reservedOutputTokens,
      retrievalBudgetTokens: budget.retrievalBudgetTokens,
      toolDefinitionTokens: budget.toolDefinitionTokens,
      assistantResponseTokens: 0,
      compactedToolResults,
      truncatedMessages,
      compactionApplied,
      overBudget: estimatedInputTokens > targetInputTokens,
    },
  };
}

export function estimateTokens(value) {
  if (value === undefined || value === null || value === "") return 0;
  let tokens = 0;
  for (const character of String(value)) {
    tokens += character.codePointAt(0) <= 0x7f ? 0.25 : 1;
  }
  return Math.ceil(tokens);
}

export function truncateToTokens(value, maxTokens) {
  const text = String(value || "");
  if (maxTokens <= 0) return "";
  if (estimateTokens(text) <= maxTokens) return text;
  const marker = "\n...[truncated to context budget]...\n";
  const markerTokens = estimateTokens(marker);
  if (maxTokens <= markerTokens + 8) return takeTokenPrefix(text, maxTokens);
  const available = maxTokens - markerTokens;
  const prefix = takeTokenPrefix(text, Math.floor(available * 0.72));
  const suffix = takeTokenSuffix(text, Math.max(1, available - estimateTokens(prefix)));
  return `${prefix}${marker}${suffix}`;
}

function compactToolResult(message) {
  const summary = typeof message.summary === "string" && message.summary.trim()
    ? truncateToTokens(message.summary.trim(), 96)
    : "Earlier tool result";
  const excerpt = truncateToTokens(message.content, COMPACTED_TOOL_EXCERPT_TOKENS);
  return `[Earlier tool output compacted after use.]\nSummary: ${summary}\nExcerpt:\n${excerpt}`;
}

function compactMessageContent(message, maxTokens) {
  if (typeof message.content !== "string" || !message.content) return false;
  const next = truncateToTokens(message.content, maxTokens);
  if (next === message.content) return false;
  message.content = next;
  return true;
}

function estimateMessages(messages) {
  return messages.reduce((total, message) => {
    return total
      + estimateTokens(message.content)
      + estimateTokens(message.toolCallId)
      + estimateTokens(JSON.stringify(message.toolCalls || []))
      + estimateAttachments(message.attachments)
      + 8;
  }, 0);
}

function cloneMessage(message) {
  return {
    ...message,
    attachments: Array.isArray(message.attachments)
      ? message.attachments.map((attachment) => ({
        ...attachment,
        metadata: attachment.metadata ? { ...attachment.metadata } : attachment.metadata,
      }))
      : message.attachments,
    toolCalls: Array.isArray(message.toolCalls)
      ? message.toolCalls.map((call) => ({ ...call }))
      : message.toolCalls,
  };
}

function estimateAttachments(attachments) {
  if (!Array.isArray(attachments)) return 0;
  return attachments.reduce((total, attachment) => {
    const textTokens = estimateTokens(attachment.textExcerpt) + estimateTokens(JSON.stringify(attachment.metadata || {}));
    const rawTokens = Number.isSafeInteger(attachment.sizeBytes)
      ? Math.ceil(attachment.sizeBytes * 0.25)
      : 0;
    const visualTokens = attachment.type === "image" ? 2_048 : 0;
    return total + Math.min(32_000, Math.max(256, textTokens, rawTokens, visualTokens));
  }, 0);
}

function findLastIndex(values, predicate) {
  for (let index = values.length - 1; index >= 0; index -= 1) {
    if (predicate(values[index], index)) return index;
  }
  return -1;
}

function takeTokenPrefix(value, maxTokens) {
  let result = "";
  let tokens = 0;
  for (const character of value) {
    const cost = character.codePointAt(0) <= 0x7f ? 0.25 : 1;
    if (tokens + cost > maxTokens) break;
    tokens += cost;
    result += character;
  }
  return result;
}

function takeTokenSuffix(value, maxTokens) {
  const characters = Array.from(value);
  let result = "";
  let tokens = 0;
  for (let index = characters.length - 1; index >= 0; index -= 1) {
    const character = characters[index];
    const cost = character.codePointAt(0) <= 0x7f ? 0.25 : 1;
    if (tokens + cost > maxTokens) break;
    tokens += cost;
    result = character + result;
  }
  return result;
}

function integerInRange(value, min, max, fallback) {
  return Number.isInteger(value) && value >= min && value <= max ? value : fallback;
}
