package com.hpj.admin.monitor.mongodb;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import com.hpj.admin.monitor.metric.MonitoringCalculations;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation.RATE_WITH_UPTIME;

/** Observations of the one process selected by the existing native connection, never a cluster sum. */
public final class MongoMonitoringAdapter implements MonitoringAdapter {
    private static final List<String> OPERATIONS = List.of("insert", "query", "update", "delete", "getmore", "command");
    private static final Map<String, String> OPERATION_LABELS = Map.of("insert", "插入", "query", "查询", "update", "更新",
            "delete", "删除", "getmore", "游标续取", "command", "命令");
    private static final double MAX_SAFE_DOUBLE_INTEGER = 9_007_199_254_740_991D;
    private final MonitoringProperties properties;
    private final MongoMonitoringConnections connections;
    private final Clock clock;

    public MongoMonitoringAdapter(MonitoringProperties properties, MongoMonitoringConnections connections, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public MiddlewareType type() { return MiddlewareType.MONGODB; }

    @Override public CollectionResult collect(CollectionRequest request) {
        Instant started = atLeast(request.scheduledAt());
        if (request.kind() == CollectionKind.CAPACITY) {
            return capacity(request, started);
        }
        Scope scope = new Scope(ScopeKind.PROCESS, request.targetId(), null);
        Definition probeDefinition = definition("mongodb.probe.duration", "连接探测耗时", Unit.MILLISECONDS, scope,
                "现有连接所选进程的原生专用连接与 ping", "单调时钟测量连接建立及 ping 耗时；仅证明所选进程可响应命令");
        Read hello = null;
        Read status = null;
        String nodeIdentity = null;
        ServiceProbe probe = null;
        MetricSample latency = null;
        MissingReason failure = null;
        long probeStarted = System.nanoTime();
        try {
            request.control().checkActive();
            try (MongoMonitoringConnections.Session session = connections.open(request)) {
                nodeIdentity = session.nodeIdentity();
                if (!successfulReply(session.ping())) throw new MongoMonitoringConnections.Failure(MissingReason.INVALID_VALUE, false);
                request.control().checkActive();
                Instant observed = atLeast(started);
                probe = new ServiceProbe(ServiceAvailability.AVAILABLE, null, scope, observed, observed.plus(properties.getServiceTtl()));
                latency = success(probeDefinition, BigDecimal.valueOf(Math.max(0, System.nanoTime() - probeStarted), 6), observed);
                hello = read(request, session::hello, observed);
                status = read(request, session::serverStatus, hello.at());
            }
        } catch (MongoMonitoringConnections.Failure error) {
            failure = error.reason();
            if (probe == null) {
                Instant observed = atLeast(started);
                probe = new ServiceProbe(error.connectionFailure() && failure == MissingReason.FAILED
                        ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN, failure, scope, observed,
                        observed.plus(properties.getServiceTtl()));
            }
        } catch (CollectionControl.InactiveCollectionException inactive) {
            failure = inactive.reason();
        }
        Instant finished = atLeast(status != null ? status.at() : hello != null ? hello.at() : started);
        if (failure == null && (hello == null || status == null)) failure = MissingReason.FAILED;
        if (probe == null) probe = new ServiceProbe(ServiceAvailability.UNKNOWN, failure, scope, finished, finished);
        if (hello == null) hello = new Read(null, failure, finished);
        if (status == null) status = new Read(null, failure, finished);
        List<MetricSample> metrics = new ArrayList<>();
        metrics.add(latency == null ? MetricSample.missing(probeDefinition, failure, finished) : latency);
        metrics.add(gauge(definition("mongodb.connections.current", "当前进入进程的连接数", Unit.COUNT, scope,
                "serverStatus.connections.current", "当前所选 mongod/mongos 进程的进入连接，包含监控及其他服务器连接；不是客户端池连接数"),
                status, "connections", "current"));
        metrics.add(gauge(definition("mongodb.connections.available", "当前可接纳连接数", Unit.COUNT, scope,
                "serverStatus.connections.available", "当前所选进程尚可接纳的进入连接数；不是客户端池空闲数或整个集群容量"),
                status, "connections", "available"));
        NativeText role = role(hello);
        NativeText kind = kind(status, role);
        if (role.reason() == null && kind.reason() == null && !kind.value().equals(roleKind(role.value()))) {
            role = new NativeText(null, MissingReason.INVALID_VALUE);
            kind = new NativeText(null, MissingReason.INVALID_VALUE);
        }
        metrics.add(text(definition("mongodb.process.kind", "当前进程类型", Unit.TEXT, scope,
                "serverStatus.process 可执行文件名与同连接 hello", "规范化已知可执行文件名或依据已验证角色，只输出 mongod/mongos；统计只归当前所选进程"), kind, status.at()));
        metrics.add(text(definition("mongodb.process.role", "当前进程角色", Unit.TEXT, scope,
                "同一原生连接 hello 的角色字段", "固定角色枚举；isWritablePrimary 为真也可能是 standalone 或 mongos，不单独推断副本集主节点"), role, hello.at()));
        for (String operation : OPERATIONS) {
            String label = OPERATION_LABELS.get(operation);
            metrics.add(gauge(definition("mongodb.operations." + operation + ".total", label + "操作累计", Unit.COUNT, scope,
                    "serverStatus.opcounters." + operation, "当前所选进程的原生操作累计；不是文档数、成功操作数或集群合计，分类遵循实际服务版本"),
                    status, "opcounters", operation));
            metrics.add(rate(request, definition("mongodb.operations." + operation + ".rate", label + "操作速率", Unit.COUNT_PER_SECOND, scope,
                    "serverStatus.opcounters." + operation + ", uptimeMillis；同连接 hello 进程身份",
                    "相邻同进程计数增量 / 实际采样秒数；首次、进程改变、计数或 uptime 回退时等待采样"), status, hello, nodeIdentity, operation));
        }
        metrics.add(MetricSample.missing(definition("mongodb.cpu.percent", "当前进程 CPU 使用率", Unit.PERCENT, scope,
                "当前已授权 MongoDB 原生接口能力", "ping/hello/serverStatus 没有跨平台通用的进程 CPU 使用率来源；不以 admin JVM 或连接数代替"),
                MissingReason.UNSUPPORTED, finished));
        resources(metrics, request, status, kind, nodeIdentity);
        MissingReason missing = failure != null ? failure : metrics.stream().map(MetricSample::missingReason)
                .filter(Objects::nonNull).findFirst().orElse(null);
        CollectionStatus collectionStatus = missing == null ? CollectionStatus.SUCCESS
                : latency != null ? CollectionStatus.PARTIAL : failureStatus(missing);
        return new CollectionResult(collectionStatus, started, atLeast(finished), metrics, probe, true, missing);
    }

    private void resources(List<MetricSample> metrics, CollectionRequest request, Read status, NativeText kind, String identity) {
        String node = publicNode(identity);
        Scope process = new Scope(ScopeKind.PROCESS, request.targetId(), node);
        Scope cache = new Scope(ScopeKind.CACHE, "wiredTiger", node);
        NativeNumber resident = number(status, "mem", "resident");
        Definition memory = definition("mongodb.memory.resident.bytes", "所选进程常驻内存", Unit.BYTES, process,
                "serverStatus.mem.resident", "所选 mongod/mongos 进程报告的 MiB × 1048576；不是宿主机或集群内存");
        metrics.add(resident.reason() == null ? success(memory, resident.value().multiply(BigDecimal.valueOf(1_048_576)), status.at())
                : MetricSample.missing(memory, resident.reason(), status.at()));
        NativeValue engine = field(status, "storageEngine", "name");
        NativeText name = "mongos".equals(kind.value()) ? new NativeText("router", null)
                : engine.reason() != null ? new NativeText(null, engine.reason())
                : !engine.value().isString() || !boundedName(engine.value().asString().getValue()) ? new NativeText(null, MissingReason.INVALID_VALUE)
                : new NativeText(switch (engine.value().asString().getValue()) {
                    case "wiredTiger", "inMemory", "mmapv1" -> engine.value().asString().getValue();
                    default -> "other";
                }, null);
        metrics.add(text(definition("mongodb.storage.engine", "所选进程存储引擎", Unit.TEXT, process,
                "serverStatus.storageEngine.name；同连接 hello 角色", "已知引擎或 other；mongos 为 router，不发布原生自定义文本"), name, status.at()));
        MissingReason applicability = name.reason() != null ? name.reason() : "wiredTiger".equals(name.value()) ? null : MissingReason.NOT_APPLICABLE;
        Read cacheRead = applicability == null ? status : new Read(null, applicability, status.at());
        NativeNumber used = number(cacheRead, "wiredTiger", "cache", "bytes currently in the cache");
        NativeNumber maximum = number(cacheRead, "wiredTiger", "cache", "maximum bytes configured");
        metrics.add(gauge(definition("mongodb.wiredtiger.cache.used.bytes", "WiredTiger 缓存当前字节", Unit.BYTES, cache,
                "serverStatus.wiredTiger.cache.bytes currently in the cache", "所选 mongod 的 WiredTiger 缓存；不是 resident 或宿主机内存"), cacheRead, "wiredTiger", "cache", "bytes currently in the cache"));
        metrics.add(gauge(definition("mongodb.wiredtiger.cache.maximum.bytes", "WiredTiger 缓存配置上限", Unit.BYTES, cache,
                "serverStatus.wiredTiger.cache.maximum bytes configured", "此 WiredTiger 缓存的原生配置上限"), cacheRead, "wiredTiger", "cache", "maximum bytes configured"));
        metrics.add(ratio(definition("mongodb.wiredtiger.cache.used.percent", "WiredTiger 缓存使用比例", Unit.PERCENT, cache,
                "同一次 serverStatus 中的缓存字节与配置上限", "缓存字节 / 配置上限 × 100；零分母不计算，瞬时超限不截成 100%"), used, maximum, status.at(), false, CollectionKind.ORDINARY));
    }

    private CollectionResult capacity(CollectionRequest request, Instant started) {
        List<String> configured = new ArrayList<>(new LinkedHashSet<>(request.scope().databases()));
        int limit = Math.min(properties.getLimits().getDatabases(), 100);
        List<String> selected = configured.subList(0, Math.min(configured.size(), limit));
        List<String> allowed = selected.stream().filter(MongoMonitoringConnections::validDatabase).toList();
        Map<String, Read> reads = new LinkedHashMap<>();
        Read hello = null;
        String node = null;
        MissingReason failure = selected.size() != allowed.size() ? MissingReason.INVALID_VALUE : null;
        Instant observed = started;
        if (!allowed.isEmpty()) try {
            request.control().checkActive();
            try (var session = connections.open(request)) {
                node = publicNode(session.nodeIdentity());
                hello = read(request, session::hello, observed);
                observed = hello.at();
                for (String database : allowed) {
                    Read stats = read(request, () -> session.dbStats(database), observed);
                    BigDecimal scale = stats.reason() == null && stats.values().containsKey("scaleFactor") ? integer(stats.values().get("scaleFactor")) : BigDecimal.ONE;
                    if (stats.reason() == null && (!stats.values().containsKey("db") || !stats.values().get("db").isString()
                            || !database.equals(stats.values().getString("db").getValue()) || stats.values().containsKey("scaleFactor")
                            && (scale == null || scale.compareTo(BigDecimal.ONE) != 0))) {
                        stats = new Read(null, MissingReason.INVALID_VALUE, stats.at());
                    }
                    reads.put(database, stats);
                    observed = stats.at();
                }
            }
        } catch (MongoMonitoringConnections.Failure error) { failure = error.reason(); }
        catch (CollectionControl.InactiveCollectionException inactive) { failure = inactive.reason(); }
        Instant finished = atLeast(observed);
        List<MetricSample> metrics = new ArrayList<>();
        boolean truncated = configured.size() > selected.size();
        Scope coverage = new Scope(ScopeKind.CONFIGURED_SCOPE, request.targetId(), null);
        for (var entry : Map.of("databases.configured", configured.size(), "databases.requested", allowed.size(), "limits.databases", limit).entrySet()) {
            metrics.add(capacitySample(definition("mongodb.capacity." + entry.getKey(), switch (entry.getKey()) {
                case "databases.configured" -> "容量配置数据库数"; case "databases.requested" -> "容量有效请求库数"; default -> "容量数据库上限";
            }, Unit.COUNT, coverage,
                    "显式配置数据库范围", "只对有效配置库执行 scale=1、freeStorage=0 的 dbStats；不枚举数据库或集合"), entry.getValue(), null, finished));
        }
        metrics.add(capacitySample(definition("mongodb.capacity.coverage.truncated", "配置库范围触及限额", Unit.BOOLEAN, coverage,
                "数据库配置及固定 100 库上限", "超出有效数据库上限的库未请求；不存在跨库完整总量"), truncated, null, finished));
        if (configured.isEmpty()) return new CollectionResult(CollectionStatus.UNSUPPORTED, started, finished, metrics, null, true, MissingReason.NOT_APPLICABLE);
        NativeText role = hello == null ? new NativeText(null, failure == null ? MissingReason.FAILED : failure) : role(hello);
        Read filesystem = null;
        boolean readable = false;
        MissingReason missing = failure;
        for (String database : allowed) {
            Read data = reads.getOrDefault(database, new Read(null, failure == null ? MissingReason.FAILED : failure, finished));
            if (data.reason() == null) { readable = true; if (filesystem == null || filesystem.reason() != null) filesystem = data; }
            else if (missing == null) missing = data.reason();
            Scope scope = new Scope(ScopeKind.DATABASE, database, node);
            for (String field : List.of("dataSize", "storageSize", "indexSize")) {
                NativeNumber number = number(data, field);
                if (missing == null) missing = number.reason();
                metrics.add(capacitySample(definition("mongodb.database." + field + ".bytes", switch (field) {
                    case "dataSize" -> "数据库未压缩逻辑数据"; case "storageSize" -> "数据库集合分配存储"; default -> "数据库索引分配存储";
                }, Unit.BYTES, scope, "dbStats(scale=1, freeStorage=0)." + field,
                        "配置库的原生字段，三种大小互不替代、不相加为整盘使用量；mongos 是原生分片聚合，其他进程为所选节点"), number.value(), number.reason(), data.at()));
            }
        }
        if (filesystem == null) filesystem = new Read(null, missing == null ? MissingReason.UNSUPPORTED : missing, finished);
        MissingReason fsReason = role.reason() != null ? role.reason() : "router".equals(role.value()) ? MissingReason.NOT_APPLICABLE : node == null ? MissingReason.UNSUPPORTED : null;
        if (fsReason != null) filesystem = new Read(null, fsReason, filesystem.at());
        Scope scope = new Scope(ScopeKind.FILESYSTEM, "data-directory", node);
        NativeNumber used = number(filesystem, "fsUsedSize"), total = number(filesystem, "fsTotalSize");
        if (missing == null) missing = used.reason() != null ? used.reason() : total.reason();
        metrics.add(capacitySample(definition("mongodb.filesystem.used.bytes", "数据目录文件系统已用字节", Unit.BYTES, scope,
                "所选 mongod 的 dbStats.fsUsedSize", "同一所选进程的 dbpath 所在文件系统，只采用一个原生数据库响应，不跨库累加"), used.value(), used.reason(), filesystem.at()));
        metrics.add(capacitySample(definition("mongodb.filesystem.total.bytes", "数据目录文件系统总字节", Unit.BYTES, scope,
                "所选 mongod 的 dbStats.fsTotalSize", "dbpath 所在文件系统容量；不是数据库逻辑数据或分配存储"), total.value(), total.reason(), filesystem.at()));
        MetricSample percent = ratio(definition("mongodb.filesystem.used.percent", "数据目录文件系统使用比例", Unit.PERCENT, scope,
                "同一原生 dbStats 的 fsUsedSize / fsTotalSize", "零容量、缺失或不一致字段不计算；mongos 聚合不能代表路由进程的本机卷"), used, total, filesystem.at(), true, CollectionKind.CAPACITY);
        metrics.add(percent);
        if (missing == null) missing = percent.missingReason();
        if (truncated && missing == null) missing = MissingReason.PARTIAL_SCOPE;
        CollectionStatus status = missing == null ? CollectionStatus.SUCCESS : readable ? CollectionStatus.PARTIAL : failureStatus(missing);
        return new CollectionResult(status, started, finished, metrics, null, node != null || allowed.isEmpty(), missing);
    }

    private MetricSample ratio(Definition definition, NativeNumber numerator, NativeNumber denominator, Instant at,
                               boolean bounded, CollectionKind kind) {
        MissingReason reason = numerator.reason() != null ? numerator.reason() : denominator.reason();
        if (reason == null && denominator.value().signum() == 0) reason = MissingReason.NOT_APPLICABLE;
        if (reason == null && bounded && numerator.value().compareTo(denominator.value()) > 0) reason = MissingReason.INVALID_VALUE;
        BigDecimal value = reason == null ? numerator.value().multiply(BigDecimal.valueOf(100)).divide(denominator.value(), MathContext.DECIMAL64) : null;
        return kind == CollectionKind.CAPACITY ? capacitySample(definition, value, reason, at)
                : reason == null ? success(definition, value, at) : MetricSample.missing(definition, reason, at);
    }
    private MetricSample capacitySample(Definition definition, Object value, MissingReason reason, Instant at) {
        return reason == null ? MetricSample.success(definition, value, at, properties.getCapacityInterval().multipliedBy(properties.getMetricTtlMultiplier()))
                : MetricSample.missing(definition, reason, at);
    }
    private static String publicNode(String identity) {
        if (identity == null || identity.isBlank() || identity.length() > 256 || identity.chars().anyMatch(Character::isISOControl)) return null;
        try { return "mongo-node-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Required digest unavailable"); }
    }

    private Read read(CollectionRequest request, NativeRead operation, Instant previous) {
        request.control().checkActive();
        try {
            BsonDocument document = operation.read();
            request.control().checkActive();
            return successfulReply(document) ? new Read(document, null, atLeast(previous))
                    : new Read(null, MissingReason.INVALID_VALUE, atLeast(previous));
        } catch (MongoMonitoringConnections.Failure failure) {
            return new Read(null, failure.reason(), atLeast(previous));
        }
    }

    private MetricSample rate(CollectionRequest request, Definition definition, Read status, Read hello,
                              String nodeIdentity, String operation) {
        NativeNumber counter = number(status, "opcounters", operation);
        NativeNumber uptimeMillis = number(status, "uptimeMillis");
        MissingReason missing = counter.reason() != null ? counter.reason() : uptimeMillis.reason();
        NativeValue topology = field(hello, "topologyVersion", "processId");
        if (missing == null) missing = topology.reason();
        if (missing == null && !topology.value().isObjectId()) missing = MissingReason.INVALID_VALUE;
        if (missing == null && (nodeIdentity == null || nodeIdentity.isBlank())) missing = MissingReason.UNSUPPORTED;
        if (missing == null && (nodeIdentity.length() > 256 || nodeIdentity.chars().anyMatch(Character::isISOControl))) missing = MissingReason.INVALID_VALUE;
        if (missing != null) return MetricSample.missing(definition, missing, status.at());
        var sample = new MonitoringCalculations.CounterSample(status.at(),
                List.of(request.bindingId(), nodeIdentity, topology.value().asObjectId().getValue().toHexString()),
                List.of(counter.value(), uptimeMillis.value().movePointLeft(3)));
        try {
            var result = request.control().calculate(definition, RATE_WITH_UPTIME, sample);
            return result.reason() == null ? success(definition, result.value(), status.at())
                    : MetricSample.missing(definition, result.reason(), status.at());
        } catch (CollectionControl.InactiveCollectionException inactive) {
            return MetricSample.missing(definition, inactive.reason(), status.at());
        }
    }

    private NativeText role(Read hello) {
        if (hello.reason() != null) return new NativeText(null, hello.reason());
        BsonDocument data = hello.values();
        for (String field : List.of("isWritablePrimary", "secondary", "arbiterOnly", "isreplicaset")) {
            if (data.containsKey(field) && !data.get(field).isBoolean()) return new NativeText(null, MissingReason.INVALID_VALUE);
        }
        boolean primary = flag(data, "isWritablePrimary");
        boolean secondary = flag(data, "secondary");
        boolean arbiter = flag(data, "arbiterOnly");
        boolean replicaSet = flag(data, "isreplicaset");
        if ((primary ? 1 : 0) + (secondary ? 1 : 0) + (arbiter ? 1 : 0) > 1) return new NativeText(null, MissingReason.INVALID_VALUE);
        boolean setName = data.containsKey("setName");
        if (setName && (!data.get("setName").isString() || !boundedName(data.getString("setName").getValue()))) {
            return new NativeText(null, MissingReason.INVALID_VALUE);
        }
        if (data.containsKey("msg")) {
            if (!data.get("msg").isString() || !"isdbgrid".equals(data.getString("msg").getValue())
                    || secondary || arbiter || setName || replicaSet) return new NativeText(null, MissingReason.INVALID_VALUE);
            return new NativeText("router", null);
        }
        if (secondary) return new NativeText("replica-secondary", null);
        if (arbiter) return new NativeText("replica-arbiter", null);
        if (setName) return new NativeText(primary ? "replica-primary" : "replica-other", null);
        if (replicaSet) return new NativeText("replica-other", null);
        return primary ? new NativeText("standalone", null) : new NativeText(null, MissingReason.UNSUPPORTED);
    }

    private NativeText kind(Read status, NativeText role) {
        NativeValue process = field(status, "process");
        if (process.reason() != null) {
            return role.reason() == null ? new NativeText(roleKind(role.value()), null) : new NativeText(null, process.reason());
        }
        if (!process.value().isString()) return new NativeText(null, MissingReason.INVALID_VALUE);
        String binary = process.value().asString().getValue();
        if (binary.isBlank() || binary.length() > 4096 || binary.chars().anyMatch(Character::isISOControl)) {
            return new NativeText(null, MissingReason.INVALID_VALUE);
        }
        // MongoDB 6.0 takes binaryName from argv[0] and strips only '/', leaving Windows paths intact.
        String basename = binary.substring(Math.max(binary.lastIndexOf('/'), binary.lastIndexOf('\\')) + 1);
        String knownKind = switch (basename) {
            case "mongod", "mongod.exe" -> "mongod";
            case "mongos", "mongos.exe" -> "mongos";
            default -> null;
        };
        if (knownKind != null) return new NativeText(knownKind, null);
        // An executable may be renamed. Only independent hello evidence can classify an unknown name.
        return role.reason() == null ? new NativeText(roleKind(role.value()), null)
                : new NativeText(null, MissingReason.INVALID_VALUE);
    }

    private static String roleKind(String role) { return "router".equals(role) ? "mongos" : "mongod"; }
    private static boolean flag(BsonDocument document, String field) { return document.containsKey(field) && document.getBoolean(field).getValue(); }
    private static boolean boundedName(String value) { return !value.isBlank() && value.length() <= 255 && value.chars().noneMatch(Character::isISOControl); }

    private MetricSample gauge(Definition definition, Read data, String... path) {
        NativeNumber number = number(data, path);
        return number.reason() == null ? success(definition, number.value(), data.at())
                : MetricSample.missing(definition, number.reason(), data.at());
    }

    private MetricSample text(Definition definition, NativeText text, Instant at) {
        return text.reason() == null ? success(definition, text.value(), at) : MetricSample.missing(definition, text.reason(), at);
    }

    private NativeNumber number(Read data, String... path) {
        NativeValue field = field(data, path);
        if (field.reason() != null) return new NativeNumber(null, field.reason());
        BigDecimal number = integer(field.value());
        return number == null ? new NativeNumber(null, MissingReason.INVALID_VALUE) : new NativeNumber(number, null);
    }

    private NativeValue field(Read data, String... path) {
        if (data.reason() != null) return new NativeValue(null, data.reason());
        BsonValue value = data.values();
        for (String segment : path) {
            if (!value.isDocument()) return new NativeValue(null, MissingReason.INVALID_VALUE);
            if (!value.asDocument().containsKey(segment)) return new NativeValue(null, MissingReason.UNSUPPORTED);
            value = value.asDocument().get(segment);
        }
        return new NativeValue(value, null);
    }

    private static BigDecimal integer(BsonValue value) {
        if (value == null) return null;
        if (value.isInt32()) return value.asInt32().getValue() >= 0 ? BigDecimal.valueOf(value.asInt32().getValue()) : null;
        if (value.isInt64()) return value.asInt64().getValue() >= 0 ? BigDecimal.valueOf(value.asInt64().getValue()) : null;
        if (value.isDouble()) {
            double number = value.asDouble().getValue();
            if (Double.isFinite(number) && number >= 0 && number <= MAX_SAFE_DOUBLE_INTEGER && number == Math.rint(number)) return BigDecimal.valueOf(number);
        }
        return null;
    }

    private static boolean successfulReply(BsonDocument document) {
        if (document == null) return false;
        BigDecimal ok = integer(document.get("ok"));
        return ok != null && ok.compareTo(BigDecimal.ONE) == 0;
    }

    private static Definition definition(String key, String label, Unit unit, Scope scope, String source, String calculation) {
        return new Definition(key, label, unit, scope, source, calculation);
    }
    private MetricSample success(Definition definition, Object value, Instant at) {
        return MetricSample.success(definition, value, at,
                properties.getOrdinaryInterval().multipliedBy(properties.getMetricTtlMultiplier()));
    }
    private Instant atLeast(Instant previous) { Instant now = clock.instant(); return now.isBefore(previous) ? previous : now; }
    private static CollectionStatus failureStatus(MissingReason reason) {
        return switch (reason) {
            case UNAUTHORIZED -> CollectionStatus.UNAUTHORIZED;
            case UNSUPPORTED, NOT_APPLICABLE -> CollectionStatus.UNSUPPORTED;
            case BUSY -> CollectionStatus.BUSY;
            case WAITING_SAMPLE, NO_REQUESTS -> CollectionStatus.WAITING;
            default -> CollectionStatus.FAILED;
        };
    }
    @FunctionalInterface private interface NativeRead { BsonDocument read(); }
    private record Read(BsonDocument values, MissingReason reason, Instant at) { }
    private record NativeValue(BsonValue value, MissingReason reason) { }
    private record NativeNumber(BigDecimal value, MissingReason reason) { }
    private record NativeText(String value, MissingReason reason) { }
}
