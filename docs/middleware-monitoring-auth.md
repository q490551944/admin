# 监控认证与访问权限

监控复用 `user` 表中的员工身份，使用独立的登录会话和服务端用户 ID 白名单。`chat.enabled=false` 时仍可登录；`monitor.enabled=false` 时整个 `/api/monitor/**` 命名空间返回 `404 MONITOR_DISABLED`。

```yaml
monitor:
  enabled: true
  allowed-user-ids: [1001, 1002]
```

名单默认为空。只有处于启用状态、用户名仍与登录时一致且 ID 在名单内的员工可查看监控。更改用户名、删除或停用员工会使下一次监控请求返回 401；更改白名单会在下一次授权检查生效。名单只授予监控查看权限，不授予 `ROLE_ADMIN`。现有聊天身份和 `ROLE_CHAT_USER` 不会自动获得监控身份或权限。

## HTTP 契约

接口前缀为 `/api/monitor/v1`，使用同源的 `JSESSIONID` Cookie。前端需携带 Cookie，写请求还需携带当前会话的 CSRF 令牌。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /csrf-token` | 匿名可访问，返回 `headerName`、`parameterName`、`token`。 |
| `POST /sessions` | 提交表单 `username`、`password` 及 CSRF；正确凭据返回 201 和公开身份，`Location` 指向 `/sessions/current`。成功后轮换会话 ID 并清除旧 CSRF 令牌。 |
| `GET /sessions/current` | 校验当前员工与白名单，返回公开身份；无效或过期会话返回 401，非白名单返回 403。 |
| `DELETE /sessions/current` | 携带 CSRF，清除监控身份和监控 CSRF，返回 204。无需查询身份数据库。 |

登录只建立身份。启用员工可完成登录，但不在白名单时读取 `/sessions/current` 和其他监控数据仍返回 403；登录页面须处理此结果。成功登录后重新请求 `/csrf-token`，不要继续使用登录前的令牌。退出后再次读取数据需重新登录。

| HTTP 状态 | 错误代码 | 含义 |
| --- | --- | --- |
| 401 | `INVALID_CREDENTIALS` | 用户名、密码不正确，账号停用或用户名不唯一；响应不区分这些情况。 |
| 401 | `SESSION_EXPIRED` | 没有有效监控身份，或员工已删除、停用、更名。 |
| 403 | `ACCESS_DENIED` | 有效监控身份未获用户 ID 白名单授权。 |
| 403 | `CSRF_INVALID` | 写请求缺少或使用了无效 CSRF 令牌。 |
| 404 | `MONITOR_DISABLED` | 监控功能关闭。 |
| 503 | `IDENTITY_UNAVAILABLE` | 登录、密码升级或每请求身份复查时，员工身份服务暂不可用。 |

身份服务暂时故障不销毁已有有效会话；服务恢复后可重试。响应只包含固定错误代码和提示，不输出 SQL、连接 URI、密码、内部异常或堆栈。新密码及兼容迁移使用现有 `ChatPasswordEncoder` 的 BCrypt 策略；历史 DES 兼容仍由现有 `chat.security.allow-legacy-des-passwords` 控制，此策略不要求开启聊天功能。

## 会话隔离

监控安全链先于聊天链匹配，只匹配 `/api/monitor/**`；兜底链也拒绝该前缀，避免配置缺失时匿名放行。监控使用 `MONITOR_SECURITY_CONTEXT` 和 `MONITOR_CSRF_TOKEN` 两个独立 session 属性。监控退出和失效身份清理只清除监控状态，保留共享 Cookie 会话里的聊天身份。浏览器聊天的 `X-Chat-Session` 不用作监控凭据。

## 员工身份的管理边界

开启监控时，现有 `/sys/users/**` 和 `/groovy/**` 强制要求 `ROLE_ADMIN`，其中写请求还要求 CSRF。员工读取接口目前返回员工实体，因此也受保护。这两类入口分别可以修改登录身份或执行应用内脚本，匿名开放会绕过监控的员工停用、密码和白名单检查。

监控查看账号不会得到上述管理权限。本功能不新增管理员授权、注册或员工管理页面；没有既有管理员认证来源的部署，应通过现有受控管理方式维护员工数据。监控关闭时保留旧接口原有的保护开关行为。

## 验证

认证集成测试使用真实 Spring Security 过滤链、员工 Mapper 与 H2 数据库，覆盖聊天开关两种配置、默认空白名单、401/403/503、账号状态变化、会话轮换、CSRF 和聊天会话隔离。数据库故障只在身份 Mapper 边界注入，固定错误响应需通过敏感字符串检查。既有聊天认证和浏览器回归继续作为交付门禁。
