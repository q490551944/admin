package com.hpj.admin.monitor.api;

import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ReadState;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ReadTarget;

import java.time.Instant;
import java.time.Duration;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

import static com.hpj.admin.monitor.api.MonitoringResponses.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Pure current-state projection. Reads never change stored samples, deadlines or counter baselines. */
public final class MonitoringResponseProjector {
    private final MonitoringProperties properties;
    public MonitoringResponseProjector() { this(new MonitoringProperties()); }
    public MonitoringResponseProjector(MonitoringProperties properties) { this.properties = java.util.Objects.requireNonNull(properties); }
    public CatalogResponse catalog(ReadState state, Instant serverTime) {
        List<DirectoryTarget> targets = state.targets().stream().map(this::directory).toList();
        List<TypeCatalog> types = Arrays.stream(MiddlewareType.values()).map(type -> {
            List<DirectoryTarget> members = targets.stream().filter(target -> target.type() == type).toList();
            int configured = count(members, ConfigurationStatus.CONFIGURED);
            int missing = count(members, ConfigurationStatus.CONFIGURATION_MISSING);
            int disabled = count(members, ConfigurationStatus.DISABLED);
            int resolving = count(members, null);
            ConfigurationStatus status = configured > 0 ? ConfigurationStatus.CONFIGURED
                    : resolving > 0 ? null : missing > 0 ? ConfigurationStatus.CONFIGURATION_MISSING
                    : ConfigurationStatus.DISABLED;
            return new TypeCatalog(type, type.displayName(), status, configured, missing, disabled, resolving, members);
        }).toList();
        return new CatalogResponse(serverTime, state.generation(), state.resolutionState(), types);
    }

    public SnapshotResponse project(ReadState state, Instant serverTime) {
        Map<String, TargetSnapshot> samples = samples(state);
        List<TargetOverview> targets = state.targets().stream().map(target -> {
            TargetSnapshot sample = samples.get(target.display().id());
            return overview(target, sample, metrics(sample, serverTime), serverTime);
        }).toList();
        Map<ServiceAvailability, Integer> summary = new EnumMap<>(ServiceAvailability.class);
        for (ServiceAvailability availability : ServiceAvailability.values()) summary.put(availability, 0);
        targets.stream().filter(target -> target.schedulingState() == SchedulingState.ACTIVE)
                .forEach(target -> summary.compute(target.serviceAvailability(), (key, count) -> count + 1));
        return new SnapshotResponse(serverTime, state.generation(), state.resolutionState(), targets, rules(), summary);
    }

    public Optional<DetailResponse> detail(ReadState state, Instant serverTime, String targetId) {
        return state.targets().stream().filter(target -> target.display().id().equals(targetId)).findFirst().map(target -> {
            TargetSnapshot sample = samples(state).get(targetId);
            List<MetricObservation> metrics = metrics(sample, serverTime);
            return new DetailResponse(serverTime, state.generation(), state.resolutionState(), overview(target, sample, metrics, serverTime),
                    metrics, metrics.isEmpty() ? CapabilityState.UNKNOWN : CapabilityState.OBSERVED,
                    metrics.stream().map(this::capability).toList(), rules());
        });
    }

    private DirectoryTarget directory(ReadTarget target) {
        var display = target.display();
        return new DirectoryTarget(display.id(), display.type(), display.name(), display.status(),
                target.resolutionReason(), display.connectionSources(), display.scope(), target.memberIds(),
                target.bindings().stream().map(binding ->
                        new Binding(binding.bindingId(), binding.source().reference(), binding.scope())).toList());
    }

    private TargetOverview overview(ReadTarget target, TargetSnapshot sample, List<MetricObservation> metrics, Instant serverTime) {
        SchedulingState scheduling = target.display().status() == null ? SchedulingState.RESOLVING
                : switch (target.display().status()) {
                    case DISABLED -> SchedulingState.DISABLED;
                    case CONFIGURATION_MISSING -> SchedulingState.CONFIGURATION_MISSING;
                    case CONFIGURED -> SchedulingState.ACTIVE;
                };
        Map<BindingKind, Attempt> attempts = new HashMap<>();
        if (sample != null) sample.attempts().forEach(attempt ->
                attempts.put(new BindingKind(attempt.bindingId(), attempt.kind()), attempt));
        List<CollectionObservation> collections = new ArrayList<>();
        List<ServiceObservation> services = new ArrayList<>();
        Set<BindingKind> expiredKinds = new HashSet<>();
        Map<BindingKind, Instant> valueDeadlines = new HashMap<>();
        metrics.stream().filter(metric -> metric.validUntil() != null).forEach(metric ->
                valueDeadlines.merge(new BindingKind(metric.bindingId(), metric.kind()), metric.validUntil(),
                        (left, right) -> left.isBefore(right) ? left : right));
        metrics.stream().filter(metric -> metric.valueState() == ValueState.EXPIRED)
                .forEach(metric -> expiredKinds.add(new BindingKind(metric.bindingId(), metric.kind())));
        if (scheduling == SchedulingState.ACTIVE) {
            for (var binding : target.bindings()) {
                for (CollectionKind kind : CollectionKind.values()) {
                    Attempt attempt = attempts.get(new BindingKind(binding.bindingId(), kind));
                    Instant validUntil = attempt == null ? null : expires(attempt.completedAt(), interval(kind));
                    boolean expired = validUntil != null && serverTime.isAfter(validUntil);
                    CollectionStatus status = attempt == null ? CollectionStatus.WAITING : expired ? CollectionStatus.STALE : attempt.status();
                    boolean expiredValues = expiredKinds.contains(new BindingKind(binding.bindingId(), kind));
                    if (expiredValues && status == CollectionStatus.SUCCESS) status = CollectionStatus.STALE;
                    collections.add(new CollectionObservation(binding.bindingId(), binding.source().reference(), kind,
                            status, attempt == null ? MissingReason.WAITING_SAMPLE : attempt.reason(), attempt, validUntil,
                            valueDeadlines.get(new BindingKind(binding.bindingId(), kind)), expired || expiredValues));
                }
                ServiceProbe probe = sample == null ? null : sample.serviceProbes().get(binding.bindingId());
                boolean expired = probe != null && serverTime.isAfter(probe.validUntil());
                services.add(new ServiceObservation(binding.bindingId(), binding.source().reference(),
                        probe == null || expired ? ServiceAvailability.UNKNOWN : probe.availability(),
                        probe == null ? MissingReason.WAITING_SAMPLE : probe.reason(), probe, probe == null ? null : probe.validUntil(), expired));
            }
        }
        ServiceAvailability service = service(services);
        CollectionStatus collection = collection(collections);
        boolean exceptional = scheduling == SchedulingState.CONFIGURATION_MISSING || scheduling == SchedulingState.RESOLVING
                || scheduling == SchedulingState.ACTIVE && (service != ServiceAvailability.AVAILABLE || collection != CollectionStatus.SUCCESS
                || metrics.stream().anyMatch(metric -> metric.valueState() != ValueState.CURRENT));
        return new TargetOverview(directory(target), scheduling, collections, services, metrics.size(),
                (int) metrics.stream().filter(metric -> metric.valueState() != ValueState.CURRENT).count(),
                coreMetrics(target.display().type(), metrics), service, collection, exceptional);
    }

    /** A bounded preview of original series, with no cross-binding/scope aggregation or invented samples. */
    private static List<MetricObservation> coreMetrics(MiddlewareType type, List<MetricObservation> metrics) {
        List<String> preferred = switch (type) {
            case MYSQL -> List.of("mysql.probe.duration", "mysql.threads.running", "mysql.questions.rate");
            case REDIS -> List.of("redis.probe.duration", "redis.commands.rate", "redis.memory.used.bytes");
            case KAFKA -> List.of("kafka.probe.duration", "kafka.brokers.discovered", "kafka.topics.observed");
            case MONGODB -> List.of("mongodb.probe.duration", "mongodb.memory.resident.bytes", "mongodb.operations.query.rate");
            case ELASTICSEARCH -> List.of("elasticsearch.probe.duration", "elasticsearch.cluster.health", "elasticsearch.jvm.heap.used.bytes");
            case MINIO -> List.of("minio.health.live", "minio.health.read.ready", "minio.bucket.accessible");
        };
        Comparator<MetricObservation> order = Comparator.comparingInt((MetricObservation metric) -> {
            int rank = preferred.indexOf(metric.latestAttempt().definition().key());
            return rank < 0 ? preferred.size() : rank;
        }).thenComparing(MetricObservation::bindingId)
                .thenComparing(metric -> metric.latestAttempt().definition().key())
                .thenComparing(metric -> metric.latestAttempt().definition().scope().kind())
                .thenComparing(metric -> metric.latestAttempt().definition().scope().id())
                .thenComparing(metric -> metric.latestAttempt().definition().scope().nodeId(), Comparator.nullsFirst(String::compareTo));
        return metrics.stream().filter(metric -> metric.kind() == CollectionKind.ORDINARY).sorted(order).limit(3).toList();
    }

    private MetricCapability capability(MetricObservation metric) {
        var latest = metric.latestAttempt();
        MissingReason reason = latest.missingReason();
        Capability capability = reason == null ? Capability.AVAILABLE : switch (reason) {
            case UNSUPPORTED -> Capability.UNSUPPORTED;
            case UNAUTHORIZED -> Capability.UNAUTHORIZED;
            case NOT_APPLICABLE -> Capability.NOT_APPLICABLE;
            default -> Capability.UNKNOWN;
        };
        return new MetricCapability(metric.bindingId(), metric.source(), metric.kind(), latest.definition(), capability,
                reason, latest.sampledAt(), metric.lastSuccess() == null ? null : metric.lastSuccess().lastSuccessAt(),
                metric.validUntil(), metric.valueState());
    }

    private List<MetricObservation> metrics(TargetSnapshot snapshot, Instant now) {
        if (snapshot == null) return List.of();
        Map<BindingKind, Attempt> attempts = new HashMap<>();
        snapshot.attempts().forEach(attempt -> attempts.put(new BindingKind(attempt.bindingId(), attempt.kind()), attempt));
        return snapshot.metrics().stream().map(metric -> {
            MetricSample latest = metric.latestAttempt();
            Attempt attempt = attempts.get(new BindingKind(metric.bindingId(), metric.kind()));
            if (attempt != null && attempt.status() != CollectionStatus.SUCCESS &&
                    (latest.sampledAt().isBefore(attempt.startedAt()) || attempt.status() != CollectionStatus.PARTIAL
                            && !latest.sampledAt().isAfter(attempt.completedAt()) && latest.missingReason() == null)) {
                MissingReason reason = attempt.reason() != null ? attempt.reason() : switch (attempt.status()) {
                    case UNAUTHORIZED -> MissingReason.UNAUTHORIZED;
                    case UNSUPPORTED -> MissingReason.UNSUPPORTED;
                    case BUSY -> MissingReason.BUSY;
                    case WAITING -> MissingReason.WAITING_SAMPLE;
                    case PARTIAL -> MissingReason.PARTIAL_SCOPE;
                    default -> MissingReason.FAILED;
                };
                latest = MetricSample.missing(latest.definition(), reason, attempt.completedAt());
            }
            MetricSample success = metric.lastSuccess();
            Instant validUntil = success == null ? null : success.validUntil();
            ValueState valueState = success == null ? ValueState.NO_DATA : now.isAfter(validUntil) ? ValueState.EXPIRED
                    : latest.missingReason() != null ? ValueState.LAST_SUCCESS : ValueState.CURRENT;
            return new MetricObservation(metric.source(), metric.bindingId(), metric.kind(), latest, success, valueState, validUntil);
        }).toList();
    }

    private Duration interval(CollectionKind kind) {
        return kind == CollectionKind.CAPACITY ? properties.getCapacityInterval() : properties.getOrdinaryInterval();
    }
    private Instant expires(Instant at, Duration period) {
        try { return at.plus(period.multipliedBy(properties.getMetricTtlMultiplier())); }
        catch (DateTimeException | ArithmeticException overflow) { return Instant.MAX; }
    }
    private FreshnessRules rules() {
        return new FreshnessRules(millis(properties.getRefreshInterval()), millis(properties.getOrdinaryInterval()),
                millis(properties.getCapacityInterval()), millis(properties.getServiceTtl()), properties.getMetricTtlMultiplier(), true);
    }
    private static long millis(Duration duration) {
        try { return Math.max(1, duration.toMillis()); } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    private static ServiceAvailability service(List<ServiceObservation> services) {
        for (ServiceAvailability value : List.of(ServiceAvailability.CONNECTION_FAILED, ServiceAvailability.DEGRADED, ServiceAvailability.UNKNOWN))
            if (services.stream().anyMatch(service -> service.availability() == value)) return value;
        return services.isEmpty() ? ServiceAvailability.UNKNOWN : ServiceAvailability.AVAILABLE;
    }
    private static CollectionStatus collection(List<CollectionObservation> collections) {
        for (CollectionStatus status : List.of(CollectionStatus.FAILED, CollectionStatus.UNAUTHORIZED, CollectionStatus.STALE,
                CollectionStatus.BUSY, CollectionStatus.PARTIAL))
            if (collections.stream().anyMatch(collection -> collection.status() == status)) return status;
        if (collections.stream().anyMatch(collection -> collection.status() == CollectionStatus.UNSUPPORTED))
            return collections.stream().allMatch(collection -> collection.status() == CollectionStatus.UNSUPPORTED) ? CollectionStatus.UNSUPPORTED : CollectionStatus.PARTIAL;
        if (collections.isEmpty() || collections.stream().anyMatch(collection -> collection.status() == CollectionStatus.WAITING)) return CollectionStatus.WAITING;
        return CollectionStatus.SUCCESS;
    }

    private static Map<String, TargetSnapshot> samples(ReadState state) {
        Map<String, TargetSnapshot> result = new HashMap<>();
        for (TargetSnapshot sample : state.snapshots()) {
            if (sample.generation() != state.generation()) throw new IllegalStateException("Inconsistent monitoring generation");
            result.put(sample.targetId(), sample);
        }
        return result;
    }

    private static int count(List<DirectoryTarget> targets, ConfigurationStatus status) {
        return (int) targets.stream().filter(target -> target.configurationStatus() == status).count();
    }
    private record BindingKind(String bindingId, CollectionKind kind) { }
}
