# Redis 运行状态与命令指标

启用 Redis 目标后，普通调度通过已经解析的实际连接配置读取当前进程指标。HTTP 接口只读取共享快照，不因页面刷新向 Redis 发出额外请求。

```yaml
monitor:
  enabled: true
  allowed-user-ids: [1001]
  targets:
    - id: cache-main
      type: redis
      enabled: true
      connection-source: redisConnectionFactory
```

`connection-source` 引用已有 Bean；也可按实际对象使用 `redisson`。同实例合并和不同 DB/身份保留规则见 [连接来源](middleware-monitoring-connections.md)。本页指标属于 Redis 服务进程，不是某个 DB 的统计，也不使用 admin 进程的资源值。

## 读取与计算

| 指标键 | 原生来源或单位 |
| --- | --- |
| `redis.probe.duration` | 建立连接和 PING 耗时，毫秒 |
| `redis.clients.connected` / `redis.clients.blocked` | connected_clients / blocked_clients，个 |
| `redis.role` | role |
| `redis.commands.rate` | total_commands_processed 差值/秒 |
| `redis.keyspace.hits.total` / `.delta` | keyspace_hits，累计/周期次数 |
| `redis.keyspace.misses.total` / `.delta` | keyspace_misses，累计/周期次数 |
| `redis.keyspace.hit.percent` | 周期命中率，百分比 |
| `redis.keys.evicted.total` / `.delta` | evicted_keys，累计/周期次数 |
| `redis.keys.expired.total` / `.delta` | expired_keys，累计/周期次数 |
| `redis.persistence.loading` | loading，布尔值 |
| `redis.rdb.bgsave.in_progress` | rdb_bgsave_in_progress，布尔值 |
| `redis.rdb.last_bgsave.status` | rdb_last_bgsave_status |
| `redis.aof.enabled` / `redis.aof.rewrite.in_progress` | aof_enabled / aof_rewrite_in_progress，布尔值 |
| `redis.aof.last_bgrewrite.status` / `redis.aof.last_write.status` | aof_last_bgrewrite_status / aof_last_write_status，ok/err |

连接建立后执行 `PING`，分别读取 `INFO server`、`clients`、`stats`、`persistence`、`replication`。每个分区独立处理失败；PING 成功而 INFO 被拒绝时，服务仍有成功探测，相关指标显示无权限。缺失字段、未知状态、超时和真实零值分别表达；只发布固定字段及已知状态，不返回原始 INFO、run_id、地址、密码或异常文本。

命令处理速率使用 `total_commands_processed` 的周期差值除以实际采样秒数。命中/未命中、淘汰/过期分别提供累计值和周期增量；命中率使用 `Δhits / (Δhits + Δmisses) × 100%`。没有键查找请求时命中率显示 `NO_REQUESTS`，不会显示为零。命中/未命中针对键查找，不等于所有命令；监控自身的连接设置、PING 和 INFO 会影响服务的命令计数。

派生指标复用公共前一样本存储。首次采样、`run_id` 变化、计数下降或样本间隔超过三个普通周期时建立新基线；缺少 run_id 只影响需要基线的指标。默认普通采集周期 15 秒、指标有效期 45 秒、服务探测有效期 45 秒。只保留计算当前值所需的前一样本，不保存趋势历史。

## 连接与支持范围

采集从已解析的 Lettuce、Jedis 或 Redisson 生效配置建立自有单节点 TCP 连接，保留实际主机、端口、DB 和静态认证；不向业务池借连接，不改动或关闭业务客户端。初始化仅使用必要的 AUTH/SELECT，监控命令仅使用 PING 和固定 INFO 分区，不发出 CLIENT SETINFO、写入探测、KEYS、SCAN 或 CONFIG。

本项支持普通 TCP standalone。TLS、StartTLS、动态凭据、自定义路由、未知客户端选项和多节点拓扑明确返回不支持；当前实现没有可证明等价的跨客户端信任库、端点校验和路由复制路径，不会改用明文或任意节点继续采集。目录中“已解析”只表示连接配置可识别，不保证该配置可由当前适配器安全采集。

连接和每次读取受同一请求剩余预算限制，保留原生更短的超时。自有 socket 在 connect 前登记，取消会实际关闭网络连接。标准 Java DNS 不能强制中断；截止后保留原工作槽及资源预留，解析返回后才完成清理，不增加替代线程。

响应在交给原生解码器分配内存前检查帧类型及声明长度：INFO bulk 最大 262144 字节，状态/错误行最大 4096 字节。网络、超时或协议错误会终止自有会话，后续分区不能继续写入或重连；完整的 Redis 权限错误允许继续读取其他已授权分区。

## 验证

本地契约及实际网络超时/取消测试：

```powershell
.\mvnw.cmd '-Dtest=MonitoringRedis*Test,MonitoringCatalogTest' test
```

有 Docker 的环境运行真实 Redis 7.2.4 验收：

```powershell
.\mvnw.cmd '-Dtest=MonitoringRedisAdapterIntegrationTest' '-Dmonitor.test.type=redis' test
```

CI 的 Redis 任务调用同一入口。测试只操作自有临时容器；受控键、ACL 和 RESETSTAT 属于测试准备，不在生产采集器中执行。真实测试验证已启动 Lettuce/Redisson 的实际来源、原生字段、无请求与计数归零、部分 INFO 拒绝，以及原有业务连接和数据保持可用。真实测试固定普通 TCP 单节点 Redis 7.2.4；进程 run_id 切换使用契约测试覆盖，不声称已验证真实重启或其他 Redis 版本。按 INFO 首参数限制权限使用固定测试版本支持的 ACL 能力，不要求修改应用服务的授权。

CPU、内存和 AOF 文件大小由 #30 补充。

## 语义依据

- [Redis INFO](https://redis.io/docs/latest/commands/info/)：进程计数、角色及持久化字段。
- [Redis ACL](https://redis.io/docs/latest/operate/oss_and_stack/management/security/acl/)：命令和首参数授权；首参数机制已弃用，测试固定在仍支持的 Redis 7.2.4。
