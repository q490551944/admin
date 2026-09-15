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

只调用 `describeCluster` 和限定名称的 `describeTopics`，禁用授权操作枚举，不调用 `listTopics`。当前客户端对旧 broker 可能回退读取全主题元数据以兼容旧协议，但不会自动创建主题，最终只发布配置范围内的名称。本项不承诺未验收版本具有与 Kafka 3.8.0 完全相同的能力。

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

lag 与日志目录由 #32、#33 接续。

## 语义依据

- [Kafka 3.6.1 AdminClient 实现](https://github.com/apache/kafka/blob/3.6.1/clients/src/main/java/org/apache/kafka/clients/admin/KafkaAdminClient.java)：超时、关闭等待及限定主题兼容行为。
- [Kafka 3.8 授权响应](https://github.com/apache/kafka/blob/3.8.0/core/src/main/scala/kafka/server/AuthHelper.scala)：DescribeCluster 与授权操作字段的关系。
- [Kafka ACL](https://kafka.apache.org/38/security/authorization-and-acls/)：主题的 DESCRIBE 权限。
