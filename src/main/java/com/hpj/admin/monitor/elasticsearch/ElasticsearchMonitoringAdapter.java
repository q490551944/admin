package com.hpj.admin.monitor.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import com.hpj.admin.monitor.metric.MonitoringCalculations;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation.RATE;

/** Fixed read-only observations. Native allocation health, index copies and node pools keep separate scopes. */
public final class ElasticsearchMonitoringAdapter implements MonitoringAdapter {
    private static final int MAX_METRICS = 4500;
    private static final int FIXED_METRICS = 24;
    private static final int MAX_SHARD_ROWS = 2000;
    private static final List<String> POOLS = List.of("search", "search_coordination", "write");
    private final MonitoringProperties properties;
    private final ElasticsearchMonitoringConnections connections;
    private final Clock clock;

    public ElasticsearchMonitoringAdapter(MonitoringProperties properties, ElasticsearchMonitoringConnections connections, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public MiddlewareType type() { return MiddlewareType.ELASTICSEARCH; }

    @Override public CollectionResult collect(CollectionRequest request) {
        Instant started = atLeast(request.scheduledAt());
        boolean capacity = request.kind() == CollectionKind.CAPACITY;
        List<String> configured = new ArrayList<>(new LinkedHashSet<>(request.scope().indices()));
        int indexLimit = Math.min(ElasticsearchMonitoringConnections.MAX_NAMES,
                Math.min(properties.getLimits().getIndices(), (MAX_METRICS - FIXED_METRICS) / 6));
        List<String> selected = configured.subList(0, Math.min(configured.size(), indexLimit));
        List<String> requested = selected.stream().filter(ElasticsearchMonitoringAdapter::validIndex).toList();
        int nodeLimit = Math.min(ElasticsearchMonitoringConnections.MAX_NAMES, Math.min(properties.getLimits().getNodes(),
                (MAX_METRICS - FIXED_METRICS - selected.size() * 6) / POOLS.size()));
        Observations observed = new Observations();
        observed.truncated = selected.size() < configured.size();
        Read health = null, pools = null, stats = null, before = null, after = null;
        List<String> nodes = List.of();
        Map<String, Routing> routing = Map.of();
        MissingReason failure = null;
        ServiceProbe probe = null;
        MetricSample latency = null;
        Scope cluster = new Scope(ScopeKind.CLUSTER, request.targetId(), null);
        long probeStarted = System.nanoTime();
        try {
            request.control().checkActive();
            try (ElasticsearchMonitoringConnections.Session session = connections.open(request)) {
                if (!capacity) {
                    health = read(request, session::health, started);
                    if (health.reason() == null) {
                        probe = available(cluster, health.at());
                        latency = success(probeDefinition(cluster), BigDecimal.valueOf(Math.max(0, System.nanoTime() - probeStarted), 6), health.at(), false);
                    }
                    pools = read(request, session::threadPools, health.at());
                    nodes = nodes(pools, nodeLimit, observed);
                    if (probe == null && pools.reason() == null) probe = available(cluster, pools.at());
                    // Bracket the counter read: a restart between stats and its identity read must not pair old counters with a new process.
                    if (!requested.isEmpty() && !nodes.isEmpty()) {
                        List<String> known = nodes;
                        before = read(request, () -> session.nodeStarts(known), pools.at());
                    }
                }
                if (!requested.isEmpty()) {
                    stats = read(request, () -> session.indexStats(requested, request.kind()),
                            before != null ? before.at() : pools != null ? pools.at() : started);
                    if (!capacity) routing = routing(stats, requested, observed);
                    if (!capacity) {
                        TreeSet<String> contributing = new TreeSet<>();
                        routing.values().stream().filter(value -> value.reason() == null).forEach(value -> contributing.addAll(value.nodes()));
                        // Only IDs already observed in the bounded native node response may be requested.
                        contributing.retainAll(nodes);
                        if (!contributing.isEmpty()) {
                            List<String> ids = List.copyOf(contributing);
                            after = read(request, () -> session.nodeStarts(ids), stats.at());
                        }
                        if (probe == null && stats.reason() == null) probe = available(cluster, stats.at());
                    }
                }
            }
        } catch (ElasticsearchMonitoringConnections.Failure error) {
            failure = error.reason();
            if (!capacity && probe == null) {
                Instant at = atLeast(started);
                probe = new ServiceProbe(error.connectionFailure() && failure == MissingReason.FAILED ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN,
                        failure, cluster, at, at.plus(properties.getServiceTtl()));
            }
        } catch (CollectionControl.InactiveCollectionException inactive) {
            failure = inactive.reason();
        }
        Instant finished = atLeast(started);
        MissingReason fallback = failure == null ? MissingReason.FAILED : failure;
        if (health == null) health = new Read(null, fallback, finished);
        if (pools == null) pools = new Read(null, fallback, finished);
        if (stats == null) stats = new Read(null, fallback, finished);
        if (before == null) before = new Read(null, MissingReason.UNSUPPORTED, finished);
        if (after == null) after = new Read(null, MissingReason.UNSUPPORTED, finished);
        observed.problem(failure);
        List<MetricSample> metrics = new ArrayList<>();
        if (!capacity) {
            if (probe == null) probe = new ServiceProbe(ServiceAvailability.UNKNOWN, health.reason() == null ? fallback : health.reason(), cluster, finished, finished);
            metrics.add(latency == null ? MetricSample.missing(probeDefinition(cluster), health.reason() == null ? fallback : health.reason(), health.at()) : latency);
            appendHealth(metrics, health, cluster, observed);
            appendPools(metrics, pools, nodes, observed);
        }
        boolean exactResponse = requested.isEmpty() || exactIndices(stats, requested);
        boolean shardsComplete = requested.isEmpty() || complete(stats, "_shards");
        if (!requested.isEmpty() && (!exactResponse || !shardsComplete)) observed.problem(stats.reason() == null ? MissingReason.UNSUPPORTED : stats.reason());
        for (int i = 0; i < selected.size(); i++) {
            String index = selected.get(i);
            Scope scope = new Scope(ScopeKind.INDEX, validIndex(index) ? index : "invalid-index#" + i, null);
            Read value = !validIndex(index) ? new Read(null, MissingReason.INVALID_VALUE, stats.at()) : index(stats, index);
            Routing route = routing.get(index);
            boolean complete = value.reason() == null && exactResponse && shardsComplete && (capacity
                    ? number(value, "primaries", "store", "size_in_bytes").reason() == null && number(value, "total", "store", "size_in_bytes").reason() == null
                    : route != null && route.reason() == null);
            if (value.reason() == null) observed.indices++;
            if (!complete) observed.problem(value.reason() == null ? MissingReason.UNSUPPORTED : value.reason());
            if (capacity) {
                metrics.add(gauge(def("elasticsearch.index.store.primary.bytes", "主分片 Store 容量", Unit.BYTES, scope,
                        "index stats primaries.store.size_in_bytes", "当前响应主分片的 Lucene store；容量周期采集，完整性见本索引 coverage；不是文件系统使用量"), value, true, "primaries", "store", "size_in_bytes"));
                metrics.add(gauge(def("elasticsearch.index.store.total.bytes", "主副本 Store 容量", Unit.BYTES, scope,
                        "index stats total.store.size_in_bytes", "当前响应主副本的 Lucene store 合计；可能只是已响应部分，不表示节点整盘或可用磁盘"), value, true, "total", "store", "size_in_bytes"));
            } else {
                metrics.add(gauge(def("elasticsearch.index.docs.count", "主分片 Lucene 文档数", Unit.COUNT, scope,
                        "index stats primaries.docs.count", "当前响应主分片文档数，包含 Lucene nested 文档且受 refresh 影响；不重复累计副本文档"), value, false, "primaries", "docs", "count"));
                appendOperation(metrics, request, scope, value, route, before, after, nodes, complete, "query", "查询阶段", "total", "search", "query_total");
                appendOperation(metrics, request, scope, value, route, before, after, nodes, complete, "indexing", "主分片写入", "primaries", "indexing", "index_total");
            }
            metrics.add(success(def("elasticsearch.index.coverage.complete", "索引响应范围完整", Unit.BOOLEAN, scope,
                    capacity ? "本次精确索引请求、_shards 与 store 字段" : "本次精确索引请求、_shards 与 shard routing",
                    "仅当前响应分片范围和全部配置索引均完整时为真；部分累计仍按已响应副本解释，不能用于完整速率"), complete, stats.at(), capacity));
        }
        observed.problem(failure);
        for (MetricSample sample : metrics) observed.problem(sample.missingReason());
        Scope configuredScope = new Scope(ScopeKind.CONFIGURED_SCOPE, request.targetId(), null);
        appendCoverage(metrics, configuredScope, configured.size(), requested.size(), nodes.size(), indexLimit, nodeLimit, observed, finished, capacity);
        MissingReason reason = observed.reason;
        boolean anyNative = metrics.stream().anyMatch(sample -> sample.value() != null && sample.definition().scope().kind() != ScopeKind.CONFIGURED_SCOPE
                && !sample.definition().key().endsWith("coverage.complete"));
        CollectionStatus status = reason == null ? observed.truncated ? CollectionStatus.PARTIAL : CollectionStatus.SUCCESS
                : anyNative ? CollectionStatus.PARTIAL : failureStatus(reason);
        boolean inventoryComplete = !observed.truncated && (capacity || pools.reason() == null && complete(pools, "_nodes")
                && equalsNumber(pools, nodes.size(), "_nodes", "successful"));
        return new CollectionResult(status, started, finished, metrics, capacity ? null : probe, inventoryComplete, reason);
    }

    private void appendHealth(List<MetricSample> metrics, Read health, Scope scope, Observations observed) {
        observed.problem(health.reason());
        NativeValue status = field(health, "status");
        MissingReason reason = status.reason();
        String color = status.value() != null && status.value().isTextual() ? status.value().textValue() : null;
        if (reason == null && (color == null || !List.of("green", "yellow", "red").contains(color))) reason = MissingReason.INVALID_VALUE;
        metrics.add(sample(def("elasticsearch.cluster.health", "原生分片分配健康", Unit.TEXT, scope, "cluster health.status",
                "原样保留 green/yellow/red；表示主副本分片分配状态，不代表 CPU、内存、磁盘或所有请求健康"), color, reason, health.at(), false));
        metrics.add(gauge(def("elasticsearch.cluster.nodes", "原生集群节点数", Unit.COUNT, scope,
                "cluster health.number_of_nodes", "健康响应中的节点数量，与受展示上限约束的线程池节点行分开"), health, false, "number_of_nodes"));
        metrics.add(gauge(def("elasticsearch.cluster.unassigned_shards", "原生未分配分片数", Unit.COUNT, scope,
                "cluster health.unassigned_shards", "原生集群健康中的未分配主副本分片条目数；不猜测原因或资源健康"), health, false, "unassigned_shards"));
        NativeValue timedOut = field(health, "timed_out");
        boolean validTimeout = timedOut.reason() == null && timedOut.value().isBoolean();
        if (!validTimeout || timedOut.value().booleanValue()) observed.problem(validTimeout ? MissingReason.TIMEOUT
                : timedOut.reason() == null ? MissingReason.INVALID_VALUE : timedOut.reason());
    }

    private List<String> nodes(Read pools, int limit, Observations observed) {
        NativeValue values = field(pools, "nodes");
        if (values.reason() != null || !values.value().isObject()) {
            observed.problem(values.reason() == null ? MissingReason.INVALID_VALUE : values.reason());
            return List.of();
        }
        List<String> selected = new ArrayList<>();
        var entries = values.value().fieldNames();
        int inspected = 0;
        while (entries.hasNext() && inspected < limit) {
            String id = entries.next();
            inspected++;
            if (validNode(id)) selected.add(id); else observed.problem(MissingReason.INVALID_VALUE);
        }
        observed.truncated |= entries.hasNext();
        if (!complete(pools, "_nodes") || !equalsNumber(pools, selected.size(), "_nodes", "successful") && !observed.truncated) {
            observed.problem(MissingReason.UNSUPPORTED);
        }
        return List.copyOf(selected);
    }

    private void appendPools(List<MetricSample> metrics, Read pools, List<String> nodes, Observations observed) {
        observed.problem(pools.reason());
        for (String id : nodes) {
            Scope scope = new Scope(ScopeKind.NODE, id, id);
            for (String pool : POOLS) metrics.add(gauge(def("elasticsearch.thread_pool." + pool + ".rejected", pool + " 线程池拒绝累计", Unit.COUNT,
                    scope, "nodes stats thread_pool." + pool + ".rejected", "仅此节点此池的原生拒绝任务累计；不是所有节点的业务请求数，不派生拒绝速率"),
                    pools, false, "nodes", id, "thread_pool", pool, "rejected"));
        }
    }

    private Map<String, Routing> routing(Read stats, List<String> requested, Observations observed) {
        Map<String, Routing> result = new TreeMap<>();
        int budget = Math.min(MAX_SHARD_ROWS, properties.getLimits().getPartitions());
        int rows = 0;
        for (String index : requested) {
            Read value = index(stats, index);
            NativeValue shardValue = field(value, "shards");
            NativeValue uuid = field(value, "uuid");
            if (shardValue.reason() != null || uuid.reason() != null || !shardValue.value().isObject()
                    || !uuid.value().isTextual() || !validNode(uuid.value().textValue())) {
                result.put(index, new Routing(null, Set.of(), shardValue.reason() != null ? shardValue.reason() : uuid.reason() != null ? uuid.reason() : MissingReason.INVALID_VALUE));
                continue;
            }
            List<String> parts = new ArrayList<>();
            TreeSet<String> nodes = new TreeSet<>();
            MissingReason missing = null;
            BigDecimal query = BigDecimal.ZERO, indexing = BigDecimal.ZERO;
            var shards = shardValue.value().fields();
            while (shards.hasNext() && missing == null) {
                var entry = shards.next();
                if (!entry.getKey().matches("0|[1-9][0-9]{0,9}") || !entry.getValue().isArray() || entry.getValue().isEmpty()) { missing = MissingReason.INVALID_VALUE; break; }
                Set<String> copies = new HashSet<>();
                int primaries = 0;
                for (JsonNode shard : entry.getValue()) {
                    if (++rows > budget) { observed.truncated = true; missing = MissingReason.UNSUPPORTED; break; }
                    JsonNode route = shard.path("routing");
                    String node = route.path("node").asText("");
                    JsonNode relocating = route.path("relocating_node");
                    if (!validNode(node) || !route.path("primary").isBoolean() || !"STARTED".equals(route.path("state").asText())
                            || !relocating.isNull() && !relocating.isMissingNode() || !copies.add(node)) { missing = MissingReason.UNSUPPORTED; break; }
                    boolean primary = route.path("primary").booleanValue();
                    if (primary) primaries++;
                    nodes.add(node);
                    parts.add(entry.getKey() + ":" + node + ":" + primary + ":STARTED");
                    // Cross-check the exact contributors of each ordinary aggregate, not just their response count.
                    if (shard.has("search") || shard.has("indexing")) {
                        BigDecimal q = integer(shard.path("search").path("query_total"));
                        BigDecimal w = integer(shard.path("indexing").path("index_total"));
                        if (q == null || w == null) { missing = MissingReason.INVALID_VALUE; break; }
                        query = query.add(q);
                        if (primary) indexing = indexing.add(w);
                    }
                }
                if (primaries != 1 && missing == null) missing = MissingReason.UNSUPPORTED;
            }
            if (parts.isEmpty() && missing == null) missing = MissingReason.UNSUPPORTED;
            if (missing == null && value.values().path("total").has("search")) {
                if (!numericEquals(query, integer(value.values().path("total").path("search").path("query_total")))
                        || !numericEquals(indexing, integer(value.values().path("primaries").path("indexing").path("index_total")))) missing = MissingReason.INVALID_VALUE;
            }
            parts.sort(String::compareTo);
            if (missing == null) parts.add(0, uuid.value().textValue());
            result.put(index, new Routing(missing == null ? digest(parts) : null, Set.copyOf(nodes), missing));
        }
        if (!equalsNumber(stats, rows, "_shards", "successful")) {
            result.replaceAll((key, value) -> new Routing(value.fingerprint(), value.nodes(), value.reason() == null ? MissingReason.UNSUPPORTED : value.reason()));
        }
        return result;
    }

    private void appendOperation(List<MetricSample> metrics, CollectionRequest request, Scope scope, Read value, Routing route,
                                 Read before, Read after, List<String> knownNodes, boolean complete, String operation, String label, String... path) {
        String meaning = operation.equals("query") ? "已响应主副本 query 阶段操作；跨分片查询计多次，不是业务请求 QPS" : "已响应主分片 indexing 操作；不重复计算副本执行，不等同于最终文档数";
        String source = "index stats " + String.join(".", path);
        metrics.add(gauge(def("elasticsearch.index." + operation + ".total", label + "操作累计", Unit.COUNT, scope, source, meaning), value, false, path));
        Definition definition = def("elasticsearch.index." + operation + ".rate", label + "操作速率", Unit.COUNT_PER_SECOND, scope, source + "、index UUID、routing 与节点启动时间",
                meaning + "；仅完整响应在相同索引/分片/进程身份间按实际秒数求增量，首次或重置等待采样；未观察到的分片迁出迁回不保证检测");
        NativeNumber counter = number(value, path);
        MissingReason missing = counter.reason();
        if (missing == null && !complete) missing = MissingReason.UNSUPPORTED;
        if (missing == null && !knownNodes.containsAll(route.nodes())) missing = MissingReason.UNSUPPORTED;
        if (missing == null && (!complete(before, "_nodes") || !complete(after, "_nodes"))) {
            missing = before.reason() != null ? before.reason() : after.reason() != null ? after.reason() : MissingReason.UNSUPPORTED;
        }
        List<String> epoch = new ArrayList<>();
        if (missing == null) {
            epoch.add(request.bindingId());
            epoch.add(route.fingerprint());
            for (String node : new TreeSet<>(route.nodes())) {
                NativeNumber first = number(before, "nodes", node, "jvm", "start_time_in_millis");
                NativeNumber second = number(after, "nodes", node, "jvm", "start_time_in_millis");
                if (first.reason() != null || second.reason() != null) { missing = first.reason() != null ? first.reason() : second.reason(); break; }
                if (first.value().signum() == 0 || second.value().signum() == 0) { missing = MissingReason.INVALID_VALUE; break; }
                if (!numericEquals(first.value(), second.value())) { missing = MissingReason.WAITING_SAMPLE; break; }
                epoch.add(node + ":" + first.value().toPlainString());
            }
        }
        if (missing != null) { metrics.add(MetricSample.missing(definition, missing, value.at())); return; }
        try {
            var calculated = request.control().calculate(definition, RATE,
                    new MonitoringCalculations.CounterSample(value.at(), digest(epoch), List.of(counter.value())));
            metrics.add(sample(definition, calculated.value(), calculated.reason(), value.at(), false));
        } catch (CollectionControl.InactiveCollectionException inactive) {
            metrics.add(MetricSample.missing(definition, inactive.reason(), value.at()));
        }
    }

    private void appendCoverage(List<MetricSample> metrics, Scope scope, int configured, int requested, int nodes, int indexLimit,
                                int nodeLimit, Observations observed, Instant at, boolean capacity) {
        String source = "服务端配置范围、本次原生响应与有界展示预算";
        metrics.add(success(def("elasticsearch.indices.configured", "配置索引数", Unit.COUNT, scope, source, "连接自身配置的去重索引数；空范围不读取全部索引"), configured, at, capacity));
        metrics.add(success(def("elasticsearch.indices.requested", "纳入请求范围索引数", Unit.COUNT, scope, source, "通过具体名称校验且纳入预算的索引数；连接失败时可能尚未发出"), requested, at, capacity));
        metrics.add(success(def("elasticsearch.indices.observed", "本次响应索引数", Unit.COUNT, scope, source, "只计精确匹配配置名称的索引响应；不输出 alias 或 data stream 展开的额外索引"), observed.indices, at, capacity));
        if (!capacity) metrics.add(success(def("elasticsearch.nodes.observed", "已展示线程池节点数", Unit.COUNT, scope, source, "此次 nodes stats 内通过身份校验并纳入预算的节点数，不是集群总节点数"), nodes, at, false));
        metrics.add(success(def("elasticsearch.coverage.complete", "本次配置范围完整", Unit.BOOLEAN, scope, source, "所有本次端点及指标均可读且无截断；不代表配置外索引或集群资源健康"), observed.reason == null && !observed.truncated, at, capacity));
        metrics.add(success(def("elasticsearch.coverage.truncated", "本次触及条目预算", Unit.BOOLEAN, scope, source, "索引、节点或内部 shard 证据超出预算时为真，不能解释为原生分页"), observed.truncated, at, capacity));
        metrics.add(success(def("elasticsearch.limits.indices", "有效索引上限", Unit.COUNT, scope, source, "配置索引上限与总 series 预算的较小值"), indexLimit, at, capacity));
        metrics.add(success(def("elasticsearch.limits.nodes", "有效节点上限", Unit.COUNT, scope, source, "预留索引行后，节点配置与剩余三池 series 预算的较小值"), nodeLimit, at, capacity));
        metrics.add(success(def("elasticsearch.limits.metrics", "单次指标上限", Unit.COUNT, scope, source, "本适配器保留共享快照预算余量，最多 4500 个 series"), MAX_METRICS, at, capacity));
    }

    private Read read(CollectionRequest request, NativeRead operation, Instant previous) {
        request.control().checkActive();
        try {
            JsonNode data = operation.read();
            request.control().checkActive();
            return data != null && data.isObject() ? new Read(data, null, atLeast(previous)) : new Read(null, MissingReason.INVALID_VALUE, atLeast(previous));
        } catch (ElasticsearchMonitoringConnections.Failure failure) {
            return new Read(null, failure.reason(), atLeast(previous));
        }
    }

    private static Read index(Read stats, String index) {
        NativeValue value = field(stats, "indices", index);
        return value.reason() != null ? new Read(null, value.reason(), stats.at())
                : value.value().isObject() ? new Read(value.value(), null, stats.at()) : new Read(null, MissingReason.INVALID_VALUE, stats.at());
    }

    private static boolean exactIndices(Read stats, List<String> requested) {
        NativeValue value = field(stats, "indices");
        if (value.reason() != null || !value.value().isObject() || value.value().size() != requested.size()) return false;
        return requested.stream().allMatch(value.value()::has);
    }

    private static boolean complete(Read read, String field) {
        NativeNumber total = number(read, field, "total"), successful = number(read, field, "successful"), failed = number(read, field, "failed");
        return total.reason() == null && successful.reason() == null && failed.reason() == null
                && failed.value().signum() == 0 && numericEquals(total.value(), successful.value());
    }

    private static NativeValue field(Read read, String... path) {
        if (read.reason() != null) return new NativeValue(null, read.reason());
        JsonNode value = read.values();
        for (String part : path) {
            if (!value.isObject()) return new NativeValue(null, MissingReason.INVALID_VALUE);
            value = value.get(part);
            if (value == null) return new NativeValue(null, MissingReason.UNSUPPORTED);
        }
        return new NativeValue(value, null);
    }

    private static NativeNumber number(Read read, String... path) {
        NativeValue value = field(read, path);
        if (value.reason() != null) return new NativeNumber(null, value.reason());
        BigDecimal number = integer(value.value());
        return new NativeNumber(number, number == null ? MissingReason.INVALID_VALUE : null);
    }

    private static BigDecimal integer(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? new BigDecimal(value.bigIntegerValue()) : null;
    }
    private static boolean equalsNumber(Read read, long expected, String... path) {
        return numericEquals(BigDecimal.valueOf(expected), number(read, path).value());
    }
    private static boolean numericEquals(BigDecimal left, BigDecimal right) { return left != null && right != null && left.compareTo(right) == 0; }
    private static boolean validNode(String id) { return id != null && id.matches("[A-Za-z0-9_-]{22}"); }
    private static boolean validIndex(String index) {
        return ElasticsearchMonitoringConnections.validIndex(index);
    }
    private static String digest(List<String> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) { digest.update(part.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest unavailable"); }
    }
    private MetricSample gauge(Definition definition, Read read, boolean capacity, String... path) {
        NativeNumber value = number(read, path);
        return sample(definition, value.value(), value.reason(), read.at(), capacity);
    }
    private MetricSample sample(Definition definition, Object value, MissingReason reason, Instant at, boolean capacity) {
        return reason == null ? success(definition, value, at, capacity) : MetricSample.missing(definition, reason, at);
    }
    private MetricSample success(Definition definition, Object value, Instant at, boolean capacity) {
        return MetricSample.success(definition, value, at, (capacity ? properties.getCapacityInterval() : properties.getOrdinaryInterval()).multipliedBy(properties.getMetricTtlMultiplier()));
    }
    private ServiceProbe available(Scope scope, Instant at) { return new ServiceProbe(ServiceAvailability.AVAILABLE, null, scope, at, at.plus(properties.getServiceTtl())); }
    private static Definition probeDefinition(Scope scope) { return def("elasticsearch.probe.duration", "原生 HTTP 探测耗时", Unit.MILLISECONDS, scope,
            "现有 RestClient 的 cluster health 只读请求", "单调时钟测量原生健康端点响应耗时；任何原生颜色均可证明端点响应，不把分片分配色彩当资源健康"); }
    private static Definition def(String key, String label, Unit unit, Scope scope, String source, String calculation) { return new Definition(key, label, unit, scope, source, calculation); }
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
    @FunctionalInterface private interface NativeRead { JsonNode read(); }
    private record Read(JsonNode values, MissingReason reason, Instant at) { }
    private record NativeValue(JsonNode value, MissingReason reason) { }
    private record NativeNumber(BigDecimal value, MissingReason reason) { }
    private record Routing(String fingerprint, Set<String> nodes, MissingReason reason) { }
    private static final class Observations {
        private MissingReason reason;
        private boolean truncated;
        private int indices;
        private void problem(MissingReason next) {
            if (next != null && (reason == null || reason == MissingReason.WAITING_SAMPLE && next != MissingReason.WAITING_SAMPLE)) reason = next;
        }
    }
}
