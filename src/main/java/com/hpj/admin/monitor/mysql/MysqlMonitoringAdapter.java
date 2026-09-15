package com.hpj.admin.monitor.mysql;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import com.hpj.admin.monitor.metric.MonitoringCalculations;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation.*;

/** Read-only MySQL process observations; the scheduler owns the only previous-counter samples. */
public final class MysqlMonitoringAdapter implements MonitoringAdapter {
    private final MonitoringProperties properties;
    private final MysqlMonitoringConnections connections;
    private final Clock clock;

    public MysqlMonitoringAdapter(MonitoringProperties properties, MysqlMonitoringConnections connections, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public MiddlewareType type() { return MiddlewareType.MYSQL; }

    @Override public CollectionResult collect(CollectionRequest request) {
        Instant started = atLeast(request.scheduledAt());
        if (request.kind() == CollectionKind.CAPACITY) {
            return new CollectionResult(CollectionStatus.UNSUPPORTED, started, atLeast(started), List.of(), null,
                    true, MissingReason.NOT_APPLICABLE);
        }
        Scope scope = new Scope(ScopeKind.PROCESS, request.targetId(), null);
        List<Definition> definitions = definitions(scope);
        Read status = null;
        Read variables = null;
        ServiceProbe probe = null;
        MetricSample latency = null;
        MissingReason failure = null;
        long probeStarted = System.nanoTime();
        try {
            request.control().checkActive();
            try (MysqlMonitoringConnections.Session session = connections.open(request)) {
                session.probe();
                request.control().checkActive();
                Instant observed = atLeast(started);
                probe = new ServiceProbe(ServiceAvailability.AVAILABLE, null, scope, observed,
                        observed.plus(properties.getServiceTtl()));
                latency = success(definitions.get(0), BigDecimal.valueOf(Math.max(0, System.nanoTime() - probeStarted), 6), observed);
                status = read(request, session::globalStatus, observed);
                variables = read(request, session::globalVariables, status.at());
            }
        } catch (SQLException error) {
            failure = reason(error);
            if (probe == null) {
                Instant observed = atLeast(started);
                ServiceAvailability availability = error.getSQLState() != null && error.getSQLState().startsWith("08")
                        && failure == MissingReason.FAILED ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN;
                probe = new ServiceProbe(availability, failure, scope, observed, observed.plus(properties.getServiceTtl()));
            }
        } catch (CollectionControl.InactiveCollectionException inactive) {
            failure = inactive.reason();
        }
        Instant finished = atLeast(variables != null ? variables.at() : status != null ? status.at() : started);
        if (failure == null && (status == null || variables == null)) failure = MissingReason.FAILED;
        if (probe == null) probe = new ServiceProbe(ServiceAvailability.UNKNOWN, failure, scope, finished, finished);
        if (status == null) status = new Read(Map.of(), failure, finished);
        if (variables == null) variables = new Read(Map.of(), failure, finished);
        List<MetricSample> metrics = new ArrayList<>();
        metrics.add(latency == null ? MetricSample.missing(definitions.get(0), failure, finished) : latency);
        metrics.add(gauge(definitions.get(1), status, "Threads_connected"));
        metrics.add(gauge(definitions.get(2), variables, "max_connections"));
        metrics.add(gauge(definitions.get(3), status, "Threads_running"));
        metrics.add(derived(request, definitions.get(4), status, variables, "Questions", RATE_WITH_UPTIME));
        metrics.add(gauge(definitions.get(5), status, "Slow_queries"));
        metrics.add(derived(request, definitions.get(6), status, variables, "Slow_queries", DELTA_WITH_UPTIME));
        MissingReason missing = failure != null ? failure : metrics.stream().map(MetricSample::missingReason)
                .filter(Objects::nonNull).findFirst().orElse(null);
        CollectionStatus collectionStatus = missing == null ? CollectionStatus.SUCCESS
                : latency != null ? CollectionStatus.PARTIAL : failureStatus(missing);
        return new CollectionResult(collectionStatus, started, atLeast(finished), metrics, probe, true, missing);
    }

    private Read read(CollectionRequest request, SqlRead operation, Instant previous) {
        request.control().checkActive();
        try {
            Map<String, String> values = operation.read();
            request.control().checkActive();
            return new Read(values == null ? Map.of() : values, null, atLeast(previous));
        } catch (SQLException error) {
            return new Read(Map.of(), reason(error), atLeast(previous));
        }
    }

    private MetricSample gauge(Definition definition, Read data, String field) {
        NativeNumber value = number(data, field);
        return value.reason() == null ? success(definition, value.value(), data.at())
                : MetricSample.missing(definition, value.reason(), data.at());
    }

    private MetricSample derived(CollectionRequest request, Definition definition, Read status, Read variables,
                                 String field, MonitoringCalculations.Calculation calculation) {
        NativeNumber counter = number(status, field);
        NativeNumber uptime = number(status, "Uptime");
        String uuid = variables.values().get("server_uuid");
        MissingReason missing = counter.reason() != null ? counter.reason() : uptime.reason();
        if (missing == null) {
            missing = variables.reason() != null ? variables.reason() : uuid == null ? MissingReason.UNSUPPORTED
                    : !uuid.matches("(?i)[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    ? MissingReason.INVALID_VALUE : null;
        }
        if (missing != null) return MetricSample.missing(definition, missing, status.at());
        // A connection ID changes on every read-only connection. UUID + binding is stable; counter/Uptime
        // regressions also reset the common baseline. A same-UUID restart whose counters already caught up
        // cannot always be identified from these native observations; no durable restart history is invented.
        var sample = new MonitoringCalculations.CounterSample(status.at(),
                List.of(request.bindingId(), uuid.toLowerCase(Locale.ROOT)), List.of(counter.value(), uptime.value()));
        try {
            var result = request.control().calculate(definition, calculation, sample);
            return result.reason() == null ? success(definition, result.value(), status.at())
                    : MetricSample.missing(definition, result.reason(), status.at());
        } catch (CollectionControl.InactiveCollectionException inactive) {
            return MetricSample.missing(definition, inactive.reason(), status.at());
        }
    }

    private NativeNumber number(Read data, String field) {
        if (data.reason() != null) return new NativeNumber(null, data.reason());
        String value = data.values().get(field);
        if (value == null) return new NativeNumber(null, MissingReason.UNSUPPORTED);
        // Native counters are unsigned integers; do not accept NaN, exponents or unbounded server text.
        if (!value.matches("[0-9]{1,20}")) return new NativeNumber(null, MissingReason.INVALID_VALUE);
        return new NativeNumber(new BigDecimal(value), null);
    }

    private MetricSample success(Definition definition, BigDecimal value, Instant at) {
        return MetricSample.success(definition, value, at,
                properties.getOrdinaryInterval().multipliedBy(properties.getMetricTtlMultiplier()));
    }

    private Instant atLeast(Instant previous) {
        Instant now = clock.instant();
        return now.isBefore(previous) ? previous : now;
    }

    private static MissingReason reason(SQLException error) {
        String state = error.getSQLState();
        if (error instanceof SQLTimeoutException || "HYT00".equals(state) || "HYT01".equals(state)) return MissingReason.TIMEOUT;
        if (error instanceof SQLFeatureNotSupportedException || state != null && state.startsWith("0A")) return MissingReason.UNSUPPORTED;
        if (state != null && state.startsWith("28")) return MissingReason.UNAUTHORIZED;
        // MySQL also uses 42000 for unknown databases (1049) and SQL syntax errors (1064).
        // Only explicit access-denied vendor codes establish a statistics permission failure.
        if (state != null && state.startsWith("42") && switch (error.getErrorCode()) {
            case 1044, 1142, 1143, 1227 -> true;
            default -> false;
        }) return MissingReason.UNAUTHORIZED;
        return MissingReason.FAILED;
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
                new Definition("mysql.probe.duration", "连接探测耗时", Unit.MILLISECONDS, scope,
                        "监控专用 MySQL 连接及 SELECT 1", "单调时钟测量连接建立及 SELECT 1 的耗时"),
                new Definition("mysql.connections.current", "当前连接数", Unit.COUNT, scope,
                        "SHOW GLOBAL STATUS: Threads_connected", "MySQL 当前打开的连接数，包含监控连接"),
                new Definition("mysql.connections.max", "最大连接数", Unit.COUNT, scope,
                        "SHOW GLOBAL VARIABLES: max_connections", "MySQL 配置允许的最大客户端连接数"),
                new Definition("mysql.threads.running", "运行线程数", Unit.COUNT, scope,
                        "SHOW GLOBAL STATUS: Threads_running", "MySQL 当前非休眠线程数，包含监控查询"),
                new Definition("mysql.questions.rate", "客户端语句速率", Unit.COUNT_PER_SECOND, scope,
                        "SHOW GLOBAL STATUS: Questions, Uptime", "相邻 Questions 增量 / 实际采样秒数；包含监控客户端语句；首次或计数、Uptime 回退时等待采样"),
                new Definition("mysql.slow_queries.total", "慢查询累计", Unit.COUNT, scope,
                        "SHOW GLOBAL STATUS: Slow_queries", "MySQL 服务本次运行以来原生慢查询累计；不是每秒速率"),
                new Definition("mysql.slow_queries.delta", "本周期慢查询增量", Unit.COUNT, scope,
                        "SHOW GLOBAL STATUS: Slow_queries, Uptime", "相邻 Slow_queries 差值；首次或计数、Uptime 回退时等待采样，不除以秒数"));
    }

    @FunctionalInterface private interface SqlRead { Map<String, String> read() throws SQLException; }
    private record Read(Map<String, String> values, MissingReason reason, Instant at) { }
    private record NativeNumber(BigDecimal value, MissingReason reason) { }
}
