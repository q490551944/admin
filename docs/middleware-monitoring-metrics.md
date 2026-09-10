# 当前指标契约

此文档定义六类采集器共用的输入、输出和内存计算。调度由 #24 实现，HTTP API 由 #25 实现，旧值/过期的展示投影由 #44 实现。示例数值仅用于说明格式，不是开发机或生产环境的采样结果。

## 适配器与采集输入

`MonitoringAdapter` 通过 `MiddlewareType` 注册到 `MonitoringAdapterRegistry`，重复类型在启动时被拒绝。适配器构造和类型发现不得连接中间件，`collect(CollectionRequest)` 才执行只读采集。

`CollectionRequest` 是服务端对象，持有已有客户端、有效配置、单个来源的授权范围，以及目标、来源、普通/容量类别、身份代次、序号、计划时间和截止时间。截止时间从计划入队时起算，包含等待；采集器不得关闭借用的业务客户端。调度器把每个 `SourceBinding` 自己的范围传入，不能传合并目录的范围并集。

`CollectionResult` 返回开始/结束时间、采集状态、指标样本、可选的独立服务探测及 `inventoryComplete`。普通和容量任务是不同的采集序列，来源也各自独立；一个来源的成功不会覆盖另一来源的失败。

`inventoryComplete=true` 表示返回的指标身份集合是该来源、类别、授权范围的完整清单，允许退役不再存在的节点/指标。它不代表每项数值都成功：已知仍存在但采集失败的指标必须返回缺失占位，以保留最近成功值。无法确认资源清单完整时使用默认 `false`，不能靠一次 `SUCCESS` 推断清单完整。完整空清单只清除对应来源和类别，不影响其他来源或其他采集类别。

## 状态和指标

| 维度 | 稳定枚举 |
| --- | --- |
| 服务可用性 | `AVAILABLE`、`DEGRADED`、`CONNECTION_FAILED`、`UNKNOWN` |
| 采集状态 | `SUCCESS`、`PARTIAL`、`UNAUTHORIZED`、`UNSUPPORTED`、`BUSY`、`FAILED`、`STALE`、`WAITING` |
| 指标缺失原因 | `UNSUPPORTED`、`UNAUTHORIZED`、`NOT_APPLICABLE`、`WAITING_SAMPLE`、`NO_REQUESTS`、`BUSY`、`TIMEOUT`、`FAILED`、`INVALID_VALUE` |

`CONNECTION_FAILED` 只表示从应用侧连接失败。认证拒绝或统计接口不支持不能产生这个结论；未知服务结论必须有原因。原生降级与 CPU/内存负载是不同概念。

每项 `MetricSample` 包含定义、值或缺失原因、采样时间、最后成功时间和有效期。定义包含稳定 key、名称、单位、对象范围、原生命令/字段来源和计算说明。值仅接受不可变的有限数值、文本或布尔值；数值规范化为 `BigDecimal`，真实 `0` 保持为数值，不是缺失。文本值最长 4096 字符，错误消息使用固定文本，不回显原始响应或凭据。

`ScopeKind` 区分进程、JVM 堆、缓存、数据库、索引、主题、分区、消费组、桶、节点、集群、文件系统、节点报告的 OS、配置范围和端点。文件系统应带节点/路径，不能将逻辑数据量标为磁盘容量，也不跨实例相加共享卷。部分结果通过独立的覆盖数量/总数指标和 `PARTIAL` 说明，不能当作全量。

成功样本记录自己的 `lastSuccessAt`，缺失样本不宣称成功。`StoredMetric` 同时保留最近尝试和最近成功；失败不改写旧值的采样时间或有效期。普通样本默认有效 45 秒，容量样本默认 180 秒，服务探测默认 45 秒。`now == validUntil` 仍在有效期，严格晚于有效期才过期；状态投影在 #44 落地，不在当前存储里改写原生样本。

## 实时计算

`MonitoringCalculations.evaluate` 使用前后两份计数，返回数值或缺失原因，以及是否替换基线。计数和差值用 `BigDecimal`，除法使用 DECIMAL128；时间使用实际秒数并保留纳秒边界。`CounterSample.epoch` 是服务端不可变代次标记，应包含实际连接代次及可取得的服务重启标识，不能只用公开目标 ID。

| 计算 | 输入/规则 | 输出示例 |
| --- | --- | --- |
| `RATE` | 计数差 / 实际间隔 | 100→130，15 秒：2/秒；100→100：0/秒 |
| `DELTA` | 本周期计数差 | 100→130：30 |
| `REDIS_HIT_PERCENT` | `[hits, misses]` 增量中 hits 的比例 | 增量 10、10：50% |
| `MYSQL_HIT_PERCENT` | `1 - physicalReads / logicalReads` 的增量比率 | 增量 2、20：90% |
| `CPU_SINGLE_CORE_PERCENT` | `[process user seconds, process system seconds]` 的增量之和 / 墙钟秒数 × 100 | 增量 15、15，墙钟 15 秒：200% |
| `percent` | 容量或配置限额的分子 / 正分母 × 100 | 0/100：0%；10/0：不适用；缺失输入：无效值 |

首样本、任一计数下降、身份变化或间隔严格超过三个采集周期时返回 `WAITING_SAMPLE` 并建立新基线。周期 15 秒时，45 秒间隔可计算，45 秒加 1 纳秒必须重建。同代次的相等/倒退时间不覆盖较新的基线；空值或负计数返回 `INVALID_VALUE` 并保留有效基线。

命中率分母为零返回 `NO_REQUESTS`，不会变成 0%。MySQL 物理读增量大于逻辑读增量返回无效值并更新原始计数基线，下一周期可恢复。Redis CPU 不包含子进程计数，不截断到 100%；使用量超过配置上限时容量比例也保留超过 100% 的实际结果，不擅自钳制。

## 有界内存和结果接纳

`MonitoringCounterStore` 每个计算系列只留一份基线；系列键必须包含来源、采集类别和原生对象范围，身份代次放在样本值中，换连接不会不断增加键。目标上限来自 `monitor.max-targets`，每目标最多 5000 个计数系列；超限返回 `BUSY`。键最长 2048 字符，不能包含控制字符。

权威清单确认对象退役时，采集协调层用 `removeSeries(targetId, seriesId, calculation)` 精确释放对应计数基线；最后一个系列删除后释放目标槽位。其他活跃对象/公式的基线继续保留。#24 必须同时协调快照和计数的退役，并在任何基线写入前拒绝超时或退役任务，避免旧任务重新创建已释放的系列。

`MonitoringSnapshotStore` 每目标最多保留 5000 个当前指标，来源上限为 `monitor.max-targets`；普通/容量及来源分别记序号。新增目标/来源/指标超限时整次更新被拒绝，不留下半份结果。完整清单可退役旧资源，为节点替换释放空间。配置身份代次切换清空当前快照，删除目标和清空操作释放状态。

调度器先激活目标代次，接纳时拒绝旧代次、重复/倒退序号、倒退采样时间、超过截止时间和无效时间范围。每个来源保留自己的服务探测；同采样时间的已接受新结果能够替换旧结论。调度器必须在写入计数基线前也拒绝退役代次/超时结果。重启后内存清空，不创建指标表、历史接口或趋势记录。

完整示例响应见 [middleware-monitoring-metrics-example.json](middleware-monitoring-metrics-example.json)。`MonitoringMetricExampleTest` 将其按实际 Java 契约反序列化，验证 CPU 200%、真实零值、缺失比例及失败保留的旧容量时间戳。
