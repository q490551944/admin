/** Session ID 仅存于当前页面会话；刷新保留，新窗口登录重新建立独立 Session。 */
window.ChatSession = (() => {
  const key = "chat.sessionId";
  const header = "X-Chat-Session";
  let generation = 0;
  return Object.freeze({
    clear() { generation++; window.sessionStorage.removeItem(key); },
    async fetch(url, options = {}) {
      const target = new URL(url, window.location.href);
      const base = new URL(window.CHAT_CONFIG?.apiBaseUrl || "/api/chat/v1", window.location.href);
      if (target.origin !== window.location.origin || target.origin !== base.origin
          || !target.pathname.startsWith(base.pathname.replace(/\/$/, "") + "/")) {
        throw new Error("会话凭证只能发送到同源聊天接口");
      }
      const current = generation;
      const id = window.sessionStorage.getItem(key);
      const headers = new Headers(options.headers);
      headers.set(header, id || "new");
      const response = await fetch(url, { ...options, headers, credentials: "omit", cache: "no-store" });
      // 退出、重新登录或 ID 轮换之后，不允许迟到的响应恢复旧凭证。
      const next = response.headers.get(header);
      if (current === generation && window.sessionStorage.getItem(key) === id && next !== null) {
        if (next) window.sessionStorage.setItem(key, next);
        else window.sessionStorage.removeItem(key);
      }
      return response;
    }
  });
})();
