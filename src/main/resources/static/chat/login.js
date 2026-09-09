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
    // 即使窗口复制过 sessionStorage，主动登录也先建立新 Session，不轮换另一窗口的身份。
    window.ChatSession.clear();
    const csrfResponse = await window.ChatSession.fetch(base + "/csrf-token");
    if (!csrfResponse.ok) throw new Error("聊天服务暂不可用，请确认已启用 CHAT_ENABLED");
    const csrf = await csrfResponse.json();
    const payload = new URLSearchParams(new FormData(form));
    const response = await window.ChatSession.fetch(base + "/sessions", {
      method: "POST",
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
