const { test } = require("node:test");
const assert = require("node:assert/strict");
const details = require("../../main/resources/static/monitor/details.js");
const monitor = require("../../main/resources/static/monitor/overview.js");
const target = { id: "redis-a", type: "REDIS" };
const data = identity => ({ generation: 1, serverTime: "2026-10-13T07:00:00Z", target: { target: identity }, metrics: [] });
const nativeMetric = (key, value, scope = {}, missingReason = null) => {
  const sample = { definition: { key, unit: key.endsWith("percent") ? "PERCENT" : "BYTES", scope }, value, missingReason };
  return { latestAttempt: sample, lastSuccess: missingReason ? null : sample, valueState: missingReason ? "NO_DATA" : "CURRENT" };
};
test("resource views preserve CPU above one core and distinguish zero maxmemory from missing or other real zero", () => {
  assert.equal(details.resourceView("REDIS", nativeMetric("redis.cpu.single_core.percent", 175)).text, "175");
  assert.equal(details.resourceView("REDIS", nativeMetric("redis.memory.maxmemory.bytes", 0)).text, "未设置上限");
  assert.equal(details.resourceView("REDIS", nativeMetric("redis.memory.maxmemory.bytes", 0)).nativeUnit, "B");
  assert.equal(details.resourceView("REDIS", nativeMetric("redis.memory.maxmemory.bytes", null, {}, "UNAUTHORIZED")).text, "—");
  assert.equal(details.resourceView("REDIS", nativeMetric("redis.memory.used.bytes", 0)).text, "0");
  const old = nativeMetric("redis.memory.maxmemory.bytes", 0); old.valueState = "EXPIRED";
  assert.match(details.resourceView("REDIS", old).note, /已过期/);
});
test("resource context keeps cache, process, logical data, file and volume scopes distinct", () => {
  assert.match(details.resourceNotes("MYSQL", []).join(" "), /缓冲池.*数据库缓存.*进程内存/);
  assert.match(details.resourceNotes("KAFKA", []).join(" "), /副本日志.*文件系统.*共享同一卷/);
  assert.match(details.resourceNotes("MONGODB", []).join(" "), /逻辑数据.*分配空间.*索引空间/);
  assert.equal(details.category(nativeMetric("minio.native.process.resident.bytes", 1)), "MEMORY");
  assert.equal(details.category(nativeMetric("minio.native.process.virtual.bytes", 1)), "MEMORY");
});
test("node ids are stable and incomplete native coverage has a visible explanation", () => {
  const metrics = [nativeMetric("elasticsearch.process.cpu.percent", 1, { nodeId: "b" }), nativeMetric("elasticsearch.jvm.heap.used.percent", 2, { nodeId: "a" }), nativeMetric("elasticsearch.nodes.failed", 1), nativeMetric("other", 1, { nodeId: "b" })];
  assert.deepEqual(details.nodes(metrics), ["a", "b"]);
  assert.match(details.resourceNotes("ELASTICSEARCH", metrics).join(" "), /部分节点/);
});
test("S3-only native absence and router resources remain missing instead of inferred zero", () => {
  assert.match(details.resourceNotes("MINIO", [nativeMetric("minio.native.metrics.available", null, {}, "UNSUPPORTED")]).join(" "), /能力有限.*不支持/);
  assert.match(details.resourceNotes("MONGODB", [nativeMetric("mongodb.process.kind", "mongos")]).join(" "), /当前对象为路由进程/);
});
test("MinIO capability warnings use both cycles latest attempts even while a successful old value remains", () => {
  const ordinary = { ...nativeMetric("minio.native.metrics.available", true), kind: "ORDINARY" };
  const capacity = { ...nativeMetric("minio.native.metrics.available", null, {}, "UNSUPPORTED"), kind: "CAPACITY" };
  assert.match(details.resourceNotes("MINIO", [ordinary, capacity]).join(" "), /容量原生统计能力有限.*不支持/);
  const denied = { ...ordinary, latestAttempt: { ...ordinary.latestAttempt, value: null, missingReason: "UNAUTHORIZED" }, valueState: "LAST_SUCCESS" };
  assert.match(details.resourceNotes("MINIO", [denied]).join(" "), /普通原生统计能力有限.*未授权/);
  assert.match(details.resourceNotes("MINIO", [{ ...ordinary, valueState: "EXPIRED" }]).join(" "), /已过期/);
});
test("generic categories retain native scope and classify independent capacity without guessing missing resources", () => {
  const metric = (key, kind = "ORDINARY", scope = "PROCESS") => ({ kind, latestAttempt: { definition: { key, scope: { kind: scope } } } });
  assert.equal(details.category(metric("redis.cpu.single_core.percent")), "CPU");
  assert.equal(details.category(metric("mysql.innodb.buffer_pool.data.bytes")), "MEMORY");
  assert.equal(details.category(metric("elasticsearch.jvm.heap.used.bytes")), "MEMORY");
  assert.equal(details.category(metric("mongodb.db.logical.bytes", "CAPACITY", "DATABASE")), "STORAGE");
  assert.equal(details.category(metric("kafka.consumer.lag", "ORDINARY", "CONSUMER_GROUP")), "SPECIFIC");
});
test("detail identity is checked before rendering and the 5000-metric bound rejects malformed responses", () => {
  assert.equal(details.validate(data(target), target).target.target.id, target.id);
  assert.throws(() => details.validate(data({ ...target, id: "other" }), target));
  assert.throws(() => details.validate(data({ ...target, type: "MYSQL" }), target));
  assert.throws(() => details.validate({ ...data(target), metrics: Array(5001).fill({}) }, target));
  assert.throws(() => details.validate({ ...data(target), serverTime: "unknown" }, target));
});
test("all native absence reasons remain distinct from a real zero", () => {
  const definition = { label: "CPU", unit: "PERCENT", scope: { kind: "PROCESS", id: "remote" } };
  const success = { definition, value: 0, sampledAt: "2026-10-13T07:00:00Z" };
  assert.equal(monitor.metricView({ latestAttempt: success, lastSuccess: success, valueState: "CURRENT" }).text, "0");
  const notes = new Set();
  for (const missingReason of ["UNSUPPORTED", "UNAUTHORIZED", "NOT_APPLICABLE", "WAITING_SAMPLE", "FAILED"]) {
    const view = monitor.metricView({ latestAttempt: { definition, value: null, missingReason }, valueState: "NO_DATA" });
    assert.equal(view.text, "—"); assert.equal(view.sample, null); notes.add(view.note);
  }
  assert.equal(notes.size, 5);
});
test("switching instances cancels the previous read and late data cannot be attached to the selected identity", async () => {
  const requests = [], rendered = [], errors = [];
  const reader = details.createReader({ load(id, signal) { return new Promise(resolve => requests.push({ id, signal, resolve })); }, onData(value) { rendered.push(value.target.target.id); }, onError(error) { errors.push(error); } });
  const first = reader.read(target); await new Promise(resolve => setImmediate(resolve));
  const secondTarget = { id: "mysql-b", type: "MYSQL" }; const second = reader.read(secondTarget);
  await new Promise(resolve => setImmediate(resolve)); assert.equal(requests[0].signal.aborted, true);
  requests[1].resolve(data(secondTarget)); await second; requests[0].resolve(data(target)); await first;
  assert.deepEqual(rendered, ["mysql-b"]); assert.deepEqual(errors, []);
});
test("repeated detail clicks coalesce and stopping after logout prevents late private data from rendering", async () => {
  let release, signal; const rendered = [];
  const reader = details.createReader({ load(_, value) { signal = value; return new Promise(resolve => { release = resolve; }); }, onData(value) { rendered.push(value); }, onError() {} });
  const pending = reader.read(target); assert.equal(reader.read(target), pending);
  await new Promise(resolve => setImmediate(resolve)); reader.stop(); assert.equal(signal.aborted, true);
  release(data(target)); await pending; assert.deepEqual(rendered, []);
});

test("retained detail ages ordinary and capacity independently without replacing successful clocks or zero", () => {
  const base = Date.parse("2026-10-13T07:00:00Z"), until = delay => new Date(base + delay).toISOString();
  const sample = { definition: { key: "redis.memory.bytes", label: "内存", unit: "BYTES", scope: {} }, value: 0, sampledAt: until(0), validUntil: until(45000) };
  const ordinary = { latestAttempt: { ...sample, value: null, missingReason: "TIMEOUT", sampledAt: until(15000) }, lastSuccess: sample, valueState: "LAST_SUCCESS", validUntil: until(45000) };
  const capacity = { latestAttempt: sample, lastSuccess: { ...sample, validUntil: until(180000) }, valueState: "CURRENT", validUntil: until(180000) };
  const original = { ...data(target), target: { target, serviceObservations: [{ availability: "AVAILABLE", validUntil: until(45000) }], collections: [] }, metrics: [ordinary, capacity] };
  const atBoundary = details.ageDetail(original, base + 45000);
  assert.equal(atBoundary.target.serviceObservations[0].availability, "AVAILABLE"); assert.equal(atBoundary.metrics[0].valueState, "LAST_SUCCESS");
  const aged = details.ageDetail(original, base + 45001);
  assert.equal(aged.target.serviceObservations[0].availability, "UNKNOWN"); assert.deepEqual(aged.metrics.map(item => item.valueState), ["EXPIRED", "CURRENT"]);
  assert.equal(monitor.metricView(aged.metrics[0]).text, "0"); assert.match(monitor.metricView(aged.metrics[0]).note, /超时/);
  assert.equal(aged.metrics[0].lastSuccess.sampledAt, until(0)); assert.equal(original.metrics[0].valueState, "LAST_SUCCESS");
  assert.equal(details.ageDetail(original, base + 180001).metrics[1].valueState, "EXPIRED");
});
