import { defineConfig } from "@playwright/test";

const port = Number(process.env.CODEAGENT_PLAYWRIGHT_PORT ?? "4173");
if (!Number.isInteger(port) || port < 1 || port > 65_535) {
  throw new Error(`Invalid CODEAGENT_PLAYWRIGHT_PORT: ${process.env.CODEAGENT_PLAYWRIGHT_PORT}`);
}
const baseURL = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [
    ["list"],
    ["html", { outputFolder: "../build/reports/playwright", open: "never" }],
  ],
  outputDir: "../build/test-results/playwright",
  snapshotPathTemplate: "{testDir}/__screenshots__/{arg}-{projectName}{ext}",
  expect: {
    timeout: 8_000,
    toHaveScreenshot: {
      animations: "disabled",
      caret: "hide",
      // Chromium text rasterization differs between macOS and the Linux CI image.
      // Geometry and interaction assertions remain exact; this budget covers glyph pixels only.
      maxDiffPixelRatio: 0.05,
    },
  },
  use: {
    baseURL,
    browserName: "chromium",
    colorScheme: "dark",
    locale: "en-US",
    reducedMotion: "reduce",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    video: "retain-on-failure",
  },
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    { name: "tool-window-360", use: { viewport: { width: 360, height: 900 }, deviceScaleFactor: 1 } },
    { name: "tool-window-420", use: { viewport: { width: 420, height: 900 }, deviceScaleFactor: 1 } },
    { name: "docked-640", use: { viewport: { width: 640, height: 900 }, deviceScaleFactor: 1 } },
  ],
});
