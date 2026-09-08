/**
 * 聊天前端运行配置；URL 中有效的 mode 参数可覆盖这里的模式。
 *
 * mode：
 * - "auto"：先访问后端，仅接口返回 404 时允许回退演示；认证失败必须重新登录。
 * - "live"：使用真实后端，连接失败时显示错误。
 * - "demo"：只使用本地演示数据，可通过 ?mode=demo 显式预览。
 */
window.CHAT_CONFIG = Object.freeze({
  mode: "auto",
  apiBaseUrl: "/api/chat/v1",
  webSocketEndpoint: "/ws/chat",
  requestTimeoutMs: 5000,
  historyPageSize: 30,
  reconnectAttempts: 5,
  // 演示身份；实时模式始终从 /sessions/current 获取当前用户。
  currentUser: {
    id: 1001,
    name: "林澈",
    username: "linche",
    avatar: "林"
  }
});
