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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        if (request.kind() == CollectionKind.CAPACITY) return capacity(request, started);
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
        appendBufferPool(metrics, request, status, variables);
        appendUnsupportedProcessResources(metrics, scope, finished);
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
        return derived(request, definition, status, variables, calculation, List.of(field, "Uptime"));
    }

    private MetricSample derived(CollectionRequest request, Definition definition, Read status, Read variables,
                                 MonitoringCalculations.Calculation calculation, List<String> fields) {
        List<NativeNumber> counters = fields.stream().map(field -> number(status, field)).toList();
        String uuid = variables.values().get("server_uuid");
        MissingReason missing = counters.stream().map(NativeNumber::reason).filter(Objects::nonNull).findFirst().orElse(null);
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
                List.of(request.bindingId(), uuid.toLowerCase(Locale.ROOT)), counters.stream().map(NativeNumber::value).toList());
        try {
            var result = request.control().calculate(definition, calculation, sample);
            return result.reason() == null ? success(definition, result.value(), status.at())
                    : MetricSample.missing(definition, result.reason(), status.at());
        } catch (CollectionControl.InactiveCollectionException inactive) {
            return MetricSample.missing(definition, inactive.reason(), status.at());
        }
    }

    private void appendBufferPool(List<MetricSample> metrics, CollectionRequest request, Read status, Read variables) {
        Scope scope = new Scope(ScopeKind.CACHE, request.targetId() + ".innodb-buffer-pool", null);
        metrics.add(gauge(new Definition("mysql.innodb.buffer_pool.configured.bytes", "InnoDB 缓冲池配置量", Unit.BYTES, scope,
                "SHOW GLOBAL VARIABLES: innodb_buffer_pool_size", "原生缓冲池配置字节，可在线调整；不等于进程总内存或宿主机内存"), variables, "innodb_buffer_pool_size"));
        metrics.add(gauge(new Definition("mysql.innodb.buffer_pool.data.bytes", "InnoDB 缓冲池数据字节", Unit.BYTES, scope,
                "SHOW GLOBAL STATUS: Innodb_buffer_pool_bytes_data", "当前缓冲池中干净及脏数据页的原生字节，不含全部管理开销；压缩页大小不同，不用页数乘固定页大小替代"), status, "Innodb_buffer_pool_bytes_data"));
        metrics.add(derived(request, new Definition("mysql.innodb.buffer_pool.hit.percent", "本周期 InnoDB 缓存读命中率", Unit.PERCENT, scope,
                "SHOW GLOBAL STATUS: Innodb_buffer_pool_reads, Innodb_buffer_pool_read_requests, Uptime",
                "(1 - 相邻物理读增量 / 逻辑读请求增量) × 100；本周期无逻辑请求显示无请求；首次、计数或 Uptime 回退、身份变化时等待采样"),
                status, variables, MYSQL_HIT_PERCENT_WITH_UPTIME, List.of("Innodb_buffer_pool_reads", "Innodb_buffer_pool_read_requests", "Uptime")));
    }

    private static void appendUnsupportedProcessResources(List<MetricSample> metrics, Scope scope, Instant at) {
        metrics.add(MetricSample.missing(new Definition("mysql.process.cpu.percent", "MySQL 进程 CPU", Unit.PERCENT, scope,
                "现有 MySQL 原生只读状态接口", "未取得可靠的远端进程 CPU 来源；不使用 admin 自身资源或运行线程数推测"), MissingReason.UNSUPPORTED, at));
        metrics.add(MetricSample.missing(new Definition("mysql.process.memory.bytes", "MySQL 进程总内存", Unit.BYTES, scope,
                "现有 MySQL 原生只读状态接口", "未取得可靠的远端进程总内存来源；InnoDB 缓冲池不能替代整个进程内存"), MissingReason.UNSUPPORTED, at));
    }

    private CollectionResult capacity(CollectionRequest request, Instant started) {
        List<String> configured = new ArrayList<>(new LinkedHashSet<>(request.scope().databases()));
        int databaseLimit = Math.min(MysqlMonitoringConnections.MAX_DATABASES, Math.max(0, properties.getLimits().getDatabases()));
        int tableLimit = Math.min(MysqlMonitoringConnections.MAX_TABLES, Math.max(0, properties.getLimits().getTables()));
        List<String> selected = configured.subList(0, Math.min(configured.size(), databaseLimit));
        List<String> valid = selected.stream().filter(MysqlMonitoringConnections::validDatabase).toList();
        Map<String, CapacityRead> values = new LinkedHashMap<>();
        Instant previous = started;
        MissingReason failure = null;
        int remaining = tableLimit;
        int selectedTables = 0;
        boolean truncated = selected.size() < configured.size();
        try {
            request.control().checkActive();
            if (!valid.isEmpty()) try (MysqlMonitoringConnections.Session session = connections.open(request)) {
                for (String database : valid) {
                    request.control().checkActive();
                    if (remaining == 0) {
                        truncated = true;
                        values.put(database, new CapacityRead(null, MissingReason.UNSUPPORTED, previous, 0));
                        continue;
                    }
                    int budget = remaining;
                    try {
                        MysqlMonitoringConnections.DatabaseCapacity value = session.database(database, budget);
                        request.control().checkActive();
                        previous = atLeast(previous);
                        MissingReason invalid = validCapacity(value, database, budget);
                        values.put(database, new CapacityRead(value, invalid, previous, budget));
                        if (invalid == null) {
                            int used = value.selectedTables().size();
                            selectedTables += used;
                            remaining -= used;
                            truncated |= value.truncated();
                        } else remaining = 0; // Untrusted row counts cannot expand the remaining collection budget.
                    } catch (SQLException error) {
                        request.control().checkActive();
                        previous = atLeast(previous);
                        values.put(database, new CapacityRead(null, reason(error), previous, budget));
                    }
                    remaining = Math.min(remaining, Math.max(0, session.remainingTableBudget()));
                }
            }
        } catch (SQLException error) { failure = reason(error); }
        catch (CollectionControl.InactiveCollectionException inactive) { failure = inactive.reason(); }
        Instant finished = atLeast(previous);
        List<MetricSample> metrics = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            String name = selected.get(i);
            boolean safe = MysqlMonitoringConnections.validDatabase(name);
            CapacityRead read = values.get(name);
            if (read == null) read = new CapacityRead(null, !safe ? MissingReason.INVALID_VALUE : failure == null ? MissingReason.FAILED : failure, finished, 0);
            appendDatabase(metrics, new Scope(ScopeKind.DATABASE, safe ? name : "invalid-database#" + i, null), read);
        }
        MissingReason nativeReason = failure != null ? failure : firstReason(metrics);
        boolean anyNative = metrics.stream().anyMatch(metric -> metric.value() != null && metric.definition().unit() != Unit.BOOLEAN);
        appendCapacityCoverage(metrics, new Scope(ScopeKind.CONFIGURED_SCOPE, request.targetId(), null), configured.size(), valid.size(),
                databaseLimit, tableLimit, selectedTables, truncated, nativeReason == null && !truncated, finished);
        appendUnsupportedFilesystems(metrics, request.targetId(), finished);
        MissingReason missing = nativeReason != null ? nativeReason : MissingReason.UNSUPPORTED;
        return new CollectionResult(anyNative ? CollectionStatus.PARTIAL : failureStatus(missing), started, finished, metrics, null,
                selected.size() == configured.size(), missing);
    }

    /** Validate the selected native inventory before any field can contribute to a database aggregate. */
    private static MissingReason validCapacity(MysqlMonitoringConnections.DatabaseCapacity value, String declared, int budget) {
        if (value == null || value.selectedTables() == null || value.tables() == null
                || value.selectedTables().size() > budget || value.tables().size() > value.selectedTables().size()) return MissingReason.INVALID_VALUE;
        if (!value.visible()) return value.canonicalName() == null && value.selectedTables().isEmpty() && value.tables().isEmpty() && !value.truncated()
                ? null : MissingReason.INVALID_VALUE;
        if (!MysqlMonitoringConnections.validDatabase(value.canonicalName()) || !value.canonicalName().equalsIgnoreCase(declared)) return MissingReason.INVALID_VALUE;
        Set<String> selected = new HashSet<>();
        for (String name : value.selectedTables()) if (!validTable(name) || !selected.add(name)) return MissingReason.INVALID_VALUE;
        Set<String> observed = new HashSet<>();
        for (MysqlMonitoringConnections.TableSize row : value.tables()) {
            if (row == null || !value.canonicalName().equals(row.schema()) || !selected.contains(row.table()) || !observed.add(row.table())) return MissingReason.INVALID_VALUE;
        }
        return null;
    }

    private void appendDatabase(List<MetricSample> metrics, Scope scope, CapacityRead read) {
        MysqlMonitoringConnections.DatabaseCapacity value = read.value();
        MissingReason unavailable = read.reason() != null ? read.reason() : value.reason() != null ? value.reason()
                : !value.visible() ? MissingReason.UNSUPPORTED : null;
        MissingReason sizeReason = unavailable != null ? unavailable
                : value.truncated() || value.tables().size() != value.selectedTables().size() ? MissingReason.UNSUPPORTED : null;
        NativeNumber data = sizeReason == null ? sum(value.tables(), true) : new NativeNumber(null, sizeReason);
        NativeNumber index = sizeReason == null ? sum(value.tables(), false) : new NativeNumber(null, sizeReason);
        String source = "限定配置库 INFORMATION_SCHEMA.TABLES 可见基础表元数据";
        String common = "仅当前凭据可见且本轮名单完整的基础表；沿用服务统计缓存，采样时间为本次读取时间，不是统计更新时间；不同引擎口径不同，不表示整库磁盘或文件系统容量";
        metrics.add(capacitySample(new Definition("mysql.database.data.bytes", "可见基础表数据空间估算", Unit.BYTES, scope, source,
                "可见基础表 DATA_LENGTH 合计；InnoDB 是聚簇索引分配估算，MEMORY 是内存分配。" + common), data.value(), data.reason(), read.at()));
        metrics.add(capacitySample(new Definition("mysql.database.index.bytes", "可见基础表索引空间估算", Unit.BYTES, scope, source,
                "可见基础表 INDEX_LENGTH 合计；InnoDB 是非聚簇索引分配估算。" + common), index.value(), index.reason(), read.at()));
        metrics.add(capacitySample(new Definition("mysql.database.tables.observed", "本次响应可见基础表数", Unit.COUNT, scope, source,
                "仅计此次限定名单中的已响应基础表；零表示当前凭据未见基础表，不证明整个数据库为空；不包含视图、临时表或不可见表"),
                unavailable == null ? value.tables().size() : null, unavailable, read.at()));
        metrics.add(capacitySample(new Definition("mysql.database.coverage.complete", "本次可见基础表范围完整", Unit.BOOLEAN, scope, source,
                "本次名单未截断、统计响应匹配且所需字段完整；不证明不存在权限不可见的表，也不证明表统计为实时值"),
                data.reason() == null && index.reason() == null, null, read.at()));
    }

    private static NativeNumber sum(List<MysqlMonitoringConnections.TableSize> rows, boolean data) {
        BigDecimal total = BigDecimal.ZERO;
        for (MysqlMonitoringConnections.TableSize row : rows) {
            if (row.engine() == null) return new NativeNumber(null, MissingReason.UNSUPPORTED);
            if (!row.engine().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) return new NativeNumber(null, MissingReason.INVALID_VALUE);
            NativeNumber size = unsigned(data ? row.dataLength() : row.indexLength());
            if (size.reason() != null) return size;
            total = total.add(size.value());
        }
        return new NativeNumber(total, null);
    }

    private void appendCapacityCoverage(List<MetricSample> metrics, Scope scope, int configured, int requested, int databases,
                                        int tables, int selected, boolean truncated, boolean complete, Instant at) {
        String source = "服务端配置库范围及本次有界可见基础表元数据";
        metrics.add(capacitySample(new Definition("mysql.databases.configured", "配置数据库数", Unit.COUNT, scope, source, "仅显式配置范围的去重库数；空范围不扫描全部库"), configured, null, at));
        metrics.add(capacitySample(new Definition("mysql.databases.requested", "纳入请求范围库数", Unit.COUNT, scope, source, "合法名称且纳入库数预算的数量；耗尽表预算或超时时可能尚未请求"), requested, null, at));
        metrics.add(capacitySample(new Definition("mysql.limits.databases", "有效数据库上限", Unit.COUNT, scope, source, "配置库数上限与 100 库硬上限的较小值"), databases, null, at));
        metrics.add(capacitySample(new Definition("mysql.limits.tables", "单次容量表预算", Unit.COUNT, scope, source, "配置表上限与 1000 表硬上限的较小值，全部配置库共享；用于判断截断的额外名字不读取统计"), tables, null, at));
        metrics.add(capacitySample(new Definition("mysql.tables.selected", "本次选中可见基础表数", Unit.COUNT, scope, source, "已取得名单并纳入动态统计范围的表数，包含统计失败或大小字段缺失的表"), selected, null, at));
        metrics.add(capacitySample(new Definition("mysql.capacity.coverage.complete", "本次可见容量范围完整", Unit.BOOLEAN, scope, source, "配置库和当前凭据可见表的名单、统计字段均完整且未截断；不含不可见表及无来源的文件系统资源"), complete, null, at));
        metrics.add(capacitySample(new Definition("mysql.capacity.coverage.truncated", "本次触及容量范围预算", Unit.BOOLEAN, scope, source, "库数或各库共享的表预算使本轮范围截断；缺失库和表不能作为零容量"), truncated, null, at));
    }

    private static void appendUnsupportedFilesystems(List<MetricSample> metrics, String target, Instant at) {
        Scope scope = new Scope(ScopeKind.FILESYSTEM, target + ".unresolved-filesystem", null);
        metrics.add(MetricSample.missing(new Definition("mysql.filesystem.total.bytes", "MySQL 文件系统总量", Unit.BYTES, scope,
                "现有 MySQL 原生只读接口未解析文件系统", "未取得可靠文件系统身份和总量；此 scope 是未解析占位，不是远端路径；表大小、DATA_FREE 和缓冲池不能替代整盘资源"), MissingReason.UNSUPPORTED, at));
        metrics.add(MetricSample.missing(new Definition("mysql.filesystem.available.bytes", "MySQL 文件系统可用量", Unit.BYTES, scope,
                "现有 MySQL 原生只读接口未解析文件系统", "未取得可靠文件系统可用空间；DATA_FREE 可能重复描述共享表空间，不代表磁盘可用量，不求和或计算磁盘使用率"), MissingReason.UNSUPPORTED, at));
    }

    private MetricSample capacitySample(Definition definition, Object value, MissingReason reason, Instant at) {
        return reason == null ? MetricSample.success(definition, value, at, properties.getCapacityInterval().multipliedBy(properties.getMetricTtlMultiplier()))
                : MetricSample.missing(definition, reason, at);
    }
    private static MissingReason firstReason(List<MetricSample> metrics) { return metrics.stream().map(MetricSample::missingReason).filter(Objects::nonNull).findFirst().orElse(null); }
    private static boolean validTable(String value) { return value != null && !value.isBlank() && value.length() <= 64 && value.chars().noneMatch(Character::isISOControl); }

    private NativeNumber number(Read data, String field) {
        if (data.reason() != null) return new NativeNumber(null, data.reason());
        return unsigned(data.values().get(field));
    }

    private static NativeNumber unsigned(String value) {
        if (value == null) return new NativeNumber(null, MissingReason.UNSUPPORTED);
        // Native counters are unsigned integers; do not accept NaN, exponents or unbounded server text.
        if (!value.matches("[0-9]{1,20}")) return new NativeNumber(null, MissingReason.INVALID_VALUE);
        BigDecimal number = new BigDecimal(value);
        return number.compareTo(new BigDecimal("18446744073709551615")) <= 0 ? new NativeNumber(number, null) : new NativeNumber(null, MissingReason.INVALID_VALUE);
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
        if (state != null && state.startsWith("22")) return MissingReason.INVALID_VALUE;
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
    private record CapacityRead(MysqlMonitoringConnections.DatabaseCapacity value, MissingReason reason, Instant at, int budget) { }
}
