const { test, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");
const views = require("../../main/resources/static/monitor/details.js");
const overview = require("../../main/resources/static/monitor/overview.js");
const API = "/api/monitor/v1";
const row = (page, id) => page.locator(`[data-target-id="${id}"]`);
const metric = (page, key) => page.locator(`#detail-metrics [data-metric-key="${key}"]`);
const headers = () => ({ "X-Monitor-Test-Token": process.env.MONITOR_E2E_CONTROL_TOKEN });
async function control(request, data, route = "runtime") {
  const response = await request.post(`/__monitor_test/${route}`, { headers: headers(), data });
  expect(response.status()).toBe(200); return response.json();
}
async function login(page, username = "monitor-allowed") {
  await page.goto("/monitor/login.html");
  await page.getByLabel("用户名", { exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill("Monitor-Test-26!");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  if (username === "monitor-allowed") {
    await expect(page.locator("#identity-name")).toHaveText(username);
    await expect(page.locator("#type-cards .type-card")).toHaveCount(6);
  }
}
async function open(page, id) {
  const reply = page.waitForResponse(response => new URL(response.url()).pathname === `${API}/targets/${id}` && response.status() === 200);
  await row(page, id).locator(".detail-button").click();
  const response = await reply;
  const data = await response.json();
  await expect(page.locator("#detail-message")).toBeHidden();
  expect(response.headers()["cache-control"]).toContain("no-store"); return data;
}
async function fits(page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  for (const id of ["filter-type", "filter-service", "filter-collection", "refresh-snapshot", "pause-snapshot", "detail-close"]) {
    const element = page.locator(`#${id}`);
    if (await element.isVisible()) {
      const box = await element.boundingBox(); expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(page.viewportSize().width + 1);
    }
  }
}
async function capture(page, info, name) {
  const file = info.outputPath(`${name}.png`); await page.screenshot({ path: file, fullPage: true });
  await info.attach(name, { path: file, contentType: "image/png" });
}
async function report(info, name, value) {
  const text = JSON.stringify(value, null, 2);
  fs.writeFileSync(path.join(process.env.MONITOR_RUNTIME_RUN, `acceptance-${name}.json`), text);
  await info.attach(name, { body: Buffer.from(text), contentType: "application/json" });
}
async function browserTime(page, instant) {
  return page.evaluate(value => new Date(value).toLocaleString("zh-CN", { hour12: false }), instant);
}
async function visibleSample(page, data, key) {
  const sample = data.metrics.find(item => item.latestAttempt.definition.key === key);
  expect(sample).toBeTruthy();
  const view = views.resourceView(data.target.target.type, sample);
  const card = metric(page, key);
  await expect(card.locator(".metric-label")).toHaveText(view.definition.label);
  await expect(card.locator(".metric-value")).toHaveText(view.text + (view.unit ? ` ${view.unit}` : ""));
  await expect(card).toHaveAttribute("data-value-state", view.state);
  await expect(card).toContainText(`对象范围 ${overview.scopeText(view.definition.scope)}`);
  await expect(card).toContainText(`单位 ${view.nativeUnit ?? view.unit}`);
  if (view.sample) await expect(card).toContainText(`数值采样 ${await browserTime(page, view.sample.sampledAt)}`);
  await card.locator("summary").click();
  await expect(card).toContainText(`原生来源 ${view.definition.source}`);
  await expect(card).toContainText(`绑定 ${sample.bindingId} · ${sample.source}`);
  return { key, value: view.sample?.value ?? null, missingReason: sample.latestAttempt.missingReason, unit: view.definition.unit,
    scopeKind: view.definition.scope.kind, sampledAt: view.sample?.sampledAt ?? null };
}

test.beforeEach(async ({ request }) => {
  await control(request, { reset: true }, "control");
  await control(request, { connected: true, loadConnections: 0 });
});
test.afterEach(async ({ request }) => {
  await control(request, { connected: true, loadConnections: 0 });
  await control(request, { reset: true }, "control");
});

for (const width of [1280, 390]) test(`real native content and first-content P95 at ${width}px`, async ({ page, context }, info) => {
  await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
  await login(page);
  expect((await context.request.get("/api/chat/v1/sessions/current")).status()).toBe(403);
  for (const type of ["KAFKA", "MONGODB", "ELASTICSEARCH", "MINIO"]) await expect(page.locator(`[data-type="${type}"]`)).toContainText("未启用");
  const timings = [];
  for (let i = 0; i < 20; i++) {
    await page.goto("/monitor/index.html");
    const measured = await page.waitForFunction(() => {
      const cards = document.querySelectorAll("#type-cards .type-card");
      const sources = ["mysql-00", "redis-00"].map(id => document.querySelector(`[data-target-id="${id}"]`));
      return cards.length === 6 && sources.every(card => card?.querySelector(".core-metric")) ? performance.now() : false;
    });
    timings.push(await measured.jsonValue()); await measured.dispose();
  }
  const sorted = [...timings].sort((a, b) => a - b), p95 = sorted[Math.ceil(sorted.length * .95) - 1];
  expect(p95).toBeLessThanOrEqual(2000); await fits(page); await capture(page, info, `overview-${width}`);
  const native = JSON.parse(process.env.MONITOR_NATIVE_REFERENCES);
  const mysql = await open(page, "mysql-00");
  const mysqlVisible = await visibleSample(page, mysql, "mysql.innodb.buffer_pool.configured.bytes");
  expect(mysqlVisible.value).toBe(native.mysqlBufferPoolBytes);
  const capacity = mysql.metrics.find(item => item.latestAttempt.definition.key === "mysql.filesystem.total.bytes");
  expect(capacity.latestAttempt.value).toBeNull(); expect(capacity.valueState).toBe("NO_DATA");
  await expect(metric(page, "mysql.filesystem.total.bytes").locator(".metric-value")).toHaveText("— B");
  await expect(metric(page, "mysql.filesystem.total.bytes")).toContainText("不支持");
  await fits(page); await capture(page, info, `mysql-detail-${width}`);
  const redis = await open(page, "redis-00");
  const redisVisible = await visibleSample(page, redis, "redis.memory.maxmemory.bytes"); expect(redisVisible.value).toBe(native.redisMaxmemoryBytes);
  await expect(metric(page, "redis.memory.maxmemory.bytes").locator(".metric-value")).toHaveText("未设置上限");
  await fits(page); await capture(page, info, `redis-detail-${width}`);
  expect(await page.locator("body").innerText()).not.toMatch(/历史记录|趋势图|自定义告警/);
  await report(info, `content-${width}`, { width, measurements: timings.length, firstContentP95Millis: p95,
    definition: "authenticated navigation start to six cards and both real source core samples rendered; warmed periodic source startup excluded",
    visibleNativeSamples: [mysqlVisible, redisVisible], missingCapacityRenderedAsDash: true, horizontalOverflow: false, responseMocks: false });
});

test("real Redis connections, default fault budget, retained values, expiry and automatic recovery", async ({ page, context, request }, info) => {
  await login(page); const initial = await open(page, "redis-00");
  const baseline = initial.metrics.find(item => item.latestAttempt.definition.key === "redis.clients.connected").latestAttempt.value;
  expect(typeof baseline).toBe("number");
  expect((await control(request, { loadConnections: 8 })).ownedLoadConnections).toBe(8);
  // INFO includes the collector connection; concurrent capacity probes can add one transient native connection.
  await expect.poll(async () => Number((await metric(page, "redis.clients.connected").locator(".metric-value").innerText()).split(" ")[0].replaceAll(",", "")),
    { timeout: 35000, intervals: [500] }).toBeGreaterThanOrEqual(baseline + 7);
  const changed = await (await context.request.get(`${API}/targets/redis-00`)).json();
  const retained = changed.metrics.find(item => item.latestAttempt.definition.key === "redis.clients.connected");
  expect(retained.latestAttempt.value).toBeGreaterThanOrEqual(baseline + 7);
  const mysqlBefore = await (await context.request.get(`${API}/targets/mysql-00`)).json();
  const mysqlAt = mysqlBefore.metrics.find(item => item.latestAttempt.definition.key === "mysql.connections.current").latestAttempt.sampledAt;
  const faultStart = Date.now(); await control(request, { connected: false });
  await expect(row(page, "redis-00").locator('[data-state="CONNECTION_FAILED"]')).toBeVisible({ timeout: 35000 });
  const faultLatency = Date.now() - faultStart; expect(faultLatency).toBeLessThanOrEqual(35000);
  await expect(page.locator('#detail-states [data-state="CONNECTION_FAILED"]')).toBeVisible();
  await expect(metric(page, "redis.clients.connected")).toHaveAttribute("data-value-state", "LAST_SUCCESS");
  await expect(metric(page, "redis.clients.connected")).toContainText("上次成功值");
  const failedData = await (await context.request.get(`${API}/targets/redis-00`)).json();
  const failedMetric = failedData.metrics.find(item => item.latestAttempt.definition.key === "redis.clients.connected");
  expect(failedMetric.latestAttempt.value).toBeNull(); expect(failedMetric.latestAttempt.missingReason).toBe("FAILED");
  expect(failedMetric.lastSuccess.value).toBeGreaterThanOrEqual(baseline + 7);
  const oldView = overview.metricView(failedMetric);
  await expect(metric(page, "redis.clients.connected").locator(".metric-value")).toHaveText(`${oldView.text} ${oldView.unit}`);
  await expect(metric(page, "redis.clients.connected")).toContainText(`上次成功 ${await browserTime(page, failedMetric.lastSuccess.sampledAt)}`);
  await expect.poll(async () => {
    const data = await (await context.request.get(`${API}/targets/mysql-00`)).json();
    return Date.parse(data.metrics.find(item => item.latestAttempt.definition.key === "mysql.connections.current").latestAttempt.sampledAt);
  }, { timeout: 35000, intervals: [500] }).toBeGreaterThan(Date.parse(mysqlAt));
  await expect(row(page, "mysql-00").locator('[data-state="AVAILABLE"]')).toBeVisible();
  await capture(page, info, "redis-failed-mysql-live");
  // Keep the real connection refused through the 45s ordinary value TTL; no browser time jump here.
  await expect(metric(page, "redis.clients.connected")).toHaveAttribute("data-value-state", "EXPIRED", { timeout: 55000 });
  await expect(metric(page, "redis.clients.connected")).toContainText("已过期 · 上次成功值");
  await expect(metric(page, "redis.clients.connected").locator(".metric-value")).toHaveText(`${oldView.text} ${oldView.unit}`);
  await expect(row(page, "redis-00").locator('.core-metric[data-value-state="EXPIRED"]')).not.toHaveCount(0);
  await page.getByLabel("仅看异常").check(); await expect(row(page, "redis-00")).toBeVisible();
  await expect(page.locator('[data-service="CONNECTION_FAILED"] strong')).toHaveText("1");
  await capture(page, info, "redis-expired");
  await control(request, { connected: true });
  await expect(metric(page, "redis.clients.connected")).toHaveAttribute("data-value-state", "CURRENT", { timeout: 35000 });
  // A failed independent capacity probe also has to recover on its genuine 60s period.
  await expect(metric(page, "redis.aof.current.bytes")).toHaveAttribute("data-value-state", "CURRENT", { timeout: 80000 });
  await expect(row(page, "redis-00").locator('[data-state="AVAILABLE"]')).toBeVisible();
  await expect(page.locator('#detail-states [data-state="AVAILABLE"]')).toBeVisible();
  await expect(page.locator('[data-service="AVAILABLE"] strong')).toHaveText("2");
  await expect(metric(page, "redis.clients.connected")).not.toContainText("上次成功值");
  await capture(page, info, "redis-recovered");
  await report(info, "fault", { knownOwnedConnections: 8, baseline, changed: retained.latestAttempt.value, ordinaryFaultToVisibleMillis: faultLatency,
    budgetMillis: 35000, realOrdinaryExpiryMillis: 45000, retainedValue: failedMetric.lastSuccess.value, retainedSampledAt: failedMetric.lastSuccess.sampledAt,
    mysqlSamplingContinued: true, ordinaryAndCapacityRecovery: true, automaticRecovery: true, periodicCollections: true, responseMocks: false });
});

for (const mode of ["paused", "offline"]) test(`real cached ordinary and capacity TTL stays consistent while ${mode}`, async ({ page, context }, info) => {
  await page.clock.install(); await login(page); await open(page, "redis-00");
  await page.clock.pauseAt(new Date(await page.evaluate(() => Date.now() + 1000)));
  let reads = 0; page.on("request", request => { if (new URL(request.url()).pathname === `${API}/snapshots`) reads++; });
  if (mode === "paused") {
    await page.getByRole("button", { name: "暂停刷新", exact: true }).click();
    const snapshotReply = page.waitForResponse(response => new URL(response.url()).pathname === `${API}/snapshots` && response.status() === 200);
    const detailReply = page.waitForResponse(response => new URL(response.url()).pathname === `${API}/targets/redis-00` && response.status() === 200);
    await page.getByRole("button", { name: "手动刷新", exact: true }).click();
    await Promise.all([(await snapshotReply).finished(), (await detailReply).finished()]);
    await expect(page.locator("#detail-message")).toBeHidden(); await expect(page.locator("#refresh-snapshot")).toBeEnabled();
  } else { await context.setOffline(true); }
  const baseline = reads;
  // Advance only cached browser age. All original samples and API responses came from real sources.
  await page.clock.runFor(181000);
  await expect(page.locator('[data-service="UNKNOWN"] strong')).toHaveText("2");
  await expect(row(page, "redis-00").locator('[data-state="UNKNOWN"]')).toBeVisible();
  await expect(page.locator('#detail-states [data-state="UNKNOWN"]')).toBeVisible();
  for (const key of ["redis.clients.connected", "redis.aof.current.bytes"]) await expect(metric(page, key)).toHaveAttribute("data-value-state", "EXPIRED");
  await expect(row(page, "redis-00").locator('.core-metric[data-value-state="EXPIRED"]')).not.toHaveCount(0);
  await page.getByLabel("仅看异常").check(); await expect(row(page, "redis-00")).toBeVisible();
  if (mode === "paused") { expect(reads).toBe(baseline); await expect(page.locator("#refresh-status")).toContainText("已暂停"); }
  else { await expect(page.locator("#overview-message")).toContainText("整体快照读取失败"); }
  await capture(page, info, `${mode}-expired`);
  if (mode === "paused") await page.getByRole("button", { name: "继续刷新", exact: true }).click();
  else await context.setOffline(false);
  await page.clock.runFor(15000);
  await expect(page.locator('[data-service="AVAILABLE"] strong')).toHaveText("2");
  await expect(metric(page, "redis.clients.connected")).toHaveAttribute("data-value-state", "CURRENT");
  await expect(metric(page, "redis.aof.current.bytes")).toHaveAttribute("data-value-state", "CURRENT");
  await report(info, mode, { browserCachedAgeAdvancedMillis: 181000, realSourcesKeptRunning: true,
    cardDetailSummaryAndExceptionalConsistent: true, recoveredWithRealAPI: true, responseMocks: false });
});

test("real 401, denied 403 and employee identity outage 503 with chat disabled", async ({ page, context, request }, info) => {
  expect((await context.request.get(`${API}/snapshots`)).status()).toBe(401);
  await login(page, "monitor-denied");
  await expect(page.locator("#status-panel")).toContainText("无查看权限");
  expect((await context.request.get(`${API}/snapshots`)).status()).toBe(403);
  await context.clearCookies(); await login(page);
  await control(request, { outage: true }, "control");
  expect((await context.request.get(`${API}/snapshots`)).status()).toBe(503);
  await page.reload(); await expect(page.locator("#status-panel")).toContainText("认证服务暂不可用");
  await capture(page, info, "identity-unavailable");
  await control(request, { outage: false }, "control");
  await page.getByRole("button", { name: "重试", exact: true }).click();
  await expect(page.locator("#identity-name")).toHaveText("monitor-allowed");
  await expect(page.locator("#type-cards .type-card")).toHaveCount(6);
  expect((await context.request.get("/api/chat/v1/sessions/current")).status()).toBe(403);
  await report(info, "auth", { anonymous: 401, denied: 403, identityUnavailable: 503, chatEnabled: false, recovered: true });
});
