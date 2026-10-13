const { test } = require("node:test");
const assert = require("node:assert/strict");
const monitor = require("../../main/resources/static/monitor/overview.js");
const at = "2026-10-13T06:00:00Z";
const row = (id, schedulingState = "ACTIVE", states = ["AVAILABLE"], statuses = ["SUCCESS"]) => ({
  target: { id, type: "REDIS", name: id, configurationStatus: schedulingState === "ACTIVE" ? "CONFIGURED" : schedulingState },
  schedulingState, serviceObservations: states.map(availability => ({ availability })), collections: statuses.map(status => ({ status }))
});
function model(rows) {
  return monitor.model({ generation: 1, types: [{ type: "REDIS", targets: rows.map(item => item.target) }] },
    { generation: 1, serverTime: at, targets: rows });
}

test("service summary counts only configured active targets once and keeps collection problems separate", () => {
  const data = model([row("a"), row("b", "ACTIVE", ["AVAILABLE", "DEGRADED"], ["SUCCESS", "UNAUTHORIZED"]),
    row("c", "ACTIVE", ["UNKNOWN", "CONNECTION_FAILED"], ["FAILED"]), row("d", "ACTIVE", [], []),
    row("disabled", "DISABLED"), row("missing", "CONFIGURATION_MISSING"), row("resolving", "RESOLVING")]);
  assert.deepEqual(data.summary, { AVAILABLE: 1, DEGRADED: 1, CONNECTION_FAILED: 1, UNKNOWN: 1 });
  assert.deepEqual(data.configuration, { disabled: 1, missing: 1, resolving: 1, collectionProblems: 2 });
  assert.equal(data.types.length, 6);
  assert.equal(data.types.find(item => item.type === "REDIS").rows.length, 7);
  assert.equal(data.rows[1].collection, "UNAUTHORIZED");
});

test("no observations remain unknown/waiting, and an empty directory still has all six cards", () => {
  assert.equal(model([row("a", "ACTIVE", [], [])]).rows[0].service, "UNKNOWN");
  assert.equal(model([row("a", "ACTIVE", [], [])]).rows[0].collection, "WAITING");
  assert.equal(model([]).types.length, 6);
  assert.deepEqual(model([]).summary, { AVAILABLE: 0, DEGRADED: 0, CONNECTION_FAILED: 0, UNKNOWN: 0 });
});

test("a successful probe cannot hide unknown service bindings or failed metric collection", () => {
  const data = model([row("partial", "ACTIVE", ["AVAILABLE", "UNKNOWN"], ["SUCCESS", "FAILED"])]);
  assert.equal(data.rows[0].service, "UNKNOWN");
  assert.equal(data.rows[0].collection, "FAILED");
  assert.equal(data.configuration.collectionProblems, 1);
});

test("an unsupported capacity cycle does not hide supported ordinary collection", () => {
  assert.equal(model([row("a", "ACTIVE", ["AVAILABLE"], ["SUCCESS", "UNSUPPORTED"])]).rows[0].collection, "PARTIAL");
  assert.equal(model([row("a", "ACTIVE", ["AVAILABLE"], ["UNSUPPORTED"])]).rows[0].collection, "UNSUPPORTED");
});

test("never joins samples to a different directory generation, missing target or duplicate identity", () => {
  const rows = [row("a")];
  const catalog = { generation: 1, types: [{ targets: rows.map(item => item.target) }] };
  assert.throws(() => monitor.model(catalog, { generation: 2, serverTime: at, targets: rows }));
  assert.throws(() => monitor.model(catalog, { generation: 1, serverTime: at, targets: [] }));
  assert.throws(() => model([row("a"), row("a")]));
});

test("a real zero stays zero; missing values have reasons and failures retain the original last success clock", () => {
  const definition = { label: "内存", unit: "BYTES", scope: { kind: "PROCESS", id: "remote-process" } };
  const lastSuccess = { definition, value: 0, sampledAt: at };
  assert.equal(monitor.metricView({ latestAttempt: lastSuccess, lastSuccess }).text, "0");
  const latestAttempt = { definition, value: null, missingReason: "UNAUTHORIZED", sampledAt: "2026-10-13T06:00:10Z" };
  const missing = monitor.metricView({ latestAttempt });
  assert.equal(missing.text, "—");
  assert.equal(missing.note, "未授权");
  const retained = monitor.metricView({ latestAttempt, lastSuccess });
  assert.equal(retained.text, "0");
  assert.equal(retained.note, "上次成功值 · 未授权");
  assert.equal(retained.sample.sampledAt, at);
  assert.match(monitor.scopeText(definition.scope), /进程.*remote-process/);
});
