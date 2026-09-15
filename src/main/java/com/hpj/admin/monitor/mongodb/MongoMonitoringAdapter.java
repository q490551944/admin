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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
            return new CollectionResult(CollectionStatus.UNSUPPORTED, started, atLeast(started), List.of(), null,
                    true, MissingReason.NOT_APPLICABLE);
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
        MissingReason missing = failure != null ? failure : metrics.stream().map(MetricSample::missingReason)
                .filter(Objects::nonNull).findFirst().orElse(null);
        CollectionStatus collectionStatus = missing == null ? CollectionStatus.SUCCESS
                : latency != null ? CollectionStatus.PARTIAL : failureStatus(missing);
        return new CollectionResult(collectionStatus, started, atLeast(finished), metrics, probe, true, missing);
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
