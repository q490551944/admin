const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../../main/resources/static/chat/app.js"), "utf8");
const sessionSource = fs.readFileSync(path.join(__dirname, "../../main/resources/static/chat/session.js"), "utf8");
function storage() {
  const values = new Map();
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
}

function harness() {
  const nodes = new Map();
  const observers = [];
  const element = (tag = "div") => {
    let text = "";
    const attributes = new Map();
    const node = {
      tagName: tag.toUpperCase(), className: "", value: "", disabled: false, hidden: false,
      children: [], parentElement: null, style: {}, dataset: {}, scrollTop: 0, scrollHeight: 1000, clientHeight: 400,
      get isConnected() { return this === document.body || [...nodes.values()].includes(this) || Boolean(this.parentElement?.isConnected); },
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
      removeEventListener(name, callback) { if (this[name] === callback) delete this[name]; },
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
  const windowEvents = {};
  const location = {
    href: "http://localhost:8090/chat/?mode=auto", search: "?mode=auto",
    protocol: "http:", host: "localhost:8090", origin: "http://localhost:8090", assign: url => redirects.push(url)
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
    window: { CHAT_CONFIG: { mode: "auto" }, location, sessionStorage: storage(), setTimeout: schedule, setInterval: schedule,
      clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
      navigator: { onLine: true }, addEventListener(name, callback) { windowEvents[name] = callback; } },
    document, URL, URLSearchParams, Headers, FormData, AbortController, TextEncoder, WebSocket: Socket,
    MutationObserver: class { constructor(callback) { observers.push(callback); } observe() {} },
    console, requestAnimationFrame: callback => callback(), fetch: (...args) => fetchHandler(...args)
  });
  vm.runInContext(sessionSource, context);
  vm.runInContext(source + "\nglobalThis.subject = { api, state, ApiError, NativeStompClient, requireLogin, initialize, mergeMessages, handleRealtimeMessage, handleMessageAck, handleSocketError, retryMessage, dispatchMessage, syncConversation, loadEarlierMessages, ackTimers, setupRealtime, createOptimisticMessage, openDirectDialog, searchPeople, createDirectConversation, renderPeople, renderMessages, renderDetails, sendImageMessage, normalizeMessage, bindEvents, privateImageUrl, releaseMediaUrl, mediaUrls, mediaBlobs, loadPrivateImage, openOriginalImage };", context);
  return { ...context.subject, document, nodes, timers, redirects, Socket, window: context.window, windowEvents,
    fetch: handler => { fetchHandler = handler; },
    flush: () => new Promise(resolve => setImmediate(() => { observers.forEach(callback => callback()); resolve(); }))
  };
}

function response(status, value) {
  return { ok: status >= 200 && status < 300, status, headers: new Headers({ "content-type": "application/json" }),
    json: async () => value };
}

test("window sessions send independent IDs without cookies and clearing one leaves the other intact", async () => {
  const alice = harness(), bob = harness();
  for (const [h, id] of [[alice, "alice-session"], [bob, "bob-session"]]) {
    h.window.sessionStorage.setItem("chat.sessionId", id);
    h.fetch(async (url, options) => {
      assert.equal(options.headers.get("X-Chat-Session"), id);
      assert.equal(options.credentials, "omit");
      return response(200, { id });
    });
    assert.equal((await h.api.getCurrentUser()).id, id);
  }
  alice.requireLogin();
  assert.equal(alice.window.sessionStorage.getItem("chat.sessionId"), null);
  assert.equal((await bob.api.getCurrentUser()).id, "bob-session");
});

test("late session headers cannot restore a cleared session or undo an ID rotation", async () => {
  const h = harness();
  h.window.sessionStorage.setItem("chat.sessionId", "old");
  const pending = [];
  h.fetch(() => new Promise(resolve => pending.push(resolve)));
  const first = h.window.ChatSession.fetch("/api/chat/v1/sessions/current");
  const second = h.window.ChatSession.fetch("/api/chat/v1/sessions/current");
  const rotated = response(200, {}); rotated.headers.set("X-Chat-Session", "rotated");
  pending[0](rotated); await first;
  const stale = response(200, {}); stale.headers.set("X-Chat-Session", "old");
  pending[1](stale); await second;
  assert.equal(h.window.sessionStorage.getItem("chat.sessionId"), "rotated");
  const third = h.window.ChatSession.fetch("/api/chat/v1/sessions/current");
  h.window.ChatSession.clear();
  pending[2](rotated); await third;
  assert.equal(h.window.sessionStorage.getItem("chat.sessionId"), null);
});

test("session headers never leave the same-origin chat API and private images use that session", async () => {
  const h = harness();
  let calls = 0;
  h.window.sessionStorage.setItem("chat.sessionId", "private-session");
  h.fetch(async (url, options) => {
    calls++;
    assert.equal(options.headers.get("X-Chat-Session"), "private-session");
    assert.equal(options.credentials, "omit");
    return { ...response(200, {}), blob: async () => new Blob(["bytes"], { type: "image/png" }) };
  });
  await assert.rejects(h.window.ChatSession.fetch("https://external.invalid/api/chat/v1/sessions/current"));
  await assert.rejects(h.window.ChatSession.fetch("/sys/users"));
  await assert.rejects(h.api.imageBlob("https://external.invalid/api/chat/v1/attachments/1/content"));
  assert.equal(calls, 0);
  assert.equal((await h.api.imageBlob("/api/chat/v1/attachments/1/thumbnail")).type, "image/png");
  assert.equal(calls, 1);
});

test("shared media thumbnails use the window session and never a cookie image request", async () => {
  const h = harness();
  h.window.sessionStorage.setItem("chat.sessionId", "private-session");
  h.state.messages.set("1", [{ type: "IMAGE", imageUrl: "/api/chat/v1/attachments/2/thumbnail" }]);
  let calls = 0;
  h.fetch(async (url, options) => {
    calls++;
    assert.equal(url, "/api/chat/v1/attachments/2/thumbnail");
    assert.equal(options.headers.get("X-Chat-Session"), "private-session");
    assert.equal(options.credentials, "omit");
    return { ...response(200, {}), blob: async () => new Blob(["bytes"], { type: "image/png" }) };
  });
  h.renderDetails({ id: "1", type: "DIRECT_MESSAGE", name: "Bob" });
  const image = h.nodes.get("mediaGrid").children[0];
  assert.equal(image.getAttribute("src"), null);
  await h.flush();
  assert.equal(calls, 1);
  assert.match(image.getAttribute("src"), /^blob:/);
  h.requireLogin();
});

test("WebSocket obtains a CSRF protected single-use ticket instead of putting the session ID in its URL", async () => {
  const h = harness();
  h.window.sessionStorage.setItem("chat.sessionId", "long-lived-session");
  h.api.csrf = { headerName: "X-CSRF-TOKEN", token: "csrf" };
  h.fetch(async (url, options) => {
    assert.equal(url, "/api/chat/v1/websocket-tickets");
    assert.equal(options.method, "POST");
    assert.equal(options.headers.get("X-CSRF-TOKEN"), "csrf");
    return response(201, { ticket: "one-time-ticket" });
  });
  const client = new h.NativeStompClient("/ws/chat");
  await client.connect();
  assert.equal(h.Socket.created[0].url, "ws://localhost:8090/ws/chat?ticket=one-time-ticket");
});

test("thumbnail eviction leaves in-flight consumers working and image load releases each URL", async () => {
  const h = harness();
  h.fetch(async () => ({ ...response(200, {}), blob: async () => new Blob(["image"]) }));
  const images = Array.from({ length: 35 }, (_, index) => {
    const image = h.document.createElement("img");
    h.document.body.append(image);
    h.loadPrivateImage(image, `/api/chat/v1/attachments/${index + 1}/thumbnail`);
    return image;
  });
  await h.flush();
  assert.equal(h.mediaBlobs.size, 32);
  for (const image of images) {
    const url = image.getAttribute("src");
    assert.equal(await (await fetch(url)).text(), "image");
    const source = image.dataset.source;
    image.load();
    assert.equal(image.dataset.source, source);
    await assert.rejects(fetch(url));
  }
  assert.equal(h.mediaUrls.size, 0);
  h.requireLogin();
});

test("original image windows release uncached content when closed", async () => {
  const h = harness();
  h.fetch(async () => ({ ...response(200, {}), blob: async () => new Blob(["original"]) }));
  let openedUrl;
  const preview = { closed: false, location: { replace(url) { openedUrl = url; } }, close() { this.closed = true; } };
  h.window.open = () => preview;
  await h.openOriginalImage("/api/chat/v1/attachments/1/content");
  assert.equal(h.mediaBlobs.size, 0);
  assert.equal(await (await fetch(openedUrl)).text(), "original");
  preview.closed = true;
  for (const timer of [...h.timers.values()]) if (timer.delay === 1000) timer.callback();
  await assert.rejects(fetch(openedUrl));
  assert.equal(h.mediaUrls.size, 0);
  h.requireLogin();
});

test("removing a lazy image releases its URL and discards an unfinished response", async () => {
  for (const pending of [true, false]) {
    const h = harness();
    let complete;
    h.fetch(() => new Promise(resolve => { complete = resolve; }));
    const image = h.document.createElement("img");
    image.loading = "lazy";
    h.document.body.append(image);
    h.loadPrivateImage(image, "/api/chat/v1/attachments/1/thumbnail");
    const success = { ...response(200, {}), blob: async () => new Blob(["image"]) };
    if (!pending) { complete(success); await h.flush(); }
    const url = image.getAttribute("src");
    image.remove();
    await h.flush();
    if (pending) { complete(success); await h.flush(); }
    assert.equal(h.mediaUrls.size, 0);
    assert.equal(image.dataset.source, undefined);
    if (pending) assert.equal(image.getAttribute("src"), null);
    else await assert.rejects(fetch(url));
    h.requireLogin();
  }
});

test("replacing an image source cancels its old response without cancelling the new load", async () => {
  const h = harness();
  const pending = [];
  h.fetch(() => new Promise(resolve => pending.push(resolve)));
  const image = h.document.createElement("img");
  h.document.body.append(image);
  h.loadPrivateImage(image, "/api/chat/v1/attachments/1/thumbnail");
  h.loadPrivateImage(image, "/api/chat/v1/attachments/2/thumbnail");
  const success = { ...response(200, {}), blob: async () => new Blob(["image"]) };
  pending[1](success); await h.flush();
  const url = image.getAttribute("src");
  pending[0](success); await h.flush();
  assert.equal(image.getAttribute("src"), url);
  assert.equal(h.mediaUrls.size, 1);
  assert.equal(await (await fetch(url)).text(), "image");
  image.load();
  assert.equal(h.mediaUrls.size, 0);
  h.requireLogin();
});

test("opening the direct dialog loads actual employees and does not offer demo people", async () => {
  const h = harness();
  const calls = [];
  h.fetch(async url => { calls.push(url); return response(200, [{ userId: "9007199254740993", name: "bob", avatar: null }]); });
  h.openDirectDialog();
  await h.flush();
  assert.deepEqual(calls, ["/api/chat/v1/users?query="]);
  const buttons = h.nodes.get("peopleList").children;
  assert.equal(buttons.length, 1);
  assert.equal(buttons[0].dataset.personId, "9007199254740993");
  buttons[0].click();
  assert.equal(h.state.selectedPerson.name, "bob");
  assert.equal(h.nodes.get("submitDirectButton").disabled, false);
});

test("stale employee searches cannot overwrite a newer result or restore expired session data", async () => {
  const h = harness();
  let firstResolve;
  h.fetch(url => url.endsWith("query=old") ? new Promise(resolve => { firstResolve = resolve; })
    : response(200, [{ userId: "3", name: "carol" }]));
  const first = h.searchPeople("old");
  await h.searchPeople("new");
  firstResolve(response(200, [{ userId: "2", name: "bob" }]));
  await first;
  assert.equal(h.nodes.get("peopleList").children[0].dataset.personId, "3");
  const expired = h.searchPeople("old");
  h.requireLogin();
  firstResolve(response(200, [{ userId: "2", name: "bob" }]));
  await expired;
  assert.equal(h.nodes.get("peopleList").children.length, 0);
});

test("large employee IDs remain distinct when selecting colleagues", () => {
  const h = harness();
  const people = [{ id: "9007199254740992", name: "first" }, { id: "9007199254740993", name: "second" }];
  h.state.selectedPerson = people[1];
  h.renderPeople(people);
  assert.equal(h.nodes.get("peopleList").children[0].className, "person-option");
  assert.equal(h.nodes.get("peopleList").children[1].className, "person-option selected");
});

test("direct creation uses the canonical peer field, session CSRF and string ID", async () => {
  const h = harness();
  h.api.csrf = { headerName: "X-CSRF-TOKEN", token: "session-token" };
  const calls = [];
  h.fetch(async (url, options) => { calls.push({ url, options }); return response(200, { id: "10" }); });
  await h.api.createDirectConversation("9007199254740993");
  assert.equal(calls[0].url, "/api/chat/v1/direct-conversations");
  assert.deepEqual(JSON.parse(calls[0].options.body), { peer_user_id: "9007199254740993" });
  assert.equal(calls[0].options.headers.get("X-CSRF-TOKEN"), "session-token");
});

test("reopening a direct conversation keeps one entry and retrieves saved history", async () => {
  const h = harness();
  const calls = [];
  h.state.currentUser = { id: "1", name: "alice" };
  h.api.csrf = { headerName: "X-CSRF-TOKEN", token: "session-token" };
  h.fetch(async (url) => {
    calls.push(url);
    if (url.endsWith("/direct-conversations")) return response(200, { id: "10", type: "DIRECT_MESSAGE", name: "bob", peerUserId: "2" });
    return response(200, { items: [], afterCursor: "baseline", hasMore: false });
  });
  await h.createDirectConversation({ id: "2", name: "bob" });
  await h.createDirectConversation({ id: "2", name: "bob" });
  assert.equal(h.state.conversations.length, 1);
  assert.equal(h.state.activeConversationId, "10");
  assert.equal(calls.filter(url => url.includes("/messages")).length, 2);
  assert.equal(h.state.cursors.get("10").afterCursor, "baseline");
  assert.deepEqual(Array.from(h.state.conversations[0].members, member => member.name), ["alice", "bob"]);
  assert.equal(h.nodes.get("detailsMembers").children.length, 2);
});

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
  assert.equal(calls[0].options.credentials, "omit");
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
  assert.equal(calls[2].options.credentials, "omit");
});

test("login creates a fresh window session and sends its ID with the fetched CSRF token", async () => {
  const loginSource = fs.readFileSync(path.join(__dirname, "../../main/resources/static/chat/login.js"), "utf8");
  let submit;
  const form = { addEventListener: (name, callback) => { submit = callback; }, reset() {} };
  const button = { disabled: false };
  const error = { textContent: "" };
  const calls = [];
  const redirects = [];
  const context = vm.createContext({
    document: { getElementById: id => ({ loginForm: form, loginButton: button, loginError: error })[id] },
    window: { CHAT_CONFIG: { apiBaseUrl: "/api/chat/v1/" }, sessionStorage: storage(), location: {
      href: "http://localhost:8090/chat/login.html", origin: "http://localhost:8090", replace: url => redirects.push(url) } },
    URLSearchParams, URL, Headers,
    FormData: class { *[Symbol.iterator]() { yield ["username", "alice"]; yield ["password", "secret"]; } },
    fetch: async (url, options) => {
      calls.push({ url, options });
      const result = url.endsWith("/csrf-token") ? response(200, { headerName: "X-CSRF-TOKEN", token: "login-token" })
        : response(201, { id: "1" });
      result.headers.set("X-Chat-Session", url.endsWith("/csrf-token") ? "anonymous-session" : "authenticated-session");
      return result;
    }
  });
  vm.runInContext(sessionSource, context);
  context.window.sessionStorage.setItem("chat.sessionId", "copied-from-another-window");
  vm.runInContext(loginSource, context);
  await submit({ preventDefault() {} });
  assert.deepEqual(calls.map(call => call.url), ["/api/chat/v1/csrf-token", "/api/chat/v1/sessions"]);
  assert.equal(calls[1].options.method, "POST");
  assert.equal(calls[1].options.headers.get("X-CSRF-TOKEN"), "login-token");
  assert.equal(calls[1].options.body.get("username"), "alice");
  assert.equal(calls[1].options.credentials, "omit");
  assert.equal(calls[0].options.headers.get("X-Chat-Session"), "new");
  assert.equal(calls[1].options.headers.get("X-Chat-Session"), "anonymous-session");
  assert.equal(context.window.sessionStorage.getItem("chat.sessionId"), "authenticated-session");
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

test("image upload uses same-origin authenticated CSRF requests and waits for READY", async () => {
  const h = harness();
  h.api.csrf = { headerName: "X-CSRF-TOKEN", token: "token" };
  const file = new Blob(["image bytes"], { type: "image/png" });
  file.name = "sample.png";
  const calls = [];
  h.fetch(async (url, options) => {
    calls.push({ url, options });
    return response(200, calls.length === 1 ? { id: "55", uploadUrl: "https://untrusted.invalid/upload" }
      : { id: "55", status: "READY", contentUrl: "/api/chat/v1/attachments/55/content" });
  });
  const result = await h.api.uploadAttachment("10", file);
  assert.equal(result.status, "READY");
  assert.deepEqual(calls.map(call => call.url), ["/api/chat/v1/conversations/10/attachments", "/api/chat/v1/attachments/55/upload"]);
  for (const { options } of calls) {
    assert.equal(options.credentials, "omit");
    assert.equal(options.headers.get("X-CSRF-TOKEN"), "token");
  }
  assert.equal(calls[1].options.body, file);
});

test("offline closes the socket immediately without spending retries and online resumes it", async () => {
  const h = harness(); messageFixture(h);
  const states = [];
  const client = new h.NativeStompClient("/ws/chat", { onState: state => states.push(state) });
  client.socket = new h.Socket("ws://localhost/ws/chat"); client.connected = true;
  h.state.socket = client;
  h.bindEvents();
  h.window.navigator.onLine = false;
  h.windowEvents.offline();
  assert.equal(client.connected, false);
  assert.equal(client.reconnectCount, 0);
  assert.equal(states.at(-1), "disconnected");
  h.fetch(async () => response(200, { id: "1", name: "alice" }));
  h.window.navigator.onLine = true;
  h.windowEvents.online();
  await h.flush();
  assert.equal(h.Socket.created.length, 2);
  h.requireLogin();
  h.windowEvents.online();
  await h.flush();
  assert.equal(h.Socket.created.length, 2);
});

test("scrolling to the top requests earlier history with the existing before cursor", async () => {
  const h = harness(); messageFixture(h); h.bindEvents();
  h.state.cursors.set("10", { beforeCursor: "before-30", afterCursor: "after-59", hasMore: true });
  const requested = [];
  h.api.getMessages = async (id, parameters) => { requested.push({ id, before: parameters.before });
    return { items: [message("29")], beforeCursor: "before-29", afterCursor: "after-29", hasMore: false }; };
  const scroller = h.nodes.get("messageScroller"); scroller.scrollTop = 0;
  scroller.scroll({ currentTarget: scroller });
  await h.flush();
  assert.deepEqual(requested, [{ id: "10", before: "before-30" }]);
  assert.equal(h.state.cursors.get("10").afterCursor, "after-59");
});

test("switching conversations during image upload cannot move the outgoing message", async () => {
  const h = harness(); messageFixture(h);
  let completeUpload;
  const sent = [];
  const client = new h.NativeStompClient("/ws/chat");
  client.connected = true;
  client.readyConversationId = "10";
  client.subscriptions.set("chat-acks", { ready: true });
  client.subscriptions.set("chat-errors", { ready: true });
  client.subscriptions.set("chat-messages", { ready: true });
  client.sendFrame = (command, headers, body) => { sent.push(JSON.parse(body)); return true; };
  h.state.socket = client;
  h.api.uploadAttachment = () => new Promise(resolve => { completeUpload = resolve; });
  const file = new Blob(["png"], { type: "image/png" }); file.name = "test.png";
  const pending = h.sendImageMessage(file, "");
  assert.equal(h.state.messages.get("10")[0].uploading, true);
  h.state.conversations.push({ id: "11", name: "Another", type: "PUBLIC_ROOM" });
  h.state.activeConversationId = "11";
  client.readyConversationId = "11";
  h.state.messages.set("11", []);
  completeUpload({ id: "55", contentUrl: "/api/chat/v1/attachments/55/content" });
  await pending;
  assert.equal(sent.length, 1);
  assert.equal(sent[0].conversationId, "10");
  assert.equal(sent[0].attachmentId, "55");
  assert.equal(h.state.messages.get("11").length, 0);
  URL.revokeObjectURL(h.state.messages.get("10")[0].imageUrl);
});

test("failed image upload retries the file and preserves the original message request ID", async () => {
  const h = harness(); messageFixture(h);
  const sent = [];
  h.state.socket = { sendMessage: request => { sent.push(request); return true; } };
  let attempts = 0;
  h.api.uploadAttachment = async () => { if (++attempts === 1) throw new Error("upload failed"); return { id: "55" }; };
  const file = new Blob(["png"], { type: "image/png" }); file.name = "test.png";
  await h.sendImageMessage(file, "");
  const message = h.state.messages.get("10")[0];
  const requestId = message.clientRequestId;
  assert.equal(message.status, "FAILED");
  assert.equal(sent.length, 0);
  h.retryMessage(message.id);
  await h.flush();
  assert.equal(attempts, 2);
  assert.equal(sent[0].clientRequestId, requestId);
  assert.equal(h.state.messages.get("10").length, 1);
  URL.revokeObjectURL(message.imageUrl);
});

test("restored image messages use separate protected thumbnail and original URLs", () => {
  const h = harness(); messageFixture(h);
  const image = h.normalizeMessage({ ...message("42"), type: "IMAGE", body: null, attachmentId: "55",
    attachment: { id: "55", origFilename: "sample.png", thumbnailUrl: "/api/chat/v1/attachments/55/thumbnail",
      contentUrl: "/api/chat/v1/attachments/55/content" } }, "10");
  assert.equal(image.imageUrl, "/api/chat/v1/attachments/55/thumbnail");
  assert.equal(image.originalUrl, "/api/chat/v1/attachments/55/content");
  h.state.messages.set("10", [image]); h.renderMessages();
  const rendered = h.nodes.get("messageList").querySelector("img");
  assert.equal(rendered.dataset.originalUrl, image.originalUrl);
});

test("ACK, broadcast and history reconcile one request without merging different senders", () => {
  const h = harness(); messageFixture(h);
  h.state.messages.set("10", [{ ...message("pending", "same"), status: "SENDING" }]);
  h.handleRealtimeMessage({ eventType: "MESSAGE_CREATED", message: message("20", "same", "2") });
  h.handleRealtimeMessage({ eventType: "MESSAGE_CREATED", message: message("21", "same") });
  h.handleMessageAck({ eventType: "MESSAGE_ACK", clientRequestId: "same", message: message("21", "same") });
  const merged = h.mergeMessages(h.state.messages.get("10"), [message("21", "same"), message("20", "same", "2")]);
  assert.equal(merged.length, 2);
  assert.deepEqual(Array.from(merged, item => item.id), ["20", "21"]);
  assert.ok(merged.every(item => item.status === "SENT"));
  h.handleSocketError({ clientRequestId: "same", message: "late error" });
  assert.equal(h.state.messages.get("10").find(item => item.id === "21").status, "SENT");
});

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
  assert.equal(row.querySelector(".sender-line").classList.contains("hidden"), false);
  assert.equal(list.querySelectorAll(".is-new").length, 0);
  assert.equal(scroller.scrollTop, 275);
});

test("retry keeps the request ID and missing ACK becomes retryable without a duplicate", async () => {
  const h = harness(); messageFixture(h);
  const pending = { ...message("pending", "stable"), status: "FAILED" };
  h.state.messages.set("10", [pending]);
  const sent = [];
  h.state.socket = { readyConversationId: "10", sendMessage: payload => { sent.push(payload); return true; } };
  h.retryMessage("pending");
  assert.equal(sent[0].clientRequestId, "stable");
  assert.equal(sent[0].senderId, undefined);
  h.timers.get(h.ackTimers.get("stable")).callback();
  assert.equal(pending.status, "FAILED");
  h.retryMessage("pending");
  h.handleMessageAck({ clientRequestId: "stable", message: message("30", "stable") });
  assert.deepEqual(sent.map(item => item.clientRequestId), ["stable", "stable"]);
  assert.equal(h.state.messages.get("10").length, 1);
  assert.equal(h.state.messages.get("10")[0].id, "30");
  assert.equal(h.ackTimers.size, 0);
});

test("sending waits for actual broker receipts for all private queues and conversation", async () => {
  const h = harness(); messageFixture(h);
  h.fetch(async () => response(200, { id: "1" }));
  const ready = [];
  const client = new h.NativeStompClient("/ws/chat", { onReady: id => ready.push(id) });
  client.subscribeToConversation("10");
  await client.connect();
  client.handleFrame("CONNECTED", {}, "");
  const payload = { conversationId: "10", clientRequestId: "stable", type: "TEXT", body: "世界 😀" };
  assert.equal(client.sendMessage(payload), false);
  for (const id of ["chat-acks", "chat-errors", "chat-messages"]) {
    client.handleFrame("RECEIPT", { "receipt-id": client.subscriptions.get(id).receipt }, "");
    assert.equal(client.sendMessage(payload), false);
  }
  client.handleFrame("RECEIPT", { "receipt-id": client.subscriptions.get("conversation-10").receipt }, "");
  assert.deepEqual(ready, ["10"]);
  assert.equal(client.sendMessage(payload), true);
  assert.match(client.socket.sent.at(-1), new RegExp(`content-length:${Buffer.byteLength(JSON.stringify(payload))}`));
  client.subscribeToConversation("11");
  assert.equal(client.sendMessage(payload), false);
});

test("the inbox discovers new direct conversations without changing the active view or duplicating unread messages", async () => {
  const h = harness(); messageFixture(h);
  const client = new h.NativeStompClient("/ws/chat", { onMessage: h.handleRealtimeMessage });
  client.socket = new h.Socket("ws://localhost/ws/chat");
  client.handleFrame("CONNECTED", {}, "");
  assert.equal(client.subscriptions.get("chat-messages").destination, "/user/queue/chat.messages");
  const event = { eventType: "MESSAGE_CREATED", message: { ...message("30", "first", "2"), conversationId: "20" },
    conversation: { id: "20", type: "DIRECT_MESSAGE", name: "bob", peerUserId: "2" } };
  client.handleFrame("MESSAGE", { subscription: "chat-messages" }, JSON.stringify(event));
  assert.equal(h.state.activeConversationId, "10");
  const direct = h.state.conversations.find(item => item.id === "20");
  assert.equal(direct.name, "bob");
  assert.equal(direct.peerId, "2");
  assert.equal(direct.unread, 1);
  assert.match(direct.preview, /hello/);
  // A topic frame and repeated inbox delivery must merge with the original message.
  h.handleRealtimeMessage({ message: event.message });
  client.handleFrame("MESSAGE", { subscription: "chat-messages" }, JSON.stringify(event));
  assert.equal(direct.unread, 1);
  assert.equal(h.state.messages.get("20").length, 1);
  h.handleRealtimeMessage({ ...event, message: { ...event.message, id: "31", clientRequestId: "second" } });
  assert.equal(direct.unread, 2);
  h.handleRealtimeMessage({ ...event, message: { ...event.message, id: "32", clientRequestId: "own", senderId: "1" } });
  assert.equal(direct.unread, 2);
  client.subscribeToConversation("10");
  client.subscribeToConversation("20");
  assert.ok(client.subscriptions.has("chat-messages"));
  h.requireLogin();
  h.handleRealtimeMessage(event);
  assert.equal(h.state.conversations.length, 0);
  assert.equal(h.state.messages.size, 0);
});

test("an empty conversation list receives the first direct message through the inbox", () => {
  const h = harness();
  h.state.currentUser = { id: "2", name: "bob" };
  h.handleRealtimeMessage({ message: message("30"),
    conversation: { id: "10", type: "DIRECT_MESSAGE", name: "alice", peerUserId: "1" } });
  assert.equal(h.state.conversations.length, 1);
  assert.equal(h.state.conversations[0].unread, 1);
  assert.equal(h.document.getElementById("conversationEmpty").classList.contains("hidden"), true);
  assert.equal(h.state.messages.get("10").length, 1);
});

test("inbox receipts refresh conversations after connection and reconnect without overwriting newer live messages", async () => {
  const h = harness(); messageFixture(h);
  const pending = [];
  h.fetch(url => url.endsWith("/conversations") ? new Promise(resolve => pending.push(resolve))
    : response(201, { ticket: "test-ticket" }));
  h.setupRealtime(); await h.flush();
  const client = h.state.socket;
  const readyInbox = () => {
    client.handleFrame("CONNECTED", {}, "");
    client.handleFrame("RECEIPT", { "receipt-id": client.subscriptions.get("chat-messages").receipt }, "");
  };
  readyInbox();
  const conversation = { id: "20", type: "DIRECT_MESSAGE", name: "bob", peerUserId: "2", lastMessageId: "29",
    lastActivityAt: "2026-09-08T09:00:00", lastMessagePreview: "stale" };
  h.handleRealtimeMessage({ message: { ...message("30", "live", "2"), conversationId: "20" }, conversation });
  pending.shift()(response(200, [conversation, { ...conversation, id: "21" }]));
  await h.flush();
  assert.equal(h.state.conversations.filter(item => item.id === "20").length, 1);
  assert.equal(h.state.conversations.find(item => item.id === "20").unread, 1);
  assert.match(h.state.conversations.find(item => item.id === "20").preview, /hello/);
  assert.ok(h.state.conversations.some(item => item.id === "21"));
  client.handleDisconnect();
  await client.connect(); readyInbox();
  pending.shift()(response(200, [{ ...conversation, id: "22" }]));
  await h.flush();
  assert.ok(h.state.conversations.some(item => item.id === "22"));
  assert.equal(h.state.activeConversationId, "10");
  readyInbox(); h.requireLogin();
  pending.shift()(response(200, [conversation]));
  await h.flush();
  assert.equal(h.state.conversations.length, 0);
});

test("catch-up keeps its REST watermark while live messages arrive and pages until caught up", async () => {
  const h = harness(); messageFixture(h);
  h.state.cursors.set("10", { beforeCursor: "oldest", afterCursor: "cursor-10", hasMore: true });
  let firstResponse;
  const urls = [];
  h.fetch((url) => {
    urls.push(url);
    if (urls.length === 1) return new Promise(resolve => { firstResponse = resolve; });
    return Promise.resolve(response(200, { items: [message("12")], afterCursor: "cursor-12", hasMore: false }));
  });
  const recovery = h.syncConversation("10");
  h.handleRealtimeMessage({ message: message("12") });
  firstResponse(response(200, { items: [message("11")], afterCursor: "cursor-11", hasMore: true }));
  await recovery;
  assert.equal(urls.length, 2);
  assert.match(urls[0], /after=cursor-10/);
  assert.match(urls[1], /after=cursor-11/);
  assert.deepEqual(Array.from(h.state.messages.get("10"), item => item.id), ["11", "12"]);
  assert.equal(h.state.cursors.get("10").beforeCursor, "oldest");
  assert.equal(h.state.cursors.get("10").hasMore, true);
  assert.equal(h.state.cursors.get("10").afterCursor, "cursor-12");
});

test("first history response preserves broadcasts received while request is in flight", async () => {
  const h = harness(); messageFixture(h);
  let complete;
  h.fetch(() => new Promise(resolve => { complete = resolve; }));
  const loading = h.syncConversation("10");
  h.handleRealtimeMessage({ message: message("12") });
  complete(response(200, { items: [message("11")], afterCursor: "cursor-11", hasMore: false }));
  await loading;
  assert.deepEqual(Array.from(h.state.messages.get("10"), item => item.id), ["11", "12"]);
  assert.equal(h.state.cursors.get("10").afterCursor, "cursor-11");
});

test("late history for a previous conversation never populates the new conversation", async () => {
  const h = harness(); messageFixture(h);
  h.state.cursors.set("10", { beforeCursor: "older", afterCursor: "latest", hasMore: true });
  let complete;
  h.fetch(() => new Promise(resolve => { complete = resolve; }));
  const loading = h.loadEarlierMessages();
  h.state.activeConversationId = "11";
  complete(response(200, { items: [message("5")], beforeCursor: "first", afterCursor: "cursor-5", hasMore: false }));
  await loading;
  assert.equal(h.state.messages.get("10")[0].id, "5");
  assert.equal(h.state.messages.has("11"), false);
  assert.equal(h.state.cursors.get("10").afterCursor, "latest");
});

test("message ordering preserves microseconds and IDs above Number safe integer", () => {
  const h = harness();
  const ordered = h.mergeMessages([], [
    message("9007199254740994", "b"), message("9007199254740993", "a"),
    message("9007199254740995", "c", "2", "2026-09-08T10:00:00.123455")
  ]);
  assert.deepEqual(Array.from(ordered, item => item.id), ["9007199254740995", "9007199254740993", "9007199254740994"]);
});

test("browser clock ahead cannot suppress acknowledged or newer server summaries", () => {
  const h = harness(); messageFixture(h);
  const pending = h.createOptimisticMessage({ type: "TEXT", body: "pending", createdAt: "2035-01-01T00:00:00" });
  h.handleMessageAck({ clientRequestId: pending.clientRequestId, message: message("20", pending.clientRequestId) });
  h.handleRealtimeMessage({ message: { ...message("21", "next", "2"), body: "next server message" } });
  assert.equal(h.state.conversations[0].latestMessage.id, "21");
  assert.match(h.state.conversations[0].preview, /next server message/);
});

test("subscription failures do not reset the finite reconnect budget on CONNECTED alone", () => {
  const h = harness();
  const client = new h.NativeStompClient("/ws/chat");
  client.socket = new h.Socket("ws://localhost/ws/chat");
  client.reconnectCount = 4;
  client.handleFrame("CONNECTED", {}, "");
  assert.equal(client.reconnectCount, 4);
  client.handleDisconnect();
  assert.equal(client.reconnectCount, 5);
  client.socket = new h.Socket("ws://localhost/ws/chat");
  client.handleFrame("CONNECTED", {}, "");
  client.handleDisconnect();
  assert.equal(client.reconnectCount, 5);
  assert.equal(h.timers.has(client.reconnectTimer), false);
});
