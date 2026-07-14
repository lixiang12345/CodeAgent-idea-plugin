import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { PostgresProductStore } from "../src/product-store.mjs";

const connectionString = process.env.TEST_DATABASE_URL;

test("persists normalized conversations, messages, and tasks in PostgreSQL", { skip: !connectionString }, async () => {
  const store = new PostgresProductStore({ connectionString, ssl: false });
  await store.initialize();
  const userId = `postgres-test-${randomUUID()}`;
  const conversation = {
    id: "thread-1",
    title: "PostgreSQL conversation",
    mode: "agent",
    updatedAt: 1_000,
    selectedModelId: "model-a",
    selectedSkillIds: ["review"],
    selectedRuleIds: ["tests"],
    pinned: true,
    summary: null,
    messages: [
      { id: "message-1", role: "user", content: "Inspect the change", createdAt: 900 },
      { id: "message-2", role: "assistant", content: "Inspection complete", createdAt: 950 },
    ],
    tasks: [{ id: "task-1", name: "Review change", state: "in_progress" }],
  };

  try {
    await store.upsertUser({ id: userId, email: "postgres@example.com", displayName: "PostgreSQL Test", claims: {} });
    const created = await store.putConversation(userId, conversation);
    assert.equal(created.version, 1);
    assert.deepEqual(created.messages, conversation.messages);
    assert.deepEqual(created.tasks, conversation.tasks);

    const restored = await store.getConversation(userId, conversation.id);
    assert.equal(restored.selectedModelId, "model-a");
    assert.deepEqual(restored.selectedSkillIds, ["review"]);
    assert.deepEqual(restored.selectedRuleIds, ["tests"]);
    assert.deepEqual(restored.messages, conversation.messages);
    assert.deepEqual(restored.tasks, conversation.tasks);

    const listed = await store.listConversations(userId);
    assert.equal(listed.length, 1);
    assert.equal(listed[0].messageCount, 2);
    assert.equal(listed[0].taskCount, 1);

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
