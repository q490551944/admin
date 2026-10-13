# 当前监控数据 API

实现 issue #25、#40 和 #44。API 使用现有监控会话与服务端用户 ID 白名单；聊天开关不影响权限。

| 方法与路由 | 内容 |
| --- | --- |
| `GET /api/monitor/v1/catalog` | 六类目录、配置状态与数量、安全目标和绑定范围 |
| `GET /api/monitor/v1/snapshots` | 全部声明目标的最后采集尝试、服务观测、指标数量和缺失数量 |
| `GET /api/monitor/v1/targets/{targetId}` | 一个规范展示目标的总览、全部有界指标和逐指标能力证据 |

三种成功响应均带 `serverTime`、`generation`、`resolutionState`。时间字段固定使用 ISO 8601 字符串，与应用的全局日期序列化开关无关。所有响应禁止缓存；读取不解析连接、不提交采集任务、不访问原生客户端，也不改写内存中的时间或计数基线。每次读取仅调用一次时钟。完整 JSON 样例见 `middleware-monitoring-api-examples.json`。

## 目录和初始状态

`types` 始终包含 MySQL、Redis、Kafka、MongoDB、Elasticsearch、MinIO。每类返回 `configuredCount`、`missingCount`、`disabledCount` 和 `resolvingCount`；没有声明目标的类型为 `DISABLED`。

`resolutionState=RESOLVING` 表示启动连接解析尚未完成。此时启用目标的 `configurationStatus` 和 `resolutionReason` 为 null，单独禁用目标仍为 `DISABLED`。解析完成后为 `READY`，包括零目标的情况。缺少连接和禁用目标仍在目录中，不因没有快照消失。

目标只含 `id/type/name/configurationStatus/resolutionReason/connectionSources/scope/memberIds/bindings`。`connectionSources` 是安全引用名称，不是 URL。每个 binding 有自己的 `bindingId/source/scope`；展示目标的 scope 并集只用于目录说明，不代表某个绑定可读取并集中的其他对象。

详情仅接受目录返回的规范 `id`，不将合并前 `memberIds` 建成隐式别名。未知目标返回 HTTP 404：

```json
{"code":"TARGET_NOT_FOUND","message":"监控目标不存在"}
```

错误不回显传入 ID。匿名访问为 401 `SESSION_EXPIRED`，无白名单权限为 403 `ACCESS_DENIED`，整体禁用为 404 `MONITOR_DISABLED`，身份数据库不可用为 503 `IDENTITY_UNAVAILABLE`。

## 观测和能力

`schedulingState` 分为 `ACTIVE/RESOLVING/DISABLED/CONFIGURATION_MISSING`。只有实际配置完成的绑定参与采集。每个绑定分别返回普通和容量采集项；没有尝试时 `status=WAITING`、`reason=WAITING_SAMPLE`、`lastAttempt=null`。禁用、缺连接和解析中目标没有虚构的等待任务。

`serviceObservations` 按 binding 返回最后服务观测；无观测时为 `UNKNOWN/WAITING_SAMPLE` 且 `lastObservation=null`。权限不足、统计不支持或采集超时不据此变成连接失败。采集状态和服务观测彼此独立。

详情 `metrics` 和总览 `coreMetrics` 使用同一指标投影：每项保留来源、binding、普通/容量类别、`latestAttempt` 和可空的 `lastSuccess`，并附 `valueState/validUntil`。定义中的原生对象范围、来源、单位和计算说明原样返回；真实 0 保持为数值，不可采则保持 null 和具体原因。容量值的时间不因普通采集刷新。

`capabilityState=UNKNOWN` 表示尚无逐指标证据；`OBSERVED` 只表示存在观测，不保证所有指标可采。每个 capability 基于该指标最后尝试：成功为 `AVAILABLE`，权限不足、不支持、不适用分别保留 `UNAUTHORIZED/UNSUPPORTED/NOT_APPLICABLE`，超时、失败等为 `UNKNOWN`。每项附 `sampledAt/lastSuccessAt/validUntil`，不得把服务连通或存在适配器解释为远端所有资源可采。

`valueState` 为 `CURRENT/LAST_SUCCESS/EXPIRED/NO_DATA`：成功样本仍有效为当前值；最近尝试失败且旧成功值有效为上次成功值；超过旧成功样本自身 `validUntil` 为已过期；从未成功为暂无数据且有效期为 null。边界是严格超过，等于有效期仍有效。普通默认 15 秒、容量 60 秒，各自使用周期 3 倍的 TTL；服务观测默认 45 秒。实际有效期来自原始成功样本，读取不会延长。

整轮失败或部分采集没有返回旧系列时，投影为该 binding/kind 附上对应失败原因，保留旧成功值及其原时间；当前轮已明确读取的部分成功项仍保留自己的证据。投影不回写内存。原始最后探测保存在 `lastObservation`，过期后的当前 `availability=UNKNOWN/expired=true`；不会把权限、TLS 或统计缺失当作服务宕机。

目标返回统一 `serviceAvailability/collectionStatus/exceptional`；`serviceSummary` 只互斥计数 ACTIVE 目标。`collections` 附最近整轮有效期和独立的 `valuesValidUntil`（该 binding/kind 最早成功值有效期），轻量探测成功不能隐藏未展开指标的过期。详情和列表在同一 `serverTime` 下投影一致。

快照/详情下发 `freshnessRules`：实际刷新/普通/容量周期、服务 TTL、指标 TTL 倍数与 `expiresAfterValidUntil=true`，供页面采用相同规则。所有时间为 ISO 字符串。重复读取只改变响应的服务器时间和由此推算的状态，不补历史。

`PARTIAL` 不升级成成功，也不按返回指标数量计算对象覆盖率。底层 `inventoryComplete` 仅用于清理退出身份清单的指标，不是完整集群覆盖证明。

总览最多返回三条原始普通核心指标，不复制全部指标、不跨 scope 合并。完整数值与能力从受保护详情读取；调用方不能指定任意原生命令或指标查询。

## 一致性和边界

`MonitoringScheduler.readState()` 在调度器 gate 内按 gate → snapshot-store 的锁顺序读取一个代次的安全目录和不可变样本。替换目录、清理旧快照与采集结果发布使用同一 gate；JSON 投影与编码在释放锁后完成。公开读取对象不持有连接、settings、client、身份凭据或异常；安全性不依赖 Jackson 忽略注解。

默认最多 20 个目标、每目标 5000 项指标；保留范围和绑定身份，避免多连接合并后混用授权。内存中仅存当前观测及必要的上次成功值，无历史数据或趋势查询。

## 验证

自动化验证包括真实安全链、H2 员工 SQL、真实 CSRF/登录、404 错误结构、无样本、部分指标、独立绑定范围、重复读取不触发采集，以及目标替换期间的并发代次一致性。

性能基线使用真实 HTTP 服务与五个独立授权会话，预置 20 个目标 × 每目标 5000 项指标，分别测量总览和实例详情；认证 SQL、内存复制、投影和 JSON 编码均计入。用 `-Dmonitor.performance=true -Dtest=MonitoringReadPerformanceTest` 运行，P95 断言为不超过 1 秒。实测环境与结果记录在本 issue 的验证报告中；该基线不是所有机器上的生产性能承诺。

2026-09-14 本机实测：Windows 10 amd64、JDK 17.0.9、16 个逻辑处理器、最大堆 3948 MiB、H2 2.2.224。10 次预热后，总览与详情各测量 40 次；测量包含完整响应传输与客户端 JSON 解码，登录和预热不计入。

| 端点 | P95 | 最大响应大小 |
| --- | --- | --- |
| 总览 | 214.12 ms | 22,370 bytes |
| 5000 项指标详情 | 354.19 ms | 5,882,386 bytes |

测量期间 resolver 和 adapter 调用增量均为 0。CI 使用相同测试门槛并归档 `target/monitor-api-performance.json`。详情响应较大，前端总览不应为每个卡片循环下载所有详情。

2026-10-13 在相同本机环境验证 #44：总览 P95 262.07 ms、最大 76,461 bytes；详情 P95 366.31 ms、最大 6,295,270 bytes。仍为 20×5000、五个独立授权页面，读取期间解析与采集增量为 0。此项为受 API 投影变更影响的专项性能检查。
