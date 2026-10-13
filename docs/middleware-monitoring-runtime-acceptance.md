# 真实采集运行链路与预算验收

关联 #47。矩阵在已有六类服务作业中执行原生适配器测试，再执行 `MonitoringRuntimePipelineIntegrationTest`。Redis 作业另外执行 `MonitoringRuntimeBudgetIntegrationTest`。真实路径为：自有服务与已有客户端 → 生产连接解析器 → 生产调度器及普通/容量线程池 → 共享快照 → 生产认证过滤链和 HTTP API。

测试应用使用独立 H2 员工库和真实 Cookie/CSRF 登录，`chat.enabled=false`；被监控的 MySQL 等服务均由 Testcontainers 单独创建。H2 员工库不会替代监控源。配置、随机端口与凭据由夹具内部生成，不接受外部 endpoint。生产部署无需这些测试容器或控制端点。

## 固定兼容范围

| 类型 | 实际验收服务 | 原生能力和分支 |
| --- | --- | --- |
| MySQL | 8.0.36 | Hikari/Druid、配置库容量、可见子集/不可见库、表预算；缺失字段与重置边界由契约测试补充 |
| Redis | 7.2.4 | Lettuce/Redisson、INFO/ACL、CPU/内存/AOF；20 个独立实例的暂停超时、恢复与容量隔离 |
| Kafka | native 3.8.0（客户端 3.6.1） | 精确主题与消费组、ACL、提交偏移、共享卷日志目录、原生容量条件分支 |
| MongoDB | 6.0.11 | 原生进程、只读账号、限定库 dbStats、共享文件系统；路由和缺失字段契约 |
| Elasticsearch | 8.10.4 | HTTPS/错误信任、账号/索引权限、红黄状态、真实双节点/共享路径/暂停节点 |
| MinIO | RELEASE.2025-09-07T16-13-09Z | S3-only、受限桶账号、默认 JWT 的普通/容量统计、认证拒绝、原生 restart 后基线 |

只声明上述固定版本。其他版本没有在该矩阵运行，不能据此宣称兼容全部同系列版本。字段映射、缺失、计数重置和部分范围契约与对应适配器测试共同验收；不会通过扩大生产权限、打开匿名统计、增加端口或安装组件使测试通过。

## 运行链路证明

每类夹具准备自己的哨兵数据及客户端，由生产解析器获得目标。测试通过调度器的受控触发执行两种采集，仍使用实际工作线程、5 秒含排队预算和真实清理协调；不直接调用适配器。HTTP 详情逐项与该原生调度批次的共享存储样本比较，另核对稳定原生参照：MySQL 缓冲池配置、Redis maxmemory、Kafka 主题分区、MongoDB 引擎、Elasticsearch 文档数、MinIO 进程启动时间。

五个独立登录会话读取目录/快照/详情时，采集调用数保持不变。受控调度仅用于使这一计数证明可重复；#48 使用真实周期调度验证浏览器旅程。采集前准备与末尾哨兵校验分开，运行采集期间没有测试写入。

## 二十目标与读者预算

Redis 预算用例创建 20 个端点不同的真实 Redis 实例，经过解析器得到 20 个目标。保持默认普通8槽、容量2槽、5秒共享预算；暂停一个自有实例，验证其超时不影响其余19个目标的普通/容量采集及恢复。报告保留实际峰值、工作/清理线程数、超时批次完成时间与 API 延迟。

五个独立 Cookie 会话并发执行100组“总览+详情”HTTP请求，包含真实身份SQL检查、完整传输与JSON解析。每组P95≤1秒是比单个请求更严格的门禁，页面读取不增加采集次数。既有 `MonitoringReadPerformanceTest` 继续单独验证20目标×5000指标的极限序列化压力；其预填充快照仅用于极限读负载，不能替代上述真实运行链路。

## 执行与产物

在可用 Linux Docker 环境中运行（控制令牌是本次新随机值，不能使用生产凭据）：

```bash
export MONITOR_E2E_CONTROL_TOKEN="$(openssl rand -hex 32)"
./mvnw -B -Dtest=MonitoringRuntimePipelineIntegrationTest,MonitoringRuntimeBudgetIntegrationTest -Dmonitor.test.type=redis test
```

将类型替换为 mysql/kafka/mongodb/elasticsearch/minio 可只执行对应真实链路；20目标预算只在 Redis 作业执行。本机无 Docker 时只运行受影响的调度契约和 `MonitoringRuntimeWiringTest` 接线验证，不能把真实用例跳过结果当成验收通过。

CI 按类型保存 `monitor-runtime-<type>.json`、`monitor-runtime-budget.json`、Surefire报告和脱敏环境日志，沿用15分钟作业超时以及自有实例/数据的清理。报告不包含凭据、Bearer、原始地址或业务数据；已有聊天、API极限性能和浏览器回归继续作为合入门禁。
