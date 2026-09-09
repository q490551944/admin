# RESTful 接口迁移说明

本次统一已实现 HTTP 接口的资源路径、HTTP 方法和响应状态，并同步聊天前端、安全过滤器及测试。
保留 `/sys`、`/groovy`、`/kafka` 和 `/api/chat/v1` 模块前缀，现有安全配置继续覆盖这些路径。
旧动作路径和旧更新方法已移除，外部调用方需要按下表迁移。

## 路由对照

| 功能 | 原接口 | 当前接口 | 成功响应 |
| --- | --- | --- | --- |
| 用户列表 | `GET /sys/users?current=1&size=20` | 路径不变，分页参数默认 1、20 | 200，用户数组 |
| 单个用户 | `GET /sys/users/one?id=42` | `GET /sys/users/42` | 200，用户对象 |
| 创建用户 | `POST /sys/users` | 路径不变 | 201，用户对象，`Location` 指向新用户 |
| 部分更新用户 | `PUT /sys/users`，请求体携带 id | `PATCH /sys/users/42` | 204，无响应体 |
| 单个删除 | `DELETE /sys/users/42`，原实现只打印 ID | 路径不变，实际删除指定用户 | 204，无响应体 |
| 批量删除 | `DELETE /sys/users?ids=1,2`，原实现将整个字符串当单个 ID | 路径不变，按多个 Long ID 删除 | 204，无响应体 |
| 执行 Groovy | `POST /groovy/single/script/execute` | `POST /groovy/script-executions` | 200，脚本执行结果 |
| 启动 Kafka 消费 | `GET /kafka?topic=events` | `POST /kafka/consumers`，JSON `{"topic":"events"}` | 202，无响应体 |
| 获取 CSRF 凭证 | `GET /api/chat/v1/session/csrf` | `GET /api/chat/v1/csrf-token` | 200，凭证对象 |
| 登录 | `POST /api/chat/v1/session/login` | `POST /api/chat/v1/sessions` | 201，身份对象，`Location: /api/chat/v1/sessions/current` |
| 当前登录身份 | `GET /api/chat/v1/session/me` | `GET /api/chat/v1/sessions/current` | 200，身份对象 |
| 退出登录 | `POST /api/chat/v1/session/logout` | `DELETE /api/chat/v1/sessions/current` | 204，无响应体 |
| 可见会话列表 | `GET /api/chat/v1/conversations` | 不变 | 200，数组 |
| 创建聊天室 | `POST /api/chat/v1/rooms` | 不变 | 201，会话对象 |
| 解散聊天室 | `DELETE /api/chat/v1/rooms/{id}` | 不变 | 204，无响应体 |

WebSocket 握手和 STOMP 目的地不是 HTTP 资源操作，本次保持现有协议。
MongoController 尚无 HTTP 接口。前端中尚未实现的员工搜索、消息历史等预留接口不在本次新增范围内。

## 用户请求约定

用户列表继续仅返回启用用户的数组；`current`、`size` 必须为正整数。
创建用户仍使用原用户字段及用户名、密码校验规则，ID 由服务端生成，请求体不得指定 ID。

更新采用 PATCH，因为现有 MyBatis-Plus 更新逻辑仅修改提供的非空字段：

```http
PATCH /sys/users/42
Content-Type: application/json

{"status":false}
```

可更新 `username`、`password`、`status`、`sex`。省略或值为 null 的字段保持原值；不支持以 null 清空字段。
请求必须包含至少一个非空更新字段。请求体可以省略 id；若携带，必须与路径一致。
单个用户的查询、更新、删除在资源不存在时返回 404。

批量删除支持 `?ids=1,2,3` 或 `?ids=1&ids=2&ids=3`，参数不能为空，所有 ID 必须为正整数。
任意 ID 非法时整个请求返回 400，不执行删除。批量删除忽略已不存在的 ID，重复请求成功返回 204。

## 会话及执行接口

登录继续使用 `application/x-www-form-urlencoded` 的 `username/password`；Cookie、Session ID 轮换、
CSRF、账号启用状态和 WebSocket 鉴权规则保持有效。客户端先获取 CSRF Token，再 POST 创建会话，
登录后重新获取 Token。只有携带有效 CSRF Token 的 DELETE 请求才能退出；GET 当前会话不会退出登录。
前端统一处理 204 空响应，不尝试解析 JSON。

脚本执行请求仍为 `{"expression":"a + b","paramMap":{"a":2,"b":3}}`，expression 不能为空。
执行同步完成且不持久化执行资源，因此返回 200 和执行结果。
Kafka 202 仅表示消费启动请求已接受；消费仍在后台执行，不代表消息已经消费成功，暂不提供任务状态查询。

## 错误状态

后台接口继续使用 `{status, data, timestamp}` 错误结构，但 HTTP 状态现在与错误一致：

| HTTP 状态 | 场景 |
| --- | --- |
| 400 | 参数缺失、JSON/类型/校验错误、ID 冲突、非法分页参数 |
| 404 | 用户或路由不存在 |
| 405 | HTTP 方法不受支持，保留 `Allow` 响应头 |
| 409 | 数据完整性冲突 |
| 415 | 不支持的请求 Content-Type |
| 500 | 服务内部错误，不向客户端返回内部异常详情 |

聊天接口继续使用 `{code, message}`：401 未登录或失效，403 越权或 CSRF 无效，404 资源不存在，
409 业务冲突，422 输入校验失败；HTTP 方法错误保留 405。调用方应优先检查 HTTP 状态。

## 验证命令

使用 Java 17 和已配置依赖的 Maven 环境：

```sh
mvn -Dtest=UserRestControllerTest,ExecutionRestControllerTest,Chat*Test test
node --test src/test/js/chat-security.test.cjs
node --check src/main/resources/static/chat/app.js
node --check src/main/resources/static/chat/login.js
```

用户和执行接口用 MockMvc 隔离验证，Kafka 测试不启动真实消费者；聊天回归使用独立 H2 和随机端口。
`ChatMySqlMigrationTest` 仍为显式启用的独立 MySQL 迁移测试，默认跳过。

2026-09-08 验证结果：后端 39 项通过、1 项 MySQL 测试按配置跳过，前端 9 项通过；
JavaScript 语法检查及 `git diff --check` 通过。后端使用本机 Java 17 和离线 Maven 3.6.3，
未连接业务数据库或 Kafka；真实 Kafka 消费不在本次验证范围内。
