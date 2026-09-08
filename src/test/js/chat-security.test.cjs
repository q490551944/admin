const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../../main/resources/static/chat/app.js"), "utf8");

function harness() {
  const nodes = new Map();
  const element = () => ({
    value: "", disabled: false, hidden: false, textContent: "", children: [],
    classList: { add() {}, remove() {}, toggle() {} },
    replaceChildren(...children) { this.children = children; },
    querySelector() { return element(); }, querySelectorAll() { return []; },
    addEventListener() {}, setAttribute() {}, append() {}, focus() {}
  });
  const document = {
    body: element(),
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); },
    querySelector: () => element(), querySelectorAll: () => [], createElement: element, addEventListener() {}
  };
  const timers = new Map();
  let nextTimer = 0;
  const schedule = (callback, delay) => { timers.set(++nextTimer, { callback, delay }); return nextTimer; };
  const redirects = [];
  const location = {
    href: "http://localhost:8090/chat/?mode=auto", search: "?mode=auto",
    protocol: "http:", host: "localhost:8090", assign: url => redirects.push(url)
  };
  class Socket {
    static OPEN = 1;
    static created = [];
    constructor(url) { this.url = url; this.readyState = 1; this.listeners = {}; this.sent = []; Socket.created.push(this); }
    addEventListener(name, callback) { this.listeners[name] = callback; }
    send(frame) { this.sent.push(frame); }
    close() { this.readyState = 3; this.listeners.close?.({ code: 1000 }); }
  }
  let fetchHandler = () => new Promise(() => {}); // Keep automatic startup pending; test methods remain real.
  const context = vm.createContext({
    window: { CHAT_CONFIG: { mode: "auto" }, location, setTimeout: schedule, setInterval: schedule,
      clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id), addEventListener() {} },
    document, URL, URLSearchParams, Headers, FormData, AbortController, TextEncoder, WebSocket: Socket,
    console, fetch: (...args) => fetchHandler(...args)
  });
  vm.runInContext(source + "\nglobalThis.subject = { api, state, ApiError, NativeStompClient, requireLogin, initialize };", context);
  return { ...context.subject, document, nodes, timers, redirects, Socket,
    fetch: handler => { fetchHandler = handler; },
    flush: () => new Promise(resolve => setImmediate(resolve))
  };
}

function response(status, value) {
  return { ok: status >= 200 && status < 300, status, headers: new Headers({ "content-type": "application/json" }),
    json: async () => value };
}

test("REST mutations carry the session CSRF token and never a client owner", async () => {
  const h = harness();
  const calls = [];
  h.api.csrf = { headerName: "X-CSRF-TOKEN", token: "session-token" };
  h.fetch(async (url, options) => { calls.push({ url, options }); return response(201, { id: "9007199254740993" }); });
  await h.api.createRoom("Team");
  assert.equal(calls[0].url, "/api/chat/v1/rooms");
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls[0].options.headers.get("X-CSRF-TOKEN"), "session-token");
  assert.deepEqual(JSON.parse(calls[0].options.body), { name: "Team" });
  assert.equal(calls[0].options.credentials, "include");
  assert.equal(calls[0].options.cache, "no-store");
});

test("session reads use resource URLs and DELETE accepts an empty 204 response", async () => {
  const h = harness();
  const calls = [];
  h.fetch(async (url, options) => {
    calls.push({ url, options });
    if (url.endsWith("/csrf-token")) return response(200, { headerName: "X-CSRF-TOKEN", token: "fresh-token" });
    if (options.method === "DELETE") return {
      ok: true, status: 204, headers: new Headers({ "content-type": "application/json" }),
      json: async () => { throw new Error("204 must not be parsed as JSON"); },
      text: async () => { throw new Error("204 has no body"); }
    };
    return response(200, { id: "1" });
  });
  await h.api.loadSession();
  assert.equal(await h.api.logout(), null);
  assert.deepEqual(calls.map(call => call.url), [
    "/api/chat/v1/csrf-token", "/api/chat/v1/sessions/current", "/api/chat/v1/sessions/current"
  ]);
  assert.equal(calls[2].options.method, "DELETE");
  assert.equal(calls[2].options.headers.get("X-CSRF-TOKEN"), "fresh-token");
  assert.equal(calls[2].options.credentials, "include");
});

test("login creates a session using POST with the fetched CSRF token", async () => {
  const loginSource = fs.readFileSync(path.join(__dirname, "../../main/resources/static/chat/login.js"), "utf8");
  let submit;
  const form = { addEventListener: (name, callback) => { submit = callback; }, reset() {} };
  const button = { disabled: false };
  const error = { textContent: "" };
  const calls = [];
  const redirects = [];
  const context = vm.createContext({
    document: { getElementById: id => ({ loginForm: form, loginButton: button, loginError: error })[id] },
    window: { CHAT_CONFIG: { apiBaseUrl: "/api/chat/v1/" }, location: { replace: url => redirects.push(url) } },
    URLSearchParams,
    FormData: class { *[Symbol.iterator]() { yield ["username", "alice"]; yield ["password", "secret"]; } },
    fetch: async (url, options) => {
      calls.push({ url, options });
      return url.endsWith("/csrf-token") ? response(200, { headerName: "X-CSRF-TOKEN", token: "login-token" })
        : response(201, { id: "1" });
    }
  });
  vm.runInContext(loginSource, context);
  await submit({ preventDefault() {} });
  assert.deepEqual(calls.map(call => call.url), ["/api/chat/v1/csrf-token", "/api/chat/v1/sessions"]);
  assert.equal(calls[1].options.method, "POST");
  assert.equal(calls[1].options.headers["X-CSRF-TOKEN"], "login-token");
  assert.equal(calls[1].options.body.get("username"), "alice");
  assert.equal(calls[1].options.credentials, "include");
  assert.equal(error.textContent, "");
  assert.equal(button.disabled, false);
  assert.deepEqual(redirects, ["./index.html?mode=live"]);
});

test("HTTP 401 removes private state and UI and cannot fall back to demo", async () => {
  const h = harness();
  h.state.conversations = [{ id: "private" }];
  h.state.messages.set("private", [{ body: "secret" }]);
  h.state.cursors.set("private", "cursor");
  h.fetch(async () => response(401, { code: "SESSION_EXPIRED" }));
  await h.initialize();
  assert.equal(h.state.authExpired, true);
  assert.equal(h.state.usingDemo, false);
  assert.equal(h.state.conversations.length, 0);
  assert.equal(h.state.messages.size, 0);
  assert.equal(h.state.cursors.size, 0);
  assert.equal(h.document.body.hidden, true);
  assert.equal(h.redirects[0], "http://localhost:8090/chat/login.html");
});

test("an in-flight successful response is discarded after another request expires the session", async () => {
  const h = harness();
  let complete;
  h.fetch(() => new Promise(resolve => { complete = resolve; }));
  const pending = h.api.getConversations();
  h.requireLogin();
  complete(response(200, [{ id: "private", lastMessagePreview: "secret" }]));
  await assert.rejects(pending, error => error.status === 401);
  assert.equal(h.state.messages.size, 0);
  assert.equal(h.redirects.length, 1);
});

test("expired HTTP preflight never creates a WebSocket or schedules reconnect", async () => {
  const h = harness();
  h.fetch(async () => response(401, { code: "SESSION_EXPIRED" }));
  const client = new h.NativeStompClient("/ws/chat");
  h.state.socket = client;
  await client.connect();
  assert.equal(h.Socket.created.length, 0);
  assert.equal(client.shouldReconnect, false);
  assert.equal(client.reconnectCount, 0);
});

test("CONNECT carries CSRF and unescaped host, and Tomcat policy close stops retry immediately", async () => {
  const h = harness();
  h.fetch(async () => response(200, { id: "1" }));
  h.api.csrf = { headerName: "X-CSRF-TOKEN", token: "session-token" };
  const client = new h.NativeStompClient("/ws/chat");
  h.state.socket = client;
  await client.connect();
  const socket = h.Socket.created[0];
  socket.listeners.open();
  assert.match(socket.sent[0], /X-CSRF-TOKEN:session-token/);
  assert.match(socket.sent[0], /host:localhost:8090/);
  socket.listeners.close({ code: 1008, reason: "This connection was established under an authenticated HTTP session that has ended." });
  assert.equal(client.shouldReconnect, false);
  assert.equal(client.reconnectCount, 0);
  assert.equal(h.redirects.length, 1);
});

test("STOMP session error cancels an already scheduled reconnect and ignores old frames", async () => {
  const h = harness();
  h.fetch(async () => response(200, { id: "1" }));
  const client = new h.NativeStompClient("/ws/chat");
  h.state.socket = client;
  await client.connect();
  const oldSocket = h.Socket.created[0];
  client.handleDisconnect();
  const timer = client.reconnectTimer;
  assert.equal(h.timers.has(timer), true);
  client.handleFrame("ERROR", {}, JSON.stringify({ status: 401, code: "SESSION_EXPIRED" }));
  assert.equal(h.timers.has(timer), false);
  oldSocket.listeners.message({ data: "CONNECTED\nversion:1.2\n\n\0" });
  assert.equal(client.connected, false);
  assert.equal(client.shouldReconnect, false);
  assert.equal(h.redirects.length, 1);
});

test("cancel during HTTP preflight prevents a late response from opening a socket", async () => {
  const h = harness();
  let complete;
  h.fetch(() => new Promise(resolve => { complete = resolve; }));
  const client = new h.NativeStompClient("/ws/chat");
  const pending = client.connect();
  client.disconnect(false);
  complete(response(200, { id: "1" }));
  await pending;
  assert.equal(h.Socket.created.length, 0);
});
