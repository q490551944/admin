# 监控登录浏览器回归

监控入口为 `/monitor/index.html`，匿名访问会进入 `/monitor/login.html`。页面复用员工账号，通过同源 Cookie 调用监控认证接口。登录成功后仍需通过当前身份和白名单检查，才显示监控入口。此阶段提供登录及身份入口，后续指标页面按各自 Issue 实现。

## 本地运行

需要 Java 17、Node.js 20 或以上和 Chromium。测试启动独立的 `MonitoringE2eApplication`，使用 H2 内存数据库、随机回环端口和 `chat.enabled=false`，不需要 MinIO、Docker 或业务数据库。

在项目根目录执行：

```powershell
.\mvnw.cmd -B -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/monitor-e2e-classpath.txt' '-DincludeScope=test'
npm ci
npx playwright install chromium
npm test
npm run test:monitor:e2e
```

Linux 或 macOS 将第一条命令中的 `.\mvnw.cmd` 替换为 `bash mvnw`。CI 的 Chromium 安装命令增加 `--with-deps`。

已有聊天构建的 `target/chat-e2e-classpath.txt` 也可复用；runner 优先使用 `target/monitor-e2e-classpath.txt`，其次使用聊天文件。`MONITOR_E2E_CLASSPATH_FILE` 可显式指定文件。使用已安装的 Edge 时，可在 PowerShell 设置 `$env:MONITOR_E2E_BROWSER='msedge'`。`npm run test:monitor:e2e -- --grep 'outage'` 可选跑故障用例。

runner 为每次执行创建随机控制令牌，通过进程环境传给测试应用和 Playwright，令牌不写入 ready 文件或启动日志。测试专用控制端点只用于重置临时员工、切换白名单、注入身份服务故障及关闭监控；它们不进入生产应用。账号 `monitor-allowed`、`monitor-denied`、`monitor-disabled` 和固定测试密码只存在于该内存测试夹具。

## 覆盖与产物

浏览器测试通过实际 HTTP、Spring Security 过滤链、员工 Mapper 和 H2 数据库验证以下行为：

- 匿名从 `/monitor`、`/monitor/` 或页面地址进入登录页；允许账号可按 Enter 登录，随后复查白名单，刷新保留身份，退出后失去访问权限。
- 错误密码、未知或停用账号显示一致提示；非白名单账号虽然登录返回 201，仍显示无查看权限，并可退出后换用获授权账号。白名单变更在下一次检查时生效。
- 身份服务返回 503 时隐藏旧身份，可重试恢复，也可在故障期间退出。
- 监控关闭返回 404；聊天关闭时监控登录仍可使用；员工被停用后下一次检查返回登录页。
- 登录缺少 CSRF 被拒绝；登录前的令牌失效，新令牌可完成真实写请求；退出也要求 CSRF。
- 两个标签页共享 Cookie，一页退出后另一页在重新获得焦点时复查身份。
- 1280px 桌面与 390px 手机尺寸下，表单、身份和错误操作可见且没有横向溢出。错误页面不显示 SQL、连接信息、内部异常或密码。

测试不拦截或伪造监控认证 API。异常在测试应用的员工 Mapper 边界注入，CSRF 验证由真实过滤链完成。当前页面读取失败和写请求的更细粒度竞态由 `src/test/js/monitor-session.test.cjs` 补充。

每次测试结束时 runner 停止自己启动的应用，H2 数据随进程销毁。应用日志位于 `target/monitor-e2e-<随机 ID>/app.log`，HTML 报告位于 `target/monitor-e2e-report/`，截图和失败 trace 位于 `target/monitor-e2e-results/`。桌面和手机的登录页、入口页、故障页截图在成功运行时也保留。

现有 Chat regression 工作流在聊天浏览器验收之后运行监控用例，始终上传监控报告、截图和应用日志。聊天和监控使用各自的 Playwright 配置及测试文件，原有聊天用例继续执行。
