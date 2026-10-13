# 监控登录浏览器回归

监控入口为 `/monitor/index.html`，匿名访问会进入 `/monitor/login.html`。页面复用员工账号，通过同源 Cookie 调用监控认证接口。登录成功后仍需通过当前身份和白名单检查，才显示总览。总览读取受保护目录和共享快照；目录代次变化时最多重读一次，不发起同步采集。

## 本地运行

需要 Java 17、Node.js 20 或以上和 Chromium。测试启动独立的 `MonitoringE2eApplication`，使用 H2 内存数据库、随机回环端口和 `chat.enabled=false`，不需要 MinIO、Docker 或业务数据库。

在项目根目录执行：

```powershell
.\mvnw.cmd -B -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/monitor-e2e-classpath.txt' '-DincludeScope=test'
npm ci
npx playwright install chromium
node --test src/test/js/monitor-session.test.cjs src/test/js/monitor-overview.test.cjs src/test/js/monitor-details.test.cjs
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

测试不拦截或伪造监控认证 API。异常在测试应用的员工 Mapper 边界注入，CSRF 验证由真实过滤链完成。认证竞态由 `src/test/js/monitor-session.test.cjs` 补充。`monitor-overview.spec.cjs` 仅在浏览器测试中提供目录/快照夹具，覆盖六类卡片、同类型多目标、互斥服务汇总、等待首样本、部分采集、真实零值、旧值与 XSS 名称，以及 1280px/390px 无横向溢出；夹具不进入正式页面。

总览 API 的 `coreMetrics` 最多返回三条原始普通指标，包含范围、单位、最近尝试和独立的上次成功样本；不会跨来源或对象相加。完整数据仍由受保护详情 API 提供。

点击“查看实例”按需读取所选目标详情，保留目标 ID、类型及连接来源，分别展示 CPU、内存、存储与容量、专属指标。每项显示单位、原生对象范围、数值采样时间、最近尝试、来源及计算说明；容量明确显示服务器配置的独立周期。没有观测的类别显示“未报告”，原生不支持、未授权、不适用、等待及失败保留各自原因，不产生零值。详情切换或权限失效会中止旧请求并清除旧目标数值。

详情最多接受服务器约定的 5000 项，首次渲染 200 项，使用“继续显示”增加 200 项；该操作复用当前详情，不新增请求。自动或手动更新总览时仅更新已打开的实例详情。浏览器覆盖六类、空项、长名称、390px、详情切换、5000 项边界、目标移除和授权失败。

总览按类型、服务状态和采集状态组合筛选；“仅看异常”包含配置问题、未知服务和异常采集，排除未启用目标。点击服务汇总会清除其他条件，以便筛选结果与汇总数量一致。筛选只操作当前快照，不请求 API。

页面可见且未暂停时，每 15 秒以 GET 读取共享目录/快照，手动刷新使用相同接口并复用正在进行的请求。暂停会中止当前读取，仍可手动读取；继续会立即读取。返回前台先复查权限，按服务器时间和单调时钟重算各自有效期；暂停状态保留，只有未暂停时自动读取。权限失效会清除私有数据，迟到响应不会恢复旧内容。五页面浏览器用例验证每页最多一个并发读取且不发送采集写请求；后端只读链路的零采集调用由 API 测试验证。

只验证刷新改动可运行 `npm run test:monitor:e2e -- --grep 'combined overview|automatic shared|background return|aborted old|five pages'`；布局或登录生命周期发生变化时，加跑对应桌面、手机和授权用例。完整浏览器集由既有 CI 自动执行。

每次测试结束时 runner 停止自己启动的应用，H2 数据随进程销毁。应用日志位于 `target/monitor-e2e-<随机 ID>/app.log`，HTML 报告位于 `target/monitor-e2e-report/`，截图和失败 trace 位于 `target/monitor-e2e-results/`。桌面和手机的登录页、入口页、故障页截图在成功运行时也保留。

现有 Chat regression 工作流在聊天浏览器验收之后运行监控用例，始终上传监控报告、截图和应用日志。聊天和监控使用各自的 Playwright 配置及测试文件，原有聊天用例继续执行。
