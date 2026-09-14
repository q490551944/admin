const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "src/test/e2e", testMatch: "monitor.spec.cjs", timeout: 45000,
  expect: { timeout: 10000 }, workers: 1, fullyParallel: false,
  reporter: [["list"], ["html", { outputFolder: "target/monitor-e2e-report", open: "never" }]],
  outputDir: "target/monitor-e2e-results",
  use: {
    baseURL: process.env.MONITOR_E2E_URL, headless: true, viewport: { width: 1280, height: 900 },
    channel: process.env.MONITOR_E2E_BROWSER || process.env.CHAT_E2E_BROWSER || undefined,
    screenshot: "only-on-failure", trace: "retain-on-failure"
  }
});
