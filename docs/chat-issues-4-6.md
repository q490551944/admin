# 聊天室 Issue #4、#5、#6 实现说明

> 后续文字消息、实时 ACK 与历史查询现已补充，见 [文字消息链路说明](chat-text-messaging.md)。以下范围和测试记录描述 #4–#6 当次交付。

## 范围

本次提供现有员工账号登录、HTTP Session / STOMP 鉴权、可见会话列表、公共房间创建与解散。
不包含 #7–#15 的员工搜索/私聊创建、消息历史、文字消息落库、完整 ACK、图片服务和整套聊天室 E2E。
已有前端会调用这些后续接口；接口尚未实现时会显示失败提示，不能据此认为已经支持真实聊天。

## 启用与访问

当前 `application.yml` 默认启用聊天室（`CHAT_ENABLED=true`），可设置 `CHAT_ENABLED=false` 关闭。
在受控环境准备好数据库和现有应用依赖后再启动 Spring Boot；默认 HTTP 端口为 `8090`，不是前端预览端口 `4173`。

- 聊天入口：`http://localhost:8090/chat/index.html?mode=live`（`/chat/` 会重定向）。
- 登录入口：`http://localhost:8090/chat/login.html`。
- 纯界面预览：`/chat/index.html?mode=demo`；演示数据不是实际账号、会话或消息。
- 前端通过同源 Session Cookie 访问后端，不需要单独的静态服务器。不要把 4173 的演示页面视为登录服务。
- `auto` 模式只有后端返回 404 时才允许演示回退；401 必须重新登录，403/网络错误不回退演示。

没有加入固定生产账号或默认密码。登录读取原有 `user` 表的 `id/username/password/status`，
只接受启用且用户名唯一的账号；重复用户名拒绝登录，不会任取一个账号。

## 安全边界

- 默认只接受 BCrypt 密码，不接受明文。旧 3DES 密码默认禁用。
- 如确有历史 3DES 账号，可临时设置 `CHAT_ALLOW_LEGACY_DES_PASSWORDS=true`；
  成功登录时使用条件更新将该账号密码升级为 BCrypt。完成迁移后关闭此选项。
  不会自动重置密码、批量修改账号或恢复已停用账号。
- 登录旋转 Session ID；退出销毁 Session。后续 HTTP 请求、STOMP CONNECT/SUBSCRIBE/SEND
  以及推送投递都会核实 Session 和账号状态。空闲连接每 5 秒检查一次。
- 默认 Session 超时 30 分钟。Cookie 设置 HttpOnly、SameSite=Lax；
  HTTPS 部署应设置 `CHAT_SESSION_COOKIE_SECURE=true`。
- 默认 WebSocket 仅同源。`CHAT_ALLOWED_ORIGINS` 可配置逗号分隔的精确 HTTP(S) Origin，不允许 `*`。
  此设置只负责 WebSocket Origin，不会开启 REST 跨域；建议前后端同源部署。
- 所有 HTTP 写操作（包括登录/退出）和 STOMP CONNECT 必须携带当前 Session 的 CSRF Token。
- 身份来自服务端 `ChatPrincipal.userId`。创建请求里的 ownerId 不生效；
  STOMP 消息体伪造 senderId/userId 等身份字段会被拒绝。
- 凭证失效返回 HTTP 401 / STOMP `SESSION_EXPIRED`，或以 WebSocket 1008 关闭连接。
  前端停止定时重连、丢弃并发中的旧响应、清除私有状态并跳转登录。
- 开启聊天时隔离旧实验性 `/test` WebSocket 与 `/aaa`、`/bbb` 目的地。
  `/sys/users/**`、`/groovy/**`、`/kafka/**`、`/test/**` 接口默认保持原访问控制规则。
  已具备 `ROLE_ADMIN` 授权能力的部署可设置 `CHAT_PROTECT_LEGACY_ENDPOINTS=true` 加固这些敏感接口；
  开启前应先确认现有管理员能够取得该角色。默认 `/logout` 被关闭，仅保留专用受 CSRF 保护的退出接口。

## HTTP 契约

| 方法与路径 | 作用 | 成功响应 |
|---|---|---|
| GET `/api/chat/v1/csrf-token` | 获取当前 Session 的 Token | `{headerName, parameterName, token}` |
| POST `/api/chat/v1/sessions` | 表单 username/password 登录 | 201，`{id, name, username}`；Location 指向当前会话 |
| GET `/api/chat/v1/sessions/current` | 当前可信身份 | `{id, name, username}` |
| DELETE `/api/chat/v1/sessions/current` | 销毁会话 | 204 |
| GET `/api/chat/v1/conversations` | 可见活动会话与摘要 | 数组；无数据为 `[]` |
| POST `/api/chat/v1/rooms` | 请求体 `{"name":"讨论组"}` | 201，会话对象 |
| DELETE `/api/chat/v1/rooms/{id}` | 房主逻辑解散 | 204；重复解散幂等 |

CSRF 获取会创建 Cookie；登录后 Token 会更新，客户端应重新获取。
聊天 API 错误使用真实 HTTP 状态及 `{code, message}`。
401 为未登录/失效，403 为越权/无效 CSRF，404 为不存在或已关闭会话，
409 为名称冲突，422 为非法输入。Long ID 一律返回字符串，避免 JavaScript 精度丢失。

会话对象包含 `id/type/name/status/ownerId/lastMessageId/lastMessagePreview/lastActivityAt/createdAt/peerUserId`。
列表只包含活动公共房间和本人有有效参与记录的私聊；不返回其他人的私聊、密码、内部 direct_key。
按最后活跃时间（为空时按创建时间）倒序，再按 ID 倒序，摘要最长 120 字符，图片摘要为 `[图片]`。

## 房间事务与迁移

- 名称去首尾空白后为 2–50 个 Unicode 代码点，按 `Locale.ROOT` 小写归一化。
- 新迁移 `V2026090401` 增加生成列 `active_room_name`，唯一索引只占用活动公共房间名称。
  MySQL 上使用二进制字符排序规则，避免数据库默认忽略重音而扩大同名范围。
- 原历史迁移不修改。升级保留已有房间、原始名称、消息和审计；已解散名称可重新使用。
- 创建与 ROOM_CREATED 审计同事务，数据库唯一约束处理并发冲突。
- 解散先锁定会话并检查 owner，再执行带状态/版本条件的更新与 ROOM_DISSOLVED 审计。
  不依赖全局 MyBatis-Plus 乐观锁插件，重复解散不重复审计或广播。
- 事务提交后才向 `/topic/chat/conversations/{id}` 投递
  `{"eventType":"CONVERSATION_DISSOLVED","conversationId":"..."}`。
  回滚不广播；广播失败不回滚已提交的数据，会记录错误。
- 已解散会话不可新增订阅，也不能继续通过既有连接发送。数据保留用于后续后台访问能力。

MySQL DDL 通常不能事务回滚：部署前应备份并在预发布数据库执行迁移验证，
不要在未经验证的生产库直接反复 repair / 重放失败迁移。

## 验证与复现

2026-09-04 本地验收：后端 26 项测试全部通过（包括 MySQL 8.0.36 的真实迁移测试），
前端 7 项认证测试全部通过，JavaScript 语法检查与差异空白检查通过。
review-it 对本次未提交改动的审查已无可执行问题；审查中发现的登录目录跳转和默认退出入口问题已修复并回归。

2026-09-08 提交前回归：工作区及仅包含已跟踪/暂存文件的独立副本均通过后端测试
（27 项中 26 项通过，需显式连接独立 MySQL 的 1 项跳过），前端认证测试 9 项全部通过。
JavaScript 语法检查通过；本次未连接业务数据库。

常规回归使用独立 H2 库和随机 HTTP 端口，不连接业务 MySQL、Kafka、Redis 或 MinIO：

```sh
mvn -Dtest=Chat*Test test
node --test src/test/js/chat-security.test.cjs
node --check src/main/resources/static/chat/app.js
node --check src/main/resources/static/chat/login.js
```

`ChatMySqlMigrationTest` 默认跳过，可在独立的临时 MySQL 上显式启用：

```sh
mvn -Dtest=ChatMySqlMigrationTest -Dchat.mysql.test.url=jdbc:mysql://127.0.0.1:13316/ test
```

测试账号默认 root/空密码，仅用于自行创建的隔离测试实例；可通过
`chat.mysql.test.user` / `chat.mysql.test.password` 覆盖。
测试创建随机 `chat_test_*` 库并仅清理该库，验证旧版本带历史数据升级、重复迁移、活动名称唯一和解散后复用。
不要将此测试连接到生产实例。

覆盖证据：

- `ChatIntegrationTest`：登录、Cookie Session、CSRF、房主身份、账号停用、
  私聊隔离、摘要/空列表、静态登录入口、名称校验、六线程同名竞争、权限、历史保留、审计及回滚。
- `ChatWebSocketIntegrationTest`：真实 HTTP Cookie + 嵌入式 Tomcat + 原生 STOMP，
  覆盖未登录握手、Origin、CONNECT CSRF、私聊越权、伪造身份、提交/回滚通知、
  解散后订阅/发送、退出后禁止投递、空闲 Session 超时。
- `ChatInboundSecurityTest`：逐帧 Session 校验、伪造 header、通配订阅和 broker 注入拒绝。
- `ChatDisabledTest`：关闭开关后聊天接口不暴露，旧接口和演示资源仍可访问。
- `ChatPasswordTest` / `ChatSchemaMigrationTest`：密码兼容策略、迁移重复执行与数据库约束。
- `chat-security.test.cjs`：实际前端脚本的 CSRF、401 清理、旧响应丢弃、重连取消、
  CONNECT Header 和 Tomcat Session 关闭处理。

外部 Issue 状态与本地实现验收分开管理。
