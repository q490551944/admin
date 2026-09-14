package com.hpj.admin.monitor.api;

import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ReadState;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ReadTarget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.hpj.admin.monitor.api.MonitoringResponses.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Pure projection of one memory generation. Freshness projection is added here by issue #44. */
public final class MonitoringResponseProjector {
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
        return new SnapshotResponse(serverTime, state.generation(), state.resolutionState(), state.targets().stream()
                .map(target -> overview(target, samples.get(target.display().id()))).toList());
    }

    public Optional<DetailResponse> detail(ReadState state, Instant serverTime, String targetId) {
        return state.targets().stream().filter(target -> target.display().id().equals(targetId)).findFirst().map(target -> {
            TargetSnapshot sample = samples(state).get(targetId);
            List<StoredMetric> metrics = sample == null ? List.of() : sample.metrics();
            return new DetailResponse(serverTime, state.generation(), state.resolutionState(), overview(target, sample),
                    metrics, metrics.isEmpty() ? CapabilityState.UNKNOWN : CapabilityState.OBSERVED,
                    metrics.stream().map(this::capability).toList());
        });
    }

    private DirectoryTarget directory(ReadTarget target) {
        var display = target.display();
        return new DirectoryTarget(display.id(), display.type(), display.name(), display.status(),
                target.resolutionReason(), display.connectionSources(), display.scope(), target.memberIds(),
                target.bindings().stream().map(binding ->
                        new Binding(binding.bindingId(), binding.source().reference(), binding.scope())).toList());
    }

    private TargetOverview overview(ReadTarget target, TargetSnapshot sample) {
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
        if (scheduling == SchedulingState.ACTIVE) {
            for (var binding : target.bindings()) {
                for (CollectionKind kind : CollectionKind.values()) {
                    Attempt attempt = attempts.get(new BindingKind(binding.bindingId(), kind));
                    collections.add(new CollectionObservation(binding.bindingId(), binding.source().reference(), kind,
                            attempt == null ? CollectionStatus.WAITING : attempt.status(),
                            attempt == null ? MissingReason.WAITING_SAMPLE : attempt.reason(), attempt));
                }
                ServiceProbe probe = sample == null ? null : sample.serviceProbes().get(binding.bindingId());
                services.add(new ServiceObservation(binding.bindingId(), binding.source().reference(),
                        probe == null ? ServiceAvailability.UNKNOWN : probe.availability(),
                        probe == null ? MissingReason.WAITING_SAMPLE : probe.reason(), probe));
            }
        }
        List<StoredMetric> metrics = sample == null ? List.of() : sample.metrics();
        return new TargetOverview(directory(target), scheduling, collections, services, metrics.size(),
                (int) metrics.stream().filter(metric -> metric.latestAttempt().missingReason() != null).count());
    }

    private MetricCapability capability(StoredMetric metric) {
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
                latest.validUntil());
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
