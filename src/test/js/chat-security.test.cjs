const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../../main/resources/static/chat/app.js"), "utf8");

function harness() {
  const nodes = new Map();
  const element = (tag = "div") => {
    let text = "";
    const attributes = new Map();
    const node = {
      tagName: tag.toUpperCase(), className: "", value: "", disabled: false, hidden: false,
      children: [], parentElement: null, style: {}, dataset: {}, scrollTop: 0, scrollHeight: 1000, clientHeight: 400,
      get textContent() { return text + this.children.map(child => child.textContent).join(""); },
      set textContent(value) { this.replaceChildren(); text = String(value); },
      get firstElementChild() { return this.children[0] || null; },
      replaceChildren(...children) { this.children.slice().forEach(child => child.remove()); text = ""; this.append(...children); },
      insertBefore(child, reference) {
        if (child === reference) return child;
        child.remove();
        const index = reference ? this.children.indexOf(reference) : this.children.length;
        assert.ok(index >= 0, "reference must belong to this parent");
        this.children.splice(index, 0, child);
        child.parentElement = this;
        return child;
      },
      append(...children) { children.forEach(child => this.insertBefore(child, null)); },
      remove() {
        if (this.parentElement) {
          const siblings = this.parentElement.children;
          siblings.splice(siblings.indexOf(this), 1);
          this.parentElement = null;
        }
      },
      querySelectorAll(selector) {
        const matches = child => selector.startsWith(".") ? child.classList.contains(selector.slice(1)) : child.tagName === selector.toUpperCase();
        return this.children.flatMap(child => [...(matches(child) ? [child] : []), ...child.querySelectorAll(selector)]);
      },
      querySelector(selector) { return this.querySelectorAll(selector)[0] || null; },
      addEventListener(name, callback) { this[name] = callback; },
      setAttribute(name, value) { attributes.set(name, String(value)); }, getAttribute(name) { return attributes.get(name) ?? null; },
      focus() {}, showModal() {}, close() {}, scrollTo() {}
    };
    node.classList = {
      contains(name) { return node.className.split(/\s+/).includes(name); },
      toggle(name, force) {
        const add = force ?? !this.contains(name);
        const names = new Set(node.className.split(/\s+/).filter(Boolean));
        if (add) names.add(name); else names.delete(name);
        node.className = [...names].join(" ");
        return add;
      },
      add(...names) { names.forEach(name => this.toggle(name, true)); },
      remove(...names) { names.forEach(name => this.toggle(name, false)); }
    };
    return node;
  };
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
    console, requestAnimationFrame: callback => callback(), fetch: (...args) => fetchHandler(...args)
  });
  vm.runInContext(source + "\nglobalThis.subject = { api, state, ApiError, NativeStompClient, requireLogin, initialize, handleRealtimeMessage, handleMessageAck, handleSocketError, retryMessage, dispatchMessage, loadEarlierMessages, createOptimisticMessage, renderMessages };", context);
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

function messageFixture(h) {
  h.state.currentUser = { id: "1", name: "alice" };
  h.state.activeConversationId = "10";
  h.state.conversations = [{ id: "10", name: "Team", type: "PUBLIC_ROOM", unread: 0 }];
  h.state.messages.set("10", []);
}

function message(id, request = id, sender = "1", time = "2026-09-08T10:00:00.123456") {
  return { id, clientRequestId: request, senderId: sender, senderName: sender === "1" ? "alice" : "bob",
    conversationId: "10", type: "TEXT", body: "hello <script> 😀", status: "SENT", createdAt: time };
}

for (const firstEvent of ["ack", "broadcast"]) {
  test(`${firstEvent} first updates the existing bubble without replaying old messages or replacing images`, () => {
    const h = harness(); messageFixture(h);
    h.state.messages.set("10", [message("10"), { ...message("11", "image", "2"), type: "IMAGE", imageUrl: "/photo.png" }]);
    h.renderMessages();
    const list = h.nodes.get("messageList");
    const previousNodes = list.children.slice();
    const photo = list.querySelector("img");
    const pending = h.createOptimisticMessage({ type: "TEXT", body: "new <script> 😀", createdAt: "2026-09-08T10:01:00" });
    const row = list.children.at(-1);
    const bubble = row.querySelector(".message-bubble");
    const status = row.querySelector(".message-status");
    const avatarClass = row.querySelector(".user-avatar").className;
    assert.equal(row.classList.contains("is-new"), true);
    row.animationend();
    assert.equal(row.classList.contains("is-new"), false);
    const confirmed = { ...message("12", pending.clientRequestId, "1", "2026-09-08T10:01:01"), body: pending.body };
    const ack = () => h.handleMessageAck({ clientRequestId: pending.clientRequestId, message: confirmed });
    const broadcast = () => h.handleRealtimeMessage({ message: confirmed });
    if (firstEvent === "ack") { ack(); broadcast(); } else { broadcast(); ack(); }
    broadcast();
    assert.deepEqual(list.children.slice(0, previousNodes.length), previousNodes);
    assert.equal(list.querySelector("img"), photo);
    assert.equal(list.children.at(-1), row);
    assert.equal(row.querySelector(".message-bubble"), bubble);
    assert.equal(bubble.textContent, pending.body);
    assert.equal(bubble.children.length, 0, "message text must never become markup");
    assert.equal(row.dataset.messageId, "12");
    assert.equal(row.querySelector(".message-status"), status);
    assert.equal(row.querySelector(".user-avatar").className, avatarClass);
    assert.equal(status.classList.contains("settled"), true);
    assert.equal(list.querySelectorAll(".is-new").length, 0);
  });
}

test("failure and retry keep the original message bubble", () => {
  const h = harness(); messageFixture(h);
  const pending = h.createOptimisticMessage({ type: "TEXT", body: "retry me" });
  const row = h.nodes.get("messageList").children.at(-1);
  const bubble = row.querySelector(".message-bubble");
  row.animationend();
  h.handleSocketError({ clientRequestId: pending.clientRequestId, message: "rejected" });
  assert.equal(row.querySelector(".message-bubble"), bubble);
  assert.equal(bubble.classList.contains("failed"), true);
  const sent = [];
  h.state.socket = { readyConversationId: "10", sendMessage: payload => { sent.push(payload); return true; } };
  row.querySelector("button").click();
  assert.equal(sent[0].clientRequestId, pending.clientRequestId);
  assert.equal(row.querySelector(".message-status").textContent, "发送中…");
  assert.equal(bubble.classList.contains("failed"), false);
  assert.equal(row.classList.contains("is-new"), false);
  assert.equal(h.nodes.get("messageList").children.at(-1), row);
});

test("new broadcasts preserve history reading position while local sends reveal the new message", () => {
  const h = harness(); messageFixture(h);
  const scroller = h.document.getElementById("messageScroller");
  scroller.scrollTop = 150;
  h.handleRealtimeMessage({ message: message("10") });
  assert.equal(scroller.scrollTop, 150);
  h.createOptimisticMessage({ type: "TEXT", body: "show my message" });
  assert.equal(scroller.scrollTop, scroller.scrollHeight);
  scroller.scrollTop = scroller.scrollHeight - scroller.clientHeight;
  h.handleRealtimeMessage({ message: message("11", "remote", "2") });
  assert.equal(scroller.scrollTop, scroller.scrollHeight);
});

test("prepending history preserves existing nodes and recomputes sender grouping without animation", async () => {
  const h = harness(); messageFixture(h);
  h.state.messages.set("10", [message("11", "later", "2", "2026-09-08T10:01:00")]);
  h.state.cursors.set("10", { beforeCursor: "older", afterCursor: "latest", hasMore: true });
  h.renderMessages();
  const list = h.nodes.get("messageList");
  const row = list.children.at(-1);
  const bubble = row.querySelector(".message-bubble");
  assert.equal(row.classList.contains("grouped"), false);
  const scroller = h.nodes.get("messageScroller");
  scroller.scrollTop = 75;
  h.fetch(async () => {
    scroller.scrollHeight += 200;
    return response(200, { items: [message("10", "earlier", "2", "2026-09-08T10:00:00")], hasMore: false });
  });
  await h.loadEarlierMessages();
  assert.equal(list.children.at(-1), row);
  assert.equal(row.querySelector(".message-bubble"), bubble);
  assert.equal(row.classList.contains("grouped"), true);
  assert.equal(row.querySelector(".sender-line").classList.contains("hidden"), true);
  assert.equal(list.querySelectorAll(".is-new").length, 0);
  assert.equal(scroller.scrollTop, 275);
});
