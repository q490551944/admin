# 聊天室剩余卡片交付记录（#8、#11–#15）

本轮基于文字消息和私聊已有实现，接入图片链路、完整图片历史和浏览器恢复，并加入可重复运行的验收工具。六张卡片的代码和本地验收已完成；远端提交、CI run 与卡片关闭状态单独列在文末。

| Issue | 实现与验证入口 |
|---|---|
| #8 | 历史分页先限制消息行数再连接员工及附件，响应包含受控图片 URL、格式、尺寸、像素；`ChatMessagingIntegrationTest`、MySQL 契约与性能测试 |
| #11 | `ChatAttachmentService`、`MinioChatObjectStorage`、`ChatImageValidator`、图片 STOMP 请求、清理任务；图片集成测试在 H2 和独立 MySQL 复用 |
| #12 | 登录、会话筛选与切换、空状态、房间创建/解散、唯一私聊；三个独立浏览器身份验收 |
| #13 | 最近 30 条、向上滚动继续加载并保留位置、上传中/失败重试、受控原图和缩略图、刷新恢复 |
| #14 | 稳定请求 ID、ACK/广播/历史合并、有限指数退避；离线事件立即关闭连接，恢复事件重连；30 秒无入站心跳触发恢复 |
| #15 | 附件与越权审计、Micrometer 指标、100 连接性能门槛、真实 MinIO 三用户 E2E、GitHub Actions 工作流 |

## 图片协议

1. `POST /api/chat/v1/conversations/{id}/attachments`，JSON `{fileName, contentType, sizeBytes}`，返回 201 和 `UPLOADING` 元数据。
2. `PUT /api/chat/v1/attachments/{id}/upload`，请求体为原始文件字节，成功返回 `READY` 元数据。两步都使用同源 Session 和当前 CSRF Header；客户端不直接接触对象存储凭据或对象路径。
3. 向 `/app/chat.messages.send` 发送 `{conversationId, clientRequestId, type:"IMAGE", attachmentId}`。图片不携带文字正文；编辑框中的文字保留供单独发送。
4. 服务端在同一消息事务中锁定会话和附件，验证上传者、会话、有效期和 READY 状态，写入消息并关联 ATTACHED；提交后广播与 ACK。相同请求重试返回原消息，不再次使用附件或重复广播。

原图和缩略图分别使用 `/api/chat/v1/attachments/{id}/content`、`/thumbnail`。下载重新检查账号与会话访问权，响应禁止缓存并设置 `nosniff`。未关联的 READY 图片仅上传者可预览；已关联私聊图片仅双方可见。页面和历史响应不会暴露桶名或对象 key。

文件实际内容必须可解码为 JPEG、PNG、GIF 或 WebP；允许实际格式与客户端声明 MIME 不同，不依赖扩展名。上传成功后以识别出的 MIME 更新附件元数据，原图存储、消息、历史和下载响应均使用实际格式。上限为 10 MiB、4000 万像素，先读尺寸后分配像素内存；GIF 同时校验帧与逻辑画布尺寸。缩略图使用最长边 480 像素的 PNG；动图保留原文件，缩略图取首帧。WebP 使用 TwelveMonkeys ImageIO，MinIO 使用官方 Java SDK。

## 一致性与清理

申请附件时记录服务器生成的随机对象 key；原图和缩略图均在 READY 提交前写入私有桶。请求体读取与解码在事务外完成，存储阶段只锁附件行，避免阻塞同房间文字发送和解散；写完后重新验证会话权限和有效期。存储、校验或数据库失败不会生成聊天消息，失败记录可被清理。图片上传限制为每实例最多两项并行，以限制图像解码内存。

消息和会话投影显式使用 ISO 时间字符串，避免 HTTP 或 STOMP 默认编码把时间转成数组。每条消息显示发送者与服务端时间。上传期间切换会话仍通过已确认的私人 ACK 队列完成原会话的发送，保留原请求 ID。

默认每分钟清理最多 100 条到期、未关联的 UPLOADING/READY/FAILED 记录，默认未关联保留期 24 小时。清理与发送共用附件行锁，关联消息或 ATTACHED 状态不会被清理。先删除两个对象，再删除数据库记录；存储删除失败保留记录供下轮重试。应用退出或数据库回滚时残余对象仍能通过预先登记的 key 找回。

## 运行配置

预先创建私有 MinIO 桶，通过 `CHAT_ATTACHMENT_ENDPOINT`、`CHAT_ATTACHMENT_BUCKET`、`CHAT_ATTACHMENT_ACCESS_KEY` 和 `CHAT_ATTACHMENT_SECRET_KEY` 配置存储。默认值连接本地 MinIO，并使用开发账号 `minioadmin`；部署时配置指定桶的对象读、写、删权限账号。显式空凭据允许文字功能启动，但图片存储操作返回 503。应用不创建桶、不授予公开读取权限。`CHAT_ATTACHMENT_SECURE=true` 会将 HTTP 或无协议的 endpoint 升级为 HTTPS；显式 HTTPS 地址始终保留。

`CHAT_ATTACHMENT_UNATTACHED_RETENTION` 默认 `24h`；`chat.attachment.cleanup-interval-ms` 默认 `60000`。存储 SDK 设置连接、读写及整次调用超时。

Actuator 运维端口默认 `127.0.0.1:9091`（可通过 `CHAT_METRICS_PORT` 改端口），只暴露 health 和 metrics。使用 `/actuator/metrics` 获取指标名；`/actuator/metrics/chat.history.duration` 等读取指标。不要把运维端口通过公共反向代理发布。

指标包括：`chat.message.persist.success/failed`、`chat.message.persist.duration`、`chat.message.broadcast.failed`、`chat.message.private.failed`、`chat.message.rejected`、`chat.message.push.latency`、`chat.history.duration`、`chat.websocket.connections`、`chat.attachment.upload.success/failed`、`chat.attachment.cleanup.failed`。指标不以用户或会话 ID 为标签。持久化成功只在事务提交后计数，广播失败不改变已持久化的消息。

推送埋点衡量服务入口到提交后交给 broker 的耗时；性能测试另外衡量真实在线接收方收到消息的端到端耗时。两者不能混为送达保证。

## 本地与 CI 验证

基础测试：

```sh
bash mvnw '-Dtest=Chat*Test' test
npm ci
npm test
```

专用 MySQL 验证（服务器必须为隔离测试实例，测试自行创建并删除随机 `chat_test_*` 数据库）：

```sh
bash mvnw '-Dtest=Chat*Test' -Dchat.mysql.test.url=jdbc:mysql://127.0.0.1:13316/ -Dchat.performance=true test
```

可用 `chat.mysql.test.user/password` 指定测试凭据。性能报告写入 `target/chat-performance-mysql.json` 和 H2 对照报告。负载为 100 个独立认证 WebSocket、10000 条历史数据、3 轮每轮 100 个并发首屏请求、20 条消息分别送达全部 100 个接收方。历史 P95 必须小于 500 ms，端到端投递 P95 小于 1 秒。测试检查实际 MyBatis SQL 的 EXPLAIN 已选中历史索引，不能仅以 `possible_keys` 判定。

浏览器验收：

```sh
bash mvnw -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/chat-e2e-classpath.txt -DincludeScope=test
npx playwright install chromium
MINIO_BIN=/absolute/path/to/minio npm run test:e2e
```

Windows 可设置 `JAVA_HOME`、`MINIO_BIN`、`CHAT_E2E_BROWSER=chrome` 后运行同一 npm 命令。测试脚本创建全新对象目录、随机回环端口和一次性测试凭据；启动只存在于测试源码中的 `ChatE2eApplication`，不访问业务 MySQL。结束时停止自己启动的进程并删除专属对象数据，保留日志、截图和失败 trace。应先完成 Maven 编译，再运行浏览器测试，避免编译器更新正在运行的类文件。

`.github/workflows/chat.yml` 配置在 PR、master 推送或手工触发时执行数据库、WebSocket、前端、性能与真实 MinIO 浏览器测试，并保存报告。远端工作流是否成功必须以 GitHub 实际 run 为准。

## 图片功能初版验收记录

2026-09-09，在仅包含 Git 暂存内容的干净副本中完成验证，未包含工作区原有未跟踪源码：

| 验证 | 结果 |
|---|---|
| Java 17 / Maven 聊天全量测试 | 97 项通过，0 失败、0 错误、0 跳过，包含真实 MySQL、H2、WebSocket 与性能测试 |
| Node.js 前端测试 | 34 项通过 |
| Chrome / Playwright / 真实 MinIO | 三个独立用户完整场景通过，执行 18.4 秒；含真实重复 SEND、断网恢复、历史翻页、上传失败重试、上传期间切换会话、私聊原图/缩略图隔离和房间解散 |
| MySQL 性能 | 100 连接、10000 条历史、300 次并发首屏请求、2000 次实际送达；历史 P95 106.25 ms，消息送达 P95 83.65 ms，实际查询 EXPLAIN 选中历史索引 |
| H2 对照性能 | 相同负载，历史 P95 40.94 ms，消息送达 P95 10.41 ms |
| 自动代码审查 | 修复并验证指标测试编译、E2E 文件遗漏、上传占用会话锁、GIF 画布限制、切换会话影响待发图片等问题；复审无剩余可操作问题 |

额外回归证明：存储写入被阻塞时，同房间仍可发送文字和解散；未 READY 的图片发送立即失败，不占住会话等待上传；房间在上传期间解散后，附件不会进入 READY，残留对象可以清理。消息/会话的 HTTP 与 STOMP 时间使用 ISO 字符串，每条消息保留发送者和服务端时间。

本地日志为 `target/chat-clean-validation.log`、`target/chat-clean-npm.log`、`target/chat-clean-e2e.log` 和 `target/chat-code-review-2.log`；性能报告与最后截图保存在 `target/chat-performance-*.json`、`target/chat-e2e-final.png`。性能数字反映本次隔离实例与本机环境，CI 会按同一门槛重新测量。

以上数据为图片功能初版在隔离副本中的本地验收记录；最终交付版本由 PR 上的 GitHub Actions 重新执行相同门槛。

后续 MIME 兼容修正：移除实际格式与声明类型必须一致的限制，并保存识别出的真实 MIME。15 项附件/校验测试通过，覆盖四种支持格式的声明不一致、发送、幂等、历史和下载；真实 MinIO 浏览器回归也通过，实际 WebP 以 PNG 文件名和 MIME 上传后可正常发送、刷新及查看原图。对应日志为 `target/chat-mime-tests.log`、`target/chat-mime-e2e.log`；增量代码审查无可操作问题。

## 窗口 Session 与交付前审查

窗口独立登录、一次性 WebSocket 票据及兼容 Cookie 客户端的协议见 [窗口 Session 说明](chat-window-sessions.md)。私聊用户收件队列可发现尚未打开的会话，并向双方所有在线窗口投递；收件队列订阅确认后同步会话列表，覆盖首次连接和重连期间的新私聊。

2026-09-09 的交付前审查修复了 TLS 开关遗漏、共享图片栏鉴权和图片内存释放。缩略图缓存最多 32 项，原图关闭即释放；懒加载图片移除、替换、迟到响应和取消后重试都有回归。复审无剩余可操作发现。

本轮聊天范围的 Java 回归执行通过 75 项，33 项专用 MySQL／性能测试在该轮本地运行中未启用；前端 46 项测试通过，Chrome／Playwright／真实 MinIO 的 3 个场景通过。新增 Chromium 专项验证连续切换 40 次后临时 URL 为 0、缓存保持 32 项，并能重新显示图片。CI 会对最终提交额外执行 MySQL 与 100 连接性能门槛。

本轮本地日志为 `target/review-it-final-java.log`、`target/review-it-storage.log`、`target/review-it-js.log`、`target/review-it-final-e2e.log` 和 `target/review-it-closeout.log`。Java 总日志还包含未随聊天功能提交的其他模块测试，上述 75 项仅统计聊天及存储回归。
