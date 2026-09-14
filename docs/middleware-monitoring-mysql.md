# MySQL 连接与查询负载

MySQL 采集复用监控目录解析出的现有数据源配置。启用对应目标后，普通调度自动通过 MySQL 适配器更新共享快照；API 请求只读取快照，不额外查询 MySQL。

```yaml
monitor:
  enabled: true
  allowed-user-ids: [1001]
  targets:
    - id: mysql-main
      type: mysql
      enabled: true
      connection-source: dataSource
      scope:
        databases: [admin]
```

`connection-source` 必须指向实际已有数据源 bean。这里不另配监控密码或连接地址。普通指标为服务进程范围，不受容量统计的数据库列表限制；数据库容量与缓冲池由后续任务补充。

## 指标定义

| 指标 | 来源与含义 |
| --- | --- |
| `mysql.probe.duration` | 监控连接建立和 `SELECT 1` 的只读探测总耗时，毫秒。 |
| `mysql.connections.current` | `Threads_connected`，当前打开的服务连接数，包括采集自己的连接。 |
| `mysql.connections.max` | `max_connections`，服务允许的客户端连接配置上限；不是业务池大小。 |
| `mysql.threads.running` | `Threads_running`，非休眠线程数；不能解释为 CPU 上正在执行的线程数。 |
| `mysql.questions.rate` | 相邻 `Questions` 差值除以实际采样间隔，单位为客户端语句/秒。 |
| `mysql.slow_queries.total` | `GLOBAL Slow_queries`，服务运行以来超过 `long_query_time` 的查询累计。 |
| `mysql.slow_queries.delta` | 相邻 `GLOBAL Slow_queries` 差值，单位为次，不换算为速率。 |

探测成功与统计读取能力分别记录。统计权限不足或单项缺失只影响相关指标；真实零值保持为零，没有样本、不支持、超时和无权限都有明确缺失原因。结果不会携带 JDBC URL、密码、SQL 异常或堆栈。

所有指标使用相同目标的进程范围及固定来源说明。普通样本的有效期为普通周期的 `metric-ttl-multiplier` 倍，默认 45 秒；服务探测使用 `service-ttl`，默认也为 45 秒。

## 增量与重置

派生速率和增量复用通用计数存储，仅保留计算所需的相邻样本。第一次采样、服务身份变化、计数下降、Uptime 下降，或间隔超过三个普通周期时重新建立基线，不制造尖峰。Uptime 与查询计数一起提交，因此服务重启后查询量已追平、但 Uptime 下降时仍能识别重置。缺少 Uptime 或服务标识时不伪造可信增量。

MySQL 的 `server_uuid` 通常在进程重启后保持不变；若两次采样之间重启且查询计数和 Uptime 都已追平，仅凭这些当前原生值无法证明发生过重启。页面不提供历史补点或绝对完整的重启检测。

统计来自 GLOBAL 状态。不能把 `FLUSH STATUS` 笼统解释为全局慢查询累计归零；实际字段语义和重置行为受服务版本影响。采集器不执行 FLUSH、不改变服务参数、不写业务表。采集自身执行的 SQL 会增加服务全局语句计数。

## 连接预算与支持边界

采集根据已解析的 Hikari 或 Druid 原生连接参数建立监控独占的物理连接，保留原始单目标地址、凭据优先级和 TLS 配置。它不向业务池借连接，不调整或关闭业务池；业务池耗尽不会消耗采集预算中的借用等待时间。Connector/J 仍固定为项目已有的 8.0.32，只将依赖从运行时范围改为编译范围以校验原生接口。

连接、握手和每次读取使用同一个请求的剩余预算，驱动原有的更短超时仍然生效。监控关闭自动重连及驱动查询超时线程，通过已登记的自有 socket 关闭和连接 abort 实际取消网络读写；不使用会另外连接服务器执行 `KILL QUERY` 的 Statement.cancel 路径。成功和失败后都释放自有物理连接。

目前支持原生单主机 TCP URL，包括普通 DNS 名称和主机级凭据/TLS 参数。多主机切换、SRV、自定义 socket factory、SOCKS、propertiesTransform、useConfigs 和数据源凭据回调会明确返回不支持，不擅自截取第一个节点、替换认证方式或放宽 TLS。启用自动建库、非空 sessionVariables 或查询/连接生命周期/异常拦截器的配置同样不受支持，避免新建监控连接时执行额外 SQL 或用户回调；不会以“复用配置”为由绕过只读边界。

标准 Java DNS 解析无法强制中断。若它在截止后仍未返回，调度器按预算发布超时结果，同时保留原工作槽和资源预留；后续轮次不会创建替代线程。解析晚到后不得再建立网络连接，实际任务退出并完成清理后才释放占位。原生连接和网络取消测试用于验证可终止的握手、读取与 socket 资源路径；不能把逻辑超时写成 DNS 必然在相同时间内返回。

## 验证方法

本地契约测试使用真实计数控制器、快照存储和回环连接，覆盖首样本、实际时间间隔、零值、计数与 Uptime 回退、字段缺失、权限拒绝、超时及自有资源取消：

```powershell
.\mvnw.cmd '-Dtest=MonitoringMysql*Test,MonitoringCalculationsTest,MonitoringCatalogTest' test
```

安装 Docker 后可单独运行真实 MySQL 8.0.36 集成测试；CI 的 MySQL 环境任务调用同一入口，缺少 Docker 会失败而不是跳过：

```powershell
.\mvnw.cmd '-Dtest=MonitoringMysqlAdapterIntegrationTest' '-Dmonitor.test.type=mysql' test
```

测试仅启动自有临时容器，分别占满真实 Hikari/Druid 池后运行采集，将结果与本次原生响应逐项核对，并检查业务连接仍可用、自有连接已关闭、业务数据与写入计数未变。测试准备可对自己的会话设置慢查询阈值并执行只读负载；这些准备操作不进入生产采集器。报告位于 `target/surefire-reports`，脱敏环境记录位于 `target/monitor-environments`。

## 语义依据

- [MySQL 8.0 状态变量](https://dev.mysql.com/doc/refman/8.0/en/server-status-variables.html)：Questions、Slow_queries、Threads_connected、Threads_running。
- [SHOW STATUS](https://dev.mysql.com/doc/refman/8.0/en/show-status.html)：读取状态无需增加 PROCESS 权限。
- [MySQL 系统变量](https://dev.mysql.com/doc/refman/8.0/en/server-system-variables.html)：max_connections。
- [FLUSH STATUS](https://dev.mysql.com/doc/refman/8.0/en/flush.html#flush-status)：区分会话与全局重置行为。
