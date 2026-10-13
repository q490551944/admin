# Kafka 集群与限定主题元数据

监控使用现有 KafkaAdmin、生产者工厂或消费者工厂解析出的原生连接参数，建立自有 AdminClient。业务对象只提供连接配置，采集不会创建消费者、加入消费组、提交位点或更改主题。

```yaml
monitor:
  enabled: true
  allowed-user-ids: [1001]
  targets:
    - id: kafka-main
      type: kafka
      enabled: true
      connection-source: spring.kafka
      scope:
        topics: [orders, payments]
        consumer-groups: [billing]
```

仅服务端配置的 topics 进入统计和输出，空列表不枚举全部主题。API 读取调度快照，刷新页面不会增加 Kafka 查询。普通周期、预算及有效期沿用公共默认值：15 秒、含排队的 5 秒、指标及服务探测 45 秒。

## 观测范围

| 范围 | 主要指标 |
| --- | --- |
| 集群连接观测 | `kafka.probe.duration`、`kafka.brokers.discovered`、`kafka.brokers.observed`、`kafka.cluster.identity.known` |
| 服务端配置范围 | `kafka.topics.configured` / `.requested` / `.observed`，`kafka.partitions.observed` / `.no_leader` / `.under_replicated` / `.state.known` |
| 覆盖与限额 | `kafka.coverage.complete` / `.truncated`，`kafka.limits.topics` / `.partitions` / `.nodes` / `.metrics` |
| 单主题 | `kafka.topic.partitions`、`kafka.topic.partitions.observed`、`kafka.topic.coverage.complete` |
| 单分区 | `kafka.partition.leader.id` / `.leader.available` / `.replicas` / `.isr` / `.under_replicated` |

`DescribeCluster` 的成功响应说明连接及原生元数据请求可用，不代表整个集群都健康。返回的 broker 数标为“可发现”，不把当前可见节点当作健康节点总数。集群身份是否可识别单独表达，原始 cluster ID、节点主机和端口不进入页面数据。

指定主题逐项处理读取结果，一个主题的 ACL 拒绝或不存在不会抹掉其他成功项。每个成功主题保留原生分区数及实际展示数，分区记录 leader 是否可用、leader ID、分配副本数和 ISR 数。无 leader 与 ISR 少于已分配副本分别表达；同步副本不足不能解释成已经低于 `min.insync.replicas`。

覆盖是否完整与是否因上限截断分别展示。节点、主题和分区遵守 `monitor.limits`，每次本适配器输出最多 4500 个指标序列，主题/分区的有效行数还受这个总量限制。原生 Admin 元数据接口不提供分区分页：分区展示上限不会让原生响应只返回前几个分区。

未知主题显示 `NOT_APPLICABLE`，ACL 拒绝显示 `UNAUTHORIZED`，超时和无效原生数据另行标记，不伪造零。确定性的展示截断提供完整的本次展示清单，以退役上轮超出当前范围的记录；分区清单读取失败时保留旧证据，旧值按有效期显示为过期。公共存储不会仅因 TTL 到期就删除记录。

## 原生连接与关闭

当前生命周期实现审计并固定于项目已有 Kafka 客户端 3.6.1，保持原生 DNS、TLS 信任库、证书和端点验证配置。SASL 支持显式 JAAS 中的内建 PLAIN/SCRAM；未知登录模块、凭据刷新、全局隐式 JAAS、自定义回调/安全提供器/配置提供器和 SSL engine 明确不支持。不会改用其他认证机制或放宽 TLS。

自有客户端的请求和连接建立超时受采集剩余预算限制，原有更短超时仍有效。取消回调只发出短时关闭请求；原采集线程继续等待该客户端内部线程实际结束，再释放资源预留及工作槽。Kafka 3.6.1 的 `close(Duration.ZERO)` 内部使用无限等待的 `join(0)`，因此只能放在原采集线程作为退出确认，不能把它当成非阻塞取消。

标准 DNS 等底层阻塞可能晚于预算退出，期间继续占用原工作槽，不启动替代任务。退出确认可证明内部线程已结束；Kafka 自身会吞掉并记录部分内部资源关闭异常，采集器不声称能观测所有这类异常。原生配置日志中的 JAAS/密码使用 Kafka 的隐藏类型，测试会核验；适配器不记录原始设置或带凭据的异常原因。

只调用 `describeCluster`、限定名称的 `describeTopics` 和下述限定组/分区的位点读取，禁用授权操作枚举，不调用 `listTopics` 或 `listConsumerGroups`。当前客户端对旧 broker 可能回退读取全主题元数据以兼容旧协议，但不会自动创建主题，最终只发布配置范围内的名称。本项不承诺未验收版本具有与 Kafka 3.8.0 完全相同的能力。

## 指定消费组的提交进度差

配置 `consumer-groups` 后，用同一配置 topic 分区的 `listOffsets(latest)` 末端位点减 `listConsumerGroupOffsets` 已提交位点。两个原生批次在等待前均已发出，共享本次剩余预算；按组、分区收集已完成的结果，一个超时不会丢弃其他已经成功的结果。原生提交请求逐组显式传入分区列表，空 topic、空分区或空组范围均不请求位点，不使用 null 范围枚举全部提交。

| 范围 | 指标 |
| --- | --- |
| 组/分区 | `kafka.group.partition.end_offset`、`.committed_offset`、`.lag`、`.lag.anomaly` |
| 配置范围完整组值 | `kafka.group.lag.total`、`.maximum` |
| 已知分区局部值 | `kafka.group.lag.known.total`、`.known.maximum` |
| 覆盖与异常 | `kafka.group.partitions.expected`、`.covered`、`kafka.group.coverage.complete`、`kafka.group.lag.anomalies` |
| 组范围限额 | `kafka.groups.configured`、`.requested`、`kafka.limits.groups` |

这些 COUNT 值是**提交进度差**，不是实际尚未处理的消息总数或处理耗时；末端位点沿用 Admin 默认的 READ_UNCOMMITTED 读取，两次原生读也不构成事务快照。分区范围 ID 为 `消费组/topic#分区号`，不借用节点字段表示消费组。

完整总和与最大值只在所有配置分区均有可用、非负差值且没有条目截断时返回。缺失项使完整值成为 `PARTIAL_SCOPE`；局部小计/最大值只覆盖已成功读取且非负的分区，必须结合覆盖数，不能冒充完整总量。全未知时局部小计也未知。原生零提交位点和零差值保留为零；空配置范围的总和为零、最大值不适用。

`NO_COMMITTED_OFFSET` 专门表示原生返回该分区没有提交位点，不从零开始代算。Kafka 3.6.1 会从结果 Map 省略部分分区错误：省略键标为 `FAILED`，不能据此断言没有提交。组级 ACL 拒绝标为 `UNAUTHORIZED`，末端读取失败独立保留原因。负差保留负数并将 `.lag.anomaly` 设为 true，该分区不计入可用差值覆盖数或局部小计，完整组值保持未知。

期望分区数来自全部配置 topic 的原生元数据，分区展示截断仍保留该分母；任一 topic 清单失败或 topic 被省略时分母未知。组数和组×分区序列也纳入既有 4500 指标预算，达到预算时确定性缩小展示范围并标记截断。采集不加入消费组、不构造消费者、不提交或修改位点。

## 验证

本地契约及真实客户端生命周期测试：

```powershell
.\mvnw.cmd '-Dtest=MonitoringKafka*Test,MonitoringCatalogTest' test
```

真实 Kafka 3.8.0 验收：

```powershell
.\mvnw.cmd '-Dtest=MonitoringKafkaAdapterIntegrationTest' '-Dmonitor.test.type=kafka' test
```

CI 调用相同入口，运行时不可用会失败。真实测试只启动临时容器，通过自有主题、精确 topic ACL 和 AdminClient 验证原生映射、配置范围、截断、主题不存在与局部拒绝；测试准备的写入和授权操作不属于生产采集器。单 broker 场景验证健康 leader/ISR，无 leader 和副本不足的非零分支由契约测试覆盖。

真实位点验收另通过 Admin 在自有临时容器准备已知、无提交及负差的组，设置精确组 ACL，逐项对比原生返回，确认截断范围、资源关闭和采集前后提交 Map 完全不变。所有写入及 ACL 改动均在测试准备/清理阶段，生产连接只提供上述只读操作。

## 日志副本与原生卷容量

容量任务采用独立 60 秒周期与 180 秒有效期。只用配置 topic 元数据定位其副本所在 broker，再调用 `describeLogDirs`，不执行集群探测或消费组读取，也不更新普通样本时间。空 topic 范围不连接。现有 Kafka 3.6.1 已提供 `LogDirDescription.totalBytes()` / `usableBytes()`，不升级项目；旧协议的空字段逐项标为 `UNSUPPORTED`，可读日志副本仍保留。

| 范围 | 主要指标 |
| --- | --- |
| broker | `kafka.broker.log_directories.discovered` / `.observed`，`.replicas.expected` / `.replicas.current.covered`，`.log_coverage.complete` |
| 目录 | `kafka.log_directory.scoped_replica.bytes`、`.known_replica.bytes`、`.replicas.observed` / `.replicas.future`、`.coverage.complete` |
| 分区副本 | `kafka.replica.log.bytes`、`.future`，附 broker ID 和目录标识 |
| 目录所在卷 | `kafka.log_directory.volume.total.bytes`、`.usable.bytes`、`.identity.known` |
| 容量覆盖 | `kafka.capacity.topics.configured` / `.requested`、`.partitions.observed`、`.coverage.complete` / `.coverage.truncated`、`.limits.*` |

副本字节来自 `ReplicaInfo.size()` 的日志段大小，current/future 副本分别展示；同一分区在不同 broker 或迁移目录的副本属于不同物理复制行。它不是整盘使用量，不包含全部文件系统开销，也不是排除副本后的逻辑数据大小。API 不支持按 topic 过滤 broker 的原生日志目录响应，内部可能包含其他 topic；发布的副本值只保留配置范围，不枚举其他 broker。目录绝对路径转换为稳定 SHA-256 标识，API 不输出原始路径。

卷值只采用原生 totalBytes/usableBytes。多个目录可能共享卷，原生响应又没有可靠卷身份，因此 `.identity.known` 为 false；不因相同容量推断同卷，不跨目录或 broker 求和，不从副本日志字节推导磁盘使用率。负容量、usable > 有效 total、目录存储错误、权限拒绝均明确表达；合法原生零值保留为零。

容量最多输出 500 条序列，为既有普通采集的最多 4500 条保留空间。有效上限同时受配置和该预算约束：最多 100 个 topic、200 个分区、25 个 broker；目录与副本行共享剩余空间。确定性截断退役已省略行并显示不完整；未知目录/分区清单保留旧证据按原 TTL 过期。原生接口无分页，输出上限不保证原生响应只含这些行。

`kafka.broker.cpu.percent` 和 `.memory.bytes` 明确 `UNSUPPORTED`：当前 Admin 协议不提供 broker 进程资源，不能将 admin 应用或 AdminClient 自身资源代入，也不增加 JMX/Exporter。真实 Kafka 验收在自有容器配置两个共享文件系统的日志目录，对比同一原生响应中的卷与配置副本值，并验证日志目录接口所需集群 DESCRIBE 权限的精确 ACL 拒绝、独立 TTL、关闭和采集前后 topic/提交状态不变。

## 语义依据

- [Kafka 3.6.1 AdminClient 实现](https://github.com/apache/kafka/blob/3.6.1/clients/src/main/java/org/apache/kafka/clients/admin/KafkaAdminClient.java)：超时、关闭等待及限定主题兼容行为。
- [Kafka 3.8 授权响应](https://github.com/apache/kafka/blob/3.8.0/core/src/main/scala/kafka/server/AuthHelper.scala)：DescribeCluster 与授权操作字段的关系。
- [Kafka ACL](https://kafka.apache.org/38/security/authorization-and-acls/)：主题的 DESCRIBE 权限。
- [Kafka 3.6.1 OffsetFetch 结果处理](https://github.com/apache/kafka/blob/3.6.1/clients/src/main/java/org/apache/kafka/clients/admin/internals/ListConsumerGroupOffsetsHandler.java)：null 提交位点及省略分区错误的语义。
- [KIP-827](https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=211884837)：DescribeLogDirs v4 的 total/usable 字段属于日志目录所在卷。
