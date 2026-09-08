/**
 * 聊天页面入口：管理会话视图、REST 请求和原生 STOMP 连接。
 * 当前后端已接入认证和房间管理；员工搜索、消息历史、消息 ACK 与附件接口仍为预留调用。
 * 演示模式在浏览器内模拟数据和发送状态，不能用来判断后端功能是否已实现。
 */
const CONFIG = window.CHAT_CONFIG ?? {};
const queryMode = new URLSearchParams(window.location.search).get("mode");
const runtimeMode = ["auto", "live", "demo"].includes(queryMode) ? queryMode : (CONFIG.mode || "auto");

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const byId = (id) => document.getElementById(id);
const sleep = (milliseconds) => new Promise((resolve) => window.setTimeout(resolve, milliseconds));

const palette = ["avatar-sage", "avatar-coral", "avatar-gold", "avatar-blue", "avatar-lilac", "avatar-ink"];
const roomAccents = ["", "accent-1", "accent-2", "accent-3"];
const emojis = ["😀", "😄", "😊", "😉", "😍", "🥳", "🤔", "👏", "👍", "🙌", "🎉", "✨", "💡", "🔥", "✅", "👀", "💪", "🙏", "❤️", "🚀", "☕", "🌿", "📌", "🎨", "💬", "😂", "😅", "🤝"];

const remoteImages = [
  "https://images.unsplash.com/photo-1558655146-d09347e92766?auto=format&fit=crop&w=720&q=80",
  "https://images.unsplash.com/photo-1559028012-481c04fa702d?auto=format&fit=crop&w=720&q=80",
  "https://images.unsplash.com/photo-1581291518857-4e27b48ff24e?auto=format&fit=crop&w=720&q=80",
  "https://images.unsplash.com/photo-1552664730-d307ca884978?auto=format&fit=crop&w=720&q=80",
  "https://images.unsplash.com/photo-1613909207039-6b173b755cc1?auto=format&fit=crop&w=720&q=80",
  "https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=720&q=80"
];

const demoPeople = [
  { id: 1002, name: "周予安", username: "zhouyuan", avatar: "周", role: "产品设计", palette: "avatar-coral", online: true },
  { id: 1003, name: "陈默", username: "chenmo", avatar: "陈", role: "前端工程师", palette: "avatar-blue", online: true },
  { id: 1004, name: "苏禾", username: "suhe", avatar: "苏", role: "品牌设计", palette: "avatar-gold", online: false },
  { id: 1005, name: "顾南", username: "gunan", avatar: "顾", role: "后端工程师", palette: "avatar-lilac", online: true },
  { id: 1006, name: "唐宁", username: "tangning", avatar: "唐", role: "运营策划", palette: "avatar-sage", online: false }
];

const demoConversations = [
  { id: "room-design", type: "PUBLIC_ROOM", name: "设计协作组", avatar: "设", accent: "", preview: "周予安：第二版交互稿已更新", time: "10:42", unread: 3, pinned: true, ownerId: 1001, memberCount: 12, onlineCount: 3, description: "让好想法在这里发生。", members: demoPeople.slice(0, 4) },
  { id: "room-brand", type: "PUBLIC_ROOM", name: "品牌焕新项目", avatar: "品", accent: "accent-1", preview: "苏禾：色彩规范需要再确认", time: "09:18", unread: 0, pinned: true, ownerId: 1004, memberCount: 8, onlineCount: 2, description: "品牌焕新项目核心讨论组。", members: demoPeople.slice(1, 5) },
  { id: "dm-1002", type: "DIRECT_MESSAGE", name: "周予安", avatar: "周", palette: "avatar-coral", preview: "我把标注补充在最后一页了", time: "昨天", unread: 1, pinned: false, peerId: 1002, memberCount: 2, onlineCount: 1, members: [demoPeople[0]] },
  { id: "room-tech", type: "PUBLIC_ROOM", name: "技术分享", avatar: "技", accent: "accent-2", preview: "顾南：周五分享 WebSocket 实践", time: "昨天", unread: 0, pinned: false, ownerId: 1005, memberCount: 26, onlineCount: 6, description: "分享实践，也分享踩过的坑。", members: demoPeople.slice(0, 5) },
  { id: "dm-1003", type: "DIRECT_MESSAGE", name: "陈默", avatar: "陈", palette: "avatar-blue", preview: "接口字段我已经对齐了", time: "周五", unread: 0, pinned: false, peerId: 1003, memberCount: 2, onlineCount: 1, members: [demoPeople[1]] },
  { id: "room-coffee", type: "PUBLIC_ROOM", name: "咖啡与灵感", avatar: "咖", accent: "accent-3", preview: "唐宁：新豆子到了 ☕", time: "周四", unread: 0, pinned: false, ownerId: 1006, memberCount: 17, onlineCount: 4, description: "工作之外，交换一点新鲜灵感。", members: demoPeople.slice(0, 4) }
];

const demoMessages = {
  "room-design": [
    { id: "m-1", senderId: 1002, senderName: "周予安", senderAvatar: "周", palette: "avatar-coral", type: "TEXT", body: "早上好！首页的两套视觉方向我都整理好了，大家有空可以先看一下。", createdAt: "2026-08-31T09:28:00+08:00", status: "SENT" },
    { id: "m-2", senderId: 1003, senderName: "陈默", senderAvatar: "陈", palette: "avatar-blue", type: "TEXT", body: "收到，我主要看一下响应式和组件落地成本。", createdAt: "2026-08-31T09:31:00+08:00", status: "SENT" },
    { id: "m-3", senderId: 1001, senderName: "林澈", senderAvatar: "林", palette: "avatar-sage", type: "TEXT", body: "好的。视觉上我更偏向 B 方案，层级更安静，也更符合内部工具的使用场景。", createdAt: "2026-08-31T09:35:00+08:00", status: "SENT" },
    { id: "m-4", senderId: 1004, senderName: "苏禾", senderAvatar: "苏", palette: "avatar-gold", type: "IMAGE", body: null, imageUrl: remoteImages[0], fileName: "workspace-v2.png", createdAt: "2026-08-31T10:06:00+08:00", status: "SENT" },
    { id: "m-5", senderId: 1004, senderName: "苏禾", senderAvatar: "苏", palette: "avatar-gold", type: "TEXT", body: "我在 B 方案上继续收了一版，减少了装饰，把会话和操作区的对比拉开了。", createdAt: "2026-08-31T10:07:00+08:00", status: "SENT" },
    { id: "m-6", senderId: 1002, senderName: "周予安", senderAvatar: "周", palette: "avatar-coral", type: "TEXT", body: "这版舒服很多 👏  我把交互标注补到最后几页，下午一起过一遍？", createdAt: "2026-08-31T10:42:00+08:00", status: "SENT" }
  ],
  "room-brand": [
    { id: "b-1", senderId: 1004, senderName: "苏禾", senderAvatar: "苏", palette: "avatar-gold", type: "TEXT", body: "新的辅助色会减少饱和度，确保在大面积背景上也耐看。", createdAt: "2026-08-31T08:52:00+08:00", status: "SENT" },
    { id: "b-2", senderId: 1001, senderName: "林澈", senderAvatar: "林", palette: "avatar-sage", type: "TEXT", body: "可以，注意和现有业务系统的绿色区分开。", createdAt: "2026-08-31T09:18:00+08:00", status: "SENT" }
  ],
  "dm-1002": [
    { id: "d-1", senderId: 1002, senderName: "周予安", senderAvatar: "周", palette: "avatar-coral", type: "TEXT", body: "评审文档我更新好了。", createdAt: "2026-08-30T16:18:00+08:00", status: "SENT" },
    { id: "d-2", senderId: 1001, senderName: "林澈", senderAvatar: "林", palette: "avatar-sage", type: "TEXT", body: "辛苦，我晚点集中看。", createdAt: "2026-08-30T16:22:00+08:00", status: "SENT" },
    { id: "d-3", senderId: 1002, senderName: "周予安", senderAvatar: "周", palette: "avatar-coral", type: "TEXT", body: "我把标注补充在最后一页了", createdAt: "2026-08-30T17:03:00+08:00", status: "SENT" }
  ],
  "room-tech": [
    { id: "t-1", senderId: 1005, senderName: "顾南", senderAvatar: "顾", palette: "avatar-lilac", type: "TEXT", body: "周五下午我分享一下 STOMP WebSocket 的连接恢复和消息幂等实践，欢迎带问题来。", createdAt: "2026-08-29T15:30:00+08:00", status: "SENT" }
  ],
  "dm-1003": [
    { id: "c-1", senderId: 1003, senderName: "陈默", senderAvatar: "陈", palette: "avatar-blue", type: "TEXT", body: "接口字段我已经对齐了，空状态也加上了。", createdAt: "2026-08-28T18:02:00+08:00", status: "SENT" }
  ],
  "room-coffee": [
    { id: "f-1", senderId: 1006, senderName: "唐宁", senderAvatar: "唐", palette: "avatar-sage", type: "IMAGE", body: null, imageUrl: remoteImages[1], fileName: "coffee.jpg", createdAt: "2026-08-27T11:20:00+08:00", status: "SENT" },
    { id: "f-2", senderId: 1006, senderName: "唐宁", senderAvatar: "唐", palette: "avatar-sage", type: "TEXT", body: "新豆子到了 ☕ 下午谁来一起试？", createdAt: "2026-08-27T11:21:00+08:00", status: "SENT" }
  ]
};

// 消息及分页游标按会话缓存；authExpired 一旦置位，本次页面生命周期内不再接受正常响应。
const state = {
  mode: runtimeMode,
  authExpired: false,
  usingDemo: runtimeMode === "demo",
  conversations: [],
  activeConversationId: null,
  messages: new Map(),
  cursors: new Map(),
  currentUser: { id: 1001, name: "林澈", username: "linche", avatar: "林", ...(CONFIG.currentUser || {}) },
  filter: "all",
  search: "",
  selectedPerson: null,
  pendingFile: null,
  socket: null,
  connectionState: "connecting"
};

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function hashIndex(value, length) {
  const text = String(value ?? "");
  let hash = 0;
  for (let index = 0; index < text.length; index += 1) hash = ((hash << 5) - hash) + text.charCodeAt(index);
  return Math.abs(hash) % length;
}

function initials(name) {
  const value = String(name || "未").trim();
  return value.slice(0, value.length > 2 ? 1 : 2).toUpperCase();
}

function parseDate(value) {
  const date = value ? new Date(value) : new Date();
  return Number.isNaN(date.getTime()) ? new Date() : date;
}

function formatClock(value) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(parseDate(value));
}

function formatDateLabel(value) {
  const date = parseDate(value);
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  const key = (item) => `${item.getFullYear()}-${item.getMonth()}-${item.getDate()}`;
  if (key(date) === key(today)) return "今天";
  if (key(date) === key(yesterday)) return "昨天";
  return new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", weekday: "short" }).format(date);
}

function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 KB";
  if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function uuid() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID();
  return `chat-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/** 兼容直接响应以及历史接口的 result/data 包装，后续逻辑统一读取业务载荷。 */
function unwrapResponse(payload) {
  if (payload == null) return payload;
  if (Object.hasOwn(payload, "result")) return payload.result;
  if (Object.hasOwn(payload, "data")) return payload.data;
  return payload;
}

class ApiError extends Error {
  constructor(message, status = 0, payload = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.payload = payload;
  }
}

/** 统一管理 Session Cookie、CSRF、请求超时和 HTTP 错误到页面异常的转换。 */
class ChatApi {
  constructor(baseUrl) {
    this.baseUrl = String(baseUrl || "/api/chat/v1").replace(/\/$/, "");
    this.csrf = null;
  }

  async request(path, options = {}) {
    if (state.authExpired) throw new ApiError("登录已失效，请重新登录", 401);
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), CONFIG.requestTimeoutMs || 5000);
    const headers = new Headers(options.headers || {});
    if (this.csrf && !["GET", "HEAD"].includes(options.method || "GET")) {
      headers.set(this.csrf.headerName, this.csrf.token);
    }
    if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    try {
      const response = await fetch(`${this.baseUrl}${path}`, {
        credentials: "include",
        cache: "no-store",
        ...options,
        headers,
        signal: controller.signal
      });
      const contentType = response.headers.get("content-type") || "";
      const payload = response.status === 204 ? null
        : contentType.includes("json") ? await response.json() : await response.text();
      // 其他并发请求可能已判定登录失效，不能再让迟到的成功响应恢复私有数据。
      if (state.authExpired) throw new ApiError("登录已失效，请重新登录", 401);
      if (!response.ok) {
        if (response.status === 401) requireLogin();
        const message = payload?.message || payload?.error || `请求失败（${response.status}）`;
        throw new ApiError(message, response.status, payload);
      }
      if (payload?.status && Number(payload.status) >= 400) throw new ApiError(payload.data || payload.message || "请求失败", Number(payload.status), payload);
      return unwrapResponse(payload);
    } catch (error) {
      if (error.name === "AbortError") throw new ApiError("请求超时，请稍后重试");
      throw error;
    } finally {
      window.clearTimeout(timer);
    }
  }

  getConversations() { return this.request("/conversations"); }
  getCurrentUser() { return this.request("/sessions/current"); }
  logout() { return this.request("/sessions/current", { method: "DELETE" }); }
  async loadSession() {
    // 登录后页面重新获取当前 Session 的 CSRF Token，再读取可信身份。
    this.csrf = await this.request("/csrf-token");
    return this.getCurrentUser();
  }
  createRoom(name) { return this.request("/rooms", { method: "POST", body: JSON.stringify({ name }) }); }
  dissolveRoom(id) { return this.request(`/rooms/${encodeURIComponent(id)}`, { method: "DELETE" }); }
  // 以下员工、私聊、历史消息及附件方法预留给后续后端功能，接口失败时向页面透传错误。
  searchUsers(query = "") { return this.request(`/users?query=${encodeURIComponent(query)}`); }
  createDirectConversation(peerUserId) { return this.request("/direct-conversations", { method: "POST", body: JSON.stringify({ peerUserId, peer_user_id: peerUserId }) }); }
  getMessages(id, parameters = {}) {
    const query = new URLSearchParams();
    Object.entries(parameters).forEach(([key, value]) => value != null && query.set(key, String(value)));
    return this.request(`/conversations/${encodeURIComponent(id)}/messages?${query}`);
  }

  /** 先申请附件元数据；响应包含上传地址时，再向该地址发送文件内容。 */
  async uploadAttachment(conversationId, file) {
    const metadata = await this.request(`/conversations/${encodeURIComponent(conversationId)}/attachments`, {
      method: "POST",
      body: JSON.stringify({
        fileName: file.name,
        filename: file.name,
        origFilename: file.name,
        orig_filename: file.name,
        contentType: file.type,
        content_type: file.type,
        sizeBytes: file.size,
        size_bytes: file.size
      })
    });
    const uploadUrl = metadata?.uploadUrl || metadata?.upload_url;
    if (uploadUrl) {
      const uploadResponse = await fetch(uploadUrl, { method: metadata.method || "PUT", headers: metadata.headers || { "Content-Type": file.type }, body: file });
      if (!uploadResponse.ok) throw new ApiError("图片上传失败", uploadResponse.status);
    }
    return metadata;
  }
}

/** 基于原生 WebSocket 的轻量 STOMP 客户端，管理帧、心跳、订阅及有限次数重连。 */
class NativeStompClient {
  constructor(endpoint, callbacks = {}) {
    this.endpoint = endpoint;
    this.callbacks = callbacks;
    this.socket = null;
    this.buffer = "";
    this.connected = false;
    this.reconnectCount = 0;
    this.shouldReconnect = true;
    this.subscriptions = new Map();
    this.activeConversationId = null;
    this.reconnectTimer = null;
    this.heartbeatTimer = null;
    // 断开时递增，使旧连接或尚未完成的连接检查回调失效。
    this.generation = 0;
  }

  socketUrl() {
    const url = new URL(this.endpoint || "/ws/chat", window.location.href);
    url.protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    return url.toString();
  }

  async connect() {
    if (!this.shouldReconnect) return;
    const generation = this.generation;
    this.callbacks.onState?.("connecting");
    try {
      // 浏览器不暴露握手失败的 HTTP 状态，先用 REST 检查 Session，避免失效凭证反复重连。
      await api.getCurrentUser();
      if (!this.shouldReconnect || generation !== this.generation) return;
      this.buffer = "";
      this.socket = new WebSocket(this.socketUrl());
    } catch (error) {
      if (error.status === 401) { this.disconnect(false); return; }
      this.handleDisconnect(error);
      return;
    }
    this.socket.addEventListener("open", () => {
      if (generation !== this.generation || !this.shouldReconnect) return;
      this.sendFrame("CONNECT", {
      "accept-version": "1.2", host: window.location.host, "heart-beat": "10000,10000",
      ...(api.csrf ? { [api.csrf.headerName]: api.csrf.token } : {})
      });
    });
    this.socket.addEventListener("message", (event) => {
      if (generation === this.generation && this.shouldReconnect) this.consume(String(event.data));
    });
    this.socket.addEventListener("close", (event) => {
      if (generation !== this.generation) return;
      // 将应用或容器返回的策略关闭码 1008 视为认证终止，停止重连并跳转登录。
      if (event.code === 1008) { this.disconnect(false); requireLogin(); return; }
      this.handleDisconnect();
    });
    this.socket.addEventListener("error", () => this.callbacks.onState?.("disconnected"));
  }

  /** 忽略独立心跳，按 NUL 分割完整帧，并保留末尾未收齐的数据供下一次消费。 */
  consume(chunk) {
    if (chunk === "\n") return;
    this.buffer += chunk;
    const frames = this.buffer.split("\0");
    this.buffer = frames.pop() || "";
    frames.forEach((raw) => {
      const frame = raw.replace(/^\n+/, "");
      if (!frame) return;
      const divider = frame.indexOf("\n\n");
      const headerText = divider >= 0 ? frame.slice(0, divider) : frame;
      const body = divider >= 0 ? frame.slice(divider + 2) : "";
      const lines = headerText.split("\n");
      const command = lines.shift();
      const headers = {};
      lines.forEach((line) => {
        const index = line.indexOf(":");
        if (index > 0) headers[line.slice(0, index)] = line.slice(index + 1).replace(/\\c/g, ":").replace(/\\n/g, "\n").replace(/\\\\/g, "\\");
      });
      this.handleFrame(command, headers, body);
    });
  }

  handleFrame(command, headers, body) {
    if (command === "CONNECTED") {
      this.connected = true;
      this.reconnectCount = 0;
      window.clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = window.setInterval(() => {
        if (this.socket?.readyState === WebSocket.OPEN) this.socket.send("\n");
      }, 10000);
      this.callbacks.onState?.("connected");
      // 每次建立 STOMP 会话后恢复私人队列和当前会话订阅。
      this.subscribe("chat-acks", "/user/queue/chat.acks", (payload) => this.callbacks.onAck?.(payload));
      this.subscribe("chat-errors", "/user/queue/chat.errors", (payload) => this.callbacks.onError?.(payload));
      if (this.activeConversationId) this.subscribeToConversation(this.activeConversationId);
      return;
    }
    if (command === "MESSAGE") {
      let payload = body;
      try { payload = JSON.parse(body); } catch (_) { /* plain text message */ }
      this.subscriptions.get(headers.subscription)?.callback?.(payload, headers);
      return;
    }
    if (command === "ERROR") {
      let error = { message: body || headers.message || "实时连接错误", status: Number(headers.status) };
      try { error = { ...error, ...JSON.parse(body) }; } catch (_) { /* non-JSON protocol error */ }
      this.disconnect(false);
      if (error.status === 401 || error.code === "SESSION_EXPIRED") requireLogin();
      else this.callbacks.onError?.(error);
    }
  }

  sendFrame(command, headers = {}, body = "") {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return false;
    const escape = (value) => command === "CONNECT" ? String(value)
      : String(value).replace(/\\/g, "\\\\").replace(/\r/g, "\\r").replace(/\n/g, "\\n").replace(/:/g, "\\c");
    const escapedHeaders = Object.entries(headers).map(([key, value]) => `${key}:${escape(value)}`).join("\n");
    this.socket.send(`${command}\n${escapedHeaders}\n\n${body}\0`);
    return true;
  }

  subscribe(id, destination, callback) {
    if (this.subscriptions.has(id)) this.unsubscribe(id);
    this.subscriptions.set(id, { destination, callback });
    if (this.connected) this.sendFrame("SUBSCRIBE", { id, destination, ack: "auto" });
  }

  unsubscribe(id) {
    if (this.connected) this.sendFrame("UNSUBSCRIBE", { id });
    this.subscriptions.delete(id);
  }

  subscribeToConversation(conversationId) {
    this.activeConversationId = conversationId;
    // 当前只订阅正在查看的会话，切换时释放旧会话主题，保留私人队列。
    [...this.subscriptions.keys()].filter((id) => id.startsWith("conversation-")).forEach((id) => this.unsubscribe(id));
    const id = `conversation-${conversationId}`;
    this.subscribe(id, `/topic/chat/conversations/${conversationId}`, (payload) => this.callbacks.onMessage?.(payload));
  }

  sendMessage(payload) {
    const body = JSON.stringify(payload);
    // content-length 使用 UTF-8 字节数，不能使用包含中文或表情时的字符串长度。
    return this.connected && this.sendFrame("SEND", { destination: "/app/chat.messages.send", "content-type": "application/json", "content-length": new TextEncoder().encode(body).length }, body);
  }

  handleDisconnect(error) {
    window.clearInterval(this.heartbeatTimer);
    window.clearTimeout(this.reconnectTimer);
    const wasConnected = this.connected;
    this.connected = false;
    this.callbacks.onState?.("disconnected");
    if (wasConnected) this.callbacks.onDisconnect?.();
    if (!this.shouldReconnect) return;
    const maximum = CONFIG.reconnectAttempts ?? 5;
    if (this.reconnectCount >= maximum) {
      this.callbacks.onReconnectExhausted?.(error);
      return;
    }
    // 指数退避上限为 16 秒，叠加随机延迟，减少多个客户端同时重新连接。
    const delay = Math.min(1000 * (2 ** this.reconnectCount), 16000) + Math.round(Math.random() * 300);
    this.reconnectCount += 1;
    this.reconnectTimer = window.setTimeout(() => { if (this.shouldReconnect) this.connect(); }, delay);
  }

  /** 主动断开并取消定时任务；更新代次以阻止旧异步回调重建连接。 */
  disconnect(reconnect = false) {
    this.shouldReconnect = reconnect;
    this.generation += 1;
    window.clearTimeout(this.reconnectTimer);
    window.clearInterval(this.heartbeatTimer);
    if (this.connected) this.sendFrame("DISCONNECT", { receipt: `disconnect-${Date.now()}` });
    this.socket?.close();
    this.connected = false;
  }
}

const api = new ChatApi(CONFIG.apiBaseUrl);

/** 幂等处理登录失效：先阻止后续请求并清理私有状态，再跳转到同源登录页。 */
function requireLogin() {
  if (state.authExpired) return;
  state.authExpired = true;
  state.socket?.disconnect(false);
  state.usingDemo = false;
  state.conversations = [];
  state.messages.clear();
  state.cursors.clear();
  state.activeConversationId = null;
  state.currentUser = {};
  state.selectedPerson = null;
  if (state.pendingFile?.url) URL.revokeObjectURL(state.pendingFile.url);
  state.pendingFile = null;
  api.csrf = null;
  byId("messageList").replaceChildren();
  byId("messageInput").value = "";
  byId("messageInput").disabled = true;
  byId("sendButton").disabled = true;
  // 导航完成前隐藏整个页面，也覆盖仍然打开的私有信息弹窗。
  document.body.hidden = true;
  window.location.assign(new URL("./login.html", window.location.href).href);
}

/** 将不同命名风格的接口字段映射到页面模型，会话标识保留为字符串。 */
function normalizeConversation(raw) {
  const id = raw.id ?? raw.conversationId ?? raw.conversation_id;
  const type = raw.type || raw.conversationType || raw.conversation_type || "PUBLIC_ROOM";
  const peer = raw.peer || raw.peerUser || raw.peer_user;
  const name = raw.name || raw.displayName || raw.display_name || peer?.name || peer?.username || `会话 ${id}`;
  const members = raw.members || raw.participants || [];
  return {
    ...raw,
    id: String(id),
    type,
    name,
    avatar: raw.avatar || peer?.avatar || initials(name),
    accent: raw.accent || roomAccents[hashIndex(id, roomAccents.length)],
    palette: raw.palette || palette[hashIndex(id, palette.length)],
    preview: raw.lastMessagePreview || raw.last_message_preview || raw.preview || "还没有消息",
    time: raw.lastActivityAt || raw.last_activity_at ? formatRelativeTime(raw.lastActivityAt || raw.last_activity_at) : (raw.time || ""),
    unread: Number(raw.unreadCount ?? raw.unread_count ?? raw.unread ?? 0),
    pinned: Boolean(raw.pinned),
    ownerId: String(raw.ownerId ?? raw.owner_id ?? ""),
    memberCount: Number(raw.memberCount ?? raw.member_count ?? members.length ?? (type === "DIRECT_MESSAGE" ? 2 : 0)),
    onlineCount: Number(raw.onlineCount ?? raw.online_count ?? 0),
    description: raw.description || (type === "PUBLIC_ROOM" ? "团队协作讨论组" : "一对一私信"),
    members
  };
}

function formatRelativeTime(value) {
  const date = parseDate(value);
  const now = new Date();
  const difference = now - date;
  if (difference < 24 * 60 * 60 * 1000 && date.getDate() === now.getDate()) return formatClock(date);
  if (difference < 48 * 60 * 60 * 1000) return "昨天";
  if (difference < 7 * 24 * 60 * 60 * 1000) return new Intl.DateTimeFormat("zh-CN", { weekday: "short" }).format(date);
  return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric" }).format(date);
}

function normalizeMessage(raw, conversationId) {
  const sender = raw.sender || raw.senderUser || raw.sender_user || {};
  const senderId = String(raw.senderId ?? raw.sender_id ?? sender.id ?? "");
  const attachmentId = raw.attachmentId ?? raw.attachment_id ?? raw.attachment?.id;
  return {
    ...raw,
    id: String(raw.id ?? raw.messageId ?? raw.message_id ?? raw.clientRequestId ?? uuid()),
    clientRequestId: raw.clientRequestId ?? raw.client_request_id,
    conversationId: String(raw.conversationId ?? raw.conversation_id ?? conversationId),
    senderId,
    senderName: raw.senderName || raw.sender_name || sender.name || sender.username || (senderId === String(state.currentUser.id) ? state.currentUser.name : `用户 ${senderId}`),
    senderAvatar: raw.senderAvatar || raw.sender_avatar || sender.avatar || initials(raw.senderName || sender.name || sender.username),
    palette: raw.palette || palette[hashIndex(senderId, palette.length)],
    type: raw.type || raw.messageType || raw.message_type || "TEXT",
    body: raw.body ?? raw.content ?? "",
    attachmentId,
    imageUrl: raw.imageUrl || raw.image_url || raw.thumbnailUrl || raw.thumbnail_url || raw.attachment?.thumbnailUrl || (attachmentId ? `${CONFIG.apiBaseUrl || "/api/chat/v1"}/attachments/${attachmentId}/thumbnail` : null),
    fileName: raw.fileName || raw.file_name || raw.attachment?.origFilename || "图片",
    createdAt: raw.createdAt || raw.created_at || new Date().toISOString(),
    status: raw.status || "SENT"
  };
}

/** 兼容数组或分页包装，消息按时间升序渲染，并保留向前/向后的游标字段。 */
function normalizeMessagesResponse(payload, conversationId) {
  const source = payload || {};
  const list = Array.isArray(source) ? source : (source.items || source.messages || source.records || source.content || []);
  return {
    items: list.map((item) => normalizeMessage(item, conversationId)).sort((a, b) => parseDate(a.createdAt) - parseDate(b.createdAt)),
    beforeCursor: source.beforeCursor ?? source.before_cursor ?? source.previousCursor ?? source.previous_cursor ?? null,
    afterCursor: source.afterCursor ?? source.after_cursor ?? source.nextCursor ?? source.next_cursor ?? null,
    hasMore: Boolean(source.hasMore ?? source.has_more ?? source.beforeCursor ?? source.before_cursor)
  };
}

/** 复制演示种子数据，避免页面操作修改原始样例，便于再次进入预览。 */
function loadDemo() {
  state.usingDemo = true;
  state.conversations = clone(demoConversations);
  state.messages = new Map(Object.entries(demoMessages).map(([id, messages]) => [id, clone(messages)]));
  state.cursors = new Map(state.conversations.map((conversation) => [conversation.id, { beforeCursor: conversation.id === "room-design" ? "demo-older" : null, hasMore: conversation.id === "room-design" }]));
}

async function initialize() {
  setConnectionState("connecting");
  if (state.mode === "demo") {
    loadDemo();
    showDemoBanner();
    setConnectionState("connected");
  } else {
    try {
      state.currentUser = await api.loadSession();
      byId("profileButton").textContent = initials(state.currentUser.name);
      const payload = await api.getConversations();
      const list = Array.isArray(payload) ? payload : (payload?.items || payload?.records || payload?.conversations || []);
      state.conversations = list.map(normalizeConversation);
      state.usingDemo = false;
      hideDemoBanner();
      setupRealtime();
    } catch (error) {
      if (error.status === 401) return;
      // 自动模式仅在接口不存在时允许演示回退，权限错误和网络故障不能伪装成演示成功。
      if (state.mode === "auto" && error.status === 404) {
        loadDemo();
        showDemoBanner();
        setConnectionState("connected");
      } else {
        state.usingDemo = false;
        showDemoBanner("聊天服务不可用，请确认后端 CHAT_ENABLED 已开启，或重新连接。");
        setConnectionState("disconnected");
      }
    }
  }
  state.activeConversationId = state.conversations[0]?.id || null;
  renderConversations();
  if (state.activeConversationId) await activateConversation(state.activeConversationId, { initial: true });
  else renderNoConversation();
  renderPeople(demoPeople);
  bindEvents();
  byId("sessionButton").classList.toggle("hidden", state.usingDemo);
}

function setupRealtime() {
  state.socket?.disconnect(false);
  state.socket = new NativeStompClient(CONFIG.webSocketEndpoint, {
    onState: setConnectionState,
    onMessage: handleRealtimeMessage,
    onAck: handleMessageAck,
    onError: handleSocketError,
    onReconnectExhausted: () => toast("实时连接多次重试失败，可刷新页面后再试", "error")
  });
  state.socket.connect();
}

function setConnectionState(status) {
  state.connectionState = status;
  const dot = byId("connectionDot");
  if (!dot) return;
  dot.className = `status-dot ${status}`;
  const active = getActiveConversation();
  if (!active) return;
  const base = active.type === "PUBLIC_ROOM" ? `${active.memberCount || "—"} 位成员${active.onlineCount ? ` · ${active.onlineCount} 人在线` : ""}` : (active.onlineCount ? "在线" : "私信会话");
  const connectionText = status === "connecting" ? " · 正在连接" : status === "disconnected" ? " · 连接断开" : "";
  byId("activeSubtitle").textContent = `${base}${connectionText}`;
}

function showDemoBanner(message) {
  const banner = byId("environmentBanner");
  banner.classList.remove("hidden");
  if (message) $("span:nth-child(2)", banner).textContent = message;
}

function hideDemoBanner() {
  byId("environmentBanner").classList.add("hidden");
}

function getActiveConversation() {
  return state.conversations.find((conversation) => conversation.id === state.activeConversationId);
}

function renderConversations() {
  const query = state.search.trim().toLocaleLowerCase("zh-CN");
  const matches = state.conversations.filter((conversation) => {
    const matchesFilter = state.filter === "all" || conversation.type === state.filter;
    const matchesQuery = !query || `${conversation.name} ${conversation.preview}`.toLocaleLowerCase("zh-CN").includes(query);
    return matchesFilter && matchesQuery;
  });
  const pinned = matches.filter((conversation) => conversation.pinned);
  const recent = matches.filter((conversation) => !conversation.pinned);
  byId("pinnedSection").classList.toggle("hidden", pinned.length === 0);
  byId("pinnedCount").textContent = String(pinned.length);
  byId("recentCount").textContent = String(recent.length);
  byId("pinnedList").replaceChildren(...pinned.map(conversationElement));
  byId("conversationList").replaceChildren(...recent.map(conversationElement));
  byId("conversationEmpty").classList.toggle("hidden", matches.length > 0);
}

function conversationElement(conversation) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `conversation-item${conversation.id === state.activeConversationId ? " active" : ""}`;
  button.dataset.conversationId = conversation.id;
  button.setAttribute("aria-current", conversation.id === state.activeConversationId ? "true" : "false");

  const avatar = document.createElement("span");
  avatar.className = conversation.type === "PUBLIC_ROOM" ? `conversation-avatar room-avatar ${conversation.accent || ""}` : `conversation-avatar user-avatar ${conversation.palette || "avatar-sage"}`;
  avatar.textContent = conversation.avatar || initials(conversation.name);

  const copy = document.createElement("span");
  copy.className = "conversation-copy";
  const nameRow = document.createElement("span");
  nameRow.className = "conversation-name-row";
  const name = document.createElement("span");
  name.className = "conversation-name";
  name.textContent = conversation.name;
  const type = document.createElement("span");
  type.className = "type-mark";
  type.innerHTML = conversation.type === "PUBLIC_ROOM" ? "<svg><use href=\"#i-users\"/></svg>" : "";
  nameRow.append(name, type);
  const preview = document.createElement("span");
  preview.className = "conversation-preview";
  preview.textContent = conversation.preview || "还没有消息";
  copy.append(nameRow, preview);

  const side = document.createElement("span");
  side.className = "conversation-side";
  const time = document.createElement("span");
  time.className = "conversation-time";
  time.textContent = conversation.time || "";
  side.append(time);
  if (conversation.unread > 0) {
    const unread = document.createElement("span");
    unread.className = "unread-badge";
    unread.textContent = conversation.unread > 99 ? "99+" : String(conversation.unread);
    side.append(unread);
  } else if (conversation.pinned) {
    const pin = document.createElement("span");
    pin.className = "pin-mark";
    pin.textContent = "⌖";
    side.append(pin);
  }
  button.append(avatar, copy, side);
  button.addEventListener("click", () => activateConversation(conversation.id));
  return button;
}

/** 切换会话视图；首次访问时加载历史到缓存，随后更新当前会话订阅。 */
async function activateConversation(id, options = {}) {
  const conversation = state.conversations.find((item) => item.id === String(id));
  if (!conversation) return;
  state.activeConversationId = conversation.id;
  conversation.unread = 0;
  renderConversations();
  renderActiveHeader(conversation);
  renderDetails(conversation);
  closeMobilePanels();
  byId("messageInput").placeholder = `发送消息给 ${conversation.type === "PUBLIC_ROOM" ? "#" : ""}${conversation.name}`;
  if (!state.messages.has(conversation.id) && !state.usingDemo) {
    renderMessageLoading();
    try {
      const payload = await api.getMessages(conversation.id, { limit: CONFIG.historyPageSize || 30 });
      const result = normalizeMessagesResponse(payload, conversation.id);
      state.messages.set(conversation.id, result.items);
      state.cursors.set(conversation.id, result);
    } catch (error) {
      state.messages.set(conversation.id, []);
      toast(error.message || "消息加载失败", "error");
    }
  }
  renderMessages({ scrollToBottom: options.initial !== false });
  state.socket?.subscribeToConversation(conversation.id);
}

function renderActiveHeader(conversation) {
  const avatar = byId("activeAvatar");
  avatar.className = conversation.type === "PUBLIC_ROOM" ? `conversation-avatar room-avatar ${conversation.accent || ""}` : `conversation-avatar user-avatar ${conversation.palette || "avatar-sage"}`;
  avatar.textContent = conversation.avatar || initials(conversation.name);
  byId("activeTitle").textContent = conversation.name;
  byId("activeType").textContent = conversation.type === "PUBLIC_ROOM" ? "公开群组" : "私信";
  setConnectionState(state.connectionState);
  renderMemberStack(conversation);
}

function renderMemberStack(conversation) {
  const members = conversation.members?.length ? conversation.members : demoPeople.slice(0, Math.min(conversation.memberCount || 3, 4));
  const nodes = members.slice(0, 4).map((member, index) => avatarElement(member, index));
  const remaining = Math.max((conversation.memberCount || members.length) - nodes.length, 0);
  if (remaining) {
    const overflow = document.createElement("span");
    overflow.className = "member-overflow";
    overflow.textContent = `+${remaining}`;
    nodes.push(overflow);
  }
  byId("memberStack").replaceChildren(...nodes);
}

function avatarElement(person, index = 0) {
  const avatar = document.createElement("span");
  avatar.className = `user-avatar ${person.palette || palette[index % palette.length]}`;
  avatar.textContent = person.avatar || initials(person.name || person.username);
  avatar.title = person.name || person.username || "成员";
  return avatar;
}

function renderMessageLoading() {
  const loading = document.createElement("div");
  loading.className = "system-message";
  const text = document.createElement("span");
  text.textContent = "正在加载消息…";
  loading.append(text);
  byId("messageList").replaceChildren(loading);
}

function renderNoConversation() {
  byId("activeTitle").textContent = "选择一个会话";
  byId("activeSubtitle").textContent = "从左侧列表开始聊天";
  byId("messageInput").disabled = true;
  const empty = document.createElement("div");
  empty.className = "system-message";
  const text = document.createElement("span");
  text.textContent = "还没有会话，创建一个讨论组开始协作吧";
  empty.append(text);
  byId("messageList").replaceChildren(empty);
}

function renderMessages(options = {}) {
  const conversationId = state.activeConversationId;
  const messages = state.messages.get(conversationId) || [];
  const list = byId("messageList");
  const scroller = byId("messageScroller");
  const wasAtBottom = scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop <= 40;
  const existing = new Map([...list.children].map((node) => [node.dataset.renderKey, node]));
  const existingIds = new Map([...list.children].filter((node) => node.dataset.messageId)
    .map((node) => [JSON.stringify([node.dataset.conversationId, node.dataset.messageId]), node]));
  const nodes = [];
  let lastDate = "";
  let lastSender = null;
  let lastTimestamp = 0;
  messages.forEach((message) => {
    const date = parseDate(message.createdAt);
    const dateKey = `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
    if (dateKey !== lastDate) {
      const key = JSON.stringify([conversationId, "date", dateKey]);
      const divider = existing.get(key) || document.createElement("div");
      divider.dataset.renderKey = key;
      divider.className = "date-divider";
      setMessageText(divider, formatDateLabel(message.createdAt));
      nodes.push(divider);
      lastDate = dateKey;
      lastSender = null;
    }
    // 服务端确认会替换临时 ID；sender/request 在发送、ACK 和广播之间保持不变。
    const key = JSON.stringify([conversationId, message.type, String(message.senderId),
      message.clientRequestId ? "request" : "id", String(message.clientRequestId || message.id)]);
    if (message.type === "SYSTEM" || message.eventType) {
      const system = existing.get(key) || document.createElement("div");
      if (!system.firstElementChild) {
        system.className = "system-message";
        system.append(document.createElement("span"));
      }
      system.dataset.renderKey = key;
      setMessageText(system.firstElementChild, message.body || message.message || "会话状态已更新");
      nodes.push(system);
      return;
    }
    const timestamp = parseDate(message.createdAt).getTime();
    // 同一天内，同一发送者相隔不足五分钟的连续消息合并显示头像和时间信息。
    const grouped = lastSender === message.senderId && timestamp - lastTimestamp < 5 * 60 * 1000;
    const previous = existing.get(key) || existingIds.get(JSON.stringify([conversationId, String(message.id)]));
    const row = messageElement(message, grouped, previous);
    row.dataset.conversationId = conversationId;
    row.dataset.renderKey = key;
    if (!previous && options.animate) {
      row.classList.add("is-new");
      row.addEventListener("animationend", () => row.classList.remove("is-new"), { once: true });
    }
    nodes.push(row);
    lastSender = message.senderId;
    lastTimestamp = timestamp;
  });
  if (nodes.length === 0) {
    const empty = document.createElement("div");
    empty.className = "system-message";
    const text = document.createElement("span");
    text.textContent = "这里还很安静，发条消息打个招呼吧";
    empty.append(text);
    nodes.push(empty);
  }
  // 只插入、移动或移除真正变化的节点，保留旧消息、图片和正在进行的动画。
  const retained = new Set(nodes);
  [...list.children].forEach((node) => { if (!retained.has(node)) node.remove(); });
  nodes.forEach((node, index) => {
    if (list.children[index] !== node) list.insertBefore(node, list.children[index] || null);
  });
  const cursor = state.cursors.get(state.activeConversationId);
  byId("loadHistoryButton").classList.toggle("hidden", !cursor?.hasMore);
  byId("messageInput").disabled = false;
  updateSendButton();
  if (options.scrollToBottom === true || (options.scrollToBottom !== false && wasAtBottom)) {
    requestAnimationFrame(() => {
      if (state.activeConversationId === conversationId) scroller.scrollTop = scroller.scrollHeight;
    });
  }
}

function setMessageText(element, text) {
  const value = String(text ?? "");
  if (element.textContent !== value) element.textContent = value;
}

function messageElement(message, grouped, existing) {
  const own = String(message.senderId) === String(state.currentUser.id);
  const row = existing || document.createElement("article");
  if (!existing) row.className = "message-row";
  row.classList.toggle("own", own);
  row.classList.toggle("grouped", grouped);
  row.dataset.messageId = message.id;
  if (!existing) row.append(avatarElement({ name: message.senderName, avatar: message.senderAvatar, palette: message.palette }));
  const avatar = $(".user-avatar", row);
  avatar.className = `user-avatar ${own ? "avatar-sage" : message.palette || "avatar-sage"}`;
  avatar.title = message.senderName || "成员";
  setMessageText(avatar, message.senderAvatar || initials(message.senderName));

  const block = $(".message-block", row) || document.createElement("div");
  if (!existing) {
    block.className = "message-block";
    const sender = document.createElement("div");
    sender.className = "sender-line";
    sender.append(document.createElement("strong"), document.createElement("time"));
    block.append(sender);
    row.append(block);
  }
  const sender = $(".sender-line", block);
  sender.classList.toggle("hidden", grouped);
  setMessageText($("strong", sender), own ? "你" : message.senderName);
  const time = $("time", sender);
  time.dateTime = message.createdAt;
  setMessageText(time, formatClock(message.createdAt));

  if (!existing && message.type === "IMAGE") {
    const imageWrap = document.createElement("div");
    imageWrap.className = "image-message";
    const image = document.createElement("img");
    image.loading = "lazy";
    image.addEventListener("click", () => window.open(image.dataset.originalUrl || image.src, "_blank", "noopener,noreferrer"));
    image.addEventListener("error", () => { image.alt = "图片加载失败"; imageWrap.classList.add("failed"); });
    const caption = document.createElement("div");
    caption.className = "image-caption";
    const name = document.createElement("span");
    const hint = document.createElement("span");
    hint.textContent = "点击查看";
    caption.append(name, hint);
    imageWrap.append(image, caption);
    block.append(imageWrap);
  } else if (!existing) {
    const bubble = document.createElement("div");
    bubble.className = "message-bubble";
    block.append(bubble);
  }

  if (message.type === "IMAGE") {
    const image = $("img", block);
    const source = message.imageUrl || `${CONFIG.apiBaseUrl || "/api/chat/v1"}/attachments/${message.attachmentId}/thumbnail`;
    if (image.getAttribute("src") !== source) image.setAttribute("src", source);
    image.dataset.originalUrl = message.originalUrl || message.imageUrl || "";
    image.alt = message.fileName ? `图片：${message.fileName}` : "聊天图片";
    setMessageText($(".image-caption", block).firstElementChild, message.fileName || "图片");
  } else {
    const bubble = $(".message-bubble", block);
    bubble.classList.toggle("failed", message.status === "FAILED");
    // 用户正文只作为文本渲染，避免把消息内容解释为 HTML。
    setMessageText(bubble, message.body);
  }

  if (own) {
    const status = $(".message-status", block) || document.createElement("div");
    if (!status.parentElement) block.append(status);
    status.className = `message-status${message.status === "FAILED" ? " failed" : ""}`;
    status.classList.toggle("settled", message.status === "SENT");
    if (status.dataset.status !== message.status) {
      status.dataset.status = message.status;
      status.textContent = message.status === "FAILED" ? "发送失败 · " : message.status === "SENT" ? "" : "发送中…";
      if (message.status === "FAILED") {
        const retry = document.createElement("button");
        retry.type = "button";
        retry.textContent = "重试";
        retry.addEventListener("click", () => retryMessage(row.dataset.messageId));
        status.append(retry);
      }
    }
  }
  return row;
}

async function loadEarlierMessages() {
  const button = byId("loadHistoryButton");
  const scroller = byId("messageScroller");
  const beforeHeight = scroller.scrollHeight;
  const beforeTop = scroller.scrollTop;
  button.disabled = true;
  button.textContent = "正在加载…";
  try {
    if (state.usingDemo) {
      await sleep(450);
      const older = [
        { id: `older-${Date.now()}-1`, senderId: 1003, senderName: "陈默", senderAvatar: "陈", palette: "avatar-blue", type: "TEXT", body: "我先把基础组件和接口类型整理出来，后面联调会快一些。", createdAt: "2026-08-30T16:20:00+08:00", status: "SENT" },
        { id: `older-${Date.now()}-2`, senderId: 1001, senderName: "林澈", senderAvatar: "林", palette: "avatar-sage", type: "TEXT", body: "好，优先保证消息流和异常状态完整。", createdAt: "2026-08-30T16:28:00+08:00", status: "SENT" }
      ];
      state.messages.set(state.activeConversationId, [...older, ...(state.messages.get(state.activeConversationId) || [])]);
      state.cursors.set(state.activeConversationId, { hasMore: false, beforeCursor: null });
    } else {
      const cursor = state.cursors.get(state.activeConversationId);
      const payload = await api.getMessages(state.activeConversationId, { before: cursor?.beforeCursor, limit: CONFIG.historyPageSize || 30 });
      const result = normalizeMessagesResponse(payload, state.activeConversationId);
      const current = state.messages.get(state.activeConversationId) || [];
      state.messages.set(state.activeConversationId, mergeMessages(result.items, current));
      state.cursors.set(state.activeConversationId, result);
    }
    renderMessages({ scrollToBottom: false });
    // 历史消息插到列表前部后按新增高度补偿滚动位置，避免直接跳到列表底部。
    scroller.scrollTop = beforeTop + scroller.scrollHeight - beforeHeight;
  } catch (error) {
    toast(error.message || "更早消息加载失败", "error");
  } finally {
    button.disabled = false;
    button.textContent = "查看更早消息";
  }
}

/** 分页合并时按消息 ID 去重；第二组覆盖同 ID 数据，最后按时间重新排序。 */
function mergeMessages(first, second) {
  const result = new Map();
  [...first, ...second].forEach((message) => result.set(String(message.id), message));
  return [...result.values()].sort((a, b) => parseDate(a.createdAt) - parseDate(b.createdAt));
}

function updateComposer() {
  const input = byId("messageInput");
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight, 140)}px`;
  byId("characterCount").textContent = `${[...input.value].length} / 5000`;
  byId("characterCount").classList.toggle("warning", [...input.value].length > 4500);
  updateSendButton();
}

function updateSendButton() {
  const hasContent = byId("messageInput").value.trim().length > 0 || Boolean(state.pendingFile);
  byId("sendButton").disabled = !hasContent || !state.activeConversationId;
}

async function sendCurrentMessage() {
  const input = byId("messageInput");
  const body = input.value.trim();
  if (!body && !state.pendingFile) return;
  if ([...body].length > 5000) {
    toast("消息不能超过 5000 个字符", "error");
    return;
  }
  if (state.pendingFile) {
    await sendImageMessage(state.pendingFile.file, body);
    return;
  }
  const message = createOptimisticMessage({ type: "TEXT", body });
  input.value = "";
  updateComposer();
  await dispatchMessage(message);
}

/** 先插入 SENDING 占位消息，后续通过 clientRequestId 将确认结果回填到该条消息。 */
function createOptimisticMessage(data) {
  const message = normalizeMessage({
    id: `pending-${uuid()}`,
    clientRequestId: uuid(),
    conversationId: state.activeConversationId,
    senderId: state.currentUser.id,
    senderName: state.currentUser.name,
    senderAvatar: state.currentUser.avatar || initials(state.currentUser.name),
    palette: "avatar-sage",
    createdAt: new Date().toISOString(),
    status: "SENDING",
    ...data
  }, state.activeConversationId);
  const messages = state.messages.get(state.activeConversationId) || [];
  state.messages.set(state.activeConversationId, [...messages, message]);
  updateConversationPreview(message);
  renderMessages({ animate: true, scrollToBottom: true });
  renderConversations();
  return message;
}

async function sendImageMessage(file, caption) {
  let localUrl = null;
  let message;
  try {
    let attachment = null;
    if (!state.usingDemo) attachment = await api.uploadAttachment(state.activeConversationId, file);
    localUrl = URL.createObjectURL(file);
    message = createOptimisticMessage({
      type: "IMAGE",
      body: caption || null,
      attachmentId: attachment?.id || attachment?.attachmentId || attachment?.attachment_id,
      imageUrl: localUrl,
      fileName: file.name
    });
    clearPendingFile();
    byId("messageInput").value = "";
    updateComposer();
    await dispatchMessage(message);
  } catch (error) {
    if (localUrl) URL.revokeObjectURL(localUrl);
    toast(error.message || "图片上传失败", "error");
  }
}

/** 演示模式本地模拟成功；实时模式只提交消息内容，最终状态等待 ACK 或广播回填。 */
async function dispatchMessage(message) {
  if (state.usingDemo) {
    await sleep(420);
    updateMessageStatus(message.clientRequestId, "SENT", { id: `demo-${uuid()}` });
    return;
  }
  // 不上传页面模型里的 senderId 等身份字段，由服务端根据 Session 决定发送者。
  const sent = state.socket?.sendMessage({
    clientRequestId: message.clientRequestId,
    conversationId: message.conversationId,
    type: message.type,
    body: message.type === "TEXT" ? message.body : undefined,
    attachmentId: message.type === "IMAGE" ? message.attachmentId : undefined
  });
  if (!sent) updateMessageStatus(message.clientRequestId, "FAILED");
}

function retryMessage(id) {
  const message = (state.messages.get(state.activeConversationId) || []).find((item) => item.id === id);
  if (!message) return;
  message.status = "SENDING";
  // 当前重试会生成新的请求标识，ACK 按本次标识关联；这不是复用原请求的幂等重放。
  message.clientRequestId = uuid();
  renderMessages();
  dispatchMessage(message);
}

function updateMessageStatus(clientRequestId, status, patch = {}) {
  for (const [conversationId, messages] of state.messages.entries()) {
    const message = messages.find((item) => item.clientRequestId === clientRequestId);
    if (!message) continue;
    Object.assign(message, patch, { status });
    if (conversationId === state.activeConversationId) renderMessages({ scrollToBottom: false });
    break;
  }
}

/** 接收私人确认队列载荷，兼容完整消息或仅消息 ID/时间的响应格式。 */
function handleMessageAck(payload) {
  const clientRequestId = payload.clientRequestId || payload.client_request_id;
  const rawMessage = payload.message || payload.result;
  const patch = rawMessage
    ? normalizeMessage(rawMessage, rawMessage.conversationId || rawMessage.conversation_id || state.activeConversationId)
    : {
        id: String(payload.messageId || payload.message_id || `message-${uuid()}`),
        createdAt: payload.createdAt || payload.created_at || new Date().toISOString()
      };
  updateMessageStatus(clientRequestId, "SENT", patch);
}

function handleSocketError(payload) {
  const clientRequestId = payload.clientRequestId || payload.client_request_id;
  if (clientRequestId) updateMessageStatus(clientRequestId, "FAILED");
  toast(payload.message || "消息发送失败", "error");
}

function handleRealtimeMessage(payload) {
  if (payload.eventType === "CONVERSATION_DISSOLVED" || payload.type === "CONVERSATION_DISSOLVED") {
    const conversationId = String(payload.conversationId || payload.conversation_id);
    removeConversation(conversationId);
    toast("讨论组已解散", "error");
    return;
  }
  const raw = payload.message || payload.data || payload;
  const message = normalizeMessage(raw, raw.conversationId || raw.conversation_id || state.activeConversationId);
  const id = message.conversationId;
  const existing = state.messages.get(id) || [];
  // 广播可能先于 ACK 到达：优先回填相同请求的占位消息，再按服务端消息 ID 去重。
  const optimisticIndex = existing.findIndex((item) => item.clientRequestId && item.clientRequestId === message.clientRequestId);
  if (optimisticIndex >= 0) existing[optimisticIndex] = { ...existing[optimisticIndex], ...message, status: "SENT" };
  else if (!existing.some((item) => item.id === message.id)) existing.push(message);
  state.messages.set(id, existing.sort((a, b) => parseDate(a.createdAt) - parseDate(b.createdAt)));
  updateConversationPreview(message);
  const conversation = state.conversations.find((item) => item.id === id);
  if (conversation && id !== state.activeConversationId) conversation.unread += 1;
  renderConversations();
  if (id === state.activeConversationId) renderMessages({ animate: true });
}

function updateConversationPreview(message) {
  const conversation = state.conversations.find((item) => item.id === String(message.conversationId || state.activeConversationId));
  if (!conversation) return;
  conversation.preview = message.type === "IMAGE" ? `${message.senderName || ""}：发送了一张图片` : `${Number(message.senderId) === Number(state.currentUser.id) ? "你" : message.senderName}：${message.body}`;
  conversation.time = formatClock(message.createdAt);
}

function validateImage(file) {
  const allowed = ["image/jpeg", "image/png", "image/gif", "image/webp"];
  if (!allowed.includes(file.type)) return "仅支持 JPEG、PNG、GIF 和 WebP 图片";
  if (file.size > 10 * 1024 * 1024) return "单张图片不能超过 10 MB";
  return null;
}

function selectImage(file) {
  if (!file) return;
  const error = validateImage(file);
  if (error) {
    toast(error, "error");
    byId("imageInput").value = "";
    return;
  }
  clearPendingFile();
  const url = URL.createObjectURL(file);
  state.pendingFile = { file, url };
  byId("uploadPreviewImage").src = url;
  byId("uploadFileName").textContent = file.name;
  byId("uploadFileMeta").textContent = `${formatFileSize(file.size)} · 等待发送`;
  byId("uploadPreview").classList.remove("hidden");
  updateSendButton();
}

/** 释放待发送预览的对象 URL，避免反复选择图片持续占用浏览器内存。 */
function clearPendingFile(revoke = true) {
  if (revoke && state.pendingFile?.url) URL.revokeObjectURL(state.pendingFile.url);
  state.pendingFile = null;
  byId("uploadPreview").classList.add("hidden");
  byId("imageInput").value = "";
  updateSendButton();
}

function renderDetails(conversation) {
  const avatar = byId("detailsAvatar");
  avatar.className = conversation.type === "PUBLIC_ROOM" ? `conversation-avatar room-avatar large ${conversation.accent || ""}` : `conversation-avatar user-avatar large ${conversation.palette || "avatar-sage"}`;
  avatar.textContent = conversation.avatar || initials(conversation.name);
  byId("detailsTitle").textContent = conversation.name;
  byId("detailsDescription").textContent = conversation.description || (conversation.type === "PUBLIC_ROOM" ? "团队协作讨论组" : "一对一私信");
  byId("detailsMemberCount").textContent = conversation.memberCount || (conversation.type === "DIRECT_MESSAGE" ? 2 : "—");
  const imageMessages = (state.messages.get(conversation.id) || []).filter((message) => message.type === "IMAGE");
  byId("detailsImageCount").textContent = String(imageMessages.length);
  byId("detailsFileCount").textContent = "0";
  const members = conversation.members?.length ? conversation.members : demoPeople.slice(0, 4);
  byId("detailsMembers").replaceChildren(...members.slice(0, 4).map(memberRow));
  const images = imageMessages.map((message) => message.imageUrl).filter(Boolean);
  const displayImages = images.length ? images : (state.usingDemo ? remoteImages.slice(2, 5) : []);
  byId("mediaGrid").replaceChildren(...displayImages.slice(0, 6).map((source) => {
    const image = document.createElement("img");
    image.src = source;
    image.alt = "会话共享图片";
    image.loading = "lazy";
    return image;
  }));
  // 按钮显隐仅控制交互入口，实际房主权限仍由后端解散接口校验。
  byId("dissolveRoomButton").classList.toggle("hidden", conversation.type !== "PUBLIC_ROOM" || String(conversation.ownerId) !== String(state.currentUser.id));
}

function memberRow(member, index) {
  const row = document.createElement("div");
  row.className = "member-row";
  const info = document.createElement("div");
  const name = document.createElement("strong");
  name.textContent = member.name || member.username || "成员";
  const role = document.createElement("span");
  role.textContent = member.role || "团队成员";
  info.append(name, role);
  const status = document.createElement("small");
  status.textContent = member.online ? "在线" : "离线";
  row.append(avatarElement(member, index), info, status);
  return row;
}

function toggleDetails(force) {
  const panel = byId("detailsPanel");
  const open = force ?? !panel.classList.contains("open");
  panel.classList.toggle("open", open);
  panel.setAttribute("aria-hidden", String(!open));
  byId("appShell").classList.toggle("details-visible", open);
  if (window.innerWidth < 1280) byId("mobileScrim").classList.toggle("hidden", !open);
}

function toggleConversationPanel(force) {
  const panel = byId("conversationPanel");
  const open = force ?? !panel.classList.contains("open");
  panel.classList.toggle("open", open);
  byId("mobileScrim").classList.toggle("hidden", !open);
}

function closeMobilePanels() {
  byId("conversationPanel").classList.remove("open");
  if (!byId("detailsPanel").classList.contains("open") || window.innerWidth >= 1280) byId("mobileScrim").classList.add("hidden");
}

function renderPeople(people) {
  const selectedId = state.selectedPerson?.id;
  const list = people.map((person, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `person-option${Number(person.id) === Number(selectedId) ? " selected" : ""}`;
    button.dataset.personId = person.id;
    const info = document.createElement("span");
    const name = document.createElement("strong");
    name.textContent = person.name || person.username;
    const role = document.createElement("small");
    role.textContent = `${person.role || "团队成员"}${person.username ? ` · @${person.username}` : ""}`;
    info.append(name, role);
    const check = document.createElement("span");
    check.className = "option-check";
    check.textContent = "✓";
    button.append(avatarElement(person, index), info, check);
    button.addEventListener("click", () => {
      state.selectedPerson = person;
      renderPeople(people);
      byId("submitDirectButton").disabled = false;
    });
    return button;
  });
  byId("peopleList").replaceChildren(...list);
}

async function searchPeople(query) {
  const normalized = query.trim().toLocaleLowerCase("zh-CN");
  if (state.usingDemo) {
    renderPeople(demoPeople.filter((person) => `${person.name} ${person.username} ${person.role}`.toLocaleLowerCase("zh-CN").includes(normalized)));
    return;
  }
  try {
    const payload = await api.searchUsers(query.trim());
    const users = (Array.isArray(payload) ? payload : (payload?.items || payload?.records || payload?.users || [])).map((user) => ({
      ...user,
      id: user.id ?? user.userId ?? user.user_id,
      name: user.name || user.displayName || user.username,
      avatar: user.avatar || initials(user.name || user.username),
      palette: palette[hashIndex(user.id, palette.length)]
    }));
    renderPeople(users);
  } catch (error) {
    toast(error.message || "员工搜索失败", "error");
  }
}

async function createRoom(name) {
  const trimmed = name.trim();
  const error = byId("roomFormError");
  if ([...trimmed].length < 2 || [...trimmed].length > 50) {
    error.textContent = "名称长度需为 2–50 个字符";
    error.classList.remove("hidden");
    return;
  }
  byId("submitRoomButton").disabled = true;
  try {
    let conversation;
    if (state.usingDemo) {
      await sleep(350);
      if (state.conversations.some((item) => item.type === "PUBLIC_ROOM" && item.name.trim().toLocaleLowerCase("zh-CN") === trimmed.toLocaleLowerCase("zh-CN"))) throw new ApiError("已经有同名讨论组了", 409);
      conversation = normalizeConversation({ id: `room-${uuid()}`, type: "PUBLIC_ROOM", name: trimmed, ownerId: state.currentUser.id, memberCount: 1, onlineCount: 1, preview: "讨论组已创建", pinned: false, description: "新创建的团队讨论组", members: [] });
    } else {
      conversation = normalizeConversation(await api.createRoom(trimmed));
    }
    state.conversations.unshift(conversation);
    state.messages.set(conversation.id, [{ id: `system-${uuid()}`, type: "SYSTEM", body: `${state.currentUser.name} 创建了讨论组`, createdAt: new Date().toISOString() }]);
    byId("roomDialog").close();
    byId("roomForm").reset();
    byId("roomNameCount").textContent = "0";
    toast(`已创建“${conversation.name}”`);
    await activateConversation(conversation.id);
  } catch (requestError) {
    error.textContent = requestError.status === 409 ? "已经有同名讨论组了" : (requestError.message || "创建失败");
    error.classList.remove("hidden");
  } finally {
    byId("submitRoomButton").disabled = false;
  }
}

async function createDirectConversation(person) {
  if (!person) return;
  byId("submitDirectButton").disabled = true;
  try {
    let conversation;
    if (state.usingDemo) {
      await sleep(300);
      conversation = state.conversations.find((item) => Number(item.peerId) === Number(person.id));
      if (!conversation) conversation = normalizeConversation({ id: `dm-${person.id}`, type: "DIRECT_MESSAGE", name: person.name, peerId: person.id, avatar: person.avatar, palette: person.palette, memberCount: 2, onlineCount: person.online ? 1 : 0, members: [person], preview: "开始一段新对话" });
    } else {
      conversation = normalizeConversation(await api.createDirectConversation(person.id));
    }
    if (!state.conversations.some((item) => item.id === conversation.id)) state.conversations.unshift(conversation);
    if (!state.messages.has(conversation.id)) state.messages.set(conversation.id, []);
    byId("directDialog").close();
    state.selectedPerson = null;
    toast(`已打开与 ${conversation.name} 的对话`);
    await activateConversation(conversation.id);
  } catch (error) {
    toast(error.message || "私信创建失败", "error");
  } finally {
    byId("submitDirectButton").disabled = !state.selectedPerson;
  }
}

async function dissolveActiveRoom() {
  const conversation = getActiveConversation();
  if (!conversation) return;
  byId("confirmDissolveButton").disabled = true;
  try {
    if (state.usingDemo) await sleep(380);
    else await api.dissolveRoom(conversation.id);
    byId("confirmDialog").close();
    toggleDetails(false);
    removeConversation(conversation.id);
    toast(`“${conversation.name}”已解散`);
  } catch (error) {
    toast(error.message || "解散失败", "error");
  } finally {
    byId("confirmDissolveButton").disabled = false;
  }
}

function removeConversation(id) {
  state.conversations = state.conversations.filter((conversation) => conversation.id !== String(id));
  state.messages.delete(String(id));
  if (state.activeConversationId === String(id)) {
    state.activeConversationId = state.conversations[0]?.id || null;
    if (state.activeConversationId) activateConversation(state.activeConversationId);
    else renderNoConversation();
  }
  renderConversations();
}

function openRoomDialog() {
  byId("roomFormError").classList.add("hidden");
  byId("roomDialog").showModal();
  requestAnimationFrame(() => byId("roomNameInput").focus());
}

function openDirectDialog() {
  state.selectedPerson = null;
  byId("submitDirectButton").disabled = true;
  byId("peopleSearch").value = "";
  renderPeople(demoPeople);
  byId("directDialog").showModal();
  requestAnimationFrame(() => byId("peopleSearch").focus());
}

function toast(message, type = "success") {
  const node = document.createElement("div");
  node.className = `toast ${type}`;
  const text = document.createElement("span");
  text.textContent = message;
  const close = document.createElement("button");
  close.type = "button";
  close.textContent = "关闭";
  close.addEventListener("click", () => node.remove());
  node.append(text, close);
  byId("toastRegion").append(node);
  window.setTimeout(() => node.remove(), 4200);
}

function bindEvents() {
  byId("sessionButton").addEventListener("click", async () => {
    try {
      await api.logout();
      requireLogin();
    } catch (error) { toast(error.message || "退出失败，请重试", "error"); }
  });
  byId("conversationSearch").addEventListener("input", (event) => { state.search = event.target.value; renderConversations(); });
  $$(".view-switch button").forEach((button) => button.addEventListener("click", () => {
    state.filter = button.dataset.filter;
    $$(".view-switch button").forEach((item) => { item.classList.toggle("active", item === button); item.setAttribute("aria-selected", String(item === button)); });
    renderConversations();
  }));
  byId("newConversationButton").addEventListener("click", openDirectDialog);
  byId("createRoomButton").addEventListener("click", openRoomDialog);
  byId("messageInput").addEventListener("input", updateComposer);
  byId("messageInput").addEventListener("keydown", (event) => {
    // Enter 发送、Shift+Enter 换行；输入法组词期间的回车不触发发送。
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      sendCurrentMessage();
    }
  });
  byId("sendButton").addEventListener("click", sendCurrentMessage);
  byId("imageButton").addEventListener("click", () => byId("imageInput").click());
  byId("imageInput").addEventListener("change", (event) => selectImage(event.target.files?.[0]));
  byId("clearUploadButton").addEventListener("click", () => clearPendingFile());
  byId("emojiButton").addEventListener("click", () => byId("emojiPopover").classList.toggle("hidden"));
  byId("closeEmojiButton").addEventListener("click", () => byId("emojiPopover").classList.add("hidden"));
  byId("emojiGrid").replaceChildren(...emojis.map((emoji) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = emoji;
    button.setAttribute("aria-label", `插入表情 ${emoji}`);
    button.addEventListener("click", () => {
      const input = byId("messageInput");
      const start = input.selectionStart;
      input.setRangeText(emoji, start, input.selectionEnd, "end");
      input.focus();
      updateComposer();
    });
    return button;
  }));
  byId("loadHistoryButton").addEventListener("click", loadEarlierMessages);
  byId("conversationInfoButton").addEventListener("click", () => toggleDetails());
  byId("closeDetailsButton").addEventListener("click", () => toggleDetails(false));
  byId("openConversationButton").addEventListener("click", () => toggleConversationPanel(true));
  byId("mobileScrim").addEventListener("click", () => { toggleDetails(false); toggleConversationPanel(false); });
  byId("roomForm").addEventListener("submit", (event) => { event.preventDefault(); createRoom(byId("roomNameInput").value); });
  byId("roomNameInput").addEventListener("input", (event) => { byId("roomNameCount").textContent = [...event.target.value].length; byId("roomFormError").classList.add("hidden"); });
  byId("directForm").addEventListener("submit", (event) => { event.preventDefault(); createDirectConversation(state.selectedPerson); });
  let searchTimer;
  byId("peopleSearch").addEventListener("input", (event) => {
    window.clearTimeout(searchTimer);
    searchTimer = window.setTimeout(() => searchPeople(event.target.value), 220);
  });
  byId("dissolveRoomButton").addEventListener("click", () => {
    const conversation = getActiveConversation();
    byId("confirmRoomName").textContent = conversation?.name || "此讨论组";
    byId("confirmDialog").showModal();
  });
  byId("confirmForm").addEventListener("submit", (event) => { event.preventDefault(); dissolveActiveRoom(); });
  $$('[data-close-dialog]').forEach((button) => button.addEventListener("click", () => button.closest("dialog").close()));
  byId("retryLiveButton").addEventListener("click", retryLiveConnection);
  document.addEventListener("keydown", (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLocaleLowerCase() === "k") {
      event.preventDefault();
      if (window.innerWidth <= 720) toggleConversationPanel(true);
      byId("conversationSearch").focus();
    }
    if (event.key === "Escape") {
      byId("emojiPopover").classList.add("hidden");
      toggleDetails(false);
      toggleConversationPanel(false);
    }
  });
  window.addEventListener("resize", () => { if (window.innerWidth >= 1280) byId("mobileScrim").classList.add("hidden"); });
}

/** 重新读取真实身份和会话，清空原有消息与游标缓存后重建实时连接。 */
async function retryLiveConnection() {
  const button = byId("retryLiveButton");
  button.disabled = true;
  try {
    state.currentUser = await api.loadSession();
    const payload = await api.getConversations();
    const list = Array.isArray(payload) ? payload : (payload?.items || payload?.records || payload?.conversations || []);
    state.conversations = list.map(normalizeConversation);
    state.messages.clear();
    state.cursors.clear();
    state.usingDemo = false;
    hideDemoBanner();
    setupRealtime();
    state.activeConversationId = state.conversations[0]?.id || null;
    renderConversations();
    if (state.activeConversationId) await activateConversation(state.activeConversationId);
    toast("已切换到实时服务");
  } catch (error) {
    toast(error.message || "实时服务仍不可用", "error");
  } finally {
    button.disabled = false;
  }
}

initialize().catch((error) => {
  console.error(error);
  if (error.status !== 401) toast("页面初始化失败，请刷新后重试", "error");
});
