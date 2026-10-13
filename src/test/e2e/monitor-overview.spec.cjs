const { test, expect } = require("@playwright/test");
const types = ["MYSQL", "REDIS", "KAFKA", "MONGODB", "ELASTICSEARCH", "MINIO"];
const names = ["MySQL", "Redis", "Kafka", "MongoDB", "Elasticsearch", "MinIO"];
const API = "/api/monitor/v1";
async function reset(request) {
  expect((await request.post("/__monitor_test/control", { headers: { "X-Monitor-Test-Token": process.env.MONITOR_E2E_CONTROL_TOKEN }, data: { reset: true } })).status()).toBe(200);
}
function fixture(empty = false) {
  const at = new Date().toISOString(), expires = new Date(Date.now() + 45000).toISOString();
  function row(id, type, name, availability, status, state = "ACTIVE") {
    const target = { id, type, name, configurationStatus: state === "ACTIVE" ? "CONFIGURED" : state,
      connectionSources: ["existingClient"], memberIds: [id], bindings: [], scope: {} };
    const definition = { key: "probe.duration", label: "连接探测耗时", unit: "MILLISECONDS", scope: { kind: "ENDPOINT", id: "native-endpoint" }, source: "原生只读连接", calculation: "本次连接耗时" };
    const success = { definition, value: 0, missingReason: null, sampledAt: at, lastSuccessAt: at, validUntil: expires };
    const metric = { source: "existingClient", bindingId: id, kind: "ORDINARY", latestAttempt: success, lastSuccess: success,
      valueState: status === "FAILED" ? "LAST_SUCCESS" : "CURRENT", validUntil: expires };
    if (status === "FAILED") metric.latestAttempt = { definition, value: null, missingReason: "TIMEOUT", sampledAt: at, validUntil: at };
    const active = state === "ACTIVE";
    return { target, schedulingState: state, metricCount: active ? 1 : 0, missingMetricCount: status === "FAILED" ? 1 : 0,
      collections: active ? [{ source: "existingClient", bindingId: id, kind: "ORDINARY", status, validUntil: status === "WAITING" ? null : expires,
        valuesValidUntil: status === "WAITING" ? null : expires, expired: false,
        lastAttempt: status === "WAITING" ? null : { status, startedAt: at, completedAt: at } }] : [],
      serviceObservations: active ? [{ source: "existingClient", bindingId: id, availability, expired: false, validUntil: availability === "UNKNOWN" ? null : expires,
        lastObservation: availability === "UNKNOWN" ? null : { availability, sampledAt: at, validUntil: expires } }] : [],
      coreMetrics: active && status !== "WAITING" ? [metric] : [] };
  }
  const rows = empty ? [] : [row("mysql-a", "MYSQL", "订单库 · 主连接", "AVAILABLE", "SUCCESS"), row("redis-a", "REDIS", "缓存 A", "DEGRADED", "PARTIAL"),
    row("redis-b", "REDIS", "缓存 B · <img src=x onerror=window.pwned=true> · 很长的安全显示名称".repeat(2), "CONNECTION_FAILED", "FAILED"),
    row("mongo-a", "MONGODB", "文档库", "UNKNOWN", "WAITING"), row("es-a", "ELASTICSEARCH", "搜索", "UNKNOWN", "WAITING", "DISABLED"),
    row("minio-a", "MINIO", "对象存储", "UNKNOWN", "WAITING", "CONFIGURATION_MISSING")];
  return { catalog: { generation: 1, serverTime: at, resolutionState: "READY", types: types.map((type, i) => ({ type, name: names[i], targets: rows.filter(row => row.target.type === type).map(row => row.target) })) },
    snapshot: { generation: 1, serverTime: at, resolutionState: "READY", targets: rows,
      freshnessRules: { refreshIntervalMillis: 15000, ordinaryIntervalMillis: 15000, capacityIntervalMillis: 60000, serviceTtlMillis: 45000, metricTtlMultiplier: 3, expiresAfterValidUntil: true } } };
}
async function routeData(page, data) {
  await page.route(`**${API}/catalog`, route => route.fulfill({ json: data.catalog }));
  await page.route(`**${API}/snapshots`, route => route.fulfill({ json: data.snapshot }));
}
async function login(page) {
  await page.goto("/monitor/login.html");
  await page.getByLabel("用户名", { exact: true }).fill("monitor-allowed");
  await page.getByLabel("密码", { exact: true }).fill("Monitor-Test-26!");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.locator("#identity-name")).toHaveText("monitor-allowed");
}
test.beforeEach(async ({ request }) => reset(request));
test.afterEach(async ({ request }) => reset(request));
for (const width of [1280, 390]) {
  test(`overview ${width}px shows six types, exclusive service summary, scoped zero and safe instance entry`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    const data = fixture();
    await routeData(page, data); await login(page);
    await expect(page.locator("#type-cards .type-card")).toHaveCount(6);
    await expect(page.locator('[data-type="REDIS"]')).toContainText("已配置 2 个实例");
    for (const state of ["AVAILABLE", "DEGRADED", "CONNECTION_FAILED", "UNKNOWN"]) await expect(page.locator(`[data-service="${state}"] strong`)).toHaveText("1");
    await expect(page.locator("#configuration-summary")).toContainText("未启用 1 · 配置问题 1");
    await expect(page.locator("#configuration-summary")).toContainText("采集问题 2");
    const mysql = page.locator('[data-target-id="mysql-a"]');
    await expect(mysql).toContainText("● 可用"); await expect(mysql).toContainText("0 ms");
    await expect(mysql).toContainText("连接端点 · native-endpoint");
    await expect(page.locator('[data-target-id="mongo-a"]')).toContainText("等待首样本");
    await expect(page.locator('[data-target-id="redis-b"]')).toContainText("上次成功值 · 超时");
    await expect(page.locator('[data-target-id="redis-b"] h3')).toHaveText(data.snapshot.targets[2].target.name);
    expect(await page.evaluate(() => window.pwned)).toBeUndefined(); await expect(page.locator("#instance-list img")).toHaveCount(0);
    await mysql.locator("summary").click(); await expect(mysql).toContainText("目标 mysql-a"); await expect(mysql).toContainText("连接来源 existingClient");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    const file = testInfo.outputPath(`overview-${width}.png`); await page.screenshot({ path: file, fullPage: true });
    await testInfo.attach(`overview-${width}`, { path: file, contentType: "image/png" });
  });
}
test("overview empty directory keeps six disabled type cards and an explicit empty state", async ({ page }) => {
  await routeData(page, fixture(true)); await login(page);
  await expect(page.locator("#type-cards .type-card")).toHaveCount(6); await expect(page.locator("#instance-list")).toContainText("尚未配置监控实例");
  await expect(page.locator("#instance-list .instance-card")).toHaveCount(0); await expect(page.locator("#service-summary strong")).toHaveText(["0", "0", "0", "0"]);
});
test("overview retries a generation race once without combining different generations", async ({ page }) => {
  const data = fixture(); let reads = 0;
  await page.route(`**${API}/catalog`, route => route.fulfill({ json: data.catalog }));
  await page.route(`**${API}/snapshots`, route => route.fulfill({ json: { ...data.snapshot, generation: ++reads === 1 ? 2 : 1 } }));
  await login(page); await expect(page.locator("#instance-list .instance-card")).toHaveCount(6); expect(reads).toBe(2);
});
test("overview authorization failure clears private data and never renders raw error responses", async ({ page }) => {
  await routeData(page, fixture()); await login(page); await expect(page.locator("#instance-list .instance-card")).toHaveCount(6);
  await page.unroute(`**${API}/snapshots`);
  await page.route(`**${API}/snapshots`, route => route.fulfill({ status: 403, json: { code: "ACCESS_DENIED", message: "password=secret" } }));
  await page.reload(); await expect(page.locator("#status-panel")).toContainText("无查看权限");
  await expect(page.locator("#overview")).not.toBeVisible(); await expect(page.locator("#instance-list .instance-card")).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText("password=secret");
});

test("backend-projected expired overview keeps the original zero gray and service unknown", async ({ page }) => {
  const data = fixture(); const row = data.snapshot.targets[0];
  row.coreMetrics[0].valueState = "EXPIRED";
  row.serviceObservations[0].availability = "UNKNOWN"; row.serviceObservations[0].expired = true;
  row.collections[0].status = "STALE"; row.collections[0].expired = true;
  await routeData(page, data); await login(page);
  const card = page.locator('[data-target-id="mysql-a"]');
  await expect(card).toContainText("? 未知"); await expect(card).toContainText("已过期 · 上次成功值");
  await expect(card.locator('.core-metric[data-value-state="EXPIRED"] .metric-value')).toHaveText("0 ms");
  expect(await card.locator(".metric-value").evaluate(element => getComputedStyle(element).color)).toBe("rgb(100, 117, 108)");
  await expect(page.locator('[data-service="UNKNOWN"] strong')).toHaveText("2");
});
