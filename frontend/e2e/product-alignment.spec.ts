import { expect, test, type Page, type TestInfo } from "@playwright/test";

async function openApp(page: Page): Promise<void> {
  await page.goto("/");
  await expect(page.locator(".shell")).toBeVisible();
  await page.waitForFunction(() => window.CodeAgentDevelopment?.getSnapshot()?.context.state === "ready");
  await page.addStyleTag({
    content: `
      *, *::before, *::after { transition-duration: 0s !important; animation-duration: 0s !important; }
      * { scrollbar-width: none !important; }
      *::-webkit-scrollbar { width: 0 !important; height: 0 !important; }
      time { visibility: hidden !important; }
    `,
  });
}

async function expectViewportIntegrity(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => {
    const shell = document.querySelector(".shell")?.getBoundingClientRect();
    return {
      documentWidth: document.documentElement.scrollWidth,
      documentHeight: document.documentElement.scrollHeight,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      shell: shell && { left: shell.left, top: shell.top, right: shell.right, bottom: shell.bottom },
    };
  });
  expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth + 1);
  expect(metrics.documentHeight).toBeLessThanOrEqual(metrics.viewportHeight + 1);
  expect(metrics.shell).not.toBeNull();
  expect(metrics.shell!.left).toBeGreaterThanOrEqual(-1);
  expect(metrics.shell!.top).toBeGreaterThanOrEqual(-1);
  expect(metrics.shell!.right).toBeLessThanOrEqual(metrics.viewportWidth + 1);
  expect(metrics.shell!.bottom).toBeLessThanOrEqual(metrics.viewportHeight + 1);
}

async function captureShell(page: Page, name: string): Promise<void> {
  await expect(page.locator(".shell")).toHaveScreenshot(name);
}

function requireReferenceViewport(testInfo: TestInfo): void {
  test.skip(testInfo.project.name !== "tool-window-420", "Detailed workflow references use the canonical 420 px tool window");
}

test.beforeEach(async ({ page }) => {
  await openApp(page);
});

test("main Agent workspace stays dense and bounded", async ({ page }) => {
  await expect(page.getByText("Implement login flow with JWT", { exact: true })).toBeVisible();
  await expect(page.getByText("JWT login is implemented and the focused tests pass.", { exact: false })).toBeVisible();
  await expect(page.getByPlaceholder("Type a message or command...")).toBeVisible();
  await expect(page.getByRole("button", { name: "Send", exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "main-agent-workspace.png");
});

test("messages can be edited, rewound, and resent while the composer adapts", async ({ page }, testInfo) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    const now = Date.now();
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "idle",
      agentRun: { ...snapshot.agentRun, phase: "idle", activeToolNames: [], activeToolCount: 0 },
      messages: [
        { id: "editable-user", role: "user", content: "Inspect the current authentication boundary.", createdAt: now - 40_000, timelineSequence: 1, runId: "edit-run-1" },
        { id: "editable-assistant", role: "assistant", content: "The authentication boundary currently accepts a bearer token.", createdAt: now - 30_000, timelineSequence: 3, runId: "edit-run-1" },
        { id: "later-user", role: "user", content: "Then verify the old integration path.", createdAt: now - 20_000, timelineSequence: 4, runId: "edit-run-2" },
        { id: "later-assistant", role: "assistant", content: "The old integration path was verified.", createdAt: now - 10_000, timelineSequence: 6, runId: "edit-run-2" },
      ],
      tools: [
        { id: "editable-read", name: "read_file", summary: "AuthService.kt", status: "completed", detail: "Read authentication boundary", canRevert: false, timelineSequence: 2, runId: "edit-run-1" },
        { id: "later-search", name: "search_text", summary: "Legacy integration path", status: "completed", detail: "Found two references", canRevert: false, timelineSequence: 5, runId: "edit-run-2" },
      ],
      messageQueue: [],
    });
  });

  const composer = page.getByPlaceholder("Type a message or command...");
  const compactHeight = await composer.evaluate((element) => element.getBoundingClientRect().height);
  await composer.fill("First line\nSecond line\nThird line\nFourth line");
  await expect.poll(() => composer.evaluate((element) => element.getBoundingClientRect().height)).toBeGreaterThan(compactHeight);
  await composer.fill("");
  await expect.poll(() => composer.evaluate((element) => element.getBoundingClientRect().height)).toBeLessThanOrEqual(compactHeight + 1);

  const firstMessage = page.locator(".user-message").filter({ hasText: "Inspect the current authentication boundary." });
  await firstMessage.hover();
  await firstMessage.getByRole("button", { name: "Edit message" }).click();
  const editor = page.getByRole("textbox", { name: "Edit message" });
  await editor.fill("Inspect and replace the authentication boundary.");
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page.getByText("Inspect the current authentication boundary.", { exact: true })).toBeVisible();
  await expect(editor).toBeHidden();

  await firstMessage.hover();
  await firstMessage.getByRole("button", { name: "Edit message" }).click();
  await page.getByRole("textbox", { name: "Edit message" }).fill("Inspect and replace the authentication boundary.");
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "message-edit-resend.png");
  await page.getByRole("button", { name: "Apply & Resend", exact: true }).click();

  await expect(page.getByText("Inspect and replace the authentication boundary.", { exact: true })).toBeVisible();
  await expect(page.getByText("The authentication boundary currently accepts a bearer token.", { exact: true })).toBeHidden();
  await expect(page.getByText("Then verify the old integration path.", { exact: true })).toBeHidden();
  await expect(page.getByText("The old integration path was verified.", { exact: true })).toBeHidden();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.runState)).toBe("running");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messages.map((message) => message.id))).toEqual(["editable-user"]);

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "idle",
      agentRun: { ...snapshot.agentRun, phase: "idle", activeToolNames: [], activeToolCount: 0 },
      tools: snapshot.tools.map((tool) => ({ ...tool, status: "completed" as const, canRevert: false })),
    });
  });
  const editedMessage = page.locator(".user-message").filter({ hasText: "Inspect and replace the authentication boundary." });
  await editedMessage.hover();
  await editedMessage.getByRole("button", { name: "Resend message" }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.runState)).toBe("running");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messages.map((message) => `${message.id}:${message.content}`))).toEqual([
    "editable-user:Inspect and replace the authentication boundary.",
  ]);
  await expectViewportIntegrity(page);
});

test("Threads drawer supports scanning and search", async ({ page }, testInfo) => {
  requireReferenceViewport(testInfo);
  await page.getByRole("button", { name: "Threads", exact: true }).first().click();
  await expect(page.locator(".thread-drawer > header strong")).toHaveText("Threads");
  await expect(page.getByPlaceholder("Search threads…")).toBeVisible();
  await expect(page.getByText("Review repository architecture", { exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "threads-drawer.png");
});

test("Agent Edits and task workspace preserve review-first controls", async ({ page }, testInfo) => {
  requireReferenceViewport(testInfo);
  await page.getByTitle("More options").click();
  await page.getByRole("button", { name: "Agent Edits" }).click();
  await expect(page.getByText("Agent Edits", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Keep all" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Discard all" })).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "agent-edits.png");

  await page.getByRole("button", { name: "Back", exact: true }).click();
  await page.getByTitle("More options").click();
  await page.getByRole("button", { name: "Agent Tasklist" }).click();
  await expect(page.getByText("Active Tasklist", { exact: true })).toBeVisible();
  await expect(page.getByText("Add invalid-credential regression coverage", { exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "active-tasklist.png");
});

test("Settings exposes connected and conditional capabilities", async ({ page }, testInfo) => {
  requireReferenceViewport(testInfo);
  await page.getByTitle("Settings").click();
  await expect(page.getByText("Project Home", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "All settings" }).click();
  await page.getByRole("button", { name: "MCP Servers", exact: true }).click();
  await expect(page.getByRole("heading", { name: "MCP Servers", exact: true })).toBeVisible();
  await expect(page.getByText("Local Context MCP", { exact: true }).first()).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "mcp-settings.png");
});

test("mutating tool approval remains explicit", async ({ page }, testInfo) => {
  requireReferenceViewport(testInfo);
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "awaiting_approval",
      agentRun: { ...snapshot.agentRun, phase: "approval" },
      tools: [
        ...snapshot.tools,
        {
          id: "e2e-approval",
          name: "replace_text",
          summary: "SecurityConfig.java",
          status: "approval",
          detail: "Replace permissive authentication with the reviewed JWT policy.",
          changePath: "src/main/java/com/example/auth/SecurityConfig.java",
          canRevert: false,
          timelineSequence: 11,
        },
      ],
    });
  });
  const approval = page.getByRole("status").filter({ hasText: "Waiting for user input" });
  await expect(approval).toBeVisible();
  await expect(approval.getByRole("button", { name: "Skip" })).toBeVisible();
  await expect(approval.getByRole("button", { name: "Approve" })).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "tool-approval.png");
  await page.getByRole("button", { name: "Generation status" }).click();
  await expect(page.getByRole("button", { name: "Stop generation" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Jump to latest" })).toBeVisible();
  await page.getByRole("button", { name: "Generation status" }).click();
  await approval.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText("Waiting for user input")).toBeHidden();
});

test("specialized tool cards preserve provider-specific result structure", async ({ page }, testInfo) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      messages: [snapshot.messages[0]],
      tools: [
        {
          id: "specialized-file",
          name: "replace_text",
          summary: "AuthController.java",
          status: "completed",
          detail: "@@ authentication handler\n- return null;\n+ return tokenService.issue(request);",
          changePath: "src/main/java/com/example/auth/AuthController.java",
          canRevert: true,
          timelineSequence: 2,
        },
        {
          id: "specialized-search",
          name: "search_text",
          summary: "JWT issuer references",
          status: "completed",
          detail: "src/main/java/com/example/auth/TokenService.java:41: return jwtIssuer.issue(user);\nsrc/test/java/com/example/auth/AuthControllerTest.java:73: assertThat(token).isNotBlank();",
          canRevert: false,
          timelineSequence: 3,
        },
        {
          id: "specialized-web",
          name: "web_fetch",
          summary: "Fetched security guidance",
          status: "completed",
          detail: "https://example.test/security-guidance\nJWT validation requires issuer, audience, expiration, and signature checks.",
          canRevert: false,
          timelineSequence: 4,
        },
        {
          id: "specialized-integration",
          name: "github_search",
          summary: "Pull request checks",
          status: "completed",
          detail: "lixiang12345/test#1\nstatus=completed conclusion=success\nhttps://github.com/lixiang12345/test/pull/1",
          canRevert: false,
          timelineSequence: 5,
        },
        {
          id: "specialized-tasks",
          name: "update_tasks",
          summary: "Updated verification task",
          status: "completed",
          detail: "1. Inspect authentication flow [completed]\n2. Add invalid-credential coverage [in_progress]",
          canRevert: false,
          timelineSequence: 6,
        },
        {
          id: "specialized-agent",
          name: "subagent",
          summary: "Security reviewer completed",
          status: "completed",
          detail: "The focused review found no missing signature validation. Add an expired-token regression test before release.",
          canRevert: false,
          timelineSequence: 7,
        },
        {
          id: "specialized-diagnostics",
          name: "diagnostics",
          summary: "SecurityConfig.java",
          status: "completed",
          detail: "IntelliJ currently has no registered errors for SecurityConfig.java",
          canRevert: false,
          timelineSequence: 8,
        },
        {
          id: "specialized-terminal",
          name: "run_terminal",
          summary: "npm test (exit 0)",
          status: "completed",
          detail: "exit=0\n18 tests passed",
          canRevert: false,
          timelineSequence: 9,
        },
        {
          id: "specialized-process",
          name: "read_process",
          summary: "Read 34 chars from frontend watcher",
          status: "completed",
          detail: "terminal_id=terminal-1\nprocess_id=terminal-1\nname=frontend watcher\nstate=running\npid=1234\nwaiting_for_input=false\noutput_offsets=0-34\ncommand=npm run dev\n\nVITE ready on http://localhost:5173",
          canRevert: false,
          timelineSequence: 10,
        },
      ],
      tasks: [],
    });
  });

  const cases = [
    ["AuthController.java", "file"],
    ["JWT issuer references", "search"],
    ["Fetched security guidance", "web"],
    ["Pull request checks", "integration"],
    ["Updated verification task", "tasks"],
    ["Security reviewer completed", "agent"],
    ["SecurityConfig.java", "diagnostics"],
    ["npm test (exit 0)", "terminal"],
    ["Read 34 chars from frontend watcher", "process"],
  ] as const;

  for (const [summary, kind] of cases) {
    const card = page.locator(".tool-card").filter({ hasText: summary });
    await expect(card).toHaveCount(1);
    await card.locator(".tool-header").click();
    await expect(card.locator(`[data-tool-kind="${kind}"]`)).toBeVisible();
  }

  const integrationCard = page.locator(".tool-card").filter({ hasText: "Pull request checks" });
  await expect(integrationCard.getByText("GitHub", { exact: true })).toBeVisible();
  const fileCard = page.locator(".tool-card").filter({ hasText: "AuthController.java" });
  await expect(fileCard.getByRole("button", { name: "View Diff" })).toBeVisible();
  await expect(fileCard.getByRole("button", { name: "Undo" })).toBeVisible();
  const diagnosticsCard = page.locator(".tool-card").filter({ hasText: "SecurityConfig.java" });
  await expect(diagnosticsCard.locator(".diagnostic-result")).not.toHaveClass(/failed/);
  const processCard = page.locator(".tool-card").filter({ hasText: "Read 34 chars from frontend watcher" });
  await expect(processCard.getByText("VITE ready on http://localhost:5173", { exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  if (testInfo.project.name === "tool-window-420") {
    await integrationCard.scrollIntoViewIfNeeded();
    await captureShell(page, "specialized-tool-cards.png");
  }
});

test("long conversations preserve reading position and expose request navigation", async ({ page }, testInfo) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    const now = Date.now();
    const messages = Array.from({ length: 12 }, (_, index) => {
      const runId = `long-run-${index}`;
      return [
        {
          id: `long-user-${index}`,
          role: "user" as const,
          content: `Request ${index + 1}: inspect the implementation boundary and verify the relevant behavior.`,
          createdAt: now - (12 - index) * 60_000,
          timelineSequence: index * 3 + 1,
          runId,
          turnIndex: index,
        },
        {
          id: `long-assistant-${index}`,
          role: "assistant" as const,
          content: `Response ${index + 1}: the implementation was inspected and the evidence was recorded. `.repeat(4),
          createdAt: now - (12 - index) * 60_000 + 20_000,
          timelineSequence: index * 3 + 3,
          runId,
          turnIndex: index,
        },
      ];
    }).flat();
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "running",
      agentRun: {
        ...snapshot.agentRun,
        phase: "tools",
        turnIndex: 11,
        activeToolNames: ["read_file"],
        activeToolCount: 1,
        toolBatchTotal: 1,
        toolBatchCompleted: 0,
      },
      messages,
      tools: [{
        id: "long-running-tool",
        name: "read_file",
        summary: "frontend/src/App.svelte",
        status: "running",
        detail: "Inspecting the long-conversation navigation boundary.",
        canRevert: false,
        runId: "long-run-11",
        turnIndex: 11,
        createdAt: now,
        timelineSequence: 35,
      }],
      messageQueue: [{ id: "long-queued", text: "Run the final responsive regression checks.", mode: "agent" }],
      tasks: [],
    });
  });

  await expect(page.locator("[data-request-boundary]")).toHaveCount(12);
  await expect(page.getByText("12 / 12 requests", { exact: true })).toBeVisible();
  await expect(page.getByText("Run the final responsive regression checks.", { exact: true })).toBeVisible();

  const conversation = page.locator(".conversation");
  await conversation.evaluate((element) => {
    element.scrollTop = 0;
    element.dispatchEvent(new Event("scroll"));
  });
  await expect(page.getByRole("button", { name: "Jump to latest" }).last()).toBeVisible();
  await expect(page.getByText("1 / 12 requests", { exact: true })).toBeVisible();
  const readingPosition = await conversation.evaluate((element) => element.scrollTop);

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "awaiting_approval",
      agentRun: { ...snapshot.agentRun, phase: "approval" },
      threads: snapshot.threads.map((thread) => thread.active ? { ...thread, unreadCount: 1 } : thread),
      tools: snapshot.tools.map((tool) => tool.id === "long-running-tool"
        ? { ...tool, status: "approval" as const, detail: `${tool.detail}\nApproval is now required.` }
        : tool),
    });
  });
  await expect.poll(() => conversation.evaluate((element) => element.scrollTop)).toBe(readingPosition);
  await page.getByRole("button", { name: "Threads", exact: true }).first().click();
  await expect(page.locator(".thread-row.active .thread-unread")).toHaveText("1 new");
  await page.getByRole("button", { name: "Close", exact: true }).click();
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "long-conversation-navigation.png");

  await page.getByRole("button", { name: "Next request" }).click();
  await expect(page.getByText("2 / 12 requests", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Jump to latest" }).last().click();
  await expect.poll(() => conversation.evaluate((element) => element.scrollHeight - element.scrollTop - element.clientHeight)).toBeLessThanOrEqual(48);
  await expect(page.getByText("12 / 12 requests", { exact: true })).toBeVisible();
  await expect(page.locator(".conversation > .conversation-navigation .jump-latest")).toBeHidden();
  await page.getByRole("button", { name: "Threads", exact: true }).first().click();
  await expect(page.locator(".thread-row.active .thread-unread")).toBeHidden();
  await expectViewportIntegrity(page);
});

test("Threads exposes active approval and failure states", async ({ page }) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "awaiting_approval",
      agentRun: { ...snapshot.agentRun, phase: "approval" },
    });
  });
  await page.getByRole("button", { name: "Threads", exact: true }).first().click();
  await expect(page.locator(".thread-row.active .thread-activity")).toHaveText("Needs approval");
  await expect(page.locator(".thread-row").filter({ hasText: "Review repository architecture" }).locator(".thread-unread")).toHaveText("2 new");

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "failed",
      agentRun: { ...snapshot.agentRun, phase: "failed" },
    });
  });
  await expect(page.locator(".thread-row.active .thread-activity")).toHaveText("Failed");
  await expectViewportIntegrity(page);
});
