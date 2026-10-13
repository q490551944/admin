const { defineConfig } = require("@playwright/test");
module.exports = defineConfig({
  testDir: "src/test/e2e", testMatch: "monitor-runtime.spec.cjs", timeout: 240000,
  expect: { timeout: 10000 }, workers: 1, fullyParallel: false,
  reporter: [["list"], ["html", { outputFolder: "target/monitor-runtime-browser-report", open: "never" }]],
  outputDir: "target/monitor-runtime-browser-results",
  use: { baseURL: process.env.MONITOR_E2E_URL, headless: true, viewport: { width: 1280, height: 900 },
    channel: process.env.MONITOR_E2E_BROWSER || undefined, screenshot: "only-on-failure", trace: "on" }
});
