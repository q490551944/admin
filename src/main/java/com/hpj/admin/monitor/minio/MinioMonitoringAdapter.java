package com.hpj.admin.monitor.minio;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.minio.MinioMonitoringConnections.HealthEndpoint;
import static com.hpj.admin.monitor.minio.MinioMonitoringConnections.Observation;

/** Native HEAD results keep endpoint liveness, cluster readiness and configured-bucket access separate. */
public final class MinioMonitoringAdapter implements MonitoringAdapter {
    private final MonitoringProperties properties;
    private final MinioMonitoringConnections connections;
    private final Clock clock;

    public MinioMonitoringAdapter(MonitoringProperties properties, MinioMonitoringConnections connections, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public MiddlewareType type() { return MiddlewareType.MINIO; }

    @Override public CollectionResult collect(CollectionRequest request) {
        Instant started = atLeast(request.scheduledAt());
        // Capacity and optional native metrics have a separate capability; no S3 object enumeration is permitted.
        if (request.kind() == CollectionKind.CAPACITY) {
            return new CollectionResult(CollectionStatus.UNSUPPORTED, started, started, List.of(), null, false, MissingReason.NOT_APPLICABLE);
        }
        List<String> configured = configuredBuckets(request);
        int limit = Math.min(MinioMonitoringConnections.MAX_BUCKETS, Math.max(0, properties.getLimits().getBuckets()));
        List<String> selected = configured.subList(0, Math.min(configured.size(), limit));
        boolean truncated = configured.size() > selected.size();
        Map<HealthEndpoint, Read> health = new EnumMap<>(HealthEndpoint.class);
        Map<String, Read> buckets = new LinkedHashMap<>();
        MissingReason failure = null;
        boolean connectionFailure = false;
        Instant previous = started;
        try {
            request.control().checkActive();
            try (MinioMonitoringConnections.Session session = connections.open(request)) {
                for (HealthEndpoint endpoint : HealthEndpoint.values()) {
                    Read read = read(request, () -> session.health(endpoint), previous);
                    health.put(endpoint, read);
                    previous = read.at();
                }
                for (String bucket : selected) {
                    Read read = MinioMonitoringConnections.validBucket(bucket)
                            ? read(request, () -> session.bucket(bucket), previous)
                            : missing(MissingReason.INVALID_VALUE, false, previous);
                    buckets.put(bucket, read);
                    previous = read.at();
                }
            }
        } catch (MinioMonitoringConnections.Failure error) {
            failure = error.reason();
            connectionFailure = error.connectionFailure();
        } catch (CollectionControl.InactiveCollectionException inactive) {
            failure = inactive.reason();
        }
        Instant finished = atLeast(previous);
        MissingReason fallback = failure == null ? MissingReason.FAILED : failure;
        for (HealthEndpoint endpoint : HealthEndpoint.values()) health.putIfAbsent(endpoint, missing(fallback, connectionFailure, finished));
        for (String bucket : selected) buckets.putIfAbsent(bucket, missing(MinioMonitoringConnections.validBucket(bucket) ? fallback : MissingReason.INVALID_VALUE, false, finished));

        List<MetricSample> metrics = new ArrayList<>();
        Scope endpointScope = new Scope(ScopeKind.ENDPOINT, request.targetId(), null);
        Scope clusterScope = new Scope(ScopeKind.CLUSTER, request.targetId(), null);
        appendHealth(metrics, HealthEndpoint.LIVE, health.get(HealthEndpoint.LIVE), endpointScope);
        appendHealth(metrics, HealthEndpoint.READ_READY, health.get(HealthEndpoint.READ_READY), clusterScope);
        appendHealth(metrics, HealthEndpoint.WRITE_READY, health.get(HealthEndpoint.WRITE_READY), clusterScope);
        int requested = 0;
        for (int i = 0; i < selected.size(); i++) {
            String bucket = selected.get(i);
            boolean valid = MinioMonitoringConnections.validBucket(bucket);
            if (valid) requested++;
            appendBucket(metrics, buckets.get(bucket), new Scope(ScopeKind.BUCKET, valid ? bucket : "invalid-bucket#" + i, null));
        }
        MissingReason reason = failure;
        for (MetricSample metric : metrics) if (reason == null && metric.missingReason() != null) reason = metric.missingReason();
        boolean anyConclusion = metrics.stream().anyMatch(metric -> metric.definition().unit() == Unit.BOOLEAN && metric.value() != null);
        appendCoverage(metrics, new Scope(ScopeKind.CONFIGURED_SCOPE, request.targetId(), null), configured.size(), requested, limit,
                truncated, reason == null && !truncated, finished);
        CollectionStatus status = reason == null ? truncated ? CollectionStatus.PARTIAL : CollectionStatus.SUCCESS
                : anyConclusion ? CollectionStatus.PARTIAL : failureStatus(reason);
        return new CollectionResult(status, started, finished, metrics, probe(health.get(HealthEndpoint.LIVE), endpointScope), !truncated, reason);
    }

    private static List<String> configuredBuckets(CollectionRequest request) {
        if (!request.scope().buckets().isEmpty()) return new ArrayList<>(new LinkedHashSet<>(request.scope().buckets()));
        Object attachmentBucket = request.settings().get("bucket");
        return attachmentBucket instanceof String bucket && !bucket.isBlank() ? List.of(bucket) : List.of();
    }

    private void appendHealth(List<MetricSample> metrics, HealthEndpoint endpoint, Read read, Scope scope) {
        String key = switch (endpoint) {
            case LIVE -> "minio.health.live";
            case READ_READY -> "minio.health.read.ready";
            case WRITE_READY -> "minio.health.write.ready";
        };
        String label = switch (endpoint) {
            case LIVE -> "端点存活探测";
            case READ_READY -> "集群读就绪";
            case WRITE_READY -> "集群写就绪";
        };
        String path = switch (endpoint) {
            case LIVE -> "/minio/health/live";
            case READ_READY -> "/minio/health/cluster/read";
            case WRITE_READY -> "/minio/health/cluster";
        };
        Verdict verdict = healthVerdict(endpoint, read);
        String calculation = endpoint == HealthEndpoint.LIVE
                ? "仅此端点的原生存活结果：HTTP 200 为真，429 或 503 为假；不表示集群读写就绪或桶权限"
                : "仅对应原生集群就绪结果：HTTP 200 为真，503 为假；不从 live 或桶访问推断，也不执行读写对象";
        metrics.add(sample(def(key, label, Unit.BOOLEAN, scope, "HEAD " + path, calculation), verdict.value(), verdict.reason(), read.at()));
        appendResponse(metrics, key, label, "HEAD " + path, scope, read);
    }

    private void appendBucket(List<MetricSample> metrics, Read read, Scope scope) {
        Verdict verdict = bucketVerdict(read);
        String source = "现有 S3 凭据的限定桶区域查询及 HEAD 检查";
        metrics.add(sample(def("minio.bucket.accessible", "配置桶访问探测", Unit.BOOLEAN, scope, source,
                "只有最终 HEAD 200 为真；区域查询或 HEAD 404 为假，表示当前凭据下未找到或不可见，不能证明全局不存在；403 为权限不足的缺失值；不证明对象读写权限或服务健康"), verdict.value(), verdict.reason(), read.at()));
        appendResponse(metrics, "minio.bucket", "配置桶探测", source, scope, read);
    }

    private void appendResponse(List<MetricSample> metrics, String key, String label, String source, Scope scope, Read read) {
        String statusScope = scope.kind() == ScopeKind.BUCKET
                ? "原样记录本次桶探测最后原生响应的 HTTP 状态码；区域查询失败时尚未执行 HEAD；权限拒绝、接口缺失或重定向不等于服务宕机"
                : "原样记录此健康请求的 HTTP 状态码；权限拒绝、接口缺失或重定向不等于服务宕机";
        metrics.add(sample(def(key + ".http.status", label + " HTTP 状态码", Unit.COUNT, scope, source,
                statusScope), read.observation() == null ? null : read.observation().status(), read.reason(), read.at()));
        BigDecimal elapsed = read.observation() == null ? null : BigDecimal.valueOf(read.observation().elapsed().getSeconds()).multiply(BigDecimal.valueOf(1000))
                .add(BigDecimal.valueOf(read.observation().elapsed().getNano(), 6));
        String latencyScope = scope.kind() == ScopeKind.BUCKET
                ? "单调时钟测量本次限定桶探测的耗时，含必要的区域查询及 HEAD 检查；区域查询失败时尚未执行 HEAD；含拒绝或缺失响应，网络无结果时缺失，不是对象读写耗时"
                : "单调时钟测量本次 HEAD 请求至响应头的耗时，含拒绝或缺失响应；网络无结果时缺失，不是对象读写耗时";
        metrics.add(sample(def(key + ".latency.ms", label + " 响应耗时", Unit.MILLISECONDS, scope, source, latencyScope), elapsed, read.reason(), read.at()));
    }

    private void appendCoverage(List<MetricSample> metrics, Scope scope, int configured, int requested, int limit,
                                boolean truncated, boolean complete, Instant at) {
        String source = "连接配置桶范围与本次原生 HEAD 响应";
        metrics.add(success(def("minio.buckets.configured", "配置桶数", Unit.COUNT, scope, source,
                "显式监控桶范围优先，空范围使用已有附件桶；按名称去重；均为空时不枚举桶"), configured, at));
        metrics.add(success(def("minio.buckets.requested", "纳入请求范围桶数", Unit.COUNT, scope, source,
                "通过名称校验并纳入条目预算的桶数；失败或预算到期时可能尚未发出请求"), requested, at));
        metrics.add(success(def("minio.buckets.limit", "有效桶上限", Unit.COUNT, scope, source,
                "配置桶上限与 100 桶硬上限的较小值；每桶至多一次 HEAD 及一次必要的区域查询"), limit, at));
        metrics.add(success(def("minio.coverage.complete", "本次采集范围完整", Unit.BOOLEAN, scope, source,
                "三类 health 和全部配置桶均有可解释结果且未截断；false 就绪或桶不存在也是已观察结果，不表示资源健康"), complete, at));
        metrics.add(success(def("minio.coverage.truncated", "本次触及桶预算", Unit.BOOLEAN, scope, source,
                "配置桶超出本次有效上限时为真，未采集桶不推断为不存在或不可访问"), truncated, at));
    }

    private ServiceProbe probe(Read live, Scope scope) {
        Verdict verdict = healthVerdict(HealthEndpoint.LIVE, live);
        ServiceAvailability availability;
        MissingReason reason = verdict.reason();
        if (Boolean.TRUE.equals(verdict.value())) availability = ServiceAvailability.AVAILABLE;
        else if (Boolean.FALSE.equals(verdict.value())) {
            availability = ServiceAvailability.DEGRADED;
            reason = live.observation().status() == 429 ? MissingReason.BUSY : MissingReason.FAILED;
        } else availability = live.connectionFailure() && reason == MissingReason.FAILED
                ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN;
        return new ServiceProbe(availability, reason, scope, live.at(), live.at().plus(properties.getServiceTtl()));
    }

    private static Verdict healthVerdict(HealthEndpoint endpoint, Read read) {
        if (read.reason() != null) return new Verdict(null, read.reason());
        int status = read.observation().status();
        if (status == 200) return new Verdict(true, null);
        if (status == 503 || endpoint == HealthEndpoint.LIVE && status == 429) return new Verdict(false, null);
        return new Verdict(null, httpReason(status));
    }

    private static Verdict bucketVerdict(Read read) {
        if (read.reason() != null) return new Verdict(null, read.reason());
        return switch (read.observation().status()) {
            case 200 -> new Verdict(true, null);
            case 404 -> new Verdict(false, null);
            default -> new Verdict(null, httpReason(read.observation().status()));
        };
    }

    private static MissingReason httpReason(int status) {
        if (status == 401 || status == 403) return MissingReason.UNAUTHORIZED;
        if (status == 404 || status == 405 || status == 501 || status >= 300 && status < 400) return MissingReason.UNSUPPORTED;
        if (status == 429) return MissingReason.BUSY;
        return MissingReason.FAILED;
    }

    private Read read(CollectionRequest request, NativeRead operation, Instant previous) {
        request.control().checkActive();
        try {
            Observation observation = operation.read();
            request.control().checkActive();
            Instant at = atLeast(previous);
            if (observation == null || observation.status() < 100 || observation.status() > 599
                    || observation.elapsed() == null || observation.elapsed().isNegative()) return missing(MissingReason.INVALID_VALUE, false, at);
            return new Read(observation, null, false, at);
        } catch (MinioMonitoringConnections.Failure failure) {
            request.control().checkActive();
            return missing(failure.reason(), failure.connectionFailure(), atLeast(previous));
        }
    }

    private static Read missing(MissingReason reason, boolean connectionFailure, Instant at) { return new Read(null, reason, connectionFailure, at); }
    private MetricSample sample(Definition definition, Object value, MissingReason reason, Instant at) {
        return reason == null ? success(definition, value, at) : MetricSample.missing(definition, reason, at);
    }
    private MetricSample success(Definition definition, Object value, Instant at) {
        return MetricSample.success(definition, value, at, properties.getOrdinaryInterval().multipliedBy(properties.getMetricTtlMultiplier()));
    }
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
    @FunctionalInterface private interface NativeRead { Observation read(); }
    private record Read(Observation observation, MissingReason reason, boolean connectionFailure, Instant at) { }
    private record Verdict(Boolean value, MissingReason reason) { }
}
