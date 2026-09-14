(function (global) {
  "use strict";

  const messages = Object.freeze({
    SESSION_EXPIRED: "登录已失效，请重新登录。",
    INVALID_CREDENTIALS: "用户名或密码错误，或账号已停用。",
    ACCESS_DENIED: "无查看权限，请联系管理员确认此账号的监控查看权限。",
    CSRF_INVALID: "请求凭证已失效，请重试。",
    IDENTITY_UNAVAILABLE: "认证服务暂不可用，请稍后重试。",
    MONITOR_DISABLED: "监控功能未启用，请联系管理员。",
    NETWORK_ERROR: "无法连接服务，请检查网络后重试。",
    REQUEST_FAILED: "请求未完成，请稍后重试。"
  });

  class SessionError extends Error {
    constructor(code, status = 0) {
      const safeCode = typeof code === "string" && Object.hasOwn(messages, code) ? code : "REQUEST_FAILED";
      super(messages[safeCode]);
      this.code = safeCode;
      this.status = status;
    }
  }

  function createClient(pageUrl = global.location.href, fetcher = global.fetch.bind(global)) {
    // Pages live under <context>/monitor/; keep API requests inside that same context.
    const base = new URL("../api/monitor/v1/", pageUrl);
    async function request(path, options = {}) {
      const controller = new AbortController();
      const cancel = () => controller.abort();
      if (options.signal?.aborted) controller.abort();
      options.signal?.addEventListener("abort", cancel, { once: true });
      const timer = global.setTimeout(cancel, 10000);
      try {
        const response = await fetcher(new URL(path, base).href, {
          ...options, credentials: "same-origin", cache: "no-store", redirect: "error", signal: controller.signal
        });
        let body = null;
        if (response.status !== 204) {
          try { body = await response.json(); } catch (_) { /* Never display an HTML or raw error response. */ }
        }
        if (!response.ok) {
          const fallback = { 401: "SESSION_EXPIRED", 403: "ACCESS_DENIED", 503: "IDENTITY_UNAVAILABLE" };
          throw new SessionError(body?.code || fallback[response.status] || "REQUEST_FAILED", response.status);
        }
        return body;
      } catch (error) {
        if (options.signal?.aborted) throw error;
        if (error instanceof SessionError) throw error;
        throw new SessionError("NETWORK_ERROR");
      } finally {
        global.clearTimeout(timer);
        options.signal?.removeEventListener("abort", cancel);
      }
    }

    async function write(path, method, body, signal) {
      // Authentication rotates CSRF. Fetch a fresh token for every write, including logout.
      const csrf = await request("csrf-token", { signal });
      if (!csrf || typeof csrf.token !== "string" || !csrf.token ||
          typeof csrf.headerName !== "string" || !/^X-[A-Za-z0-9-]+$/i.test(csrf.headerName)) {
        throw new SessionError("REQUEST_FAILED");
      }
      const headers = { [csrf.headerName]: csrf.token };
      if (body) headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8";
      return request(path, { method, headers, body, signal });
    }

    return Object.freeze({
      async current(signal) {
        const identity = await request("sessions/current", { signal });
        if (!identity || typeof identity.username !== "string" || typeof identity.id !== "string") {
          throw new SessionError("REQUEST_FAILED");
        }
        return identity;
      },
      async login(username, password, signal) {
        await write("sessions", "POST", new URLSearchParams({ username, password }).toString(), signal);
        // A 201 establishes identity only. The allowlist is checked by the current-session endpoint.
        return this.current(signal);
      },
      logout(signal) { return write("sessions/current", "DELETE", undefined, signal); }
    });
  }

  global.MonitorSession = Object.freeze({ createClient, SessionError });
})(window);
