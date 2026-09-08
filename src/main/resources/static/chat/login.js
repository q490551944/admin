/** 登录页：先建立带 CSRF 凭证的 Session，再提交 Spring Security 所需的表单数据。 */
const form = document.getElementById("loginForm");
const button = document.getElementById("loginButton");
const errorMessage = document.getElementById("loginError");
const base = (window.CHAT_CONFIG?.apiBaseUrl || "/api/chat/v1").replace(/\/$/, "");

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  button.disabled = true;
  errorMessage.textContent = "";
  try {
    // 登录本身也是受 CSRF 保护的写请求；两次请求都携带 Cookie，关联同一个 Session。
    const csrfResponse = await fetch(base + "/csrf-token", { credentials: "include", cache: "no-store" });
    if (!csrfResponse.ok) throw new Error("聊天服务暂不可用，请确认已启用 CHAT_ENABLED");
    const csrf = await csrfResponse.json();
    const payload = new URLSearchParams(new FormData(form));
    const response = await fetch(base + "/sessions", {
      method: "POST", credentials: "include", cache: "no-store",
      headers: { [csrf.headerName]: csrf.token },
      body: payload
    });
    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.message || "登录失败，请重新尝试");
    }
    form.reset();
    // 成功后固定进入同源实时页面，不接受 URL 中任意指定的跳转目标。
    window.location.replace("./index.html?mode=live");
  } catch (error) {
    errorMessage.textContent = error.message || "网络异常，请稍后重试";
  } finally {
    button.disabled = false;
  }
});
