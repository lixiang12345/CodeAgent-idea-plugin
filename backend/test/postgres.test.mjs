import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { PostgresProductStore } from "../src/product-store.mjs";

const connectionString = process.env.TEST_DATABASE_URL;

test("persists normalized conversations, messages, tasks, and tools in PostgreSQL", { skip: !connectionString }, async () => {
  const store = new PostgresProductStore({ connectionString, ssl: false });
  await store.initialize();
  const userId = `postgres-test-${randomUUID()}`;
  const conversation = {
    id: "thread-1",
    title: "PostgreSQL conversation",
    mode: "agent",
    updatedAt: 1_000,
    selectedAgentProfileId: "context",
    selectedModelId: "model-a",
    selectedSkillIds: ["review"],
    selectedRuleIds: ["tests"],
    pinned: true,
    summary: null,
    messages: [
      { id: "message-1", role: "user", content: "Inspect the change", createdAt: 900, runId: "run-1", turnIndex: 0 },
      { id: "message-2", role: "assistant", content: "Inspection complete", createdAt: 950, runId: "run-1", turnIndex: 1 },
    ],
    tasks: [{ id: "task-1", name: "Review change", state: "in_progress" }],
    tools: [{
      id: "tool-1",
      name: "read_file",
      summary: "Read source",
      status: "completed",
      detail: "/src/App.kt",
      canRevert: false,
      runId: "run-1",
      turnIndex: 0,
      createdAt: 925,
    }],
  };

  try {
    await store.upsertUser({ id: userId, email: "postgres@example.com", displayName: "PostgreSQL Test", claims: {} });
    const created = await store.putConversation(userId, conversation);
    assert.equal(created.version, 1);
    assert.deepEqual(created.messages, conversation.messages);
    assert.deepEqual(created.tasks, conversation.tasks);
    assert.deepEqual(created.tools, conversation.tools);

    const restored = await store.getConversation(userId, conversation.id);
    assert.equal(restored.selectedAgentProfileId, "context");
    assert.equal(restored.selectedModelId, "model-a");
    assert.deepEqual(restored.selectedSkillIds, ["review"]);
    assert.deepEqual(restored.selectedRuleIds, ["tests"]);
    assert.deepEqual(restored.messages, conversation.messages);
    assert.deepEqual(restored.tasks, conversation.tasks);
    assert.deepEqual(restored.tools, conversation.tools);

    const listed = await store.listConversations(userId);
    assert.equal(listed.length, 1);
    assert.equal(listed[0].messageCount, 2);
    assert.equal(listed[0].taskCount, 1);
    assert.equal(listed[0].toolCount, 1);

    const updated = await store.putConversation(userId, {
      ...conversation,
      updatedAt: 2_000,
      messages: [...conversation.messages, { id: "message-3", role: "user", content: "Continue", createdAt: 1_100 }],
      tasks: [{ ...conversation.tasks[0], state: "completed" }],
    }, 1);
    assert.equal(updated.version, 2);
    assert.equal((await store.getConversation(userId, conversation.id)).messages.length, 3);

    await assert.rejects(
      () => store.putConversation(userId, { ...conversation, updatedAt: 1_500 }, 1),
      (error) => error.statusCode === 409,
    );
    assert.equal(await store.deleteConversation(userId, conversation.id), true);
    assert.equal(await store.getConversation(userId, conversation.id), null);
  } finally {
    await store.pool.query("DELETE FROM codeagent_users WHERE id = $1", [userId]);
    await store.close();
  }
});
