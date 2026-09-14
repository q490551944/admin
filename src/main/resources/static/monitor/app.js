(function () {
  "use strict";
  const loginPage = document.body.dataset.page === "login";
  const client = window.MonitorSession.createClient();
  const byId = id => document.getElementById(id);
  const form = byId("login-form");
  const panel = byId("status-panel");
  const heading = byId("status-title");
  const description = byId("status-description");
  const identity = byId("identity");
  const logout = byId("logout-button");
  const retry = byId("retry-button");
  let generation = 0;
  let active;
  let mutating = false;
  let recheckAfterMutation = false;
  // Cookie sessions are shared across tabs; some browsers keep background pages visible.
  // Broadcast only an invalidation signal, never an identity, credential or token.
  const channel = typeof window.BroadcastChannel === "function"
    ? new window.BroadcastChannel("monitor-session:" + new URL("../api/monitor/v1/", window.location.href).pathname)
    : null;

  function begin(isMutation = false) {
    active?.abort();
    active = new AbortController();
    mutating = isMutation;
    const version = ++generation;
    const signal = active.signal;
    return { signal, valid: () => version === generation && !signal.aborted };
  }

  function display(title, text, kind = "neutral") {
    heading.textContent = title;
    description.textContent = text;
    panel.dataset.kind = kind;
    panel.hidden = false;
    identity.hidden = true;
    byId("identity-name").textContent = "";
    retry.hidden = true;
    logout.hidden = true;
    if (form) form.hidden = true;
  }

  function busy(value) {
    byId("shell").setAttribute("aria-busy", String(value));
    if (form) {
      for (const input of form.elements) input.disabled = value;
    }
    logout.disabled = value;
    retry.disabled = value;
  }

  function finishMutation(task) {
    if (!task.valid()) return;
    mutating = false;
    busy(false);
    if (recheckAfterMutation) { recheckAfterMutation = false; check(); }
  }

  function showLogin() {
    display("登录监控", "使用员工账号登录，查看已获授权的监控状态。");
    form.hidden = false;
    if (new URL(window.location.href).searchParams.get("reason") === "expired") {
      description.textContent = "登录已失效，请重新登录。";
    }
  }

  function showError(error, operation) {
    const code = error.code || "REQUEST_FAILED";
    if (code === "SESSION_EXPIRED") {
      if (loginPage) showLogin();
      else window.location.replace("./login.html?reason=expired");
      return;
    }
    const titles = {
      ACCESS_DENIED: "无查看权限", IDENTITY_UNAVAILABLE: "认证服务暂不可用",
      MONITOR_DISABLED: "监控功能未启用", INVALID_CREDENTIALS: "登录未成功",
      CSRF_INVALID: "请求凭证已失效", NETWORK_ERROR: "暂时无法连接", REQUEST_FAILED: "请求未完成"
    };
    const safeError = new window.MonitorSession.SessionError(code);
    display(titles[code] || titles.REQUEST_FAILED, safeError.message, "error");
    retry.hidden = code === "MONITOR_DISABLED" || code === "INVALID_CREDENTIALS";
    // Denied users and existing sessions during identity outages must still be able to sign out.
    logout.hidden = code === "MONITOR_DISABLED" || code === "INVALID_CREDENTIALS";
    if (form && code !== "ACCESS_DENIED" && code !== "MONITOR_DISABLED" && operation !== "logout") {
      form.hidden = false;
    }
  }

  async function check() {
    if (mutating) return;
    const task = begin();
    display("正在确认身份", "正在检查登录状态与监控查看权限。");
    busy(true);
    try {
      const current = await client.current(task.signal);
      if (!task.valid()) return;
      if (loginPage) {
        window.location.replace("./index.html");
        return;
      }
      display("查看权限已确认", "当前账号已获授权，可以访问监控。", "success");
      byId("identity-name").textContent = current.username;
      identity.hidden = false;
      logout.hidden = false;
    } catch (error) {
      if (task.valid()) showError(error, "check");
    } finally {
      if (task.valid()) busy(false);
    }
  }

  form?.addEventListener("submit", async event => {
    event.preventDefault();
    if (mutating) return;
    const task = begin(true);
    const username = form.elements.username.value;
    const password = form.elements.password.value;
    busy(true);
    heading.textContent = "正在登录";
    description.textContent = "正在验证账号与查看权限。";
    panel.dataset.kind = "neutral";
    try {
      await client.login(username, password, task.signal);
      if (task.valid() && !recheckAfterMutation) window.location.replace("./index.html");
    } catch (error) {
      if (task.valid() && !recheckAfterMutation) showError(error, "login");
    } finally {
      form.elements.password.value = "";
      finishMutation(task);
    }
  });

  logout.addEventListener("click", async () => {
    if (mutating) return;
    const task = begin(true);
    display("正在退出", "正在清除监控登录状态。");
    busy(true);
    try {
      await client.logout(task.signal);
      if (task.valid()) {
        channel?.postMessage("signed-out");
        window.location.replace("./login.html");
      }
    } catch (error) {
      if (task.valid()) showError(error, "logout");
    } finally {
      finishMutation(task);
    }
  });

  retry.addEventListener("click", check);
  channel?.addEventListener("message", event => {
    if (event.data !== "signed-out") return;
    display("正在确认身份", "其他页面已退出登录，正在重新确认身份。");
    if (mutating) recheckAfterMutation = true;
    else check();
  });
  window.addEventListener("pageshow", event => { if (event.persisted) check(); });
  window.addEventListener("focus", () => {
    if (document.visibilityState === "visible") check();
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") check();
  });
  window.addEventListener("pagehide", () => {
    active?.abort();
    mutating = false;
    recheckAfterMutation = false;
    if (form) form.elements.password.value = "";
    display("正在确认身份", "返回页面后将重新确认登录状态。");
  });
  check();
})();
