const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const folder = path.join(__dirname, "../../main/resources/static/monitor");
const sessionSource = fs.readFileSync(path.join(folder, "session.js"), "utf8");
const appSource = fs.readFileSync(path.join(folder, "app.js"), "utf8");
const json = (data, status = 200) => new Response(JSON.stringify(data), { status });
const employee = { id: "1", username: "monitor-allowed" };

function harness(fetcher, page = "index") {
  const nodes = new Map();
  const events = {};
  const redirects = [];
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, {
      hidden: false, disabled: false, textContent: "", dataset: {}, value: "", attributes: {},
      setAttribute(name, value) { this.attributes[name] = value; },
      addEventListener(name, listener) { this[name] = listener; }
    });
    return nodes.get(id);
  };
  const form = node("login-form");
  form.elements = [node("username"), node("password"), node("submit")];
  form.elements.username = node("username");
  form.elements.password = node("password");
  const document = {
    body: { dataset: { page } }, visibilityState: "visible",
    getElementById: id => id === "login-form" && page !== "login" ? null : node(id),
    addEventListener(name, listener) { events[name] = listener; }
  };
  const window = {
    location: { href: `https://example.test/admin/monitor/${page === "login" ? "login" : "index"}.html`,
      replace(url) { redirects.push(url); } },
    fetch: fetcher, setTimeout, clearTimeout,
    addEventListener(name, listener) { events[name] = listener; }
  };
  const context = vm.createContext({ window, document, URL, URLSearchParams, AbortController });
  vm.runInContext(sessionSource, context);
  return { window, document, node, events, redirects,
    client: window.MonitorSession.createClient(),
    app() { vm.runInContext(appSource, context); } };
}

test("login uses context-local Cookie and fresh CSRF, then validates the allowlist", async () => {
  const requests = [];
  const responses = [json({ headerName: "X-CSRF-TOKEN", token: "before-login" }), json(employee, 201), json(employee),
    json({ headerName: "X-CSRF-TOKEN", token: "after-login" }), new Response(null, { status: 204 })];
  const h = harness(async (url, options) => { requests.push({ url, options }); return responses.shift(); });
  assert.equal((await h.client.login("a&b", "p=secret")).username, employee.username);
  await h.client.logout();
  assert.deepEqual(requests.map(request => new URL(request.url).pathname), [
    "/admin/api/monitor/v1/csrf-token", "/admin/api/monitor/v1/sessions", "/admin/api/monitor/v1/sessions/current",
    "/admin/api/monitor/v1/csrf-token", "/admin/api/monitor/v1/sessions/current"
  ]);
  for (const { options } of requests) {
    assert.equal(options.credentials, "same-origin");
    assert.equal(options.cache, "no-store");
    assert.equal(options.redirect, "error");
    assert.equal(options.headers?.["X-Chat-Session"], undefined);
  }
  assert.equal(requests[1].options.body, "username=a%26b&password=p%3Dsecret");
  assert.equal(requests[1].options.headers["X-CSRF-TOKEN"], "before-login");
  assert.equal(requests[4].options.headers["X-CSRF-TOKEN"], "after-login");
  assert.equal(requests[4].options.method, "DELETE");
});

test("successful authentication does not treat a denied employee as authorized", async () => {
  const responses = [json({ headerName: "X-CSRF-TOKEN", token: "a" }), json(employee, 201),
    json({ code: "ACCESS_DENIED", message: "jdbc:mysql://secret" }, 403)];
  const h = harness(async () => responses.shift());
  await assert.rejects(h.client.login("user", "password"), error =>
    error.code === "ACCESS_DENIED" && !error.message.includes("jdbc:"));
});

test("only fixed public errors leave the client, including malformed and unknown responses", async () => {
  for (const response of [json({ code: "SQL_FAILURE", message: "password=secret" }, 500),
    new Response("internal JDBC details", { status: 500 }), json({ code: "__proto__", message: "secret" }, 500)]) {
    const h = harness(async () => response);
    await assert.rejects(h.client.current(), error => error.code === "REQUEST_FAILED" && error.message === "请求未完成，请稍后重试。");
  }
  const h = harness(async () => json(null));
  await assert.rejects(h.client.current(), error => error.code === "REQUEST_FAILED");
});

test("an identity-service outage preserves the session so the same client can recover", async () => {
  const responses = [json({ code: "IDENTITY_UNAVAILABLE" }, 503), json(employee)];
  const h = harness(async () => responses.shift());
  await assert.rejects(h.client.current(), error => error.code === "IDENTITY_UNAVAILABLE");
  assert.equal((await h.client.current()).username, employee.username);
});

test("invalid CSRF metadata prevents a credential-bearing POST", async () => {
  let calls = 0;
  const h = harness(async () => { calls++; return json({ headerName: "Cookie", token: "a" }); });
  await assert.rejects(h.client.login("user", "password"), error => error.code === "REQUEST_FAILED");
  assert.equal(calls, 1);
});

test("network details are hidden and cancellation propagates to fetch", async () => {
  const h = harness(async () => { throw new Error("connection to internal-password-server failed"); });
  await assert.rejects(h.client.current(), error => error.code === "NETWORK_ERROR" && !error.message.includes("internal"));
  const controller = new AbortController();
  const canceled = harness((url, options) => new Promise((resolve, reject) => {
    options.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
  }));
  const result = canceled.client.current(controller.signal);
  controller.abort();
  await assert.rejects(result, /aborted/);
});

const settle = () => new Promise(resolve => setImmediate(resolve));

test("a late successful identity response cannot restore the view after logout starts", async () => {
  let reads = 0;
  let lateRead;
  const h = harness(async (url, options) => {
    if (url.endsWith("csrf-token")) return json({ headerName: "X-CSRF-TOKEN", token: "new" });
    if (options.method === "DELETE") return new Response(null, { status: 204 });
    if (++reads === 1) return json(employee);
    return new Promise(resolve => { lateRead = resolve; }); // Simulate a transport ignoring abort.
  });
  h.app();
  await settle();
  assert.equal(h.node("identity").hidden, false);
  h.events.visibilitychange();
  assert.equal(h.node("identity").hidden, true);
  await h.node("logout-button").click();
  lateRead(json(employee));
  await settle();
  assert.equal(h.node("identity").hidden, true);
  assert.equal(h.node("identity-name").textContent, "");
  assert.deepEqual(h.redirects, ["./login.html"]);
});

test("returning from browser history rechecks identity and hides stale content on 503", async () => {
  let responses = [json(employee), json({ code: "IDENTITY_UNAVAILABLE" }, 503)];
  const h = harness(async () => responses.shift());
  h.app();
  await settle();
  h.events.pagehide();
  assert.equal(h.node("identity").hidden, true);
  h.events.pageshow({ persisted: true });
  await settle();
  assert.equal(h.node("status-title").textContent, "认证服务暂不可用");
  assert.equal(h.node("identity").hidden, true);
  assert.equal(h.node("logout-button").hidden, false);
  assert.deepEqual(h.redirects, []);
});

test("bad credentials leave the form usable and clear the password", async () => {
  const responses = [json({ code: "SESSION_EXPIRED" }, 401), json({ headerName: "X-CSRF-TOKEN", token: "a" }),
    json({ code: "INVALID_CREDENTIALS", message: "password=secret" }, 401)];
  const h = harness(async () => responses.shift(), "login");
  h.app();
  await settle();
  h.node("username").value = "user";
  h.node("password").value = "wrong";
  await h.node("login-form").submit({ preventDefault() {} });
  assert.equal(h.node("login-form").hidden, false);
  assert.equal(h.node("password").value, "");
  assert.equal(h.node("password").disabled, false);
  assert.equal(h.node("status-description").textContent, "用户名或密码错误，或账号已停用。");
});

test("logout in another tab during login suppresses a stale identity and rechecks after the write", async () => {
  let readCount = 0;
  let completeOldRead;
  const h = harness(async (url, options) => {
    if (url.endsWith("csrf-token")) return json({ headerName: "X-CSRF-TOKEN", token: "a" });
    if (options.method === "POST") return json(employee, 201);
    if (++readCount === 2) return new Promise(resolve => { completeOldRead = resolve; });
    return json({ code: "SESSION_EXPIRED" }, 401);
  }, "login");
  let broadcast;
  h.window.BroadcastChannel = class {
    constructor(name) { assert.equal(name, "monitor-session:/admin/api/monitor/v1/"); broadcast = this; }
    addEventListener(event, listener) { this.receive = listener; }
    postMessage() { assert.fail("Login never broadcasts credentials or identity"); }
  };
  h.app();
  await settle();
  h.node("username").value = "user";
  h.node("password").value = "password";
  const login = h.node("login-form").submit({ preventDefault() {} });
  await settle();
  broadcast.receive({ data: "signed-out" });
  completeOldRead(json(employee));
  await login;
  await settle();
  assert.equal(readCount, 3);
  assert.deepEqual(h.redirects, []);
  assert.equal(h.node("login-form").hidden, false);
  assert.equal(h.node("identity").hidden, true);
});
