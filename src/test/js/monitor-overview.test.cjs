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

test("backend expiry marks a retained zero as old and does not invent a zero for never-observed metrics", () => {
  const sample = { definition: { label: "容量", unit: "BYTES", scope: {} }, value: 0, sampledAt: at };
  const expired = monitor.metricView({ latestAttempt: sample, lastSuccess: sample, valueState: "EXPIRED" });
  assert.equal(expired.text, "0"); assert.equal(expired.note, "已过期 · 上次成功值"); assert.equal(expired.state, "EXPIRED");
  const empty = monitor.metricView({ latestAttempt: { ...sample, value: null, missingReason: "WAITING_SAMPLE" }, valueState: "NO_DATA" });
  assert.equal(empty.text, "—"); assert.equal(empty.note, "等待采样"); assert.equal(empty.sample, null);
});

test("combined filters match active summary counts, include configuration exceptions and exclude disabled targets", () => {
  const rows = model([row("ok"), row("bad", "ACTIVE", ["AVAILABLE"], ["FAILED"]),
    row("missing", "CONFIGURATION_MISSING"), row("disabled", "DISABLED")]).rows;
  assert.deepEqual(rows.filter(item => monitor.matches(item, { type: "REDIS", service: "AVAILABLE", collection: "FAILED", exceptional: true })).map(item => item.target.id), ["bad"]);
  assert.equal(rows.filter(item => monitor.matches(item, { service: "AVAILABLE" })).length, 2);
  assert.deepEqual(rows.filter(item => monitor.matches(item, { exceptional: true })).map(item => item.target.id), ["bad", "missing"]);
  assert.equal(rows.filter(item => monitor.matches(item, { type: "MYSQL" })).length, 0);
});

test("returning to a retained snapshot uses each server deadline, preserves failure priority and never mutates the cache", () => {
  const atMillis = Date.parse(at);
  const deadline = delay => new Date(atMillis + delay).toISOString();
  const original = { targets: [{ ...row("a"), coreMetrics: [
    { valueState: "CURRENT", validUntil: deadline(45000) }, { valueState: "CURRENT", validUntil: deadline(180000) }],
    serviceObservations: [{ availability: "AVAILABLE", validUntil: deadline(45000) }],
    collections: [{ status: "SUCCESS", validUntil: deadline(45000), valuesValidUntil: deadline(45000) },
      { status: "FAILED", validUntil: deadline(90000), valuesValidUntil: deadline(45000) }] }] };
  assert.equal(monitor.ageSnapshot(original, atMillis + 45000).targets[0].serviceObservations[0].availability, "AVAILABLE");
  const aged = monitor.ageSnapshot(original, atMillis + 45001).targets[0];
  assert.equal(aged.serviceObservations[0].availability, "UNKNOWN");
  assert.deepEqual(aged.coreMetrics.map(item => item.valueState), ["EXPIRED", "CURRENT"]);
  assert.deepEqual(aged.collections.map(item => item.status), ["STALE", "FAILED"]);
  assert.equal(original.targets[0].coreMetrics[0].valueState, "CURRENT");
});

function pollingHarness() {
  let millis = 0, visible = true, handle = 0;
  const timers = new Map(), reads = [], rendered = [], errors = [];
  const poller = monitor.createPoller({ now: () => millis, visible: () => visible,
    schedule(callback, delay) { const id = ++handle; timers.set(id, { callback, at: millis + delay }); return id; },
    cancel(id) { timers.delete(id); },
    load(signal) { return new Promise((resolve, reject) => reads.push({ signal, resolve, reject })); },
    onData(data) { rendered.push(data); }, onError(error) { errors.push(error); }
  });
  return { poller, reads, rendered, errors,
    async flush() { await new Promise(resolve => setImmediate(resolve)); },
    async advance(delay) { millis += delay; for (const [id, timer] of [...timers]) if (timer.at <= millis) { timers.delete(id); timer.callback(); } await this.flush(); },
    setVisible(value) { visible = value; }, timers };
}

test("manual and automatic reads share one pending request and continue on the 15-second cadence", async () => {
  const h = pollingHarness(); const first = h.poller.start(); await h.flush();
  assert.equal(h.poller.refresh(), first); await h.advance(15000); assert.equal(h.reads.length, 1);
  h.reads[0].resolve("first"); await first; await h.advance(1); assert.equal(h.reads.length, 2);
  h.reads[1].resolve("next"); await h.flush(); await h.advance(14999); assert.equal(h.reads.length, 2);
  await h.advance(1); assert.equal(h.reads.length, 3); h.poller.stop();
});

test("paused pages allow manual reads, retain pause across background auth checks and resume immediately", async () => {
  const h = pollingHarness(); const first = h.poller.start(); await h.flush(); h.reads[0].resolve("first"); await first;
  await h.poller.setPaused(true); await h.advance(60000); assert.equal(h.reads.length, 1);
  const manual = h.poller.refresh(); await h.flush(); h.reads[1].resolve("manual"); await manual;
  h.setVisible(false); h.poller.suspend(); await h.advance(60000); h.setVisible(true); await h.poller.start();
  assert.equal(h.poller.state().paused, true); assert.equal(h.reads.length, 2);
  const resumed = h.poller.setPaused(false); await h.flush(); assert.equal(h.reads.length, 3);
  h.reads[2].resolve("resumed"); await resumed; h.poller.stop();
});

test("late aborted responses cannot overwrite a resumed page or restore private data after stop", async () => {
  const h = pollingHarness(); const old = h.poller.start(); await h.flush(); h.poller.suspend();
  assert.equal(h.reads[0].signal.aborted, true);
  const fresh = h.poller.start(); await h.flush(); h.reads[1].resolve("fresh"); await fresh;
  h.reads[0].resolve("late"); await old; assert.deepEqual(h.rendered, ["fresh"]);
  const denied = h.poller.refresh(); await h.flush(); h.poller.stop(); h.reads[2].resolve("private"); await denied;
  assert.deepEqual(h.rendered, ["fresh"]); assert.equal(h.timers.size, 0);
});
