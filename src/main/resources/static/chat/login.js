const form = document.getElementById("loginForm");
const button = document.getElementById("loginButton");
const errorMessage = document.getElementById("loginError");
const base = (window.CHAT_CONFIG?.apiBaseUrl || "/api/chat/v1").replace(/\/$/, "");

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  button.disabled = true;
  errorMessage.textContent = "";
  try {
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
    // Fixed same-origin destination; never accept an arbitrary redirect supplied in the URL.
    window.location.replace("./index.html?mode=live");
  } catch (error) {
    errorMessage.textContent = error.message || "网络异常，请稍后重试";
  } finally {
    button.disabled = false;
  }
});
