# 按窗口隔离登录 Session

同一浏览器的普通窗口现在可以分别登录不同账号。刷新页面保留当前账号，退出只销毁该窗口使用的 Session；其他窗口的账号与聊天连接继续有效。

页面在 `sessionStorage["chat.sessionId"]` 保存服务端生成的 Session ID。所有聊天 HTTP 请求使用 `X-Chat-Session`，并通过 `credentials: "omit"` 忽略共享 Cookie。登录页每次提交前建立新 Session，因此即使浏览器复制窗口时拷贝了 sessionStorage，再登录其他账号也不会轮换原窗口的 Session。

认证仍使用 Spring Security、Session ID 轮换和 Session CSRF Token。`GET /api/chat/v1/csrf-token` 携带 `X-Chat-Session: new` 创建匿名 Session；响应 Header 返回 ID。随后使用这个 ID 与 CSRF Token 调用登录接口，保存成功响应中轮换后的 ID。退出或凭证失效只清理当前窗口的 sessionStorage。没有 Header 的旧 API 客户端继续使用原来的 Cookie Session；显式携带失效 Header 时不会回退到 Cookie。

浏览器 WebSocket 不支持自定义握手 Header。页面先用自身 Session 与 CSRF 调用 `POST /api/chat/v1/websocket-tickets`，取得有效期 30 秒、只能消费一次的票据，再连接 `/ws/chat?ticket=...`。长期 Session ID 不进入 URL。CONNECT 仍需 CSRF；连接收发及定时巡检直接复核 Session 仓库，确保退出、ID 轮换、超时和账号停用生效。

消息区和共享图片栏都通过带 Session Header 的 HTTP 请求读取图片，再转为页面内 Blob URL。附件接口继续执行原有成员权限检查。缩略图 Blob 使用最多 32 项缓存，各显示元素在加载完成、失败、替换或移出页面时释放自身 URL；迟到的响应不会恢复已取消的加载。原图不进入缓存，查看窗口关闭时释放 URL。退出时清空缓存并撤销页面持有的 URL。

窗口 Session 存于当前进程内存，沿用 `server.servlet.session.timeout`（未配置时 30 分钟），每 5 秒清理过期记录。服务重启需重新登录。当前聊天代理本来就面向单实例；若部署多实例，需要同时配置共享 Session 仓库与消息代理。窗口复制后、重新登录之前，可能继承原窗口的 Session，这是浏览器复制 sessionStorage 的行为。

验证包含：同一 Cookie 容器下的双账号 HTTP/WebSocket、跨 Session CSRF 拒绝、退出互不影响、握手票据重放拒绝、并发旧请求不能恢复已注销/轮换的 ID、超时，以及真实 Chrome 中的双账号文字/图片、刷新和退出。
