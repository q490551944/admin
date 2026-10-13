package com.hpj.admin.monitor.minio;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCalculations;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.hpj.admin.monitor.metric.MetricContract.*;

/** A bounded whitelist of the native v2 text exposition; no aggregation of overlapping label sets. */
final class MinioNativeMetrics {
    private static final Pattern SAMPLE = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{([^}]{0,8192})})?\\s+([^\\s]{1,64})(?:\\s+[0-9]{1,20})?");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\[\\\\\"n]){0,512})\"");
    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();
    static {
        field("minio_s3_requests_total", "requests.total", "S3 请求累计", Unit.COUNT, false, true, false);
        field("minio_s3_requests_errors_total", "requests.errors.total", "S3 请求错误累计", Unit.COUNT, false, true, false);
        field("minio_s3_requests_4xx_errors_total", "requests.4xx.total", "S3 4xx 错误累计", Unit.COUNT, false, true, false);
        field("minio_s3_requests_5xx_errors_total", "requests.5xx.total", "S3 5xx 错误累计", Unit.COUNT, false, true, false);
        field("minio_s3_traffic_received_bytes", "traffic.received.bytes", "S3 接收累计字节", Unit.BYTES, false, true, false);
        field("minio_s3_traffic_sent_bytes", "traffic.sent.bytes", "S3 发送累计字节", Unit.BYTES, false, true, false);
        field("minio_node_process_cpu_total_seconds", "process.cpu.seconds", "MinIO 进程 CPU 累计秒", Unit.SECONDS, false, true, true);
        field("minio_node_process_resident_memory_bytes", "process.resident.bytes", "MinIO 进程驻留内存", Unit.BYTES, false, false, true);
        field("minio_node_process_virtual_memory_bytes", "process.virtual.bytes", "MinIO 进程虚拟内存", Unit.BYTES, false, false, true);
        field("minio_node_process_starttime_seconds", "process.start.seconds", "MinIO 进程原生启动时间", Unit.SECONDS, false, false, true);
        field("minio_cluster_capacity_raw_total_bytes", "cluster.raw.total.bytes", "集群原始在线容量", Unit.BYTES, true, false, false);
        field("minio_cluster_capacity_raw_free_bytes", "cluster.raw.free.bytes", "集群原始在线空闲容量", Unit.BYTES, true, false, false);
        field("minio_cluster_capacity_usable_total_bytes", "cluster.usable.total.bytes", "集群可用容量总量", Unit.BYTES, true, false, false);
        field("minio_cluster_capacity_usable_free_bytes", "cluster.usable.free.bytes", "集群可用容量空闲量", Unit.BYTES, true, false, false);
        field("minio_cluster_usage_object_total", "cluster.objects.total", "原生扫描器对象数量", Unit.COUNT, true, false, false);
        field("minio_cluster_usage_total_bytes", "cluster.objects.bytes", "原生扫描器逻辑对象字节", Unit.BYTES, true, false, false);
        field("minio_cluster_drive_online_total", "cluster.drives.online", "集群在线磁盘数", Unit.COUNT, true, false, false);
        field("minio_cluster_drive_offline_total", "cluster.drives.offline", "集群离线磁盘数", Unit.COUNT, true, false, false);
        field("minio_node_drive_free_bytes", "drive.free.bytes", "原生磁盘空闲字节", Unit.BYTES, true, false, false);
    }
    private static void field(String nativeName, String key, String label, Unit unit, boolean capacity, boolean rate, boolean process) {
        FIELDS.put(nativeName, new Field(nativeName, key, label, unit, capacity, rate, process));
    }
    private record Field(String nativeName, String key, String label, Unit unit, boolean capacity, boolean rate, boolean process) { }
    private record Row(Field field, Map<String, String> labels, BigDecimal value, MissingReason reason) { }
    record Result(List<MetricSample> metrics, MissingReason reason, boolean inventoryComplete) { }

    static Result unavailable(CollectionRequest request, Instant at, MissingReason reason) {
        return new Result(List.of(MetricSample.missing(capability(request), reason, at)), reason, false);
    }
    static Result map(CollectionRequest request, MonitoringProperties properties, MinioMonitoringConnections.MetricsObservation observation, Instant at) {
        if (observation == null) return unavailable(request, at, MissingReason.INVALID_VALUE);
        if (observation.status() != 200) return unavailable(request, at, httpReason(observation.status()));
        boolean capacity = request.kind() == CollectionKind.CAPACITY;
        TreeMap<String, Row> rows = new TreeMap<>();
        boolean truncated = false;
        try (var reader = new java.io.BufferedReader(new java.io.StringReader(Objects.requireNonNull(observation.body())))) {
            String line; int lines = 0;
            while ((line = reader.readLine()) != null) {
                if ((lines & 63) == 0) request.control().checkActive();
                if (++lines > 20000 || line.length() > 16384) return unavailable(request, at, MissingReason.INVALID_VALUE);
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                var sample = SAMPLE.matcher(line);
                if (!sample.matches()) return unavailable(request, at, MissingReason.INVALID_VALUE);
                Field field = FIELDS.get(sample.group(1));
                if (field == null || field.capacity() != capacity) continue;
                Map<String, String> labels = labels(sample.group(3));
                BigDecimal value = null; MissingReason reason = null;
                try {
                    value = new BigDecimal(sample.group(4));
                    if (value.signum() < 0 || value.precision() > 40 || Math.abs((long) value.scale()) > 18
                            || (field.unit() == Unit.BYTES || field.unit() == Unit.COUNT) && value.stripTrailingZeros().scale() > 0) reason = MissingReason.INVALID_VALUE;
                } catch (NumberFormatException invalid) { reason = MissingReason.INVALID_VALUE; }
                String identity = field.nativeName() + ":" + canonicalLabels(labels);
                if (rows.putIfAbsent(identity, new Row(field, labels, reason == null ? value : null, reason)) != null) return unavailable(request, at, MissingReason.INVALID_VALUE);
                if (rows.size() > 2000) { rows.pollLastEntry(); truncated = true; }
            }
        } catch (com.hpj.admin.monitor.metric.CollectionControl.InactiveCollectionException inactive) {
            return unavailable(request, at, inactive.reason());
        } catch (Exception invalid) { return unavailable(request, at, MissingReason.INVALID_VALUE); }
        List<MetricSample> metrics = new ArrayList<>();
        var interval = capacity ? properties.getCapacityInterval() : properties.getOrdinaryInterval();
        var ttl = interval.multipliedBy(properties.getMetricTtlMultiplier());
        metrics.add(MetricSample.success(capability(request), true, at, ttl));
        Set<String> observed = new HashSet<>();
        int limit = capacity ? 490 : 2000;
        MissingReason failure = null;
        Set<String> nodes = new TreeSet<>();
        Map<String, Row> starts = new HashMap<>();
        Set<String> ambiguousStarts = new HashSet<>();
        for (Row row : rows.values()) if (row.field().nativeName().equals("minio_node_process_starttime_seconds")) {
            String server = row.labels().getOrDefault("server", "");
            if (starts.putIfAbsent(server, row) != null) ambiguousStarts.add(server);
        }
        for (Row row : rows.values()) nodes.add(row.labels().getOrDefault("server", ""));
        Set<String> selectedNodes = new HashSet<>(nodes.stream().limit(Math.min(128, properties.getLimits().getNodes())).toList());
        truncated |= selectedNodes.size() < nodes.size();
        for (Row row : rows.values()) {
            request.control().checkActive();
            if (!selectedNodes.contains(row.labels().getOrDefault("server", ""))) continue;
            if (metrics.size() + (row.field().rate() ? 2 : 1) > limit) { truncated = true; continue; }
            observed.add(row.field().nativeName());
            Scope scope = scope(request, row);
            Definition definition = definition(row.field(), scope);
            metrics.add(row.reason() == null ? MetricSample.success(definition, row.value(), at, ttl) : MetricSample.missing(definition, row.reason(), at));
            if (failure == null && row.reason() != null) failure = row.reason();
            if (row.field().rate()) {
                String server = row.labels().getOrDefault("server", "");
                Row start = ambiguousStarts.contains(server) ? null : starts.get(server);
                Definition rate = new Definition("minio.native." + row.field().key() + ".rate", row.field().label() + "速率",
                        row.field().process() ? Unit.CORES : row.field().unit() == Unit.BYTES ? Unit.BYTES_PER_SECOND : Unit.COUNT_PER_SECOND,
                        scope, definition.source(), row.field().process() ? "进程 CPU 秒增量 / 实际秒数，单位为 CPU 核；不推定主机核数或百分比"
                        : "原生累计数增量 / 实际采样秒数；同节点原生启动时间绑定重置基线，不跨 label 求和");
                MissingReason missing = row.reason() != null ? row.reason() : start == null || start.reason() != null || start.value().signum() == 0 ? MissingReason.UNSUPPORTED : null;
                if (missing != null) metrics.add(MetricSample.missing(rate, missing, at));
                else {
                    var calculated = request.control().calculate(rate, MonitoringCalculations.Calculation.RATE,
                            new MonitoringCalculations.CounterSample(at, digest(request.bindingId() + ":" + row.labels().getOrDefault("server", "") + ":" + start.value().toPlainString()), List.of(row.value())));
                    metrics.add(calculated.reason() == null ? MetricSample.success(rate, calculated.value(), at, ttl) : MetricSample.missing(rate, calculated.reason(), at));
                    if (failure == null && calculated.reason() != null) failure = calculated.reason();
                }
                if (failure == null && missing != null) failure = missing;
            }
        }
        for (Field field : FIELDS.values()) if (field.capacity() == capacity && !observed.contains(field.nativeName()) && metrics.size() < limit) {
            metrics.add(MetricSample.missing(definition(field, new Scope(capacity ? ScopeKind.CLUSTER : ScopeKind.ENDPOINT, request.targetId(), null)), MissingReason.UNSUPPORTED, at));
            if (failure == null) failure = MissingReason.UNSUPPORTED;
        }
        metrics.add(MetricSample.success(new Definition("minio.native.coverage.truncated", "原生统计触及条目预算", Unit.BOOLEAN,
                new Scope(ScopeKind.CONFIGURED_SCOPE, request.targetId(), null), "原生 v2 有界字段与节点范围", "客户端字段截断，不是服务端分页；不自行聚合节点或 label"), truncated, at, ttl));
        return new Result(List.copyOf(metrics), failure == null && truncated ? MissingReason.PARTIAL_SCOPE : failure, !truncated && failure == null);
    }

    private static Map<String, String> labels(String body) {
        if (body == null || body.isEmpty()) return Map.of();
        Map<String, String> labels = new TreeMap<>();
        var matcher = LABEL.matcher(body); int offset = 0;
        while (matcher.find()) {
            if (matcher.start() != offset || labels.size() >= 16) throw new IllegalArgumentException();
            String value = matcher.group(2).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            if (value.chars().anyMatch(Character::isISOControl) || labels.putIfAbsent(matcher.group(1), value) != null) throw new IllegalArgumentException();
            offset = matcher.end();
            if (offset < body.length() && body.charAt(offset) == ',') offset++;
        }
        if (offset != body.length()) throw new IllegalArgumentException();
        return Collections.unmodifiableMap(labels);
    }
    private static Scope scope(CollectionRequest request, Row row) {
        String server = row.labels().get("server");
        String node = server == null || server.isEmpty() ? null : "minio-node-" + digest(server);
        ScopeKind kind = row.field().process() ? ScopeKind.PROCESS : row.field().capacity() ? row.field().key().startsWith("drive.") ? ScopeKind.FILESYSTEM : ScopeKind.CLUSTER : ScopeKind.ENDPOINT;
        return new Scope(kind, row.labels().isEmpty() ? request.targetId() : "native-" + digest(canonicalLabels(row.labels())), node);
    }
    private static String canonicalLabels(Map<String, String> labels) {
        StringBuilder value = new StringBuilder();
        labels.forEach((key, label) -> value.append(key.length()).append(':').append(key).append(label.length()).append(':').append(label));
        return value.toString();
    }
    private static Definition capability(CollectionRequest request) {
        return new Definition("minio.native.metrics.available", "原生统计接口能力", Unit.BOOLEAN, new Scope(ScopeKind.ENDPOINT, request.targetId(), null),
                request.kind() == CollectionKind.CAPACITY ? "GET /minio/v2/metrics/cluster" : "GET /minio/v2/metrics/node", "仅使用应用已有且匹配当前连接的独立 Bearer 授权；S3 密钥不作为统计凭据");
    }
    private static Definition definition(Field field, Scope scope) {
        return new Definition("minio.native." + field.key(), field.label(), field.unit(), scope,
                "MinIO 原生 v2 " + field.nativeName(), field.capacity() ? "原生集群/磁盘统计，逻辑对象量来自后台扫描器，可能滞后；不遍历对象，不跨 label 或磁盘求和"
                : "原生返回的指定 label 范围；不使用 admin 资源，不推定为宿主机，不跨节点或 API 求和");
    }
    private static MissingReason httpReason(int status) {
        if (status == 401 || status == 403) return MissingReason.UNAUTHORIZED;
        if (status == 404 || status == 405 || status == 501 || status >= 300 && status < 400) return MissingReason.UNSUPPORTED;
        return status == 429 ? MissingReason.BUSY : MissingReason.FAILED;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
