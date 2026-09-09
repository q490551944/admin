const { defineConfig } = require("@playwright/test");
const fs = require("node:fs");
const ready = process.env.CHAT_E2E_URL || JSON.parse(fs.readFileSync("target/chat-e2e-ready.json", "utf8")).baseURL;
module.exports = defineConfig({
  testDir: "src/test/e2e", timeout: 120000, expect: { timeout: 15000 }, workers: 1,
  reporter: [["list"], ["html", { outputFolder: "target/chat-e2e-report", open: "never" }]],
  outputDir: "target/chat-e2e-results",
  use: { baseURL: ready, headless: true, viewport: { width: 1440, height: 1000 },
    channel: process.env.CHAT_E2E_BROWSER || undefined, screenshot: "only-on-failure", trace: "retain-on-failure" }
});
