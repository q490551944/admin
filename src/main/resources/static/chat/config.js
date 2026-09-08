/**
 * Chat frontend runtime configuration.
 *
 * mode:
 * - "auto": try the backend; only a missing endpoint (404) permits demo fallback.
 *   Authentication failures never fall back to demo. Use ?mode=demo for an explicit preview.
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
  // Demo only. Live mode always obtains the current identity from /sessions/current.
  currentUser: {
    id: 1001,
    name: "林澈",
    username: "linche",
    avatar: "林"
  }
});
