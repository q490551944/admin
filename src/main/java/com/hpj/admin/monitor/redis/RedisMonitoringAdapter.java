package com.hpj.admin.monitor.redis;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import com.hpj.admin.monitor.metric.MonitoringCalculations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation.*;

/** Fixed, read-only Redis process observations; previous samples belong to the shared counter store. */
public final class RedisMonitoringAdapter implements MonitoringAdapter {
    private static final List<String> SECTIONS = List.of("server", "clients", "stats", "persistence", "replication");
    private static final Set<String> FIELDS = Set.of("run_id", "connected_clients", "blocked_clients", "role",
            "total_commands_processed", "keyspace_hits", "keyspace_misses", "evicted_keys", "expired_keys",
            "loading", "rdb_bgsave_in_progress", "rdb_last_bgsave_status", "aof_enabled", "aof_rewrite_in_progress",
            "aof_last_bgrewrite_status", "aof_last_write_status");
    private static final int MAX_INFO_CHARACTERS = 262_144;
    private final MonitoringProperties properties;
    private final RedisMonitoringConnections connections;
    private final Clock clock;

    public RedisMonitoringAdapter(MonitoringProperties properties, RedisMonitoringConnections connections, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public MiddlewareType type() { return MiddlewareType.REDIS; }

    @Override public CollectionResult collect(CollectionRequest request) {
        Instant started = atLeast(request.scheduledAt());
        if (request.kind() == CollectionKind.CAPACITY) {
            return new CollectionResult(CollectionStatus.UNSUPPORTED, started, atLeast(started), List.of(), null,
                    true, MissingReason.NOT_APPLICABLE);
        }
        Scope scope = new Scope(ScopeKind.PROCESS, request.targetId(), null);
        List<Definition> definitions = definitions(scope);
        Map<String, Read> sections = new HashMap<>();
        ServiceProbe probe = null;
        MetricSample latency = null;
        MissingReason failure = null;
        Instant latest = started;
        long probeStarted = System.nanoTime();
        try {
            request.control().checkActive();
            try (RedisMonitoringConnections.Session session = connections.open(request)) {
                if (!"PONG".equals(session.ping())) {
                    throw new RedisMonitoringConnections.Failure(MissingReason.INVALID_VALUE, false);
                }
                request.control().checkActive();
                latest = atLeast(started);
                probe = new ServiceProbe(ServiceAvailability.AVAILABLE, null, scope, latest,
                        latest.plus(properties.getServiceTtl()));
                latency = success(definitions.get(0), BigDecimal.valueOf(Math.max(0, System.nanoTime() - probeStarted), 6), latest);
                for (String section : SECTIONS) {
                    Read read = read(request, session, section, latest);
                    sections.put(section, read);
                    latest = read.at();
                }
            }
        } catch (RedisMonitoringConnections.Failure error) {
            failure = error.reason();
            if (probe == null) {
                latest = atLeast(latest);
                ServiceAvailability availability = error.connectionFailure() && failure == MissingReason.FAILED
                        ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN;
                probe = new ServiceProbe(availability, failure, scope, latest, latest.plus(properties.getServiceTtl()));
            }
        } catch (CollectionControl.InactiveCollectionException inactive) {
            failure = inactive.reason();
        }
        Instant finished = atLeast(latest);
        if (failure == null && sections.size() != SECTIONS.size()) failure = MissingReason.FAILED;
        if (probe == null) probe = new ServiceProbe(ServiceAvailability.UNKNOWN, failure, scope, finished, finished);
        Read unavailable = new Read(Map.of(), failure, finished);
        Read server = sections.getOrDefault("server", unavailable);
        Read clients = sections.getOrDefault("clients", unavailable);
        Read stats = sections.getOrDefault("stats", unavailable);
        Read persistence = sections.getOrDefault("persistence", unavailable);
        Read replication = sections.getOrDefault("replication", unavailable);
        List<MetricSample> metrics = new ArrayList<>();
        metrics.add(latency == null ? MetricSample.missing(definitions.get(0), failure, finished) : latency);
        metrics.add(gauge(definitions.get(1), clients, "connected_clients"));
        metrics.add(gauge(definitions.get(2), clients, "blocked_clients"));
        metrics.add(text(definitions.get(3), replication, "role", Set.of("master", "slave", "replica")));
        metrics.add(derived(request, definitions.get(4), stats, server, RATE, "total_commands_processed"));
        metrics.add(gauge(definitions.get(5), stats, "keyspace_hits"));
        metrics.add(derived(request, definitions.get(6), stats, server, DELTA, "keyspace_hits"));
        metrics.add(gauge(definitions.get(7), stats, "keyspace_misses"));
        metrics.add(derived(request, definitions.get(8), stats, server, DELTA, "keyspace_misses"));
        metrics.add(derived(request, definitions.get(9), stats, server, REDIS_HIT_PERCENT, "keyspace_hits", "keyspace_misses"));
        metrics.add(gauge(definitions.get(10), stats, "evicted_keys"));
        metrics.add(derived(request, definitions.get(11), stats, server, DELTA, "evicted_keys"));
        metrics.add(gauge(definitions.get(12), stats, "expired_keys"));
        metrics.add(derived(request, definitions.get(13), stats, server, DELTA, "expired_keys"));
        metrics.add(flag(definitions.get(14), persistence, "loading"));
        metrics.add(flag(definitions.get(15), persistence, "rdb_bgsave_in_progress"));
        metrics.add(text(definitions.get(16), persistence, "rdb_last_bgsave_status", Set.of("ok", "err")));
        metrics.add(flag(definitions.get(17), persistence, "aof_enabled"));
        metrics.add(flag(definitions.get(18), persistence, "aof_rewrite_in_progress"));
        metrics.add(text(definitions.get(19), persistence, "aof_last_bgrewrite_status", Set.of("ok", "err")));
        metrics.add(text(definitions.get(20), persistence, "aof_last_write_status", Set.of("ok", "err")));
        MissingReason missing = failure != null ? failure : metrics.stream().map(MetricSample::missingReason)
                .filter(Objects::nonNull).findFirst().orElse(null);
        CollectionStatus status = missing == null ? CollectionStatus.SUCCESS
                : latency != null ? CollectionStatus.PARTIAL : failureStatus(missing);
        return new CollectionResult(status, started, atLeast(finished), metrics, probe, true, missing);
    }

    private Read read(CollectionRequest request, RedisMonitoringConnections.Session session, String section, Instant previous) {
        request.control().checkActive();
        try {
            String response = session.info(section);
            request.control().checkActive();
            return parse(response, atLeast(previous));
        } catch (RedisMonitoringConnections.Failure failure) {
            return new Read(Map.of(), failure.reason(), atLeast(previous));
        }
    }

    private Read parse(String response, Instant at) {
        if (response == null) return new Read(Map.of(), MissingReason.INVALID_VALUE, at);
        if (response.length() > MAX_INFO_CHARACTERS) return new Read(Map.of(), MissingReason.INVALID_VALUE, at);
        Map<String, String> values = new HashMap<>();
        int lineCount = 0;
        for (String raw : response.split("\n")) {
            if (++lineCount > 4096) return new Read(Map.of(), MissingReason.INVALID_VALUE, at);
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            int separator = line.indexOf(':');
            if (separator < 0 || line.startsWith("#")) continue;
            String key = line.substring(0, separator);
            if (!FIELDS.contains(key)) continue;
            String value = line.substring(separator + 1);
            // A duplicate or overlong known field is invalid for that field, without reflecting its text.
            if (values.containsKey(key) || value.length() > 64) values.put(key, "!");
            else values.put(key, value);
        }
        return new Read(Map.copyOf(values), null, at);
    }

    private MetricSample gauge(Definition definition, Read data, String field) {
        NativeNumber number = number(data, field);
        return number.reason() == null ? success(definition, number.value(), data.at())
                : MetricSample.missing(definition, number.reason(), data.at());
    }

    private MetricSample flag(Definition definition, Read data, String field) {
        MissingReason missing = missing(data, field);
        if (missing != null) return MetricSample.missing(definition, missing, data.at());
        String value = data.values().get(field);
        return Set.of("0", "1").contains(value) ? success(definition, value.equals("1"), data.at())
                : MetricSample.missing(definition, MissingReason.INVALID_VALUE, data.at());
    }

    private MetricSample text(Definition definition, Read data, String field, Set<String> allowed) {
        MissingReason missing = missing(data, field);
        if (missing != null) return MetricSample.missing(definition, missing, data.at());
        String value = data.values().get(field);
        return allowed.contains(value) ? success(definition, value, data.at())
                : MetricSample.missing(definition, MissingReason.INVALID_VALUE, data.at());
    }

    private MetricSample derived(CollectionRequest request, Definition definition, Read stats, Read server,
                                 MonitoringCalculations.Calculation calculation, String... fields) {
        List<BigDecimal> counters = new ArrayList<>();
        for (String field : fields) {
            NativeNumber number = number(stats, field);
            if (number.reason() != null) return MetricSample.missing(definition, number.reason(), stats.at());
            counters.add(number.value());
        }
        MissingReason missing = missing(server, "run_id");
        if (missing == null && !server.values().get("run_id").matches("(?i)[a-f0-9]{40}")) missing = MissingReason.INVALID_VALUE;
        if (missing != null) return MetricSample.missing(definition, missing, stats.at());
        var sample = new MonitoringCalculations.CounterSample(stats.at(),
                List.of(request.bindingId(), server.values().get("run_id").toLowerCase(Locale.ROOT)), counters);
        try {
            var result = request.control().calculate(definition, calculation, sample);
            return result.reason() == null ? success(definition, result.value(), stats.at())
                    : MetricSample.missing(definition, result.reason(), stats.at());
        } catch (CollectionControl.InactiveCollectionException inactive) {
            return MetricSample.missing(definition, inactive.reason(), stats.at());
        }
    }

    private NativeNumber number(Read data, String field) {
        MissingReason missing = missing(data, field);
        if (missing != null) return new NativeNumber(null, missing);
        String value = data.values().get(field);
        if (!value.matches("[0-9]{1,20}")) return new NativeNumber(null, MissingReason.INVALID_VALUE);
        return new NativeNumber(new BigDecimal(value), null);
    }

    private static MissingReason missing(Read data, String field) {
        return data.reason() != null ? data.reason() : data.values().containsKey(field) ? null : MissingReason.UNSUPPORTED;
    }

    private MetricSample success(Definition definition, Object value, Instant at) {
        return MetricSample.success(definition, value, at,
                properties.getOrdinaryInterval().multipliedBy(properties.getMetricTtlMultiplier()));
    }

    private Instant atLeast(Instant previous) {
        Instant now = clock.instant();
        return now.isBefore(previous) ? previous : now;
    }

    private static CollectionStatus failureStatus(MissingReason reason) {
        return switch (reason) {
            case UNAUTHORIZED -> CollectionStatus.UNAUTHORIZED;
            case UNSUPPORTED, NOT_APPLICABLE -> CollectionStatus.UNSUPPORTED;
            case BUSY -> CollectionStatus.BUSY;
            case WAITING_SAMPLE, NO_REQUESTS -> CollectionStatus.WAITING;
            default -> CollectionStatus.FAILED;
        };
    }

    private static List<Definition> definitions(Scope scope) {
        return List.of(
                new Definition("redis.probe.duration", "连接探测耗时", Unit.MILLISECONDS, scope, "监控专用 Redis 连接及 PING", "单调时钟测量连接建立、认证及 PING 的耗时"),
                new Definition("redis.clients.connected", "已连接客户端", Unit.COUNT, scope, "INFO clients: connected_clients", "Redis 当前客户端连接数，包含监控连接，不包含副本连接"),
                new Definition("redis.clients.blocked", "阻塞客户端", Unit.COUNT, scope, "INFO clients: blocked_clients", "正在等待阻塞调用结果的客户端数"),
                new Definition("redis.role", "复制角色", Unit.TEXT, scope, "INFO replication: role", "原生角色 master、slave 或 replica；表示当前进程的复制角色"),
                new Definition("redis.commands.rate", "命令处理速率", Unit.COUNT_PER_SECOND, scope, "INFO stats: total_commands_processed", "相邻累计命令差值 / 实际采样秒数；包含监控命令；首次、进程切换或计数回退时等待采样"),
                new Definition("redis.keyspace.hits.total", "键查找命中累计", Unit.COUNT, scope, "INFO stats: keyspace_hits", "原生键字典查找命中累计；不是全部命令数"),
                new Definition("redis.keyspace.hits.delta", "本周期键查找命中", Unit.COUNT, scope, "INFO stats: keyspace_hits", "相邻原生命中累计差值；首次、进程切换或计数回退时等待采样"),
                new Definition("redis.keyspace.misses.total", "键查找未命中累计", Unit.COUNT, scope, "INFO stats: keyspace_misses", "原生键字典查找未命中累计；不是全部命令数"),
                new Definition("redis.keyspace.misses.delta", "本周期键查找未命中", Unit.COUNT, scope, "INFO stats: keyspace_misses", "相邻原生未命中累计差值；首次、进程切换或计数回退时等待采样"),
                new Definition("redis.keyspace.hit.percent", "本周期键查找命中率", Unit.PERCENT, scope, "INFO stats: keyspace_hits, keyspace_misses", "命中增量 / (命中增量 + 未命中增量) × 100；两者增量为零时明确为无请求"),
                new Definition("redis.keys.evicted.total", "淘汰键累计", Unit.COUNT, scope, "INFO stats: evicted_keys", "因 maxmemory 限制淘汰的键累计；不是客户端淘汰数"),
                new Definition("redis.keys.evicted.delta", "本周期淘汰键", Unit.COUNT, scope, "INFO stats: evicted_keys", "相邻淘汰键累计差值；不除以秒数"),
                new Definition("redis.keys.expired.total", "过期键事件累计", Unit.COUNT, scope, "INFO stats: expired_keys", "原生键过期事件累计；不是键总量或哈希字段过期数"),
                new Definition("redis.keys.expired.delta", "本周期过期键事件", Unit.COUNT, scope, "INFO stats: expired_keys", "相邻过期键事件累计差值；不除以秒数"),
                new Definition("redis.persistence.loading", "正在加载持久化数据", Unit.BOOLEAN, scope, "INFO persistence: loading", "原生 loading 标志，仅将 0/1 转为否/是"),
                new Definition("redis.rdb.bgsave.in_progress", "RDB 后台保存中", Unit.BOOLEAN, scope, "INFO persistence: rdb_bgsave_in_progress", "原生 RDB 保存进行中标志，仅将 0/1 转为否/是"),
                new Definition("redis.rdb.last_bgsave.status", "上次 RDB 后台保存状态", Unit.TEXT, scope, "INFO persistence: rdb_last_bgsave_status", "原生 ok/err 状态，不据此推断最新文件或备份可恢复性"),
                new Definition("redis.aof.enabled", "AOF 已启用", Unit.BOOLEAN, scope, "INFO persistence: aof_enabled", "原生 AOF 启用标志，仅将 0/1 转为否/是"),
                new Definition("redis.aof.rewrite.in_progress", "AOF 重写中", Unit.BOOLEAN, scope, "INFO persistence: aof_rewrite_in_progress", "原生 AOF 重写进行中标志，仅将 0/1 转为否/是"),
                new Definition("redis.aof.last_bgrewrite.status", "上次 AOF 后台重写状态", Unit.TEXT, scope, "INFO persistence: aof_last_bgrewrite_status", "原生 ok/err 状态；字段缺失时不给出推测值"),
                new Definition("redis.aof.last_write.status", "上次 AOF 写入状态", Unit.TEXT, scope, "INFO persistence: aof_last_write_status", "原生 ok/err 状态；字段缺失时不给出推测值"));
    }

    private record Read(Map<String, String> values, MissingReason reason, Instant at) { }
    private record NativeNumber(BigDecimal value, MissingReason reason) { }
}
