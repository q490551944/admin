# MongoDB 进程连接、角色与操作指标

监控从已经初始化的 MongoClient 读取实际生效的原生设置。未启用或没有对应客户端时保持无连接，页面读取后台快照，不直接向 MongoDB 发请求。

```yaml
monitor:
  enabled: true
  allowed-user-ids: [1001]
  targets:
    - id: mongo-main
      type: mongodb
      enabled: true
      connection-source: mongoClient
      scope:
        databases: [reporting]
```

连接来源名须与实际 bean 一致。本项不发现数据库、不遍历数据库或集合；配置的 databases 留给 #35 的容量采集。普通采集沿用 15 秒周期、含排队的 5 秒预算和 45 秒有效期。

## 指标与范围

全部指标描述本次连接到的一个进程，`PROCESS` 范围不代表副本集或分片集群总量。连接到 mongos 时显示路由进程；不会把它的统计解释成所有 shard 的总量。

| 指标 | 含义 |
| --- | --- |
| `mongodb.probe.duration` | 建立监控连接并完成 ping 的耗时，毫秒 |
| `mongodb.connections.current` | 当前进程接收的连接数，包含监控等连接 |
| `mongodb.connections.available` | 当前进程尚可接收的连接数 |
| `mongodb.process.kind` | 已证明的 mongod 或 mongos |
| `mongodb.process.role` | 单机、副本集主/从/仲裁/其他成员，或路由节点 |
| `mongodb.operations.<type>.total` | 原生 opcounters 累计操作数 |
| `mongodb.operations.<type>.rate` | 相邻有效样本的操作数差值除以实际间隔 |
| `mongodb.cpu.percent` | 无通用可靠来源，明确显示 `UNSUPPORTED` |

操作类型固定为 insert、query、update、delete、getmore、command；原有 18 个进程指标补充 5 个内存、引擎和缓存指标，共 23 项。opcounters 的操作数不等于文档数，也不等于成功请求数。监控自身的原生命令也可能计入服务统计。

ping 成功只证明服务响应命令。hello 和 serverStatus 的权限、能力与单字段缺失独立表达，不会把缺失指标填成零。角色由受限的原生字段组合判断，不单凭 `isWritablePrimary=true` 断言副本集主节点。原始主机、凭据、副本集名、进程标识与错误文本不进入页面。

`serverStatus.process` 实际来自启动程序名，Windows 6.0.11 可能返回完整路径。监控只提取受限文件名并归一为 mongod/mongos；程序被重命名时，需要同连接 hello 的角色证据才能判定。路径与自定义程序名始终留在服务端。

六个速率使用公共快照计算器保留必要的前一样本。首次采样、进程身份变化、计数或 uptime 回退时等待新的基线；按实际小数秒计算，超时或被拒收的结果不推进基线。缺少可靠进程身份或 uptime 时只降低速率能力，保留独立成功的累计值和连接数。

## 原生生命周期

本实现审计并固定于项目已有 MongoDB Java 驱动 4.11.1，使用其原生独占连接完成握手与认证，并在同一物理连接上执行固定的 ping、hello 和 serverStatus。不会建立另一个监控客户端拓扑，也不会借用或关闭业务池中的连接。

支持原生 `SINGLE` 模式的单 host 配置；普通单地址 URI 的默认行为属于此模式。握手后检查驱动的原生版本兼容规则，以及原配置要求的集群类型和副本集名。多节点、SRV、负载均衡、压缩及无法安全复用的自定义扩展会明确返回不支持，不改写连接目标来绕过限制。实际生效的 TLS 设置、凭据与原有更短超时保留。

原生响应在解码前受 2 MiB 与 32 层 BSON 深度上限约束，越界作为无效响应降级；不会为了解码任意服务器数据无限分配内存。本项只接受无认证或内建 SCRAM、PLAIN、X.509 的可复用设置，未知认证机制及机制扩展返回不支持。

连接、读取使用剩余预算，并在创建套接字前登记取消资源。取消会关闭监控自有套接字，覆盖 TCP 和 TLS 握手尚未完成的阶段；原采集线程返回且清理完成后才释放采集槽。原生 DNS 或不可中断的本地驱动调用仍可能延迟退出，期间保留原槽，不创建替代线程。

驱动内部工厂需要有限的反射访问；驱动升级或访问方式不兼容时返回不支持，需要重新审计与验收。命令使用驱动无会话的原生路径，不创建逻辑 session 或发送 endSessions，不需要额外服务组件、端口或权限变更。

## 验证入口

```powershell
.\mvnw.cmd '-Dtest=MonitoringMongoConnectionsTest,MonitoringMongoAdapterTest,MonitoringMongoNativeDeadlineTest,MonitoringCatalogTest' test
.\mvnw.cmd '-Dtest=MonitoringMongoAdapterIntegrationTest' '-Dmonitor.test.type=mongodb' test
```

真实集成入口使用隔离的 MongoDB 6.0.11 容器，验证原生指标、统计权限拒绝及业务数据不变。创建测试账号和造数只发生在测试拥有的容器中。单机容器验证 standalone；副本集和路由角色、计数重置等边界由契约测试覆盖，不宣称已完成真实多节点拓扑验证。

## 语义依据

- [MongoDB ping](https://www.mongodb.com/docs/v6.0/reference/command/ping/)：命令响应探测。
- [serverStatus 字段说明](https://www.mongodb.com/docs/v7.0/reference/command/serverstatus/)：进程连接与操作计数口径；版本能力以固定 6.0.11 实测为准。
- [MongoDB hello](https://www.mongodb.com/docs/manual/reference/command/hello/)：节点角色证据。
- [MongoDB 6.0.11 启动程序名](https://github.com/mongodb/mongo/blob/r6.0.11/src/mongo/db/server_options_server_helpers.cpp)：`process` 名称的来源与路径处理。
- [驱动 4.11.1 原生连接](https://github.com/mongodb/mongo-java-driver/blob/r4.11.1/driver-core/src/main/com/mongodb/internal/connection/InternalStreamConnection.java)：协议、认证和套接字生命周期。

## 内存、缓存与配置库存储

普通任务新增 `mongodb.memory.resident.bytes`：原生 `mem.resident` 的 MiB 乘 1048576，范围是同一所选 mongod/mongos 进程。`mongodb.storage.engine` 只输出已知引擎、other 或 router；WiredTiger 的 `mongodb.wiredtiger.cache.used.bytes`、`.maximum.bytes` 和 `.used.percent` 属于独立缓存范围，不能替代进程内存或宿主机内存。非 WiredTiger、mongos 缓存不适用；缺失字段不支持；零缓存上限不计算比例，实际瞬时超限不截断。新增资源带稳定匿名节点标识，不泄露地址或原始进程身份。

容量任务独立按 60 秒采集、180 秒有效期，在同一原生连接执行 hello 和配置库的 `dbStats:1, scale:1, freeStorage:0`。不 ping、不读 serverStatus、不刷新普通或服务样本，不枚举数据库、集合或内容。有效库数受配置与 100 库硬上限共同限制；每库分别返回 `mongodb.database.dataSize.bytes`（未压缩逻辑数据）、`.storageSize.bytes`（集合分配存储，含空闲但不含索引）和 `.indexSize.bytes`（索引分配存储），不可互相替代。

同一所选 mongod 的 dbStats 文件系统字段归属 dbpath 所在文件系统，与数据库大小不同。使用一个成功的原生响应返回 `mongodb.filesystem.used.bytes`、`.total.bytes`、`.used.percent`，多个配置库不重复展示或累加；也不跨目标推断卷身份或求和。缺失字段单独不支持，零总量不计算比例，used > total 的比例标为无效。mongos 的库字段是原生分片聚合，不能代表路由进程本机文件系统，文件系统指标不适用。部分库拒绝只降低该库能力，保留原成功值的时间及有效期。

新增契约验证非 WiredTiger、mongos、缺失/异常字段、零分母、共享文件系统、范围上限及独立有效期。真实 6.0.11 单机容器逐项对比内存/缓存和两个配置库的原生返回，确认文件系统只展示一次、第三个库未查询、每库权限拒绝和业务数据/写入计数不变；非 WiredTiger、mongos 和零文件系统容量由契约覆盖，不声称真实容器使用这些部署拓扑。

- [MongoDB dbStats 字段](https://www.mongodb.com/docs/manual/reference/command/dbstats/)：逻辑数据、集合/索引分配存储及文件系统范围。
- [MongoDB 6.0.11 mongos dbStats 聚合](https://github.com/mongodb/mongo/blob/r6.0.11/src/mongo/s/commands/cluster_db_stats_cmd.cpp)：聚合库大小，不能当作路由进程本机容量。
