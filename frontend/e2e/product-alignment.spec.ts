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

/**
 * An unbroken token must never make a surface scroll sideways. Code blocks, markdown
 * tables and the chip strips opt into their own horizontal scroller; nothing else may.
 * Boxes that clip with `overflow: hidden` are truncating on purpose, not scrolling.
 */
async function expectNoSidewaysScroll(page: Page, context = ""): Promise<void> {
  const sideways = await page.evaluate(() => {
    const allowed = ".code-scroll, .markdown-body pre, .markdown-body table, .context-chips, .chips";
    return [...document.querySelectorAll<HTMLElement>("body *")]
      .filter((node) => node.scrollWidth > node.clientWidth + 1 && node.clientWidth > 0)
      .filter((node) => ["auto", "scroll"].includes(getComputedStyle(node).overflowX))
      .filter((node) => !node.matches(allowed))
      .map((node) => `${node.tagName.toLowerCase()}.${node.classList.value} ${node.scrollWidth}>${node.clientWidth}`);
  });
  expect(sideways, `sideways scroll in ${context || "the panel"}`).toEqual([]);
}

async function captureShell(page: Page, name: string, maxDiffPixelRatio?: number): Promise<void> {
  await expect(page.locator(".shell")).toHaveScreenshot(
    name,
    maxDiffPixelRatio === undefined ? {} : { maxDiffPixelRatio },
  );
}

async function openServices(page: Page): Promise<void> {
  await page.getByTitle("Settings", { exact: true }).click();
  const navigationToggle = page.getByRole("button", { name: "All settings", exact: true });
  if (await navigationToggle.isVisible()) await navigationToggle.click();
  await page.getByRole("button", { name: "Services", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Services", exact: true })).toBeVisible();
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
  await expect(page.getByPlaceholder("Instruct CodeAgent, @ for context, / for commands")).toBeVisible();
  await expect(page.getByRole("button", { name: "Send", exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "main-agent-workspace.png");
});

test("main workspace reserves saturated color for compact semantic signals", async ({ page }) => {
  const threadSurfaces = await page.evaluate(() => {
    function rgba(value: string): [number, number, number, number] | null {
      const match = value.match(/^rgba?\((\d+(?:\.\d+)?),\s*(\d+(?:\.\d+)?),\s*(\d+(?:\.\d+)?)(?:,\s*(\d+(?:\.\d+)?))?\)$/);
      return match
        ? [Number(match[1]), Number(match[2]), Number(match[3]), match[4] === undefined ? 1 : Number(match[4])]
        : null;
    }

    function resolvedBackground(selector: string): { selector: string; rgb: number[] } {
      let node = document.querySelector<HTMLElement>(selector);
      if (!node) throw new Error(`Missing surface ${selector}`);
      let resolved: [number, number, number, number] = [0, 0, 0, 0];
      while (node) {
        const current = rgba(getComputedStyle(node).backgroundColor);
        if (current && current[3] > 0) {
          const remaining = 1 - resolved[3];
          resolved = [
            resolved[0] + current[0] * current[3] * remaining,
            resolved[1] + current[1] * current[3] * remaining,
            resolved[2] + current[2] * current[3] * remaining,
            resolved[3] + current[3] * remaining,
          ];
          if (resolved[3] >= 0.999) break;
        }
        node = node.parentElement as HTMLElement | null;
      }
      return { selector, rgb: resolved.slice(0, 3).map((channel) => Math.round(channel)) };
    }

    return [
      ".agent-header",
      ".subagents-strip",
      ".user-message-content",
      ".composer-announcement",
      ".composer",
      ".mode-button",
      ".auto-toggle.active",
    ].map(resolvedBackground);
  });

  for (const surface of threadSurfaces) {
    expect(Math.max(...surface.rgb) - Math.min(...surface.rgb), `${surface.selector} uses ${surface.rgb.join(",")}`).toBeLessThanOrEqual(12);
  }

  await page.getByRole("tab", { name: /Tasks/ }).click();
  const runningTaskBackground = await page.locator(".task-workspace-row.running").evaluate((element) => {
    const value = getComputedStyle(element).backgroundColor;
    const match = value.match(/^rgba?\((\d+(?:\.\d+)?),\s*(\d+(?:\.\d+)?),\s*(\d+(?:\.\d+)?)/);
    return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : [];
  });
  expect(runningTaskBackground).toHaveLength(3);
  expect(Math.max(...runningTaskBackground) - Math.min(...runningTaskBackground)).toBeLessThanOrEqual(28);
  await expectViewportIntegrity(page);
});

test("a new Agent draft resets both the content and header title", async ({ page }) => {
  await page.getByRole("button", { name: "New Thread", exact: true }).click();
  await expect(page.locator(".agent-title")).toHaveText("New Agent");
  await expect(page.getByRole("heading", { name: "New Agent Thread", exact: true })).toBeVisible();
  await expect(page.locator(".subagents-strip")).toHaveCount(0);
  await expect(page.locator(".suggested-question")).toHaveCount(0);
  await expectViewportIntegrity(page);
});

test("empty threads mirror the original mode-specific panel card", async ({ page }, testInfo) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({ ...snapshot, mode: "agent", messages: [] });
  });

  const agentCard = page.locator('.empty-thread[data-mode="agent"] .empty-thread-card');
  await expect(agentCard.getByRole("heading", { name: "New Agent Thread", exact: true })).toBeVisible();
  await expect(agentCard.getByText("Work with your agent to use tools and make file edits.", { exact: true })).toBeVisible();
  await expect(page.getByText("Start a new task", { exact: true })).toBeHidden();
  await expect(page.getByText("Explain this project", { exact: true })).toBeHidden();

  const composer = page.getByPlaceholder("Instruct CodeAgent, @ for context, / for commands");
  await expect(composer).toBeVisible();
  const layout = await page.evaluate(() => {
    const card = document.querySelector(".empty-thread-card")?.getBoundingClientRect();
    const composerBox = document.querySelector(".composer")?.getBoundingClientRect();
    return { cardTop: card?.top, cardBottom: card?.bottom, composerTop: composerBox?.top };
  });
  expect(layout.cardTop).toBeDefined();
  expect(layout.cardBottom).toBeDefined();
  expect(layout.composerTop).toBeDefined();
  expect(layout.cardTop!).toBeLessThan(280);
  expect(layout.cardBottom!).toBeLessThan(layout.composerTop!);
  if (testInfo.project.name === "docked-640") {
    const cardWidth = await agentCard.evaluate((element) => element.getBoundingClientRect().width);
    expect(cardWidth).toBeGreaterThan(560);
  }
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "empty-agent-thread.png");

  await page.locator(".mode-button").click();
  await page.locator(".mode-menu button").filter({ hasText: "Chat Mode" }).click();
  const chatCard = page.locator('.empty-thread[data-mode="chat"] .empty-thread-card');
  await expect(chatCard.getByRole("heading", { name: "New Chat Thread", exact: true })).toBeVisible();
  await expect(chatCard.getByText("Ask questions and plan with codebase awareness.", { exact: true })).toBeVisible();
  await expect(composer).toBeVisible();
  await expectViewportIntegrity(page);
});

test("first-use coachmarks progress, persist, dismiss, and restart", async ({ page }) => {
  const composer = page.getByPlaceholder("Instruct CodeAgent, @ for context, / for commands");
  await expect(page.getByRole("dialog", { name: "Refine a draft before sending" })).toBeHidden();

  await composer.fill("Plan a focused implementation and verification pass");
  let coachmark = page.getByRole("dialog", { name: "Refine a draft before sending" });
  await expect(coachmark).toBeVisible();
  await expect(coachmark.getByText("Step 1 of 3", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Enhance prompt", exact: true })).toHaveClass(/coachmark-target/);

  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect.poll(() => coachmark.evaluate((element) => getComputedStyle(element).animationName)).toBe("none");
  await expect.poll(() => page.getByRole("button", { name: "Enhance prompt", exact: true }).evaluate((element) => getComputedStyle(element).animationName)).toBe("none");

  const next = coachmark.getByRole("button", { name: "Next", exact: true });
  await next.focus();
  await expect(next).toBeFocused();
  await page.keyboard.press("Enter");

  coachmark = page.getByRole("dialog", { name: "Keep multi-step work visible" });
  await expect(coachmark).toBeVisible();
  await expect(coachmark.getByText("Step 2 of 3", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "More options", exact: true })).toHaveClass(/coachmark-target/);
  await coachmark.getByRole("button", { name: "Back", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "Refine a draft before sending" })).toBeVisible();
  await page.getByRole("dialog", { name: "Refine a draft before sending" }).getByRole("button", { name: "Next", exact: true }).click();
  await page.getByRole("dialog", { name: "Keep multi-step work visible" }).getByRole("button", { name: "Next", exact: true }).click();

  coachmark = page.getByRole("dialog", { name: "Ground the Agent in repository rules" });
  await expect(coachmark).toBeVisible();
  await expect(coachmark.getByText("Step 3 of 3", { exact: true })).toBeVisible();
  await expect(page.getByTitle("Settings", { exact: true })).toHaveClass(/coachmark-target/);
  await coachmark.getByRole("button", { name: "Open Rules", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Rules & Guidelines", exact: true })).toBeVisible();
  await expect(page.getByRole("dialog", { name: "Ground the Agent in repository rules" })).toBeHidden();
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem("codeagent-preferences") ?? "{}").onboardingCoachmarks)).toBe("completed");

  await page.reload();
  await expect(page.locator(".shell")).toBeVisible();
  await page.waitForFunction(() => window.CodeAgentDevelopment?.getSnapshot()?.context.state === "ready");
  await page.getByPlaceholder("Instruct CodeAgent, @ for context, / for commands").fill("A second draft should not restart onboarding");
  await expect(page.locator(".product-coachmark")).toBeHidden();

  await page.getByTitle("Settings", { exact: true }).click();
  const navigationToggle = page.getByRole("button", { name: "All settings", exact: true });
  if (await navigationToggle.isVisible()) await navigationToggle.click();
  await page.getByRole("button", { name: "User Experience", exact: true }).click();
  await page.getByRole("button", { name: "Restart tour", exact: true }).click();
  coachmark = page.getByRole("dialog", { name: "Refine a draft before sending" });
  await expect(coachmark).toBeVisible();
  await coachmark.getByRole("button", { name: "Dismiss quick tour", exact: true }).focus();
  await page.keyboard.press("Escape");
  await expect(coachmark).toBeHidden();
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem("codeagent-preferences") ?? "{}").onboardingCoachmarks)).toBe("dismissed");

  await page.reload();
  await expect(page.locator(".shell")).toBeVisible();
  await page.waitForFunction(() => window.CodeAgentDevelopment?.getSnapshot()?.context.state === "ready");
  await page.getByPlaceholder("Instruct CodeAgent, @ for context, / for commands").fill("Dismissed onboarding stays dismissed");
  await expect(page.locator(".product-coachmark")).toBeHidden();
  await expectViewportIntegrity(page);
  await expectNoSidewaysScroll(page, "coachmark workflow");
});

test("Services groups cloud capabilities and distinguishes discovery states", async ({ page }, testInfo) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      backendToolDiscovery: { state: "ready", label: "5 of 7 capabilities available" },
      backendTools: [
        { name: "github_search", catalogId: "github", description: "Search and read GitHub resources", risk: "read_only", available: true, requiredEnvironment: ["GITHUB_TOKEN"] },
        { name: "github_manage", catalogId: "github", description: "Create comments and reviews", risk: "mutating", available: true, requiredEnvironment: ["GITHUB_TOKEN"] },
        { name: "github_actions_manage", catalogId: "github", description: "Control approved workflow runs", risk: "mutating", available: true, requiredEnvironment: ["GITHUB_TOKEN"] },
        { name: "notion_search", catalogId: "notion", description: "Search and read shared Notion pages", risk: "read_only", available: true, requiredEnvironment: ["NOTION_TOKEN"] },
        { name: "notion_manage", catalogId: "notion", description: "Create and update approved Notion pages", risk: "mutating", available: false, unavailableReason: "Integration needs insert-content permission", requiredEnvironment: ["NOTION_TOKEN"] },
        { name: "linear_search", catalogId: "linear", description: "Search Linear issues", risk: "read_only", available: false, unavailableReason: "Set LINEAR_API_KEY on the backend", requiredEnvironment: ["LINEAR_API_KEY"] },
        { name: "subagent", catalogId: "subagent", description: "Delegate a bounded model task", risk: "read_only", available: true, requiredEnvironment: ["MODEL"] },
      ],
    });
  });

  await openServices(page);
  await expect(page.getByText("5 of 7 capabilities available", { exact: true })).toBeVisible();
  await expect(page.locator('[data-provider="github"]')).toHaveAttribute("data-state", "ready");
  await expect(page.locator('[data-provider="notion"]')).toHaveAttribute("data-state", "partial");
  await expect(page.locator('[data-provider="linear"]')).toHaveAttribute("data-state", "unavailable");
  await expect(page.locator('[data-provider="notion"] .backend-provider-state')).toHaveText("Partial");

  const notionSummary = page.locator('[data-provider="notion"] .backend-provider-summary');
  await notionSummary.focus();
  await expect(notionSummary).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(notionSummary).toHaveAttribute("aria-expanded", "true");
  const notionProvider = page.locator('[data-provider="notion"]');
  await expect(notionProvider.getByText("notion_search", { exact: true })).toBeVisible();
  await expect(notionProvider.getByText("notion_manage", { exact: true })).toBeVisible();
  await expect(notionProvider.getByText("Approval required", { exact: true })).toBeVisible();
  await expect(notionProvider.getByText("Integration needs insert-content permission", { exact: true })).toBeVisible();
  await expect(notionProvider.getByText("Requires NOTION_TOKEN", { exact: true })).toBeVisible();
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "services-cloud-discovery.png");

  // The loading state is asserted from an explicit snapshot: the development host
  // holds it for 220 ms, which a loaded machine can outrun between assertions.
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      backendToolDiscovery: { state: "loading", label: "Refreshing backend tools" },
    });
  });
  await expect(page.getByRole("status").filter({ hasText: "Checking backend capabilities" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh backend tools", exact: true })).toBeDisabled();

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      backendToolDiscovery: { state: "ready", label: "5 of 7 capabilities available" },
    });
  });
  const refresh = page.getByRole("button", { name: "Refresh backend tools", exact: true });
  await expect(refresh).toBeEnabled();
  await refresh.click();
  await expect(page.getByText("5 of 7 capabilities available", { exact: true })).toBeVisible();

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      backendToolDiscovery: { state: "error", label: "HTTP 503 from /v1/tools" },
    });
  });
  await expect(page.getByRole("alert")).toContainText("HTTP 503 from /v1/tools");
  const retry = page.getByRole("button", { name: "Retry backend tool discovery", exact: true });
  await expect(retry).toBeEnabled();
  await retry.click();
  // Retrying leaves the error state and settles back into a labelled ready row.
  await expect(page.getByRole("button", { name: "Refresh backend tools", exact: true })).toBeEnabled();
  await expect(page.getByRole("alert")).toHaveCount(0);

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      backendToolDiscovery: { state: "ready", label: "Backend reported no tool capabilities" },
      backendTools: [],
    });
  });
  await expect(page.getByText("No capabilities reported", { exact: true })).toBeVisible();
  await expect(page.getByText("Tool discovery failed", { exact: true })).toBeHidden();

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      backendToolDiscovery: { state: "unavailable", label: "Sign in to discover backend tools" },
    });
  });
  const unavailableStatus = page.getByRole("status").filter({ hasText: "Backend tools unavailable" });
  await expect(unavailableStatus).toBeVisible();
  await expect(unavailableStatus.getByText("Sign in to discover backend tools", { exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
});

test("Context Window Usage exposes live telemetry in a bounded modal", async ({ page }, testInfo) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      agentRun: {
        ...snapshot.agentRun,
        estimatedInputTokens: 44_000,
        toolDefinitionTokens: 16_000,
        assistantResponseTokens: 4_000,
        contextWindowTokens: 128_000,
        targetInputTokens: 86_400,
        reservedOutputTokens: 8_000,
        retrievalBudgetTokens: 12_000,
        compactedToolResults: 3,
        truncatedMessages: 6,
        activeToolCount: 2,
        discoverableToolCount: 11,
        catalogToolCount: 12,
      },
    });
  });

  const contextButton = page.getByRole("button", { name: "Context Window Usage", exact: true });
  await expect(contextButton).toBeVisible();
  await expect(contextButton).toHaveAttribute("data-usage-percent", "50");
  await expect(contextButton).toHaveAttribute("title", "Context Window Usage · 64.0k / 128.0k tokens");
  await expect(contextButton.locator(".context-usage-ring-fill.warning")).toHaveCount(1);
  await contextButton.click();

  const dialog = page.getByRole("dialog", { name: "Context Window Usage" });
  await expect(dialog).toBeVisible();
  const closeButton = page.getByRole("button", { name: "Close", exact: true });
  await expect(closeButton).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(dialog.locator(".context-usage-section-header").last()).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(closeButton).toBeFocused();
  await expect(dialog.getByText("64.0k / 128.0k tokens", { exact: true })).toBeVisible();
  await expect(dialog.getByText("Input / History Estimate", { exact: true }).first()).toBeVisible();
  await expect(dialog.getByText("Tool Definitions", { exact: true }).first()).toBeVisible();
  await expect(dialog.getByText("Assistant Response", { exact: true }).first()).toBeVisible();
  await expect(dialog.getByText("102.4k tokens", { exact: true })).toBeVisible();
  await expect(dialog.getByText("8.0k tokens", { exact: true })).toBeVisible();
  await expect(dialog.getByText("12.0k tokens", { exact: true })).toBeVisible();
  await expect(dialog.getByText("Compacted tool results", { exact: true })).toBeVisible();
  await expect(dialog.getByText("Truncated messages", { exact: true })).toBeVisible();
  await dialog.getByRole("button", { name: "Show Tool Definitions details", exact: true }).click();
  await expect(dialog.getByText("Tools ready", { exact: true })).toBeVisible();
  await expect(dialog.getByText("Discoverable tools", { exact: true })).toBeVisible();
  await expect(dialog.getByText("Catalog tools", { exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "context-window-usage.png");

  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(contextButton).toBeFocused();

  const composer = page.getByPlaceholder("Instruct CodeAgent, @ for context, / for commands");
  const messageCount = await page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messages.length);
  await composer.fill("Do not submit behind the context modal");
  await contextButton.click();
  await page.keyboard.press("Control+Enter");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messages.length)).toBe(messageCount);
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.runState)).toBe("idle");
  await page.keyboard.press("Escape");
  await composer.fill("");

  await contextButton.click();
  await closeButton.click();
  await expect(dialog).toBeHidden();

  await contextButton.click();
  await page.getByRole("button", { name: "Close context usage", exact: true }).click({ position: { x: 2, y: 2 } });
  await expect(dialog).toBeHidden();

  await contextButton.click();
  await dialog.getByRole("button", { name: "Show Tool Definitions details", exact: true }).focus();
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      agentRun: {
        ...snapshot.agentRun,
        estimatedInputTokens: 0,
        toolDefinitionTokens: 0,
        assistantResponseTokens: 0,
        contextWindowTokens: 0,
      },
    });
  });
  await expect(contextButton.locator(".context-usage-ring-fill")).toHaveCount(0);
  await expect(dialog.getByText("No context usage data available. Send a message to see context usage.", { exact: true })).toBeVisible();
  await expect(closeButton).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(closeButton).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      agentRun: {
        ...snapshot.agentRun,
        estimatedInputTokens: 44_000,
        toolDefinitionTokens: 16_000,
        assistantResponseTokens: 4_000,
        contextWindowTokens: 128_000,
        compactedToolResults: 3,
        truncatedMessages: 6,
        compactionApplied: true,
      },
      threads: snapshot.threads.map((thread, index) => ({ ...thread, active: index === 1 })),
    });
  });
  await expect(contextButton.locator(".context-usage-ring-fill")).toHaveCount(1);
  await expect(page.locator(".context-compaction-strip")).toBeHidden();
  await page.getByRole("button", { name: "New Thread", exact: true }).first().click();
  await expect(contextButton.locator(".context-usage-ring-fill")).toHaveCount(0);
  await expectViewportIntegrity(page);
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

  const composer = page.getByPlaceholder("Instruct CodeAgent, @ for context, / for commands");
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

  await expect(page.locator(".user-message-content").filter({ hasText: /^Inspect and replace the authentication boundary\.$/ })).toBeVisible();
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

test("Message Queue supports pause, edit, priority send, stop, and resume", async ({ page }, testInfo) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "running",
      agentRun: { ...snapshot.agentRun, phase: "tools", activeToolNames: ["read_file"], activeToolCount: 1 },
      messageQueue: [
        { id: "queue-first", text: "Run the focused queue tests.", mode: "agent" },
        { id: "queue-second", text: "Review the queued test output.", mode: "ask" },
      ],
      messageQueuePaused: false,
    });
  });

  const panel = page.locator(".message-queue-panel");
  const composer = page.getByPlaceholder("Instruct CodeAgent, @ for context, / for commands");
  await expect(panel).toBeVisible();
  await expect(panel.getByText("2 Queued", { exact: true })).toBeVisible();
  await expect(page.locator(".conversation").getByText("Run the focused queue tests.", { exact: true })).toHaveCount(0);

  await panel.getByRole("button", { name: "Collapse message queue" }).click();
  await expect(panel.getByText("Run the focused queue tests.", { exact: true })).toBeVisible();
  await expect(panel.getByText("Review the queued test output.", { exact: true })).toBeHidden();
  await panel.getByRole("button", { name: "Expand message queue" }).click();

  await panel.getByRole("button", { name: "Pause queue" }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueuePaused)).toBe(true);
  await expect(panel.getByText("2 Queued (Paused)", { exact: true })).toBeVisible();
  await panel.getByRole("button", { name: "Resume queue" }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueuePaused)).toBe(false);

  const firstItem = panel.locator(".message-queue-item").filter({ hasText: "Run the focused queue tests." });
  await firstItem.getByRole("button", { name: "Edit queued message" }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueuePaused)).toBe(true);
  const queueEditor = page.getByPlaceholder("Edit queued message...");
  await expect(queueEditor).toHaveValue("Run the focused queue tests.");
  await queueEditor.press("Escape");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueuePaused)).toBe(false);
  await expect(composer).toBeVisible();

  await firstItem.getByRole("button", { name: "Edit queued message" }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueuePaused)).toBe(true);
  await queueEditor.fill("Run the focused queue tests and record evidence.");
  await queueEditor.press("Enter");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueue[0]?.text)).toBe("Run the focused queue tests and record evidence.");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueuePaused)).toBe(false);
  await panel.locator(".message-queue-item").filter({ hasText: "Review the queued test output." }).getByRole("button", { name: "Delete queued message" }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueue.length)).toBe(1);

  await panel.getByRole("button", { name: "Send queued message now" }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messages.at(-1)?.content)).toBe("Run the focused queue tests and record evidence.");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.runState)).toBe("running");

  await composer.fill("Run the first deferred check.");
  await composer.press("Enter");
  await composer.fill("Run the second deferred check.");
  await composer.press("Enter");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueue.length)).toBe(2);
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.runState)).toBe("idle");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueuePaused)).toBe(true);
  await expect(panel.getByText("2 Queued (Paused)", { exact: true })).toBeVisible();
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "message-queue-lifecycle.png");

  await panel.getByRole("button", { name: "Resume queue" }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messages.at(-1)?.content)).toBe("Run the first deferred check.");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueue.map((message) => message.text))).toEqual(["Run the second deferred check."]);

  await composer.fill("Run this priority check now.");
  await composer.press("Meta+Enter");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messages.at(-1)?.content)).toBe("Run this priority check now.");
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.messageQueue.map((message) => message.text))).toEqual(["Run the second deferred check."]);
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

test("Threads groups history and task lists continue in a fresh chat", async ({ page }, testInfo) => {
  const fixture = await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    const now = Date.now();
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);
    yesterday.setHours(12, 0, 0, 0);
    const threads = [
      { ...snapshot.threads[0], id: "group-pinned", title: "Pinned architecture plan", updatedAt: now - 5_000, active: true, pinned: true },
      { ...snapshot.threads[1], id: "group-today", title: "Today authentication review", updatedAt: now - 60_000, active: false, pinned: false },
      { ...snapshot.threads[2], id: "group-yesterday", title: "Yesterday integration check", updatedAt: yesterday.getTime(), active: false, pinned: false },
      { id: "group-older-1", title: "Older migration plan", updatedAt: now - 10 * 24 * 60 * 60 * 1000, active: false, mode: "agent" as const, pinned: false },
      { id: "group-older-2", title: "Older release notes", updatedAt: now - 12 * 24 * 60 * 60 * 1000, active: false, mode: "chat" as const, pinned: false },
    ];
    window.CodeAgentDevelopment.setSnapshot({ ...snapshot, threads });
    return { sourceThreadId: threads[0].id, sourceTaskIds: snapshot.tasks.map((task) => task.id), sourceThreadCount: threads.length };
  });

  await page.getByRole("button", { name: "Threads", exact: true }).first().click();
  for (const label of ["Pinned", "Today", "Yesterday", "Older"]) {
    await expect(page.locator(".thread-group-header").filter({ hasText: label })).toBeVisible();
  }

  const todayRow = page.locator(".thread-row").filter({ hasText: "Today authentication review" });
  await todayRow.hover();
  await todayRow.getByRole("button", { name: "Thread actions for Today authentication review" }).click();
  await todayRow.getByRole("button", { name: "Rename", exact: true }).click();
  await page.getByRole("textbox", { name: "Rename thread" }).fill("Renamed authentication review");
  await page.getByRole("button", { name: "Save thread name" }).click();
  await expect(page.getByText("Renamed authentication review", { exact: true })).toBeVisible();

  const yesterdayRow = page.locator(".thread-row").filter({ hasText: "Yesterday integration check" });
  await yesterdayRow.hover();
  await yesterdayRow.getByRole("button", { name: "Thread actions for Yesterday integration check" }).click();
  await yesterdayRow.getByRole("button", { name: "Delete", exact: true }).click();
  await expect(page.getByText("Yesterday integration check", { exact: true })).toBeVisible();
  await yesterdayRow.getByRole("button", { name: "Confirm delete", exact: true }).click();
  await expect(page.getByText("Yesterday integration check", { exact: true })).toBeHidden();

  const olderGroup = page.locator(".thread-group-header").filter({ hasText: "Older" });
  await olderGroup.getByRole("button", { name: "Clear", exact: true }).click();
  await expect(page.getByText("Older migration plan", { exact: true })).toBeVisible();
  await olderGroup.getByRole("button", { name: "Confirm clear", exact: true }).click();
  await expect(page.getByText("Older migration plan", { exact: true })).toBeHidden();
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "threads-advanced-management.png");

  await page.getByRole("button", { name: "Close", exact: true }).click();
  await page.getByRole("tab", { name: /Tasks/ }).click();
  await page.getByRole("button", { name: "Continue in New Chat", exact: true }).click();
  const continued = await page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot());
  expect(continued?.threads).toHaveLength(fixture.sourceThreadCount - 2);
  expect(continued?.threads.some((thread) => thread.id === fixture.sourceThreadId && !thread.active)).toBe(true);
  expect(continued?.messages).toEqual([]);
  expect(continued?.tools).toEqual([]);
  expect(continued?.tasks.map((task) => task.name)).toEqual([
    "Inspect the existing authentication flow",
    "Implement JWT token issuance",
    "Add invalid-credential regression coverage",
    "Run the complete integration suite",
  ]);
  expect(continued?.tasks.every((task) => !fixture.sourceTaskIds.includes(task.id))).toBe(true);
  await expectViewportIntegrity(page);
});

test("Thread, Tasks, and Edits are keyboard-accessible primary workspaces", async ({ page }, testInfo) => {
  requireReferenceViewport(testInfo);
  const threadTab = page.getByRole("tab", { name: "Thread", exact: true });
  const tasksTab = page.getByRole("tab", { name: /Tasks/ });
  const editsTab = page.getByRole("tab", { name: /Edits/ });
  const composer = page.locator(".composer");

  await expect(threadTab).toHaveAttribute("aria-selected", "true");
  await expect(composer).toBeVisible();
  await expect(page.locator(".change-summary, .edits-bar")).toHaveCount(0);

  await editsTab.click();
  await expect(editsTab).toHaveAttribute("aria-selected", "true");
  await expect(composer).toBeHidden();
  await expect(page.getByRole("button", { name: "Keep All" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Discard All" })).toBeVisible();

  // Checkpoints expose a per-checkpoint file breakdown, mirroring the original
  // c-checkpoint-collapsible anatomy. The Agent Edits view lists checkpoints on
  // open; expanding one reveals the files captured in that checkpoint.
  const checkpointRow = page.locator(".checkpoint-row").first();
  if (await checkpointRow.count()) {
    const toggle = checkpointRow.locator(".checkpoint-toggle");
    if (await toggle.isEnabled()) {
      await toggle.click();
      await expect(page.locator(".checkpoint-files li").first()).toBeVisible();
    }
  }
  await expectViewportIntegrity(page);
  await captureShell(page, "agent-edits.png");

  await editsTab.focus();
  await page.keyboard.press("ArrowLeft");
  await expect(tasksTab).toBeFocused();
  await expect(tasksTab).toHaveAttribute("aria-selected", "true");
  await expect(composer).toBeHidden();
  await expect(page.getByText("Add invalid-credential regression coverage", { exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "active-tasklist.png");

  await page.keyboard.press("Home");
  await expect(threadTab).toBeFocused();
  await expect(composer).toBeVisible();
});

test("authenticated capability switches control the interface independently of user tier", async ({ page }) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment || !snapshot.capabilities) throw new Error("Development capabilities are unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      capabilities: {
        ...snapshot.capabilities,
        userTier: "enterprise",
        enableAgentMode: false,
        enableAgentAutoMode: false,
        enableAgentTabs: false,
        enableCustomCommands: false,
        enableSkills: false,
        enableMessageQueue: false,
        enableContextWindowUsage: false,
        enableContextUsageModal: false,
      },
    });
  });

  await expect(page.getByRole("tab", { name: "Thread", exact: true })).toBeVisible();
  await expect(page.getByRole("tab", { name: /Tasks|Edits/ })).toHaveCount(0);
  await expect(page.getByTitle("Slash commands")).toHaveCount(0);
  await expect(page.getByTitle("Skills")).toHaveCount(0);
  await expect(page.getByTitle(/Auto (ON|OFF)/)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Context Window Usage" })).toHaveCount(0);
  await expect(page.locator(".message-queue-panel, .composer-announcement")).toHaveCount(0);
  await expect(page.getByPlaceholder("Instruct CodeAgent, @ for context")).toBeVisible();
  await expectViewportIntegrity(page);
});

test("Settings exposes connected and conditional capabilities", async ({ page }, testInfo) => {
  requireReferenceViewport(testInfo);
  await page.getByTitle("Settings").click();
  await expect(page.getByText("Project Home", { exact: true })).toBeVisible();
  await expect(page.getByText("Codebase Index", { exact: true })).toBeVisible();
  await expect(page.getByText("Chunks", { exact: true })).toBeVisible();
  await expect(page.getByText("Roots watched", { exact: true })).toBeVisible();
  await page.getByTitle("Refresh index status").click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.context.state)).toBe("ready");
  const lastIndexedAt = await page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.context.lastIndexedAt);
  await page.waitForTimeout(20);
  await page.getByRole("button", { name: "Rebuild index", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.context.lastIndexedAt)).not.toBe(lastIndexedAt);
  await expectViewportIntegrity(page);
  await captureShell(page, "settings-home.png");
  await page.getByRole("button", { name: "All settings" }).click();
  await page.getByRole("button", { name: "MCP Servers", exact: true }).click();
  await expect(page.getByRole("heading", { name: "MCP Servers", exact: true })).toBeVisible();
  await expect(page.getByText("Local Context MCP", { exact: true }).first()).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "mcp-settings.png");
  await page.getByRole("button", { name: "All settings" }).click();
  await page.getByRole("button", { name: "API Keys", exact: true }).click();
  await expect(page.getByText("Provider Keys", { exact: true })).toBeVisible();
  const anthropic = page.locator(".byok-provider-row").filter({ hasText: "Anthropic" });
  await anthropic.getByRole("button", { name: "Add key", exact: true }).click();
  await page.getByLabel("Anthropic API key").fill("sk-ant-development-only");
  await page.getByLabel("Anthropic Base URL").fill("https://anthropic.example.test");
  await page.getByLabel("Anthropic API key").press("Escape");
  await expect(page.getByLabel("Anthropic API key")).toBeHidden();
  await anthropic.getByRole("button", { name: "Add key", exact: true }).click();
  await page.getByLabel("Anthropic API key").fill("sk-ant-development-only");
  await page.getByLabel("Anthropic Base URL").fill("https://anthropic.example.test");
  await page.getByRole("button", { name: "Save securely", exact: true }).click();
  await expect(anthropic.getByText("https://anthropic.example.test", { exact: true })).toBeVisible();
  await expect(page.getByText("2 configured", { exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  await captureShell(page, "api-keys-settings.png");
});

test("Rules editor validates Markdown and protects unsaved changes", async ({ page }) => {
  await page.getByTitle("Settings").click();
  const allSettings = page.getByRole("button", { name: "All settings" });
  if (await allSettings.isVisible()) await allSettings.click();
  await page.getByRole("button", { name: "Rules & Guidelines", exact: true }).click();
  await page.getByRole("button", { name: "New Rule", exact: true }).click();
  const fileName = page.getByLabel("File name");
  const content = page.getByLabel("Rule content");
  await fileName.fill("invalid.txt");
  await page.getByRole("button", { name: "Save rule", exact: true }).click();
  await expect(page.getByRole("alert")).toHaveText(/Markdown filename/);
  await fileName.fill("review.md");
  await content.fill("# Review guidance\n\nInspect the changed path before approving.");
  await page.getByTitle("Back to rules").click();
  await expect(page.getByRole("alertdialog", { name: "Discard unsaved rule changes" })).toBeVisible();
  await page.getByRole("button", { name: "Keep editing", exact: true }).click();
  await expect(content).toHaveValue(/Review guidance/);
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await page.getByRole("button", { name: "Discard", exact: true }).click();
  await expect(page.getByRole("button", { name: "New Rule", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Refresh rules" })).toBeVisible();
  const testingRule = page.locator(".rule-list > div").filter({ hasText: "Testing" });
  await testingRule.getByTitle("Delete rule").click();
  await expect(testingRule).toBeVisible();
  await testingRule.getByTitle("Confirm delete rule").click();
  await expect(testingRule).toBeHidden();
  const guidelines = page.getByRole("textbox", { name: "Workspace guidelines" });
  const openGuidelines = page.getByRole("button", { name: "Open in editor", exact: true });
  await expect(guidelines).toHaveValue(/focused changes/);
  await expect(openGuidelines).toBeEnabled();
  await openGuidelines.click();
  await guidelines.fill("# Workspace review\n\nRequire focused verification before finalizing changes.");
  await expect(openGuidelines).toBeDisabled();
  await page.getByRole("button", { name: "Reset", exact: true }).click();
  await expect(guidelines).toHaveValue(/focused changes/);
  await expect(openGuidelines).toBeEnabled();
  await guidelines.fill("# Workspace review\n\nRequire focused verification before finalizing changes.");
  await page.getByRole("button", { name: "Save guidelines", exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot()?.customization.guidelines)).toContain("Require focused verification");
  await expectViewportIntegrity(page);
});

test("Memories exposes stored summaries and clears them without deleting threads", async ({ page }, testInfo) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      threads: snapshot.threads.map((thread, index) => index < 2 ? {
        ...thread,
        summary: index === 0
          ? "JWT authentication was implemented with focused regression coverage and native Diff review remaining."
          : "Repository architecture was reviewed and the main runtime boundaries were recorded.",
        messageCount: index === 0 ? 12 : 6,
      } : thread),
    });
  });
  await page.getByTitle("Settings").click();
  const allSettings = page.getByRole("button", { name: "All settings" });
  if (await allSettings.isVisible()) await allSettings.click();
  await page.getByRole("button", { name: "Memories", exact: true }).click();
  await expect(page.getByText("2 saved", { exact: true })).toBeVisible();
  const firstMemory = page.locator(".memory-summary-row").filter({ hasText: "Implement login flow with JWT" });
  await expect(firstMemory.getByText("12 messages · agent", { exact: true })).toBeVisible();
  await firstMemory.getByTitle("Clear summary").click();
  await expect(firstMemory).toBeVisible();
  await firstMemory.getByTitle("Confirm clear summary").click();
  await expect(firstMemory).toBeHidden();
  await expect(page.getByText("1 saved", { exact: true })).toBeVisible();
  await expect(page.getByText("Review repository architecture", { exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
  if (testInfo.project.name === "tool-window-420") await captureShell(page, "memory-management.png");
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
  const approve = approval.getByRole("button", { name: "Approve" });
  await expect(approve).toBeVisible();
  const navigation = page.locator(".conversation > .conversation-navigation");
  await expect(navigation).toBeVisible();
  await page.locator(".conversation").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  const overlap = await Promise.all([approve.boundingBox(), navigation.boundingBox()]).then(([button, nav]) => {
    if (!button || !nav) return true;
    return button.x < nav.x + nav.width && button.x + button.width > nav.x
      && button.y < nav.y + nav.height && button.y + button.height > nav.y;
  });
  expect(overlap, "sticky request navigation must not cover the pointer approval target").toBe(false);
  await expectViewportIntegrity(page);
  await captureShell(page, "tool-approval.png");
  await page.getByRole("button", { name: "Generation status" }).click();
  await expect(page.getByRole("button", { name: "Stop generation" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Jump to latest" })).toBeVisible();
  await page.getByRole("button", { name: "Generation status" }).click();
  await approve.click();
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

  // The completed tool pass appends the original plugin's per-turn summary strip
  // (c-turn-summary): distinct changed/examined files plus total tools used.
  const turnSummary = page.locator(".turn-summary");
  await expect(turnSummary).toHaveCount(1);
  await expect(turnSummary.locator(".turn-summary-item").filter({ hasText: "File Changed" })).toBeVisible();
  await expect(turnSummary.locator(".turn-summary-item").filter({ hasText: "File Examined" })).toBeVisible();
  await expect(turnSummary.locator(".turn-summary-item").filter({ hasText: "Tools Used" })).toContainText("9");

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
      messageQueuePaused: false,
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
  if (testInfo.project.name === "tool-window-420") {
    // This text-heavy capture needs a slightly larger macOS/Linux glyph budget.
    await captureShell(page, "long-conversation-navigation.png", 0.06);
  }

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

test("ask_user card stays bounded and drives selection, text, submit, and skip", async ({ page }) => {
  // A single unbroken token that would overflow a narrow tool window unless the
  // option label wraps (overflow-wrap: anywhere on .ask-opt-label).
  const LONG_OPTION = "AdoptTheConsolidatedAuthenticationAndAuthorizationPipelineWithRotatingRefreshTokensEverywhere";
  await page.evaluate((longOption) => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "awaiting_approval",
      agentRun: { ...snapshot.agentRun, phase: "approval" },
      tools: [
        ...snapshot.tools,
        {
          id: "e2e-ask",
          name: "ask_user",
          summary: "Which authentication strategy should we adopt?",
          status: "approval",
          askQuestion: "Which authentication strategy should we adopt?",
          askOptions: [longOption, "Keep the existing session cookies"],
          askAllowText: true,
          canRevert: false,
          timelineSequence: 12,
        },
      ],
    });
  }, LONG_OPTION);

  const card = page.getByRole("group", { name: "Question from Agent" });
  await expect(card).toBeVisible();

  // The long unbroken option must not push the layout past the viewport.
  await expectViewportIntegrity(page);
  const overflow = await card.evaluate((node) => node.scrollWidth - node.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);

  const submit = card.getByRole("button", { name: "Submit answer" });
  const skip = card.getByRole("button", { name: "Skip" });

  // Disabled with neither a selection nor typed text.
  await expect(submit).toBeDisabled();
  await expect(skip).toBeEnabled();

  // Typed text alone enables submission.
  const details = card.getByRole("textbox", { name: "Answer details" });
  await details.fill("Prefer short-lived tokens.");
  await expect(submit).toBeEnabled();
  await details.fill("");
  await expect(submit).toBeDisabled();

  // Selecting an option enables submission and marks the chosen option.
  const chosen = card.getByRole("button", { name: LONG_OPTION });
  const unchosen = card.getByRole("button", { name: "Keep the existing session cookies" });
  await expect(chosen).toHaveAttribute("aria-pressed", "false");
  await chosen.focus();
  await chosen.press("Enter");
  await expect(chosen).toHaveClass(/\bon\b/);
  await expect(chosen).toHaveAttribute("aria-pressed", "true");
  await expect(unchosen).toHaveAttribute("aria-pressed", "false");
  await expect(submit).toBeEnabled();

  // Submitting resolves the question through resolveAskUser.
  await submit.click();
  await expect(card).toBeHidden();
  const answered = await page.evaluate(() =>
    window.CodeAgentDevelopment?.getSnapshot()?.tools.find((tool) => tool.id === "e2e-ask")?.status);
  expect(answered).toBe("completed");

  // Re-seed a fresh question to exercise the Skip path.
  await page.evaluate((longOption) => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "awaiting_approval",
      agentRun: { ...snapshot.agentRun, phase: "approval" },
      tools: snapshot.tools.map((tool) => tool.id === "e2e-ask"
        ? { ...tool, status: "approval", askQuestion: "Which authentication strategy should we adopt?", askOptions: [longOption, "Keep the existing session cookies"], askAllowText: true }
        : tool),
    });
  }, LONG_OPTION);
  const reopened = page.getByRole("group", { name: "Question from Agent" });
  await reopened.getByRole("button", { name: "Skip" }).click();
  await expect(reopened).toBeHidden();
  const skipped = await page.evaluate(() =>
    window.CodeAgentDevelopment?.getSnapshot()?.tools.find((tool) => tool.id === "e2e-ask")?.status);
  expect(skipped).toBe("rejected");

  // Under prefers-reduced-motion the settled tool card must not animate.
  await page.emulateMedia({ reducedMotion: "reduce" });
  const animationName = await page.locator(".tool-card").first().evaluate((node) =>
    getComputedStyle(node).animationName);
  expect(animationName).toBe("none");
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

test("server notifications render, act, and dismiss without claiming an unavailable surface works", async ({ page }) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      notifications: [
        {
          id: "maintenance-window",
          level: "warning",
          message: "Scheduled backend maintenance tonight. Agent runs may be interrupted.",
          actionItems: [{ title: "Maintenance notice", url: "https://status.example.com/codeagent" }],
        },
        { id: "catalog-refresh", level: "info", message: "Two new models are available in the model picker.", actionItems: [] },
      ],
    });
  });

  const banners = page.locator(".panel-banner");
  await expect(banners).toHaveCount(2);
  await expect(banners.first()).toHaveClass(/warning/);
  await expect(banners.first().getByRole("button", { name: "Maintenance notice" })).toBeVisible();
  await expectViewportIntegrity(page);

  await banners.nth(1).getByRole("button", { name: "Dismiss notification: Two new models are available in the model picker." }).click();
  await expect(banners).toHaveCount(1);
  await expect(page.getByText("Two new models are available in the model picker.")).toHaveCount(0);

  // An unconfigured deployment must disable the affordance and show the backend's reason, not fake a success.
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      sharing: { state: "unavailable", reason: "Shareable links are not configured on this deployment" },
    });
  });
  await page.getByTitle("More options").click();
  const shareButton = page.locator(".workspace-menu").getByRole("button", { name: "Share this conversation" });
  await expect(shareButton).toBeDisabled();
  await expect(shareButton).toHaveAttribute("title", "Shareable links are not configured on this deployment");
});

test("subscription reports quotas it counted and stays explicit when unconfigured", async ({ page }) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      account: {
        ...snapshot.account,
        state: "signed_in",
        subscription: { state: "unknown", quotas: [], reason: "Subscription plans are not configured on this deployment" },
      },
    });
  });
  await page.getByTitle("Settings").click();
  const allSettings = page.getByRole("button", { name: "All settings" });
  if (await allSettings.isVisible()) await allSettings.click();
  await page.getByRole("button", { name: "Subscription", exact: true }).click();
  await expect(page.getByText("Plan and quotas unavailable")).toBeVisible();
  await expect(page.getByText("Subscription plans are not configured on this deployment")).toBeVisible();
  await expect(page.locator(".quota-row")).toHaveCount(0);

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      account: {
        ...snapshot.account,
        subscription: {
          state: "approaching",
          plan: "team",
          label: "Team",
          manageUrl: "https://billing.example.com/codeagent",
          quotas: [
            { kind: "agent_run", used: 4200, limit: 5000, remaining: 800, ratio: 0.84, state: "approaching" },
            { kind: "completion", used: 10, limit: 1000, remaining: 990, ratio: 0.01, state: "ok" },
          ],
          warning: { level: "warning", kind: "agent_run", message: "You have used 4200 of 5000 agent_run units included in the Team plan." },
        },
      },
    });
  });
  await expect(page.locator(".quota-row")).toHaveCount(2);
  await expect(page.locator(".quota-row").first()).toHaveClass(/approaching/);
  await expect(page.getByRole("button", { name: "Manage", exact: true })).toBeVisible();
  await expectViewportIntegrity(page);
});

test("the pre-chat gate replaces the empty card until the panel can answer", async ({ page }) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      messages: [],
      account: { ...snapshot.account, state: "signed_out", label: "Sign in to sync Agent sessions" },
    });
  });
  await expect(page.getByRole("heading", { name: "Sign in to start" })).toBeVisible();
  await expect(page.getByText("Sign in to sync Agent sessions")).toBeVisible();
  await expect(page.locator(".suggested-question")).toHaveCount(0);
  await expectViewportIntegrity(page);

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      account: { ...snapshot.account, state: "signed_in" },
      context: { ...snapshot.context, state: "indexing", label: "Indexing 240 files" },
    });
  });
  await expect(page.getByRole("heading", { name: "Indexing this project" })).toBeVisible();
  await expect(page.locator(".gate-card p")).toHaveText("Indexing 240 files");

  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      context: { ...snapshot.context, state: "ready" },
    });
  });
  // Once the panel can answer, the ordinary empty-thread card returns.
  await expect(page.getByRole("heading", { name: /New (Agent|Chat|Ask) Thread/ })).toBeVisible();
});

test("an oversized paste is refused instead of silently sent", async ({ page }) => {
  const composer = page.locator(".composer textarea");
  await composer.click();

  await page.evaluate(() => {
    const textarea = document.querySelector<HTMLTextAreaElement>(".composer textarea");
    if (!textarea) throw new Error("Composer is unavailable");
    const transfer = new DataTransfer();
    transfer.setData("text/plain", "x".repeat(200_001));
    textarea.dispatchEvent(new ClipboardEvent("paste", { clipboardData: transfer, bubbles: true, cancelable: true }));
  });
  await expect(page.locator(".composer-notice.error")).toContainText("over the 200 KB composer limit");
  await expect(composer).toHaveValue("");

  await page.locator(".composer-notice").getByRole("button", { name: "Dismiss composer notice" }).click();
  await expect(page.locator(".composer-notice")).toHaveCount(0);

  // A paste large enough to be worth attaching warns but is still allowed through.
  await page.evaluate(() => {
    const textarea = document.querySelector<HTMLTextAreaElement>(".composer textarea");
    if (!textarea) throw new Error("Composer is unavailable");
    const transfer = new DataTransfer();
    transfer.setData("text/plain", "y".repeat(20_001));
    textarea.dispatchEvent(new ClipboardEvent("paste", { clipboardData: transfer, bubbles: true, cancelable: true }));
  });
  await expect(page.locator(".composer-notice")).toContainText("Attaching the file keeps more of it in context");
  await expectViewportIntegrity(page);
});

test("dropping project files attaches them and a pathless drop says why it cannot", async ({ page }) => {
  await page.evaluate(() => {
    const composer = document.querySelector(".composer");
    if (!composer) throw new Error("Composer is unavailable");
    const transfer = new DataTransfer();
    transfer.setData("text/uri-list", "file:///project/src/main.ts\nfile:///project/docs/guide.md");
    composer.dispatchEvent(new DragEvent("drop", { dataTransfer: transfer, bubbles: true, cancelable: true }));
  });
  await expect(page.locator(".composer-notice")).toContainText("Attaching 2 files");
  await expect(page.locator(".context-chips .chip").filter({ hasText: "main.ts" })).toBeVisible();
  await expect(page.locator(".context-chips .chip").filter({ hasText: "guide.md" })).toBeVisible();

  await page.evaluate(() => {
    const composer = document.querySelector(".composer");
    if (!composer) throw new Error("Composer is unavailable");
    const transfer = new DataTransfer();
    transfer.setData("text/uri-list", "https://example.com/not-a-file");
    composer.dispatchEvent(new DragEvent("drop", { dataTransfer: transfer, bubbles: true, cancelable: true }));
  });
  await expect(page.locator(".composer-notice.error")).toContainText("carry no file path");
  await expectViewportIntegrity(page);
});

test("subtasks render under their parent and delete with it", async ({ page }) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      tasks: [
        { id: "parent", name: "Set up the migration", state: "in_progress" },
        { id: "child-a", name: "Write the migration", state: "completed", parentId: "parent", description: "Adds the shares table" },
        { id: "child-b", name: "Verify the migration", state: "not_started", parentId: "parent" },
        { id: "ship", name: "Ship the change", state: "not_started" },
      ],
    });
  });

  await page.getByRole("tab", { name: /Tasks/ }).click();

  const rows = page.locator(".task-workspace-row");
  await expect(rows).toHaveCount(4);
  await expect(rows.nth(1)).toHaveClass(/subtask/);
  await expect(rows.nth(2)).toHaveClass(/subtask/);
  await expect(rows.nth(3)).not.toHaveClass(/subtask/);
  // Numbering counts top-level tasks only, so subtasks read as 1.1 and 1.2 and the next task is 2.
  await expect(rows.nth(0).locator("i")).toHaveText("1");
  await expect(rows.nth(1).locator("i")).toHaveText("1.1");
  await expect(rows.nth(2).locator("i")).toHaveText("1.2");
  await expect(rows.nth(3).locator("i")).toHaveText("2");
  await expect(rows.nth(1).locator("small")).toHaveText("Adds the shares table");
  await expectViewportIntegrity(page);

  // A subtask offers no nesting affordance of its own.
  await expect(rows.nth(0).getByRole("button", { name: "Add a subtask under Set up the migration" })).toBeVisible();
  await expect(rows.nth(1).getByRole("button", { name: /Add a subtask/ })).toHaveCount(0);

  await rows.nth(0).getByRole("button", { name: "Add a subtask under Set up the migration" }).click();
  await page.getByRole("textbox", { name: "New subtask of Set up the migration" }).fill("Back out the migration");
  await page.locator(".subtask-add button").click();
  await expect(page.locator(".task-workspace-row")).toHaveCount(5);
  await expect(page.locator(".task-workspace-row").nth(3)).toHaveClass(/subtask/);

  await rows.nth(0).getByTitle("Delete task and its subtasks").click();
  await expect(page.locator(".task-workspace-row")).toHaveCount(1);
  await expect(page.locator(".task-workspace-row").first().locator("strong")).toHaveText("Ship the change");
});

test("a multi-question ask_user requires every answer before submitting", async ({ page }) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "awaiting_approval",
      agentRun: { ...snapshot.agentRun, phase: "approval" },
      tools: [{
        id: "e2e-ask-many",
        name: "ask_user",
        summary: "Two questions",
        status: "approval",
        askContext: "Both answers change the migration plan.",
        askQuestions: [
          { question: "Which database?", options: ["Postgres", "SQLite"] },
          { question: "Run migrations now?", options: ["Yes", "No"] },
        ],
        canRevert: false,
        createdAt: Date.now(),
      }],
    });
  });

  const card = page.locator(".ask-card");
  await expect(card.locator(".ask-question")).toHaveCount(2);
  await expect(card.locator(".ask-card-context")).toHaveText("Both answers change the migration plan.");
  await expect(card.locator(".ask-card-question").first()).toContainText("1. Which database?");
  await expect(card.locator(".ask-card-question").nth(1)).toContainText("2. Run migrations now?");
  await expect(card.locator(".ask-progress")).toHaveText("0/2 answered");

  const submit = page.getByRole("button", { name: "Submit answers" });
  await expect(submit).toBeDisabled();
  await expectViewportIntegrity(page);

  // One answered question is not enough; the Agent asked for both.
  await card.locator(".ask-question").first().getByRole("button", { name: "Postgres" }).click();
  await expect(card.locator(".ask-progress")).toHaveText("1/2 answered");
  await expect(submit).toBeDisabled();

  await card.locator(".ask-question").nth(1).getByRole("button", { name: "No" }).click();
  await expect(card.locator(".ask-progress")).toHaveText("2/2 answered");
  await expect(submit).toBeEnabled();

  await submit.click();
  const answered = await page.evaluate(() => window.CodeAgentDevelopment?.getSnapshot());
  const resolved = answered?.tools.find((tool) => tool.id === "e2e-ask-many");
  expect(resolved?.status).not.toBe("approval");
});

test("@ mentions search project files, become removable chips, and ride along with the message", async ({ page }) => {
  const composer = page.locator(".composer textarea");
  await composer.click();
  await composer.fill("Review @Auth");

  const menu = page.locator(".at-menu");
  await expect(menu).toBeVisible();
  await expect(menu.getByRole("option")).toHaveCount(2);
  await expect(menu.getByRole("option").first()).toContainText("AuthService.java");

  await menu.getByRole("option").first().click();
  await expect(composer).toHaveValue("Review @src/main/java/com/example/AuthService.java ");
  await expect(menu).toHaveCount(0);

  const chip = page.locator(".mention-chip");
  await expect(chip).toHaveCount(1);
  await expect(chip).toContainText("AuthService.java");
  await expectViewportIntegrity(page);

  // Removing the chip must remove the token it stands for, not just the chip.
  await chip.getByRole("button", { name: "Remove mention src/main/java/com/example/AuthService.java" }).click();
  await expect(page.locator(".mention-chip")).toHaveCount(0);
  await expect(composer).toHaveValue("Review ");

  // Deleting the token by hand must retire the chip too.
  await composer.fill("Review @docs/ARCHITECTURE.md and ship");
  await composer.fill("Review @docs");
  await menu.getByRole("option").first().click();
  await expect(page.locator(".mention-chip")).toHaveCount(1);
  await composer.fill("Review nothing");
  await expect(page.locator(".mention-chip")).toHaveCount(0);

  await composer.fill("Review @README");
  await menu.getByRole("option").first().click();
  await expect(page.locator(".mention-chip")).toHaveCount(1);
  await page.locator("button.send-button").click();
  await expect(page.locator(".mention-chip")).toHaveCount(0);
  await expect(composer).toHaveValue("");
});

test("overlays return focus to the control that opened them", async ({ page }) => {
  const threadsButton = page.getByRole("button", { name: "Threads", exact: true }).first();
  await threadsButton.click();
  await expect(page.locator(".thread-drawer")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator(".thread-drawer")).toHaveCount(0);
  await expect(threadsButton).toBeFocused();

  // Closing through the explicit button must land in the same place as Escape.
  await threadsButton.click();
  await page.getByRole("button", { name: "Close", exact: true }).first().click();
  await expect(threadsButton).toBeFocused();

  const options = page.getByTitle("More options").first();
  await options.click();
  await expect(page.locator(".workspace-menu")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator(".workspace-menu")).toHaveCount(0);
  await expect(options).toBeFocused();
  await expectViewportIntegrity(page);
});

test("disabled controls say why they are unavailable", async ({ page }) => {
  await page.evaluate(() => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      models: { ...snapshot.models, state: "error", label: "Sign in to load models", options: [] },
      sharing: { state: "unavailable", reason: "Shareable links are not configured on this deployment" },
    });
  });

  const modelButton = page.locator("button.model-select");
  await expect(modelButton).toBeDisabled();
  await expect(modelButton).toHaveAttribute("title", "Sign in to load models");

  await page.getByTitle("More options").click();
  const shareButton = page.locator(".workspace-menu").getByRole("button", { name: "Share this conversation" });
  await expect(shareButton).toBeDisabled();
  await expect(shareButton).toHaveAttribute("title", "Shareable links are not configured on this deployment");

  // Every disabled control in the composer must carry an explanation.
  const unexplained = await page.evaluate(() => [...document.querySelectorAll(".composer button[disabled]")]
    .filter((button) => !button.getAttribute("title") && !button.getAttribute("aria-label"))
    .map((button) => button.className));
  expect(unexplained).toEqual([]);
});

test("dense transcripts, long tokens, and stacked approvals never break the viewport", async ({ page }) => {
  const UNBROKEN = "Augment".repeat(40);
  await page.evaluate((unbroken) => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    const now = Date.now();
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      runState: "awaiting_approval",
      agentRun: { ...snapshot.agentRun, phase: "approval" },
      messages: [
        { id: "m-long", role: "user", content: `Explain ${unbroken} and /a/very/deep/${unbroken}/path.ts`, createdAt: now, timelineSequence: 1 },
        {
          id: "m-code",
          role: "assistant",
          content: `Here is the change:\n\n\`\`\`ts\nconst ${unbroken} = "${unbroken}";\nexport function ${unbroken}() { return ${unbroken}; }\n\`\`\`\n\nAnd a table:\n\n| ${unbroken} | b |\n| --- | --- |\n| 1 | 2 |`,
          createdAt: now + 1,
          timelineSequence: 2,
        },
      ],
      attachments: Array.from({ length: 8 }, (_, index) => ({
        id: `attach-${index}`,
        label: `${unbroken}-${index}.ts`,
        path: `src/${unbroken}/${index}.ts`,
        kind: "file" as const,
      })),
      tools: [
        {
          id: "t-output",
          name: "shell",
          summary: unbroken,
          status: "completed" as const,
          detail: Array.from({ length: 40 }, (_, line) => `${line}: ${unbroken}`).join("\n"),
          canRevert: false,
          createdAt: now + 2,
        },
        ...Array.from({ length: 4 }, (_, index) => ({
          id: `t-approval-${index}`,
          name: "save_file",
          summary: `src/${unbroken}/${index}.ts`,
          status: "approval" as const,
          detail: `Writes ${unbroken}`,
          changePath: `src/${unbroken}/${index}.ts`,
          canRevert: false,
          createdAt: now + 3 + index,
        })),
      ],
    });
  }, UNBROKEN);

  await expect(page.locator(".approval").first()).toBeVisible();
  await expectViewportIntegrity(page);

  await expectNoSidewaysScroll(page);

  await page.locator(".conversation").evaluate((element) => { element.scrollTop = element.scrollHeight; });
  await expectViewportIntegrity(page);
});

test("hostile names never make an overlay page scroll sideways", async ({ page }) => {
  const UNBROKEN = "Augment".repeat(40);
  await page.evaluate((unbroken) => {
    const snapshot = window.CodeAgentDevelopment?.getSnapshot();
    if (!snapshot || !window.CodeAgentDevelopment) throw new Error("Development snapshot is unavailable");
    const now = Date.now();
    window.CodeAgentDevelopment.setSnapshot({
      ...snapshot,
      threads: snapshot.threads.map((thread, index) => ({
        ...thread,
        title: index === 0 ? thread.title : `${unbroken}-${index}`,
        summary: unbroken,
      })),
      tasks: Array.from({ length: 3 }, (_, index) => ({
        id: `task-${index}`,
        name: `${unbroken}-${index}`,
        state: "not_started" as const,
        description: unbroken,
      })),
      tools: Array.from({ length: 3 }, (_, index) => ({
        id: `tool-${index}`,
        name: "save_file",
        summary: `src/${unbroken}/${index}.ts`,
        status: "completed" as const,
        detail: `Wrote ${unbroken}`,
        changePath: `src/${unbroken}/${index}.ts`,
        canRevert: true,
        createdAt: now + index,
      })),
    });
  }, UNBROKEN);

  // Primary Agent surfaces stay contained without routing through an overflow menu.
  await page.getByRole("tab", { name: /Tasks/ }).click();
  await expectViewportIntegrity(page);
  await expectNoSidewaysScroll(page, "Tasks");
  const taskActions = page.locator(".task-import-actions");
  const actionNames = ["Export", "Import", "Continue in New Chat", "Clear Completed", "Clear All"];
  for (const actionName of actionNames) {
    await expect(taskActions.getByRole("button", { name: actionName, exact: true })).toBeVisible();
  }
  const actionBounds = await taskActions.locator("button").evaluateAll((buttons) => buttons.map((button) => {
    const rect = button.getBoundingClientRect();
    return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom };
  }));
  const shellBounds = await page.locator(".shell").boundingBox();
  expect(shellBounds).not.toBeNull();
  for (const bounds of actionBounds) {
    expect(bounds.left).toBeGreaterThanOrEqual(shellBounds!.x - 1);
    expect(bounds.right).toBeLessThanOrEqual(shellBounds!.x + shellBounds!.width + 1);
    expect(bounds.top).toBeGreaterThanOrEqual(shellBounds!.y - 1);
    expect(bounds.bottom).toBeLessThanOrEqual(shellBounds!.y + shellBounds!.height + 1);
  }
  const exportButton = taskActions.getByRole("button", { name: "Export", exact: true });
  const importButton = taskActions.getByRole("button", { name: "Import", exact: true });
  await exportButton.focus();
  await expect(exportButton).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(importButton).toBeFocused();

  await page.getByRole("tab", { name: /Edits/ }).click();
  await expectViewportIntegrity(page);
  await expectNoSidewaysScroll(page, "Edits");
  await page.getByRole("tab", { name: "Thread", exact: true }).click();

  // Secondary workspaces remain reachable from the compact overflow menu.
  for (const overlay of ["Durable Jobs", "Git Changes", "Context Canvas", "Tools catalog", "Settings"]) {
    await page.getByTitle("More options").click();
    await page.locator(".workspace-menu").getByRole("button", { name: overlay, exact: true }).click();
    await expectViewportIntegrity(page);
    await expectNoSidewaysScroll(page, overlay);
    await page.getByRole("button", { name: "Back" }).first().click();
    await expect(page.getByTitle("More options")).toBeFocused();
  }

  await page.getByTitle("Threads").first().click();
  await expectViewportIntegrity(page);
  await expectNoSidewaysScroll(page, "Threads drawer");
});
