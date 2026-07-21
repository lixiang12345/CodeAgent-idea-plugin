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
  await approval.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText("Waiting for user input")).toBeHidden();
});
