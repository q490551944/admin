# 文字消息、实时确认与历史查询

实现范围：文字/Unicode 表情消息写入、会话广播、私人 ACK/业务错误、历史游标分页，以及现有聊天页面的真实接入。对应 Issue #9、#10、#8 的核心链路。员工搜索与私聊创建见 [Issue #7 实现说明](chat-direct-messaging.md)；后续图片上传、恢复、指标与完整验收见 [剩余卡片交付记录](chat-completion.md)。

## 使用

启用 `CHAT_ENABLED=true` 并完成 Flyway 迁移后，通过 `/chat/login.html` 登录已有有效员工账号，进入 `/chat/index.html?mode=live`。创建或打开公共房间即可发送文字和表情。Enter 发送，Shift+Enter 换行；刷新读取历史，向上加载更早消息。演示模式继续使用浏览器内的模拟数据。

## 发送协议

WebSocket：`/ws/chat`，沿用 HTTP Session、Origin 校验以及 CONNECT 的 CSRF Header。浏览器页面现按窗口隔离 Session，HTTP 使用 Session Header，WebSocket 使用一次性握手票据，详见[窗口会话说明](chat-window-sessions.md)。

向 `/app/chat.messages.send` 发送：

```json
{
  "conversationId": "2097233657939705858",
  "clientRequestId": "a85d051c-3a68-4025-b598-11f3f1d228f9",
  "type": "TEXT",
  "body": "你好 😀"
}
```

- 会话 ID 为正整数，建议传字符串。所有响应 Long ID 都是字符串。
- `clientRequestId` 是 1–64 位 ASCII 字母、数字、`_` 或 `-`，区分大小写；推荐 UUID。
- 正文去除首尾空白后非空，最多 5,000 个 Unicode 代码点。孤立代理字符与 NUL 被拒绝。
- 发送者取自服务器认证身份。客户端传入 `senderId` 等身份字段会被安全拦截器拒绝。
- 重试必须复用原请求 ID 与正文。同一用户重复相同请求返回原消息；复用 ID 改变正文或会话返回 `MESSAGE_REQUEST_CONFLICT`（409）。不同用户可使用同一请求 ID。

## 实时事件

订阅 `/topic/chat/conversations/{id}` 接收 `MESSAGE_CREATED`：

```json
{
  "eventType": "MESSAGE_CREATED",
  "message": {
    "id": "2097233657939705859",
    "conversationId": "2097233657939705858",
    "senderId": "1",
    "senderName": "alice",
    "type": "TEXT",
    "clientRequestId": "a85d051c-3a68-4025-b598-11f3f1d228f9",
    "body": "你好 😀",
    "attachmentId": null,
    "createdAt": "2026-09-08T16:00:00.123456",
    "status": "SENT",
    "cursor": "不透明游标"
  }
}
```

`/user/queue/chat.acks` 收到 `MESSAGE_ACK`，包含 `clientRequestId` 和同样的完整 `message`。ACK 只发给提交请求的 WebSocket 会话。相同请求重试只重发 ACK，不重复广播、修改摘要或写审计。

`/user/queue/chat.errors` 收到 `MESSAGE_REJECTED`，包含 `status/code/message/target/clientRequestId`。例如空正文为 422 `EMPTY_MESSAGE`，超长为 413 `MESSAGE_TOO_LONG`。业务错误允许在同一连接继续发送。认证、伪造身份、越权订阅等协议安全错误仍通过 STOMP ERROR/连接关闭处理。

`/user/queue/chat.messages` 接收私聊的 `MESSAGE_CREATED`，除完整 `message` 外，还包含相对于接收用户的 `conversation`（与会话列表接口结构一致）。消息提交后通知双方所有在线窗口，接收方无需提前打开或订阅新私聊即可显示会话、摘要和未读数。主题与私人队列的重复投递按消息 ID 或发送者/请求 ID 合并；本人发送的消息不增加未读数。已删除的参与关系、停用账号及其他用户不接收这条用户队列通知，重试仍只重发 ACK。

客户端发送 SUBSCRIBE 时可携带 `receipt`。服务端在 broker 真正完成订阅后回复 STOMP RECEIPT，包括已解析的私人队列。前端等待消息主题及私人消息/ACK/错误队列确认后才启用发送。同一连接按入站顺序处理退订、订阅与 SEND。用户消息队列确认后重新读取会话列表，补齐首次加载到订阅完成之间及断线期间出现的新会话。

## 历史接口

`GET /api/chat/v1/conversations/{id}/messages`

| 参数 | 语义 |
| --- | --- |
| 不传 before/after | 最近一页，默认 30 条 |
| before | 严格早于游标的一页 |
| after | 严格晚于游标的一页，用于断线补拉 |
| limit | 1–100，默认 30 |

`before` 和 `after` 不能同时传。返回 `{items, beforeCursor, afterCursor, hasMore}`，`items` 始终按 `createdAt + id` 从旧到新排列。`hasMore` 表示当前查询方向仍有下一页。空会话也返回补拉起点游标。

游标绑定会话并保留数据库微秒精度，客户端按原值传回，不解析或重新构造。服务端每次查询都校验权限，游标本身不提供访问权。已解散会话不可读取活动历史接口，数据库中的消息保留。

前端只以已完成历史查询的 `afterCursor` 推进补拉；实时广播不会跳过尚未补齐的位置。历史、广播、ACK 按消息 ID 及发送者/请求 ID 合并。切换会话期间的迟到响应只更新原会话缓存。未收到确认的发送在 10 秒后显示失败，允许用原 ID 重试。

## 事务和迁移

- 消息、最近消息摘要与 `MESSAGE_CREATED` 审计在同一事务中提交。
- 消息写入与房间解散使用同一个会话行锁。会话内发送时间以微秒递增，避免时钟回拨导致补拉遗漏。
- 广播和 ACK 都在事务提交后执行；事务回滚不推送。广播失败写日志并继续尝试 ACK，已提交消息仍能从历史读取。
- 使用现有 `(conversation_id, created_at, id)` 历史索引和 `(sender_id, client_request_id)` 唯一约束。
- 新迁移 `V2026090801` 将 MySQL 请求 ID 列改为 `utf8mb4_bin`，避免默认大小写不敏感排序规则合并不同请求；保留已有数据和历史迁移。MySQL DDL 不能事务回滚，应沿用现有的迁移备份/演练流程。
- 单实例简单 broker 的推送仍非持久队列；服务故障时通过幂等重试和历史补拉恢复。多实例分发不在本次范围。

## 验证

```sh
mvn -Dtest=Chat*Test test
node --test src/test/js/chat-security.test.cjs
node --check src/main/resources/static/chat/app.js
```

默认后端测试使用独立 H2 和随机 HTTP 端口。显式传入独立临时 MySQL 的服务器 URL，可启用迁移和消息契约测试：

```sh
mvn -Dtest=Chat*Test -Dchat.mysql.test.url=jdbc:mysql://127.0.0.1:13316/ test
```

MySQL 测试默认 root/空密码，仅适用于自行创建的临时测试实例，可通过 `chat.mysql.test.user/password` 覆盖。测试只创建和清理自己随机生成的 `chat_test_*` 数据库。

覆盖内容：文字/表情保存与权限、重复发送及跨会话并发竞争、摘要与审计回滚、时间相同的游标分页、空会话补拉、真实 HTTP Cookie + STOMP 广播/ACK、私人队列实际订阅确认、业务拒绝后继续发送、ACK 会话隔离，以及前端迟到响应/微秒排序/补拉去重。MySQL 另验证 5,000 个补充平面表情字符的完整保存。

2026-09-09 新建私聊实时收信修复验证：38 项后端定向回归（独立 H2）、41 项前端测试及 2 项 Edge 浏览器端到端测试全部通过。新增覆盖接收方未订阅会话主题时收取首条私聊、同账号多个在线窗口、双方名称视角、非参与者隔离、参与关系删除、重复请求和事务回滚。浏览器用例不再刷新接收方页面，而是在公共房间中直接检查新私聊及未读提示，再打开会话确认消息不重复。

2026-09-08 审查目标为当时未提交的聊天室改动，已在任务内进行代码审查。该轮外部 `codex review` 未执行成功：自动审批拒绝了可能向 Codex 服务传输源码的命令。

2026-09-08 本地验证：Java 17 下后端 50 项测试全部通过（含独立 MySQL 8.0.36，0 跳过），前端实际脚本 18 项测试全部通过，JavaScript 语法和 `git diff --check` 通过。审查修复了请求 ID 排序规则、私人队列过早确认、有序接收的安全错误反馈、浏览器时钟偏差与订阅失败重连计数问题；本地复查未发现剩余可执行问题。未执行完整浏览器 E2E 或 100 并发性能基线，这些仍归 #15 验收。

## 2026-09-09 合入验证

在仅包含聊天室相关变更的独立工作树中重新验证：Java 17 下默认测试运行通过 50 项，另在专用临时 MySQL 8.0.36 上运行通过 20 项（0 失败、0 错误、0 跳过），合计 70 项后端测试；前端 28 项测试、JavaScript 语法及差异格式检查通过。MySQL 使用本任务新建的数据目录和回环端口，不访问现有业务数据库。

本文件记录 #7 员工搜索与唯一私聊、#9 文字消息持久化与幂等、#10 STOMP 广播与 ACK 的原始交付范围。#8 附件历史与性能、#11 图片链路、#12–#14 浏览器交互和恢复、#15 指标与端到端验收的后续交付状态统一记录在 [剩余卡片交付记录](chat-completion.md)。
