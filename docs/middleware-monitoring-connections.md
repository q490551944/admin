# 监控连接来源

监控目标在 `monitor.targets` 中显式声明，`connection-source` 引用已经存在的应用对象。解析器只读取 Spring 单例和连接元数据，不执行连接、探活、查询、DNS 解析，也不创建懒加载 Bean 或解引用 `FactoryBean`。全局或目标关闭时不读取对应连接。

已解析表示连接来源和必要配置可识别，不表示服务健康。探活失败不能删除目标或改变其公开 ID。无法安全读取的代理、自定义工厂或缺失来源保留为配置缺失，服务端原因分别为 `UNSUPPORTED_SOURCE`、`SOURCE_MISSING` 或 `AMBIGUOUS_SOURCE`。

## 来源选择

可以填写实际 Bean 名称；下表的语义别名用于常见来源。别名匹配多个已有候选时只接受唯一的 `primary`，否则报告歧义。显式 Bean 名称优先，不按枚举顺序选择连接。

| 来源别名 | 实际读取对象 |
| --- | --- |
| `spring.datasource` | 已存在的 DataSource；支持 Hikari / Druid 的生效 URL 和属性 |
| `redisConnectionFactory` | 已存在的 Lettuce / Jedis / RedissonConnectionFactory |
| `redisson` | 已存在的 Redisson 实例在 ServiceManager 中保存的生效配置 |
| `spring.kafka` | 已存在的 KafkaAdmin；生产者、消费者工厂使用各自 Bean 名称 |
| `spring.data.mongodb` | 已存在的 MongoClient 的最终 settings，包含 customizer 修改 |
| `spring.elasticsearch` | 优先已有 ElasticsearchClient，或已有底层 RestClient |
| `chat.attachment` | 已绑定的 ChatProperties.Attachment，沿用附件存储的 endpoint / secure 规则 |

Kafka 使用工厂当前有效配置（包含 bootstrap supplier 的结果），不使用原始 YAML。MongoDB 不读取 customizer 执行前的 settings 模板。MinIO 附件客户端是懒字段，解析配置不触发文件存储业务方法；聊天业务关闭时不把其附件配置认作启用的连接来源。已有连接对象或服务端配置引用被保留供后续只读采集使用。

## Redis 与 Redisson

分别声明两个目标引用两个来源。能够证明共享同一个实际客户端时合并；独立的普通 TCP 单节点连接只有主机文本（忽略大小写）、端口、DB、ACL 用户和静态密码全部一致时才合并。未指定的 ACL 用户与 `default` 等价，空密码与未设置密码等价。

不进行 DNS 解析，不把 `localhost`、`127.0.0.1`、`::1` 当作必然同一目标。不同 DB、身份、密码保留为不同目标。TLS、动态凭据、自定义路由、集群、哨兵、主从和未知拓扑仅在能够证明共享客户端时合并，避免仅根据地址误判权限或拓扑等价。

项目当前无参 `Redisson.create()` 的配置由 Redisson 自身决定，不能假定继承 Spring Redis 配置。`Redisson.getConfig()` 返回创建时的原始可变配置，连接管理器持有单独副本；解析器读取后者，也不为了检查配置而调用会修改原对象的 Redisson Config 复制构造函数。未知 RedissonClient 实现保留为不可解析。

已启动的 Lettuce 单节点读取现有 RedisClient 的 URI，Jedis 单节点读取现有标准连接池保存的端点和认证设置；不会使用启动后被修改的原始配置推断运行目标。当前对已启动的 Lettuce/Jedis 多节点、无标准连接池或无法安全读取的实现报告不可解析。固定字段的兼容范围由项目当前客户端版本和相应测试约束，读取失败不会触发连接或回退到不确定的端点。

合并后的公开 ID 取成员配置 ID 按字典顺序排列的第一项；服务端保留全部成员 ID 和来源。来源枚举顺序、显示名称或网络故障不会改变该 ID。配置身份发生变化应使采集计数基线失效。

## 凭据与采集范围

连接身份、TLS 设置、凭据和客户端引用仅在服务端 `ResolvedConnection` / `ResolvedTarget` 中保存。这些对象不作为 API DTO，`toString()` 也不包含连接地址或凭据。公开目录只从 `MonitoringTarget` 投影安全 ID、显示名称、类型、状态、来源引用和配置范围。

合并目标仍保留每个 `SourceBinding` 自己的凭据及范围。公开目录中的范围并集只用于说明声明范围；采集必须使用各来源自己的范围，不能拿一个来源的凭据访问另一个来源授权的范围。空范围不授权枚举所有资源。

示例见 [middleware-monitoring-config.yml](middleware-monitoring-config.yml)。示例包含七条目标声明、六类中间件；两条 Redis 声明是否合并取决于实际连接。

## 验证

`MonitoringConnectionResolverTest` 覆盖六类来源、禁用、懒初始化、歧义和范围隔离；两个 `ConnectionInspectorTest` 覆盖生效配置、身份差异及脱敏。`MonitoringConnectionResolutionIntegrationTest` 在 `monitor.test.type=redis` 时使用隔离 Redis 验证已启动的 Lettuce / Redisson，检查原始配置变更后仍读取实际运行目标，并验证同实例合并和不同 DB 保留。该测试接入 Redis CI，不接触开发机已有服务。
