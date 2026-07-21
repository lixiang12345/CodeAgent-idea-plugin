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
  await expect(page.getByTitle("Send")).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "main-agent-workspace.png");
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
      tools: snapshot.tools.map((tool) => tool.id === "long-running-tool"
        ? { ...tool, status: "approval" as const, detail: `${tool.detail}\nApproval is now required.` }
        : tool),
    });
  });
  await expect.poll(() => conversation.evaluate((element) => element.scrollTop)).toBe(readingPosition);
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "long-conversation-navigation.png");

  await page.getByRole("button", { name: "Next request" }).click();
  await expect(page.getByText("2 / 12 requests", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Jump to latest" }).last().click();
  await expect.poll(() => conversation.evaluate((element) => element.scrollHeight - element.scrollTop - element.clientHeight)).toBeLessThanOrEqual(48);
  await expect(page.getByText("12 / 12 requests", { exact: true })).toBeVisible();
  await expect(page.locator(".conversation > .conversation-navigation .jump-latest")).toBeHidden();
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
