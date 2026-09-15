# MinIO 服务健康与配置桶访问

监控沿用现有附件端点或已创建的 `MinioClient`，分别记录端点存活、集群读写就绪和配置桶访问。三者有独立的结果、HTTP 状态码、采样时间和毫秒耗时；某个桶权限不足不会改写服务探测结论。

## 结果含义

| 指标 | 原生来源 | 能证明的范围 |
| --- | --- | --- |
| `minio.health.live` | `HEAD /minio/health/live` | 当前端点存活；200 为真，429 或 503 为假 |
| `minio.health.read.ready` | `HEAD /minio/health/cluster/read` | 原生集群读就绪；200 为真，503 为假 |
| `minio.health.write.ready` | `HEAD /minio/health/cluster` | 原生集群写就绪；200 为真，503 为假 |
| `minio.bucket.accessible` | 配置桶的区域查询及 `HeadBucket` | 当前凭据能否执行桶检查；不证明对象读写权限 |

健康结论按照 [MinIO 固定版本的健康处理器](https://github.com/minio/minio/blob/RELEASE.2025-09-07T16-13-09Z/cmd/healthcheck-handler.go) 分开解释。`live=200` 只使服务探测在 `ENDPOINT` 范围内可用，不能代替两个 `CLUSTER` 就绪结果。健康接口缺失、权限不足、TLS 校验失败分别给出原因，服务为未知；已证实的 DNS、连接或套接字失败标为连接失败。`live` 明确返回 429 或 503 时服务降级。

桶检查 200 表示可访问，404 表示当前请求未找到或不可见，403 表示权限不足。S3 的无正文错误不能证明桶在全局不存在。[HeadBucket 原生说明](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadBucket.html) 也不承诺对象读写能力。只有 S3 客户端的来源仍可返回桶结果，原生 MinIO 健康能力显示不支持，服务保持未知。

各健康指标附带 `.http.status`、`.latency.ms`；桶指标另有 `minio.bucket.http.status`、`minio.bucket.latency.ms`。网络调用未取得响应时状态码缺失，不填零。单项失败保留其他实际取得的结果。

## 来源与范围

```yaml
monitor:
  enabled: true
  targets:
    - id: attachments
      type: MINIO
      enabled: true
      connection-source: chat.attachment
      scope:
        buckets: [project-attachments]
```

`chat.attachment` 引用已有附件配置，沿用 `endpoint`、`secure`、静态凭据；解析和采集不初始化聊天附件客户端。聊天关闭时该来源不启用。独立 S3 部署可把 `connection-source` 换成已创建的 `MinioClient` Bean 名称。

显式 `scope.buckets` 优先；为空时只使用来源已经配置的附件桶。两者皆空则不检查任何桶，不发出桶列表或对象列表请求。范围去重并受 `monitor.limits.buckets` 和 100 桶硬上限约束。`minio.buckets.configured/requested/limit` 及 `minio.coverage.complete/truncated` 说明覆盖范围；纳入范围不保证在总预算到期前已发出请求。

## 请求与资源约束

普通采集沿用默认 15 秒周期、5 秒含排队预算和 45 秒有效期。健康请求固定路径；桶名严格校验，未配置桶不访问。地域已知时直接执行桶 HEAD，未知时仅向同一个配置端点查询该桶的地域，再按原生签名规则检查桶；区域响应有长度上限并禁用 XML 外部实体。

采集使用现有静态 S3 凭据签名，保留受支持客户端的 TLS 和连接策略。动态凭据、无法证明等价的路由或自定义行为明确不支持。请求不跟随重定向，不修改服务权限或业务客户端设置；超时和取消仅终止本次监控请求，资源释放后才完成清理。

采集不创建桶、写对象、遍历对象，也不从 S3 响应猜测 CPU、内存、卷容量或桶总量。容量任务当前返回不适用；已开放原生 metrics 的条件采集归 #39。

## 验证

契约测试核对成功、拒绝、缺失、部分可采、范围上限、超时和服务/桶状态隔离。真实回环 HTTP/TLS 黑洞测试验证超时或取消后实际套接字关闭、清理注册释放。目录测试确认注册适配器不会初始化任何懒加载业务客户端。

`MonitoringMinioAdapterIntegrationTest` 默认使用固定 `RELEASE.2025-09-07T16-13-09Z` 容器，接入 MinIO CI。显式设置 `monitor.minio.test.binary` 可启动本地独立 MinIO 程序，使用随机端口、临时凭据及自有数据目录；测试结束清理自有实例。测试准备阶段创建数据，采集期间通过原生响应与请求白名单验证只读行为。CI 补充真实受限账号的桶权限验收；本地版本和实际覆盖范围以测试报告为准。
