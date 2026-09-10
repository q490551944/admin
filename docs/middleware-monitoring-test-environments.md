# 中间件监控隔离测试环境

关联 Issue：#46（MON-027）。本环境供后续采集器集成测试复用，只属于测试代码和 CI，不进入应用的生产部署清单。

**真实服务验收以六项 CI 作业和对应报告为准。** [Monitoring test environments 工作流](https://github.com/q490551944/admin/actions/workflows/monitor-environments.yml) 按类型执行就绪、数据隔离和清理验证。本机 Windows 尚无可用 Docker；本地编译、契约测试或默认跳过结果均不能代替真实服务验收。

## 范围与隔离约定

- 使用 test scope 的 Testcontainers `2.0.5` 启动临时 Linux 容器。可以单独选择 MySQL、Redis、Kafka、MongoDB、Elasticsearch 或 MinIO，无需同时运行六类服务。
- 每次运行拥有独立的容器、随机映射端口、测试数据和日志目录。宿主机测试端口绑定到 `127.0.0.1`，数据不复用业务目录或既有数据库。
- 入口只接受中间件类型，不接受业务 endpoint、生产凭据或任意已有实例地址。连接参数由本次创建的容器生成。
- 数据准备、权限准备及清理由测试代码负责。只有本次拥有的容器和测试数据可以被清理；保留脱敏诊断日志。不要使用清理整个 Docker 主机的命令。
- 准备测试数据所需的管理权限与采集器只读权限分开。测试中的创建数据库、topic、用户、索引或桶等操作，不属于生产监控采集路径。
- 本 Issue 验证环境的可用性与隔离能力。各采集器的字段映射、拒绝权限、故障恢复及只读行为由所属采集器 Issue 验证；完整调度/API 和浏览器旅程分别由 #47、#48 验收。

## 固定测试服务版本

以下是本测试环境选择的版本标签，不是项目生产实例的版本清单，也不代表兼容同系列所有版本。

| `monitor.test.type` | 测试镜像 | 来源 |
|---|---|---|
| `mysql` | `mysql:8.0.36` | [MySQL 官方镜像](https://hub.docker.com/_/mysql)，版本与现有 chat CI 一致 |
| `redis` | `redis:7.2.4` | [Redis 官方镜像](https://hub.docker.com/_/redis) |
| `kafka` | `apache/kafka-native:3.8.0` | [Apache Kafka Native 镜像](https://hub.docker.com/r/apache/kafka-native) |
| `mongodb` | `mongo:6.0.11` | [MongoDB 官方镜像](https://hub.docker.com/_/mongo) |
| `elasticsearch` | `docker.elastic.co/elasticsearch/elasticsearch:8.10.4` | [Elastic 官方镜像仓库](https://www.docker.elastic.co/r/elasticsearch/elasticsearch) |
| `minio` | `minio/minio:RELEASE.2025-09-07T16-13-09Z` | [MinIO 镜像](https://hub.docker.com/r/minio/minio)，release 与现有 chat CI 一致 |

镜像标签和启动选项由测试辅助类 `MonitoringTestEnvironment` 集中管理。调整测试版本时，应同步修改本表并重新运行对应真实环境验收，不能仅修改说明中的版本号。

## 应用客户端基线

以下版本已通过引入 Testcontainers 后的实际 Maven `dependency:tree` 核实，检查产物为 `target/monitor-dependency-tree.txt`。它们与上一表的服务端版本是两类独立信息；本次新增测试依赖没有改变这些应用客户端版本。

| 类型 | 已解析的客户端 |
|---|---|
| MySQL | `com.mysql:mysql-connector-j:8.0.32`；原坐标 `mysql:mysql-connector-java` 会重定位 |
| Redis | `io.lettuce:lettuce-core:6.3.0.RELEASE`、`org.springframework.data:spring-data-redis:3.2.1`、`org.redisson:redisson:3.21.0` |
| Kafka | `org.apache.kafka:kafka-clients:3.6.1` |
| MongoDB | `org.mongodb:mongodb-driver-sync:4.11.1`、`mongodb-driver-core:4.11.1` |
| Elasticsearch | `co.elastic.clients:elasticsearch-java:8.10.4`、`org.elasticsearch.client:elasticsearch-rest-client:8.10.4` |
| MinIO | `io.minio:minio:8.5.17` |

Testcontainers core/Kafka `2.0.5` 和其 Docker Java `3.7.1` 依赖均为 test scope。

Kafka 客户端 `3.6.1` 的 `LogDirDescription` 已包含 `OptionalLong totalBytes()` 和 `usableBytes()`；方法存在不代表所有 broker 均返回容量值。缺失字段仍须按不支持或不可用处理，不能由副本日志大小推算卷容量。参见 [Kafka 3.6 系列 API](https://kafka.apache.org/36/javadoc/org/apache/kafka/clients/admin/LogDirDescription.html)。

## 本地运行

`monitor.test.type` 同时激活 `monitor-environments` Maven profile，仅为真实服务测试对齐 Commons Compress `1.27.1` 声明的 Commons Lang `3.16.0` 和 Commons IO `2.16.1`。否则 Spring Boot 管理的旧 Commons Lang 缺少 `ArrayFill`，Kafka 启动脚本的 tar 打包会失败。普通构建不激活该 profile，保留应用原有依赖版本。

可在无 Docker 的机器上单独检查这条归档路径：`./mvnw -B -Dtest=MonitoringArchiveCompatibilityTest -Dmonitor.test.type=kafka test`。这只验证依赖兼容性，不能代替下方真实环境验收。

需要 Java 17、项目 Maven Wrapper，以及可运行 Linux 容器的 Docker 环境。启动 Docker 后，先确认当前用户能执行 `docker info`。Windows 若使用 Docker Desktop，应选择 Linux 容器模式。首次运行还需要能够获取上述镜像及测试依赖。

Linux 或 macOS，在项目根目录执行：

```bash
./mvnw -B -Dtest=MonitoringEnvironmentIntegrationTest -Dmonitor.test.type=mysql test
```

PowerShell，在项目根目录执行：

```powershell
.\mvnw.cmd -B '-Dtest=MonitoringEnvironmentIntegrationTest' '-Dmonitor.test.type=mysql' test
```

将 `mysql` 替换为 `redis`、`kafka`、`mongodb`、`elasticsearch` 或 `minio`，分别验证对应环境。类型名称使用小写完整值，例如 MongoDB 使用 `mongodb`。

省略 `monitor.test.type` 时，真实环境测试默认跳过，便于普通单元测试在无 Docker 的机器上执行。**显式指定类型后，Docker 不可用、镜像获取失败或服务未就绪必须导致测试失败，不能转为跳过或使用模拟响应。** 不要将默认跳过的绿色构建记录为真实环境验收通过。

无 Docker 时可单独运行类型选择与外部地址拒绝测试：

```bash
./mvnw -B -Dtest=MonitoringEnvironmentSelectionTest test
```

该测试验证配置入口的约束，不会证明真实服务就绪。PowerShell 同样将 `./mvnw` 替换为 `.\mvnw.cmd`。

现有 Java 单元测试和 chat 回归继续使用原有入口。本功能的环境准备不要求修改 `application.yml` 的业务连接，也不要求启动完整 `AdminApplication`。本地机器已安装的 MySQL、Redis、Kafka 等服务不属于本入口可使用的测试目标。

## CI 与验收证据

GitHub CI 使用 Ubuntu、Java 17 和项目 Maven Wrapper，按六类 `monitor.test.type` 分别运行上面的命令。每项显式选择一种真实服务；单项失败不能被其他五项成功抵消。现有 chat CI 保留，浏览器验收继续沿用项目已有 Playwright 基础，环境准备阶段不替代浏览器 E2E。

一次完整环境验收需要保留以下证据：

1. 六类作业分别成功启动固定版本容器，并通过服务协议层面的就绪检查；只有 TCP 端口打开不足以证明就绪。
2. 使用本次环境生成的连接参数，完成本次拥有的测试数据准备和读取验证。
3. 重复运行时资源隔离，结束时清理本次容器和数据；失败路径也执行清理。
4. 保存测试结果及脱敏诊断，失败时能区分 Docker、镜像获取、服务启动、协议就绪和数据准备问题。

服务诊断位于 `target/monitor-environments/<id>/`，每次运行使用不同的 `<id>`；JUnit 报告位于 `target/surefire-reports/`。CI 应在成功或失败时均上传相关报告。日志不应输出密码、访问密钥、带凭据 URI 或未脱敏的连接配置；分享诊断时也应检查这一点。

## 只读权限与指标能力

下面记录的是采集时已有连接需要具备的权限和能力边界，不是向生产账号增加权限的操作清单。测试初始化可以在临时实例上建立有权限和缺权限的账号；生产权限不足时应报告该项不可采集，不能通过扩大权限、调整服务参数或新增采集组件来使检查通过。

### MySQL

`SHOW GLOBAL STATUS` 只需要能够连接，无需额外权限。库容量读取应限定到配置库和账号可见对象，例如账号已拥有 `SELECT` 的表。`INFORMATION_SCHEMA.INNODB_*` 等部分表要求 `PROCESS`；采集器若使用这些来源，应在权限不足时降级，而不是要求授予全局管理权限。[SHOW STATUS](https://dev.mysql.com/doc/refman/8.0/en/show-status.html)、[INFORMATION_SCHEMA 权限](https://dev.mysql.com/doc/refman/8.0/en/information-schema-introduction.html)。

缓冲池指标表示 InnoDB 缓存范围，数据与索引大小表示逻辑存储范围，不能替代主机 CPU、总内存或磁盘总量。状态查询自身也可能增加部分计数，测试不能要求采集前后所有全局计数完全不变。

### Redis

基本采集需要已有 ACL 允许 `PING` 和 `INFO`。客户端握手实际使用的 `HELLO`、`SELECT`、`CLIENT SETNAME` 等命令应另行核对，不应直接授予 `@all`。`INFO` 属于 `@dangerous` 分类，已有业务键读取权限不一定允许它。[INFO](https://redis.io/docs/latest/commands/info/)、[ACL](https://redis.io/docs/latest/operate/oss_and_stack/management/security/acl/)。

`INFO cpu` 中进程累计 CPU 时间覆盖线程，计算单核基准占用率时允许超过 100%。内存项应区分进程和缓存。`aof_current_size` 仅 AOF 启用时出现；它不是磁盘总量。版本缺字段、ACL 拒绝和未启用 AOF 需要各自的缺失状态，不能统一填零；禁止逐键扫描或写入探测。

### Kafka

`DESCRIBE_CLUSTER` 和 `DESCRIBE_LOG_DIRS` 使用 Cluster 的 `Describe` 权限；topic 元数据和 `LIST_OFFSETS` 使用指定 Topic 的 `Describe`；`OFFSET_FETCH` 同时需要指定 Group 和 Topic 的 `Describe`。只有实际读取配置时，才需要对应资源的 `DescribeConfigs`。读取提交积压不需要加入消费组或提交位点。[Kafka ACL 操作表](https://kafka.apache.org/39/security/authorization-and-acls/)。

`DESCRIBE_LOG_DIRS` 授权失败可能返回空响应，空结果不能直接解释成“没有日志目录”。副本日志大小不等于卷已用空间；可选卷总量和可用量必须取自实际响应。普通 AdminClient 不提供 broker CPU 或 JVM 内存。缺失提交位点和未覆盖分区应保持未知或部分范围状态。

### MongoDB

`serverStatus` 需要 cluster resource 上的 `serverStatus` action；数据库容量统计需要相应数据库的 `dbStats` action。已有 `clusterMonitor` 可提供多种监控权限，但它覆盖范围较广，不能称为最小权限。测试可使用仅含所需 action 的自定义角色验证边界。[serverStatus 所需权限](https://www.mongodb.com/docs/database-tools/mongostat/mongostat-behavior/)、[内置角色](https://www.mongodb.com/docs/manual/reference/built-in-roles/?deployment-type=self)、[MongoDB 6.0 dbStats](https://www.mongodb.com/docs/v6.0/reference/command/dbStats/)。

进程内存、WiredTiger 缓存、数据库数据/索引/存储各有不同范围；其他存储引擎可能不返回 WiredTiger 字段。文件系统字段和副本集角色取决于实际部署及响应，不能以本测试单实例结果承诺所有拓扑，也不能承诺主机 CPU 或整机容量。

### Elasticsearch

cluster `monitor` 允许健康、节点信息和统计等只读操作；配置范围内的索引 `monitor` 允许索引统计。只有调用索引元数据 API 时，才需要该范围的 `view_index_metadata`，不需要索引写权限或 `manage`。[8.10 权限](https://www.elastic.co/guide/en/elasticsearch/reference/8.10/security-privileges.html)。

Nodes stats 区分 OS、process、JVM 和文件系统范围。部分 CPU 或文件描述符字段以 `-1` 表示不可用，cgroup 字段具有平台条件，均应按缺失能力处理。多个节点共享文件系统时不能盲目累加容量；节点报告的 OS/FS 数值不能自动等同宿主机范围。[8.10 Nodes stats](https://www.elastic.co/guide/en/elasticsearch/reference/8.10/cluster-nodes-stats.html)。

### MinIO

服务健康与桶权限分别验证。S3 `HeadBucket` 通常需要目标桶的 `s3:ListBucket`；SDK 的区域查询还可能涉及 `s3:GetBucketLocation`，以固定测试镜像和实际调用验证。已有 S3 权限不表示能够读取原生 metrics；认证保护的 metrics 另有权限要求，例如 `admin:Prometheus`。[HeadBucket](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadBucket.html)、[MinIO 权限](https://docs.min.io/aistor/administration/iam/access/)。

`/live`、节点 ready、集群读 quorum、集群写 quorum 是不同结论。S3 API 不承诺进程 CPU、内存、整盘容量或桶总量；仅消费已开放且已授权的原生统计。当前官方 MinIO 文档属于 AIStor，不能据此认定日期固定的社区版镜像具备全部接口。[健康探测](https://docs.min.io/aistor/operations/monitoring/healthcheck-probe/)、[Metrics v3](https://docs.min.io/aistor/operations/monitoring/metrics-and-alerts/metrics-v3/)。

## 兼容范围的更新

只有完成真实测试的服务版本、接口和权限组合才能记录为已验证；未测试版本、集群拓扑、平台及可选接口应明确保留为未验证。后续采集器测试在本环境上补充权限拒绝、字段缺失、重启、部分失败及只读行为证据，再由 #47 汇总兼容矩阵。本环境就绪成功不等同于所有监控指标已实现或已验证。
