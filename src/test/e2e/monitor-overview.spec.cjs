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
  await page.route(`**${API}/targets/*`, route => {
    const id = decodeURIComponent(new URL(route.request().url()).pathname.split("/").pop());
    const row = data.snapshot.targets.find(row => row.target.id === id);
    return route.fulfill({ json: data.details?.[id] || { generation: data.snapshot.generation, serverTime: data.snapshot.serverTime,
      target: row, metrics: row?.coreMetrics || [], capabilityState: "UNKNOWN", capabilities: [], freshnessRules: data.snapshot.freshnessRules } });
  });
}
function resourceFixture() {
  const data = detailFixture(), at = data.snapshot.serverTime;
  for (const detail of Object.values(data.details)) {
    detail.metrics = [];
    for (const record of [...detail.target.collections, ...detail.target.serviceObservations]) record.bindingId = detail.target.target.id;
  }
  function add(type, key, label, value, unit = "BYTES", scope = "PROCESS", options = {}) {
    const id = type.toLowerCase() + "-detail", kind = options.kind || "ORDINARY";
    const definition = { key, label, unit, scope: { kind: scope, id: options.id || "native-" + scope.toLowerCase(), nodeId: options.nodeId || null },
      source: options.source || "原生适配器字段", calculation: options.calculation || "原生范围直接读取；不跨对象相加" };
    const sample = { definition, value, missingReason: options.reason || null, sampledAt: at,
      validUntil: new Date(Date.parse(at) + (kind === "CAPACITY" ? 180000 : 45000)).toISOString() };
    data.details[id].metrics.push({ source: "existingClient", bindingId: id, kind, latestAttempt: sample,
      lastSuccess: options.reason ? null : sample, valueState: options.reason ? "NO_DATA" : "CURRENT", validUntil: options.reason ? null : sample.validUntil });
  }
  add("REDIS", "redis.cpu.single_core.percent", "Redis 进程 CPU（单核基准）", 175, "PERCENT");
  add("REDIS", "redis.memory.maxmemory.bytes", "Redis 配置内存上限", 0);
  add("REDIS", "redis.memory.used.percent", "分配内存与配置上限之比", null, "PERCENT", "PROCESS", { reason: "NOT_APPLICABLE" });
  add("REDIS", "redis.aof.current.bytes", "AOF 当前大小", 32, "BYTES", "PROCESS");
  add("MYSQL", "mysql.innodb.buffer_pool.data.bytes", "InnoDB 缓冲池数据字节", 64, "BYTES", "CACHE");
  add("MYSQL", "mysql.database.data.bytes", "可见基础表数据空间估算", 100, "BYTES", "DATABASE", { kind: "CAPACITY" });
  add("MYSQL", "mysql.database.index.bytes", "可见基础表索引空间估算", 20, "BYTES", "DATABASE", { kind: "CAPACITY" });
  add("MYSQL", "mysql.filesystem.total.bytes", "MySQL 文件系统总量", null, "BYTES", "FILESYSTEM", { kind: "CAPACITY", reason: "UNSUPPORTED" });
  add("KAFKA", "kafka.replica.log.bytes", "分区副本日志段字节", 100, "BYTES", "PARTITION", { kind: "CAPACITY" });
  add("KAFKA", "kafka.log_directory.volume.total.bytes", "目录所在卷原生总字节", null, "BYTES", "FILESYSTEM", { kind: "CAPACITY", reason: "UNSUPPORTED" });
  add("MONGODB", "mongodb.process.kind", "当前进程类型", "mongos", "TEXT");
  add("MONGODB", "mongodb.wiredtiger.cache.used.bytes", "WiredTiger 缓存已用量", null, "BYTES", "CACHE", { reason: "NOT_APPLICABLE" });
  add("MONGODB", "mongodb.database.dataSize.bytes", "数据库未压缩逻辑数据", 100, "BYTES", "DATABASE", { kind: "CAPACITY" });
  add("MINIO", "minio.bucket.accessible", "配置桶可访问", true, "BOOLEAN", "BUCKET");
  add("MINIO", "minio.native.metrics.available", "原生统计接口能力", null, "BOOLEAN", "ENDPOINT", { reason: "UNSUPPORTED" });
  add("MINIO", "minio.native.cluster.raw.total.bytes", "集群原始在线容量", null, "BYTES", "CLUSTER", { kind: "CAPACITY", reason: "UNSUPPORTED" });
  for (const [nodeId, cpu] of [["node-a", 15], ["node-b", 80]]) {
    add("ELASTICSEARCH", "elasticsearch.process.cpu.percent", "节点进程 CPU 使用率", cpu, "PERCENT", "PROCESS", { nodeId });
    add("ELASTICSEARCH", "elasticsearch.jvm.heap.used.percent", "JVM 堆使用率", cpu, "PERCENT", "JVM_HEAP", { nodeId });
    add("ELASTICSEARCH", "elasticsearch.os.memory.used.percent", "节点报告 OS 内存使用率", null, "PERCENT", "NODE_REPORTED_OS", { nodeId, reason: "UNSUPPORTED" });
    add("ELASTICSEARCH", "elasticsearch.filesystem.total.bytes", "节点文件系统总量", 0, "BYTES", "FILESYSTEM", { nodeId, kind: "CAPACITY" });
    add("ELASTICSEARCH", "elasticsearch.filesystem.used.percent", "节点文件系统占用比例", null, "PERCENT", "FILESYSTEM", { nodeId, kind: "CAPACITY", reason: "NOT_APPLICABLE" });
  }
  for (const id of ["path-1", "path-2"]) add("ELASTICSEARCH", "elasticsearch.data_path.total.bytes", "数据路径报告的总字节", 100, "BYTES", "FILESYSTEM", { nodeId: "node-a", id, kind: "CAPACITY", calculation: "多个路径共享同一卷，绝不累加这些行" });
  add("ELASTICSEARCH", "elasticsearch.nodes.failed", "原生节点失败数", 1, "COUNT", "CLUSTER");
  return data;
}
for (const width of [1280, 390]) {
  test(`resource Redis ${width}px preserves CPU above 100 and explains unlimited maxmemory without zero ratio`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 }); await routeData(page, resourceFixture()); await login(page);
    await page.locator('[data-target-id="redis-detail"] .detail-button').click();
    await expect(page.locator('[data-metric-key="redis.cpu.single_core.percent"] .metric-value')).toHaveText("175 %");
    await expect(page.locator('[data-metric-key="redis.memory.maxmemory.bytes"] .metric-value')).toHaveText("未设置上限");
    await expect(page.locator('[data-metric-key="redis.memory.maxmemory.bytes"]')).toContainText("单位 B");
    await expect(page.locator('[data-metric-key="redis.memory.used.percent"]')).toContainText("不适用");
    await expect(page.locator('[data-metric-key="redis.memory.used.percent"] .metric-value')).toHaveText("— %");
    await expect(page.locator("#detail-resource-context")).toContainText("可超过 100%");
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const file = testInfo.outputPath(`resources-${width}.png`); await page.locator("#target-detail").screenshot({ path: file }); await testInfo.attach(`resources-${width}`, { path: file, contentType: "image/png" });
  });
}
test("resource MySQL cache and Kafka replica bytes keep their native scope and missing capacity", async ({ page }) => {
  await routeData(page, resourceFixture()); await login(page);
  await page.locator('[data-target-id="mysql-detail"] .detail-button').click();
  await expect(page.locator("#detail-resource-context")).toContainText("缓冲池是数据库缓存");
  await expect(page.locator('[data-metric-key="mysql.innodb.buffer_pool.data.bytes"]')).toContainText("对象范围 缓存");
  await expect(page.locator('[data-metric-key="mysql.filesystem.total.bytes"] .metric-value')).toHaveText("— B");
  await page.locator('[data-target-id="kafka-detail"] .detail-button').click();
  await expect(page.locator("#detail-resource-context")).toContainText("副本日志大小与文件系统容量分别展示");
  await expect(page.locator('[data-metric-key="kafka.log_directory.volume.total.bytes"] .metric-value')).toHaveText("— B");
});
test("resource Elasticsearch node selection retains cluster evidence, shared volume rows and zero denominator absence", async ({ page }) => {
  await routeData(page, resourceFixture()); await login(page); let reads = 0;
  page.on("request", request => { if (request.url().endsWith("/targets/elasticsearch-detail")) reads++; });
  await page.locator('[data-target-id="elasticsearch-detail"] .detail-button').click();
  await expect(page.locator("#detail-node-coverage")).toContainText("已报告 2 个节点");
  await expect(page.locator("#detail-resource-context")).toContainText("部分节点");
  await page.getByLabel("查看节点", { exact: true }).selectOption("node-a");
  await expect(page.locator('#detail-metrics [data-node-id="node-b"]')).toHaveCount(0);
  await expect(page.locator('[data-metric-key="elasticsearch.process.cpu.percent"] .metric-value')).toHaveText("15 %");
  await expect(page.locator('[data-metric-key="elasticsearch.data_path.total.bytes"] .metric-value')).toHaveText(["100 B", "100 B"]);
  await expect(page.locator('[data-metric-key="elasticsearch.filesystem.used.percent"] .metric-value')).toHaveText("— %");
  await expect(page.locator('[data-metric-key="elasticsearch.nodes.failed"]')).toBeVisible();
  await page.getByLabel("查看节点", { exact: true }).selectOption("node-b");
  await expect(page.locator('[data-metric-key="elasticsearch.process.cpu.percent"] .metric-value')).toHaveText("80 %");
  await expect(page.locator('#detail-metrics [data-node-id="node-a"]')).toHaveCount(0); expect(reads).toBe(1);
});
test("resource MongoDB router and MinIO S3-only capability retain absence reasons and clear node controls", async ({ page }) => {
  await routeData(page, resourceFixture()); await login(page);
  await page.locator('[data-target-id="elasticsearch-detail"] .detail-button').click(); await expect(page.locator("#detail-node-filter")).toBeVisible();
  await page.locator('[data-target-id="mongodb-detail"] .detail-button').click();
  await expect(page.locator("#detail-resource-context")).toContainText("当前对象为路由进程"); await expect(page.locator("#detail-node-filter")).not.toBeVisible();
  await expect(page.locator('[data-metric-key="mongodb.wiredtiger.cache.used.bytes"]')).toContainText("不适用");
  await expect(page.locator('[data-metric-key="mongodb.database.dataSize.bytes"] .metric-value')).toHaveText("100 B");
  await page.locator('[data-target-id="minio-detail"] .detail-button').click();
  await expect(page.locator("#detail-resource-context")).toContainText("原生统计能力有限 · 不支持");
  await expect(page.locator('[data-metric-key="minio.bucket.accessible"] .metric-value')).toHaveText("是");
  await expect(page.locator('[data-metric-key="minio.native.cluster.raw.total.bytes"] .metric-value')).toHaveText("— B");
});
test("resource MinIO independently reports capacity limitation and new authorization loss over retained success", async ({ page }) => {
  const data = resourceFixture(), detail = data.details["minio-detail"], ordinary = detail.metrics.find(metric => metric.latestAttempt.definition.key === "minio.native.metrics.available");
  const capacity = structuredClone(ordinary); capacity.kind = "CAPACITY"; detail.metrics.push(capacity);
  ordinary.latestAttempt = { ...ordinary.latestAttempt, value: true, missingReason: null };
  ordinary.lastSuccess = ordinary.latestAttempt; ordinary.valueState = "CURRENT";
  await routeData(page, data); await login(page); await page.locator('[data-target-id="minio-detail"] .detail-button').click();
  await expect(page.locator("#detail-resource-context")).toContainText("容量原生统计能力有限 · 不支持");
  ordinary.latestAttempt = { ...ordinary.latestAttempt, value: null, missingReason: "UNAUTHORIZED" }; ordinary.valueState = "LAST_SUCCESS";
  await page.getByRole("button", { name: "重读详情", exact: true }).click();
  await expect(page.locator("#detail-resource-context")).toContainText("普通原生统计能力有限 · 未授权");
  const capability = page.locator('[data-category="SPECIFIC"] [data-metric-key="minio.native.metrics.available"]');
  await expect(capability).toContainText("上次成功值 · 未授权"); await expect(capability.locator(".metric-value")).toHaveText("是");
});
test("resource node removal after refresh clears the obsolete selection without mixing another instance", async ({ page }) => {
  const data = resourceFixture(); await routeData(page, data); await login(page);
  await page.locator('[data-target-id="elasticsearch-detail"] .detail-button').click(); await page.getByLabel("查看节点", { exact: true }).selectOption("node-b");
  data.details["elasticsearch-detail"].metrics = data.details["elasticsearch-detail"].metrics.filter(metric => metric.latestAttempt.definition.scope.nodeId !== "node-b");
  await page.getByRole("button", { name: "重读详情", exact: true }).click();
  await expect(page.getByLabel("查看节点", { exact: true })).toHaveValue(""); await expect(page.locator("#detail-node-coverage")).toContainText("已报告 1 个节点");
  await expect(page.locator('[data-metric-key="elasticsearch.process.cpu.percent"] .metric-value')).toHaveText("15 %");
});
test("resource node choice limit never leaves visible metrics filtered by an unavailable option", async ({ page }) => {
  const data = resourceFixture(), detail = data.details["elasticsearch-detail"], template = detail.metrics[0];
  const make = nodeId => ({ ...template, latestAttempt: { ...template.latestAttempt, definition: { ...template.latestAttempt.definition, scope: { kind: "PROCESS", id: "native-process", nodeId } } } });
  detail.metrics = Array.from({ length: 129 }, (_, index) => make("node-" + String(index + 1).padStart(3, "0")));
  await routeData(page, data); await login(page); await page.locator('[data-target-id="elasticsearch-detail"] .detail-button').click();
  await page.getByLabel("查看节点", { exact: true }).selectOption("node-128");
  await expect(page.locator("#detail-metrics .detail-metric")).toHaveCount(1);
  detail.metrics.unshift(make("node-000")); await page.getByRole("button", { name: "重读详情", exact: true }).click();
  await expect(page.getByLabel("查看节点", { exact: true })).toHaveValue("");
  await expect(page.locator("#detail-metrics .detail-metric")).toHaveCount(130);
  await expect(page.locator("#detail-node-coverage")).toContainText("选择列表限于前 128 个节点");
});
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
    await mysql.getByRole("button", { name: "查看实例", exact: true }).click();
    await expect(page.locator("#detail-identity")).toContainText("目标 mysql-a"); await expect(page.locator("#detail-identity")).toContainText("连接来源 existingClient");
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

function detailFixture() {
  const data = fixture(); const at = data.snapshot.serverTime, until = new Date(Date.parse(at) + 45000).toISOString(), capacityUntil = new Date(Date.parse(at) + 180000).toISOString();
  const rows = types.map((type, index) => {
    const base = structuredClone(data.snapshot.targets[0]), id = type.toLowerCase() + "-detail";
    base.target = { ...base.target, id, type, name: index === 1 ? data.snapshot.targets[2].target.name : names[index] + " · 原生实例" };
    function metric(key, label, unit, kind, scope, value, missingReason = null) {
      const definition = { key, label, unit, scope: { kind: scope, id: "remote-" + scope.toLowerCase(), nodeId: "node-a" }, source: "已有授权的原生接口", calculation: "原生字段直接读取，不跨对象相加" };
      const sample = { definition, value, missingReason, sampledAt: at, lastSuccessAt: missingReason ? null : at, validUntil: missingReason ? at : kind === "CAPACITY" ? capacityUntil : until };
      return { source: "existingClient", bindingId: id, kind, latestAttempt: sample, lastSuccess: missingReason ? null : sample, valueState: missingReason ? "NO_DATA" : "CURRENT", validUntil: missingReason ? null : sample.validUntil };
    }
    const reasons = [null, "UNSUPPORTED", "UNAUTHORIZED", "NOT_APPLICABLE", "WAITING_SAMPLE", "FAILED"];
    base.metrics = [metric(type.toLowerCase() + ".cpu.percent", "原生 CPU", "PERCENT", "ORDINARY", "PROCESS", reasons[index] ? null : 0, reasons[index]),
      metric(type.toLowerCase() + ".memory.bytes", "原生内存", "BYTES", "ORDINARY", type === "ELASTICSEARCH" ? "JVM_HEAP" : "PROCESS", 0),
      metric(type.toLowerCase() + ".data.bytes", "原生对象容量", "BYTES", "CAPACITY", type === "MINIO" ? "BUCKET" : "DATABASE", 1024),
      metric(type.toLowerCase() + ".requests.count", "原生请求数", "COUNT", "ORDINARY", "ENDPOINT", 5)];
    base.coreMetrics = base.metrics.slice(0, 2); base.metricCount = 4; base.missingMetricCount = reasons[index] ? 1 : 0;
    return base;
  });
  data.snapshot.targets = rows.map(({ metrics, ...row }) => row);
  data.catalog.types = types.map((type, i) => ({ type, name: names[i], targets: rows.filter(row => row.target.type === type).map(row => row.target) }));
  data.details = Object.fromEntries(rows.map(({ metrics, ...row }) => [row.target.id, { generation: 1, serverTime: at, target: row, metrics, capabilityState: "OBSERVED", capabilities: [], freshnessRules: data.snapshot.freshnessRules }]));
  return data;
}

test("generic details show all six native types, units, scopes, zero and distinct absence reasons", async ({ page }) => {
  const data = detailFixture(); await routeData(page, data); await login(page);
  const reasons = [null, "不支持", "未授权", "不适用", "等待采样", "采集失败"];
  for (const [index, type] of types.entries()) {
    await page.locator(`[data-target-id="${type.toLowerCase()}-detail"] .detail-button`).click();
    const detail = page.locator("#target-detail"); await expect(detail).toBeVisible(); await expect(page.locator("#detail-count")).toHaveText("当前显示 4 / 4 项原生指标");
    await expect(page.locator("#detail-title")).toContainText(names[index]); await expect(page.locator("#detail-identity")).toContainText(type.toLowerCase() + "-detail");
    const cpu = detail.locator(`[data-metric-key="${type.toLowerCase()}.cpu.percent"]`);
    await expect(cpu.locator(".metric-value")).toHaveText(reasons[index] ? "— %" : "0 %");
    if (reasons[index]) { await expect(cpu).toContainText(reasons[index]); expect(await cpu.locator(".metric-value").evaluate(element => getComputedStyle(element).color)).toBe("rgb(100, 117, 108)"); }
    await expect(detail.locator('[data-category="MEMORY"]')).toContainText("0 B");
    await expect(detail.locator('[data-category="STORAGE"]')).toContainText("容量独立采样 · 60 秒周期");
    await expect(cpu).toContainText("对象范围 进程 · remote-process · 节点 node-a"); await expect(cpu).toContainText("单位 %");
    await cpu.locator("summary").click(); await expect(cpu).toContainText("已有授权的原生接口"); await expect(cpu).toContainText("不跨对象相加");
    await page.getByRole("button", { name: "关闭详情", exact: true }).click();
  }
  expect(await page.evaluate(() => window.pwned)).toBeUndefined(); await expect(page.locator("#target-detail img")).toHaveCount(0);
});

test("empty details retain target identity and explain unreported categories without inventing values", async ({ page }) => {
  const data = detailFixture(), detail = data.details["mysql-detail"]; detail.metrics = []; detail.target.collections = []; detail.capabilityState = "UNKNOWN";
  await routeData(page, data); await login(page); await page.locator('[data-target-id="mysql-detail"] .detail-button').click();
  await expect(page.locator("#detail-count")).toHaveText("当前显示 0 / 0 项原生指标"); await expect(page.locator("#detail-metrics .metric-category")).toHaveCount(4);
  await expect(page.locator("#detail-collection")).toContainText("能力尚未确认，不表示资源为零"); await expect(page.locator("#detail-metrics .metric-value")).toHaveCount(0);
});

test("mobile details safely wrap long names and retain source explanations", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 900 }); const data = detailFixture(); await routeData(page, data); await login(page);
  await page.locator('[data-target-id="redis-detail"] .detail-button').click(); await expect(page.locator("#detail-count")).toContainText("4 / 4");
  await expect(page.locator("#detail-title")).toContainText(data.details["redis-detail"].target.target.name);
  await page.locator('#target-detail [data-metric-key="redis.cpu.percent"] summary').click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const file = testInfo.outputPath("details-390.png"); await page.locator("#target-detail").screenshot({ path: file }); await testInfo.attach("details-390", { path: file, contentType: "image/png" });
});

test("switching detail targets discards a late response and clears previous values immediately", async ({ page }) => {
  const data = detailFixture(); await routeData(page, data); await login(page);
  await page.unroute(`**${API}/targets/*`); let release, started = false; const held = new Promise(resolve => { release = resolve; });
  await page.route(`**${API}/targets/*`, async route => {
    const id = new URL(route.request().url()).pathname.split("/").pop();
    if (id === "mysql-detail") { started = true; await held; await route.fulfill({ json: data.details[id] }).catch(() => {}); }
    else await route.fulfill({ json: data.details[id] });
  });
  await page.locator('[data-target-id="mysql-detail"] .detail-button').click(); await expect.poll(() => started).toBe(true); await expect(page.locator("#detail-metrics .metric-value")).toHaveCount(0);
  await page.locator('[data-target-id="redis-detail"] .detail-button').click(); await expect(page.locator("#detail-title")).toContainText("Redis"); await expect(page.locator("#detail-count")).toContainText("4 / 4");
  release(); await expect(page.locator("#detail-title")).toContainText("Redis"); await expect(page.locator('#detail-metrics [data-metric-key^="mysql."]')).toHaveCount(0);
});

test("5000-metric details initially render 200 rows and expand explicitly without another API read", async ({ page }) => {
  const data = detailFixture(), detail = data.details["mysql-detail"], template = detail.metrics[1];
  detail.metrics = Array.from({ length: 5000 }, (_, index) => ({ ...template, latestAttempt: { ...template.latestAttempt, definition: { ...template.latestAttempt.definition, key: "mysql.memory." + index } } }));
  await routeData(page, data); await login(page); let reads = 0; page.on("request", request => { if (request.url().endsWith("/targets/mysql-detail")) reads++; });
  await page.locator('[data-target-id="mysql-detail"] .detail-button').click(); await expect(page.locator("#detail-count")).toHaveText("当前显示 200 / 5000 项原生指标");
  await expect(page.locator("#detail-metrics .detail-metric")).toHaveCount(200);
  await page.getByRole("button", { name: "继续显示 200 项", exact: true }).click(); await expect(page.locator("#detail-metrics .detail-metric")).toHaveCount(400); expect(reads).toBe(1);
});

test("missing target details show a safe message and detail authorization errors clear both private views", async ({ page }) => {
  const data = detailFixture(); await routeData(page, data); await login(page); await page.unroute(`**${API}/targets/*`);
  let denied = false;
  await page.route(`**${API}/targets/*`, route => route.fulfill({ status: denied ? 403 : 404,
    json: { code: denied ? "ACCESS_DENIED" : "TARGET_NOT_FOUND", message: "password=secret" } }));
  await page.locator('[data-target-id="mysql-detail"] .detail-button').click(); await expect(page.locator("#detail-message")).toContainText("此实例已不在当前目录");
  await expect(page.locator("#detail-metrics .metric-value")).toHaveCount(0); await expect(page.locator("#detail-identity")).toContainText("mysql-detail");
  denied = true; await page.getByRole("button", { name: "重读详情", exact: true }).click(); await expect(page.locator("#status-panel")).toContainText("无查看权限");
  await expect(page.locator("#overview")).not.toBeVisible(); await expect(page.locator("#detail-metrics .metric-value")).toHaveCount(0); await expect(page.locator("#detail-identity")).toBeEmpty();
  await expect(page.locator("body")).not.toContainText("password=secret");
});

test("a paused background return cancels pending detail loading and permits an explicit retry", async ({ page }) => {
  const data = detailFixture(); await routeData(page, data); await login(page); await page.unroute(`**${API}/targets/*`);
  let release, reads = 0; const held = new Promise(resolve => { release = resolve; });
  await page.route(`**${API}/targets/*`, async route => {
    reads++; if (reads === 1) await held;
    await route.fulfill({ json: data.details["mysql-detail"] }).catch(() => {});
  });
  await page.locator('[data-target-id="mysql-detail"] .detail-button').click(); await expect.poll(() => reads).toBe(1);
  await page.getByRole("button", { name: "暂停刷新", exact: true }).click();
  for (const state of ["hidden", "visible"]) await page.evaluate(value => { Object.defineProperty(document, "visibilityState", { configurable: true, value }); document.dispatchEvent(new Event("visibilitychange")); }, state);
  await expect(page.locator("#target-detail")).toBeVisible(); await expect(page.locator("#target-detail")).toHaveAttribute("aria-busy", "false");
  await expect(page.locator("#detail-message")).toContainText("详情尚未读取"); release();
  await expect(page.locator("#detail-metrics .metric-value")).toHaveCount(0);
  await page.getByRole("button", { name: "重读详情", exact: true }).click(); await expect(page.locator("#detail-count")).toContainText("4 / 4"); expect(reads).toBe(2);
  await expect(page.locator("#refresh-status")).toContainText("已暂停");
});

test("individual collection failure keeps the original zero gray with its reason and last successful clock", async ({ page }) => {
  const data = detailFixture(), detail = data.details["mysql-detail"], metric = detail.metrics[1], at = data.snapshot.serverTime;
  const previous = new Date(Date.parse(at) - 15000).toISOString();
  metric.lastSuccess = { ...metric.lastSuccess, sampledAt: previous, lastSuccessAt: previous };
  metric.latestAttempt = { ...metric.latestAttempt, value: null, missingReason: "TIMEOUT", sampledAt: at, lastSuccessAt: null, validUntil: at }; metric.valueState = "LAST_SUCCESS";
  detail.target.collections[0].status = "FAILED"; detail.target.collections[0].reason = "TIMEOUT";
  await routeData(page, data); await login(page); await page.locator('[data-target-id="mysql-detail"] .detail-button').click(); await expect(page.locator("#detail-count")).toContainText("4 / 4");
  const core = page.locator('[data-target-id="mysql-detail"] .core-metric[data-value-state="LAST_SUCCESS"]'), value = page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"]');
  await expect(core).toContainText("0 B"); await expect(core).toContainText("上次成功值 · 超时"); await expect(value.locator(".metric-value")).toHaveText("0 B");
  const originalClock = await page.evaluate(at => window.MonitorOverview.time(at), previous);
  await expect(core).toContainText("上次成功 " + originalClock); await expect(value).toContainText("上次成功 " + originalClock);
  expect(await value.locator(".metric-value").evaluate(element => getComputedStyle(element).color)).toBe("rgb(100, 117, 108)");
  await expect(page.locator("#detail-states")).toContainText("● 可用"); await expect(page.locator("#detail-states")).toContainText("采集失败");
});

async function stablePausedClock(page) {
  await page.getByRole("button", { name: "暂停刷新", exact: true }).click();
  await page.clock.pauseAt(new Date(await page.evaluate(() => Date.now() + 1000)));
  await page.getByRole("button", { name: "手动刷新", exact: true }).click();
  await expect(page.locator("#refresh-snapshot")).toBeEnabled(); await expect(page.locator("#detail-message")).toBeHidden();
}

test("paused TTL boundaries update cards details summary and exceptional filtering with independent capacity", async ({ page }) => {
  await page.clock.install(); const data = detailFixture(); let reads = 0;
  page.on("request", request => { if (request.url().endsWith("/snapshots")) reads++; });
  await routeData(page, data); await login(page); await page.locator('[data-target-id="mysql-detail"] .detail-button').click(); await expect(page.locator("#detail-count")).toContainText("4 / 4");
  await page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"] summary').click();
  await stablePausedClock(page); const baseline = reads; await page.getByLabel("仅看异常").check();
  await expect(page.locator('[data-target-id="mysql-detail"]')).toHaveCount(0);
  await page.clock.runFor(45000); await expect(page.locator('[data-service="AVAILABLE"] strong')).toHaveText("6");
  await expect(page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"] .live-freshness')).toHaveText("截止时刻仍有效");
  await page.clock.runFor(1000); await expect(page.locator('[data-service="UNKNOWN"] strong')).toHaveText("6");
  await expect(page.locator('[data-target-id="mysql-detail"]')).toContainText("? 未知"); await expect(page.locator('[data-target-id="mysql-detail"] .core-metric[data-value-state="EXPIRED"]')).toHaveCount(2);
  await expect(page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"]')).toHaveAttribute("data-value-state", "EXPIRED");
  await expect(page.locator('#detail-metrics [data-metric-key="mysql.data.bytes"]')).toHaveAttribute("data-value-state", "CURRENT");
  await expect(page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"] details')).toHaveAttribute("open", "");
  await expect(page.locator("#overview-time")).toContainText("已过去 46 秒"); await expect(page.locator("#detail-time")).toContainText("已过去 46 秒");
  await page.clock.runFor(135000); await expect(page.locator('#detail-metrics [data-metric-key="mysql.data.bytes"]')).toHaveAttribute("data-value-state", "EXPIRED");
  expect(reads).toBe(baseline); await expect(page.locator("#refresh-status")).toContainText("已暂停");
});

test("overall network failure keeps aging both views and automatic recovery replaces them with fresh samples", async ({ page }) => {
  await page.clock.install(); const data = detailFixture(); await routeData(page, data); let offline = false;
  await page.unroute(`**${API}/snapshots`);
  await page.route(`**${API}/snapshots`, route => offline ? route.abort("internetdisconnected") : route.fulfill({ json: data.snapshot }));
  await login(page); await page.locator('[data-target-id="mysql-detail"] .detail-button').click(); await expect(page.locator("#detail-count")).toContainText("4 / 4");
  await page.clock.pauseAt(new Date(await page.evaluate(() => Date.now() + 1000)));
  offline = true; await page.clock.runFor(15000); await expect(page.locator("#overview-message")).toContainText("整体快照读取失败");
  await page.clock.runFor(31000); await expect(page.locator('[data-service="UNKNOWN"] strong')).toHaveText("6");
  await expect(page.locator("#detail-states")).toContainText("? 未知"); await expect(page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"]')).toHaveAttribute("data-value-state", "EXPIRED");
  await page.getByLabel("仅看异常").check(); await expect(page.locator('[data-target-id="mysql-detail"]')).toBeVisible();
  const recovered = detailFixture(); recovered.details["mysql-detail"].metrics[1].latestAttempt.value = 42;
  Object.assign(data, recovered); offline = false;
  await page.clock.runFor(15000); await expect(page.locator("#overview-message")).toBeHidden();
  await expect(page.locator('[data-service="AVAILABLE"] strong')).toHaveText("6"); await expect(page.locator('[data-target-id="mysql-detail"]')).toHaveCount(0);
  await expect(page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"] .metric-value')).toHaveText("42 B");
  await expect(page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"]')).toHaveAttribute("data-value-state", "CURRENT");
});

test("client wall clock jumps do not age samples and background return respects pause then refreshes when active", async ({ page }) => {
  await page.clock.install({ time: new Date("2035-01-01T00:00:00Z") }); const data = detailFixture(); let reads = 0;
  await routeData(page, data); page.on("request", request => { if (request.url().endsWith("/snapshots")) reads++; });
  await login(page); await page.locator('[data-target-id="mysql-detail"] .detail-button').click(); await expect(page.locator("#detail-count")).toContainText("4 / 4");
  await stablePausedClock(page); const baseline = reads;
  await page.clock.setSystemTime(new Date("2060-01-01T00:00:00Z")); await page.clock.runFor(1000);
  await expect(page.locator('[data-service="AVAILABLE"] strong')).toHaveText("6"); await expect(page.locator('#detail-metrics [data-metric-key="mysql.memory.bytes"]')).toHaveAttribute("data-value-state", "CURRENT");
  async function visibility(state) { await page.evaluate(value => { Object.defineProperty(document, "visibilityState", { configurable: true, value }); document.dispatchEvent(new Event("visibilitychange")); }, state); }
  await visibility("hidden"); await page.clock.runFor(45000); await visibility("visible");
  await expect(page.locator("#overview")).toBeVisible(); await expect(page.locator('[data-service="UNKNOWN"] strong')).toHaveText("6"); await expect(page.locator("#detail-states")).toContainText("? 未知"); expect(reads).toBe(baseline);
  Object.assign(data, detailFixture()); await page.getByRole("button", { name: "继续刷新", exact: true }).click(); await expect.poll(() => reads).toBe(baseline + 1);
  await expect(page.locator('[data-service="AVAILABLE"] strong')).toHaveText("6");
  await visibility("hidden"); await page.clock.runFor(15000); await visibility("visible"); await expect.poll(() => reads).toBe(baseline + 2);
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

test("combined overview filters and summary clicks select exactly their active instances without another read", async ({ page }) => {
  const data = fixture(); let reads = 0;
  await page.route(`**${API}/catalog`, route => route.fulfill({ json: data.catalog }));
  await page.route(`**${API}/snapshots`, route => { reads++; return route.fulfill({ json: data.snapshot }); });
  await login(page); await expect(page.locator("#instance-list .instance-card")).toHaveCount(6);
  await page.getByLabel("类型", { exact: true }).selectOption("REDIS");
  await page.getByLabel("服务状态", { exact: true }).selectOption("CONNECTION_FAILED");
  await page.getByLabel("采集状态", { exact: true }).selectOption("FAILED");
  await page.getByLabel("仅看异常").check(); await expect(page.locator("#instance-list .instance-card")).toHaveCount(1);
  await expect(page.locator("#instance-list")).toContainText("缓存 B");
  await page.getByLabel("类型", { exact: true }).selectOption("MYSQL"); await expect(page.locator("#instance-list")).toContainText("没有匹配的实例");
  await page.locator('button[data-service="AVAILABLE"]').click(); await expect(page.locator("#instance-list .instance-card")).toHaveCount(1);
  await expect(page.locator("#filter-type")).toHaveValue(""); await expect(page.locator("#filter-collection")).toHaveValue("");
  await expect(page.locator('[data-target-id="mysql-a"]')).toBeVisible(); expect(reads).toBe(1);
});

test("automatic shared reads stop while paused, manual refresh keeps pause and continue refreshes immediately", async ({ page }) => {
  await page.clock.install(); const data = fixture(); let reads = 0;
  await page.route(`**${API}/catalog`, route => route.fulfill({ json: data.catalog }));
  await page.route(`**${API}/snapshots`, route => { reads++; return route.fulfill({ json: data.snapshot }); });
  await login(page); await expect(page.locator("#instance-list .instance-card")).toHaveCount(6);
  await page.clock.pauseAt(new Date(Date.now() + 1000)); const baseline = reads;
  await page.clock.runFor(15000); await expect.poll(() => reads).toBe(baseline + 1);
  await expect(page.locator("#refresh-snapshot")).toBeEnabled();
  await page.getByRole("button", { name: "暂停刷新", exact: true }).click(); await expect(page.locator("#refresh-status")).toContainText("已暂停");
  await page.clock.runFor(60000); expect(reads).toBe(baseline + 1);
  await page.getByRole("button", { name: "手动刷新", exact: true }).click(); await expect.poll(() => reads).toBe(baseline + 2);
  await expect(page.locator("#refresh-status")).toContainText("已暂停");
  await page.getByRole("button", { name: "继续刷新", exact: true }).click(); await expect.poll(() => reads).toBe(baseline + 3);
});

test("background return ages a paused snapshot before showing it and reads immediately only after continue", async ({ page }) => {
  await page.clock.install(); const data = fixture(); let reads = 0;
  await page.route(`**${API}/catalog`, route => route.fulfill({ json: data.catalog }));
  await page.route(`**${API}/snapshots`, route => { reads++; return route.fulfill({ json: data.snapshot }); });
  await login(page); await expect(page.locator('[data-target-id="mysql-a"]')).toContainText("● 可用");
  await page.getByRole("button", { name: "暂停刷新", exact: true }).click();
  await page.clock.pauseAt(new Date(Date.now() + 1000));
  async function visibility(value) { await page.evaluate(state => { Object.defineProperty(document, "visibilityState", { configurable: true, value: state }); document.dispatchEvent(new Event("visibilitychange")); }, value); }
  await visibility("hidden"); await page.clock.runFor(46000); await visibility("visible");
  await expect(page.locator("#overview")).toBeVisible(); await expect(page.locator('[data-target-id="mysql-a"]')).toContainText("? 未知");
  await expect(page.locator('[data-target-id="mysql-a"]')).toContainText("已过期 · 上次成功值");
  await expect(page.locator("#refresh-status")).toContainText("已暂停"); expect(reads).toBe(1);
  await page.getByRole("button", { name: "继续刷新", exact: true }).click(); await expect.poll(() => reads).toBe(2);
});

test("an aborted old snapshot cannot overwrite a newer manual read and pending refreshes do not overlap", async ({ page }) => {
  const data = fixture(); await routeData(page, data); await login(page); await expect(page.locator("#instance-list .instance-card")).toHaveCount(6);
  await page.unroute(`**${API}/snapshots`); let release, reads = 0;
  const held = new Promise(resolve => { release = resolve; });
  await page.route(`**${API}/snapshots`, async route => {
    reads++;
    if (reads === 1) { await held; await route.fulfill({ json: data.snapshot }).catch(() => {}); }
    else await route.fulfill({ json: { ...data.snapshot, targets: data.snapshot.targets.map(row => ({ ...row, target: { ...row.target, name: row.target.name + " · 新快照" } })) } });
  });
  await page.getByRole("button", { name: "手动刷新", exact: true }).click(); await expect.poll(() => reads).toBe(1);
  await expect(page.locator("#refresh-snapshot")).toBeDisabled();
  await page.getByRole("button", { name: "暂停刷新", exact: true }).click();
  await page.getByRole("button", { name: "手动刷新", exact: true }).click(); await expect(page.locator('[data-target-id="mysql-a"] h3')).toContainText("新快照");
  release(); await expect(page.locator('[data-target-id="mysql-a"] h3')).toContainText("新快照"); expect(reads).toBe(2);
});

test("five pages use bounded GET shared reads with no request that changes collection", async ({ page, context }) => {
  await page.clock.install();
  await login(page); const pages = [page];
  for (let i = 1; i < 5; i++) pages.push(await context.newPage());
  const reads = Array(5).fill(0), mutations = [];
  for (const [i, tab] of pages.entries()) {
    // Model five simultaneously visible pages, independent of headless tab activation.
    await tab.addInitScript(() => Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" }));
    const data = fixture();
    await tab.route(`**${API}/catalog`, route => route.fulfill({ json: data.catalog }));
    await tab.route(`**${API}/snapshots`, route => { reads[i]++; if (route.request().method() !== "GET") mutations.push(route.request().method()); return route.fulfill({ json: data.snapshot }); });
    await tab.goto("/monitor/index.html"); await expect(tab.locator("#instance-list .instance-card")).toHaveCount(6);
  }
  for (const tab of pages) await expect(tab.locator("#refresh-snapshot")).toBeEnabled();
  // Playwright's page clock controls the entire browser context, including all five pages.
  await page.clock.pauseAt(new Date(Date.now() + 1000));
  const baseline = [...reads];
  await page.clock.runFor(15000);
  await expect.poll(() => reads).toEqual(baseline.map(count => count + 1)); expect(mutations).toEqual([]);
  for (const tab of pages.slice(1)) await tab.close();
});
