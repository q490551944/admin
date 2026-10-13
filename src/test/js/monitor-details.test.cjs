const { test } = require("node:test");
const assert = require("node:assert/strict");
const details = require("../../main/resources/static/monitor/details.js");
const monitor = require("../../main/resources/static/monitor/overview.js");
const target = { id: "redis-a", type: "REDIS" };
const data = identity => ({ generation: 1, serverTime: "2026-10-13T07:00:00Z", target: { target: identity }, metrics: [] });
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
