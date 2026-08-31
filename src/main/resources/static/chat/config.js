/**
 * Chat frontend runtime configuration.
 *
 * mode:
 * - "auto": try the backend first and fall back to demo data when chat is disabled.
 * - "live": require the backend and surface connection errors.
 * - "demo": use local demo data only.
 */
window.CHAT_CONFIG = Object.freeze({
  mode: "auto",
  apiBaseUrl: "/api/chat/v1",
  webSocketEndpoint: "/ws/chat",
  requestTimeoutMs: 5000,
  historyPageSize: 30,
  reconnectAttempts: 5,
  currentUser: {
    id: 1001,
    name: "林澈",
    username: "linche",
    avatar: "林"
  }
});
