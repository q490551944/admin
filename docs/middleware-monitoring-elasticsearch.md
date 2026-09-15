# Elasticsearch 集群与索引监控

采集器使用应用已经初始化的原生 RestClient。HTTPS 信任、认证、代理、节点选择和连接池继续由原客户端处理。页面读取后台快照；关闭监控或没有可用来源时不会初始化客户端。

```yaml
monitor:
  enabled: true
  allowed-user-ids: [1001]
  targets:
    - id: search-main
      type: elasticsearch
      enabled: true
      connection-source: elasticsearchClient
      scope:
        indices: [products, orders]
```

连接来源须与实际 bean 对应。indices 只接受具体索引名称；不展开通配符、别名或 data stream，不通过 cat/list 接口枚举索引。空列表仍可读取集群健康和节点线程池，但不请求全部索引。

## 指标口径

| 指标 | 范围及含义 |
| --- | --- |
| `elasticsearch.probe.duration` | CLUSTER；现有客户端完成健康端点请求的毫秒数 |
| `elasticsearch.cluster.health` | CLUSTER；原样保留 green / yellow / red |
| `elasticsearch.cluster.nodes` | CLUSTER；健康响应中的节点数 |
| `elasticsearch.cluster.unassigned_shards` | CLUSTER；健康响应中的未分配分片数 |
| `elasticsearch.index.docs.count` | INDEX；已响应主分片的 Lucene 文档数，包含 nested 文档，受 refresh 影响 |
| `elasticsearch.index.query.total/rate` | INDEX；已响应主副本的 query 阶段操作累计及每秒速率，跨分片查询可能计多次 |
| `elasticsearch.index.indexing.total/rate` | INDEX；已响应主分片的写入操作累计及每秒速率，不重复累计副本执行 |
| `elasticsearch.index.store.primary.bytes` | INDEX；已响应主分片 Lucene store 字节数 |
| `elasticsearch.index.store.total.bytes` | INDEX；已响应主副本 Lucene store 字节数 |
| `elasticsearch.thread_pool.<pool>.rejected` | NODE；search、search_coordination、write 三种线程池拒绝任务的累计数 |

健康颜色描述分片分配：green 表示全部主副本已分配；yellow 表示主分片已分配而副本不全；red 表示存在未分配主分片。三个颜色都可证明健康端点响应，不能单凭颜色推断 CPU、内存、文件系统或业务请求健康。查询速率不是业务 QPS，store 字节数不是节点整盘使用量。本项不输出节点 CPU、JVM 堆或 OS/fs 资源指标。

普通周期默认 15 秒，store 独立按 60 秒容量周期读取，各自有效期为三个周期。所有端点共享含排队的 5 秒预算，不为后续请求重新分配预算。

## 范围、缺失与速率

每个索引附带 `elasticsearch.index.coverage.complete`。配置范围另有 configured/requested/observed 数量、节点展示数、有效上限、complete 和 truncated 指标。原生 `_nodes` 计数保留节点响应覆盖证据。

配置上限还受单次 128 个索引、128 个节点及最多 4500 个指标约束；内部 shard 证据受 partitions 配置和 2000 行上限约束。触及上限会明确标记截断。节点端点的网络响应仍受统一字节上限约束，并非服务端分页。部分响应中的累计数和容量只代表实际返回的分片副本；覆盖不完整时不计算完整索引速率。

速率使用公共计数器存储，以实际采样间隔计算增量。私有基线组合绑定身份、索引 UUID、分片路由及参与节点的进程启动时间。普通采集在索引统计前后读取已知节点的 `jvm.start_time_in_millis`，只有参与节点身份完整且两次一致才接受速率样本；这个字段仅作为服务端重启证据，不导出为 JVM 资源指标。

首次、索引重建、观察到的路由变化、节点重启或计数回退需要重新建立基线。超时、取消、快照拒收及不完整样本不推进基线。两个采样间发生而未被观察到的分片迁出后迁回，且累计值已超过旧值时，现有原生字段不能保证识别。

401/403 标为 `UNAUTHORIZED`；证书或 TLS 协议错误标为 `TLS_FAILED`，服务可用性保持 UNKNOWN。缺字段、无法可靠复用的设置、非法数据、超时和限流分别显示对应原因，均不补零。各端点独立降级；例如缺少节点启动信息只影响速率，仍可保留可读取的累计值。

## 原生客户端与清理边界

本实现审计于项目已有 Elasticsearch REST Client 8.10.4 和 Apache HttpAsyncClient 4.1.5。来源若包含 RestClientTransport，会确认其引用同一个 RestClient，并复制原 RequestOptions 中的请求头、参数、警告策略及请求配置；固定采集参数存在冲突时返回不支持。支持该版本原生 NoopInstrumentation 和 OpenTelemetryForElasticsearch，后者的请求钩子只读取追踪属性，不改变认证或路由；监控的底层 GET 不创建 Java API 层 span。自定义 transport、instrumentation、响应消费者或无法证明有效的继承超时不猜测替代配置。

每次请求复制实际配置，只缩短连接、读取和连接池等待超时。原有更短超时保留。固定 GET 请求包括 cluster health、精确索引 stats、nodes thread_pool stats，以及对已观察节点 ID 的 nodes jvm info；不调用写入、刷新、清空缓存或修改集群设置。

单个响应的原始正文与 gzip 解压结果均最多 2 MiB，并保留客户端原本更小的限制；chunked 响应同样受限。JSON 额外限制 32 层深度、50000 个 token、字符串和数字长度，拒绝重复键与尾随数据。这些限制在构造公共指标之前生效，压缩错误响应也不会在原生异常构造时无限解压。

采集只拥有本次 Cancellable 和响应消费者。取消调用原生 abort；原线程等待 dispatch 返回、终态回调及所有原生 consumer 的最终 close 后才释放槽位，不关闭业务 RestClient 或 transport。DNS、用户回调或原生清理仍可能延迟返回，期间保留原槽，不创建替代线程。HTTP abort 不保证服务端已经停止处理该只读请求。

采集器异常不携带原始响应、地址、凭据或 cause。原客户端自身已有的 debug/tracer/warning 日志仍由应用日志配置管理；监控不更改全局日志或共享连接池设置。

## 验证

```powershell
.\mvnw.cmd '-Dtest=MonitoringElasticsearchConnectionsTest,MonitoringElasticsearchAdapterTest,MonitoringElasticsearchNativeDeadlineTest,MonitoringMetricContractTest,MonitoringResponseProjectorTest,MonitoringCatalogTest' test
.\mvnw.cmd '-Dtest=MonitoringElasticsearchAdapterIntegrationTest' '-Dmonitor.test.type=elasticsearch' test
```

本地原生测试使用自有回环 HTTP/TLS 服务，验证真实套接字超时和取消、证书验证、共享客户端可继续读取，以及 chunked/gzip 响应边界。集成测试使用自有 Elasticsearch 8.10.4 容器和临时证书，验证真实索引、权限、健康颜色与只读前后状态；所有建数、角色配置和清理只作用于该测试容器。

口径参考：[Cluster health](https://www.elastic.co/guide/en/elasticsearch/reference/8.10/cluster-health.html)、[Index stats](https://www.elastic.co/guide/en/elasticsearch/reference/8.10/indices-stats.html)、[Nodes stats](https://www.elastic.co/guide/en/elasticsearch/reference/8.10/cluster-nodes-stats.html)。
