# 真实周期采集浏览器验收

`npm run test:monitor:runtime:e2e` 自行创建 MySQL 8.0.36 和 Redis 7.2.4 两个隔离容器、限定资源和哨兵数据，在 H2 中准备三个测试员工账号，启动仅监听 `127.0.0.1` 随机端口的测试应用。聊天关闭，监控白名单仅包含测试员工 1。生产连接解析、15 秒普通/60 秒容量调度、原生适配器、共享快照、认证 API 和页面均实际运行。四个其他类型显示未启用。测试入口、账号、控制路由及网络转发器全部位于 `src/test`，不进入生产 jar。

## 运行

需要 Java 17、Node.js、可用 Docker 和 Playwright Chromium：

```sh
bash mvnw -B -Dtest=OwnedRedisProxyTest test dependency:build-classpath \
  -Dmdep.outputFile=target/monitor-e2e-classpath.txt -DincludeScope=test
npm ci
npx playwright install --with-deps chromium
npm run test:monitor:runtime:e2e
```

CI 的 `Monitoring real browser / monitor-browser` 独立执行相同命令，也可通过 workflow_dispatch 重复运行。原有聊天回归、页面契约测试和六类原生运行链路门禁保留。本机缺少 Docker 时可编译全部测试、执行有真实 TCP 往返的 `OwnedRedisProxyTest`，实际 MySQL/Redis 周期到浏览器的验收必须由有 Docker 的环境完成。

## 验收口径

- 浏览器从真实登录进入六类目录，在 1280×900、390×844 下打开两种详情。检查 API 样本对应的标签、单位、对象范围、来源和采样时间，并与直接原生查询的 InnoDB 缓冲池配置和 Redis maxmemory 核对。无法采集的 MySQL 文件系统总量保持 `— B`，不变成零；页面没有历史或趋势功能。
- 两种屏宽各 20 次授权导航，记录导航开始到六类卡片和两个真实来源的首批核心指标渲染的 P95，要求 ≤2 秒。容器启动和首次原生周期采样由就绪阶段等待，不计入导航性能。报告明确记录这一定义和样本数。
- 建立 8 个直接连到自有 Redis 的长期连接，等待真实普通采集和页面刷新观察 INFO 客户端数增加。INFO 自身采集连接计入原生数字，独立容量探测可能产生一个瞬态连接，因此断言增量至少 7，并单独核对自有连接数 8。
- Redis 的已有客户端连接经过测试专用透明 TCP 转发，传输的请求/响应均来自真实 Redis。故障关闭监听与已有连接，恢复在原端口重新监听，不暂停 MySQL，也不伪造 Redis 响应。页面自动显示普通连接故障的实测耗时必须 ≤35 秒；保持真实故障跨过 45 秒普通指标 TTL，检查旧值、过期、汇总和异常筛选，MySQL 原生采样时间继续增长。恢复由真实 15 秒普通周期及独立 60 秒容量周期驱动。
- 暂停刷新和浏览器实际断网使用已收到的真实数据，推进浏览器单调时钟 181 秒检查普通/容量 TTL、卡片、详情、汇总和异常筛选的一致性。推进的是浏览器缓存年龄，后台服务继续运行；恢复后读真实 API。普通故障预算与实际故障过期用例不推进浏览器时间。
- 验证真实未登录 401、非白名单员工 403、员工 mapper 边界暂时不可用 503；身份恢复可重试，聊天关闭仍能独立登录监控。

## 隔离、报告与清理

没有监控 API 的 route/fulfill/mock。控制端点仅接受运行器生成的随机环境令牌并核对回环请求来源；不接受任意服务地址、命令、数据库或资源名。容器、应用和转发器的地址全部由创建的测试资源推导，继承的配置不能改变员工数据库或监听地址。

运行器通过受保护的 finish 路由请求正常退出，校验两类业务哨兵不变、删除自有数据并关闭容器，要求 `cleanup.json` 确认成功；失败或取消时 JVM 钩子和 Testcontainers Ryuk 负责自有资源清理。故障测试结束会恢复网络、关闭自有负载连接和还原账号状态。

独立 CI 上传成功与失败截图、每项 trace、HTML 报告、仅含测试样本的 `acceptance-*.json`、不含连接凭据的就绪/清理报告，以及应用日志。日志和报告目录为 `target/monitor-runtime-e2e-*`、`target/monitor-runtime-browser-report` 和 `target/monitor-runtime-browser-results`。测试令牌在 CI 日志中屏蔽，不写入就绪文件或 Java 参数文件。
