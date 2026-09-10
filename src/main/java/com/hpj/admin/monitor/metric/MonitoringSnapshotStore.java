package com.hpj.admin.monitor.metric;

import com.hpj.admin.common.config.monitor.MonitoringProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Bounded current state only. Time-based status projection belongs to the API/freshness layer. */
public final class MonitoringSnapshotStore {
    public enum Acceptance { ACCEPTED, INACTIVE_GENERATION, OUT_OF_ORDER, DEADLINE_EXCEEDED, INVALID_TIMING, CAPACITY_EXCEEDED }

    private final int maxTargets;
    private final int maxMetricsPerTarget;
    private final int maxBindingsPerTarget;
    private final Map<String, State> targets = new LinkedHashMap<>();

    public MonitoringSnapshotStore(int maxTargets, int maxMetricsPerTarget, int maxBindingsPerTarget) {
        if (maxTargets < 1 || maxMetricsPerTarget < 1 || maxBindingsPerTarget < 1) {
            throw new IllegalArgumentException("Snapshot limits must be positive");
        }
        this.maxTargets = maxTargets;
        this.maxMetricsPerTarget = maxMetricsPerTarget;
        this.maxBindingsPerTarget = maxBindingsPerTarget;
    }

    /** The scheduler owns monotonically increasing generations, including when a removed target is re-added. */
    public synchronized boolean activate(String targetId, long generation) {
        if (!MonitoringProperties.safeReference(targetId) || generation < 0) {
            throw new IllegalArgumentException("Invalid target generation");
        }
        State current = targets.get(targetId);
        if (current != null) {
            if (generation < current.generation) return false;
            if (generation == current.generation) return true;
        } else if (targets.size() >= maxTargets) {
            return false;
        }
        targets.put(targetId, new State(generation));
        return true;
    }

    /** Accept all observations atomically, retaining each metric's independent last successful sample. */
    public synchronized Acceptance accept(CollectionRequest request, CollectionResult result) {
        if (request == null || result == null) throw new IllegalArgumentException("Collection input is required");
        State state = targets.get(request.targetId());
        if (state == null || state.generation != request.generation()) return Acceptance.INACTIVE_GENERATION;
        BindingKey bindingKey = new BindingKey(request.bindingId(), request.kind());
        Attempt previousAttempt = state.attempts.get(bindingKey);
        if (previousAttempt != null && request.sequence() <= previousAttempt.sequence()) return Acceptance.OUT_OF_ORDER;
        if (previousAttempt != null && result.completedAt().isBefore(previousAttempt.completedAt())) return Acceptance.OUT_OF_ORDER;
        if (result.completedAt().isAfter(request.deadline())) return Acceptance.DEADLINE_EXCEEDED;
        if (result.startedAt().isBefore(request.scheduledAt())
                || result.metrics().stream().anyMatch(metric -> metric.sampledAt().isBefore(result.startedAt())
                || metric.sampledAt().isAfter(result.completedAt()))
                || result.serviceProbe() != null && (result.serviceProbe().sampledAt().isBefore(result.startedAt())
                || result.serviceProbe().sampledAt().isAfter(result.completedAt()))) return Acceptance.INVALID_TIMING;
        boolean knownBinding = state.attempts.keySet().stream().anyMatch(key -> key.bindingId.equals(request.bindingId()));
        if (!knownBinding && state.attempts.keySet().stream().map(BindingKey::bindingId).distinct().count() >= maxBindingsPerTarget) {
            return Acceptance.CAPACITY_EXCEEDED;
        }

        Map<MetricKey, StoredMetric> updates = new LinkedHashMap<>();
        for (MetricSample sample : result.metrics()) {
            MetricKey key = new MetricKey(request.bindingId(), request.kind(), sample.definition().key(), sample.definition().scope());
            StoredMetric previous = state.metrics.get(key);
            if (previous != null && sample.sampledAt().isBefore(previous.latestAttempt().sampledAt())) return Acceptance.OUT_OF_ORDER;
            // A changed definition must not attach a value with an old unit/source to new metadata.
            MetricSample lastSuccess = previous != null && previous.latestAttempt().definition().equals(sample.definition())
                    ? previous.lastSuccess() : null;
            if (sample.missingReason() == null) lastSuccess = sample;
            updates.put(key, new StoredMetric(request.source(), request.bindingId(), request.kind(), sample, lastSuccess));
        }
        long additions = updates.keySet().stream().filter(key -> !state.metrics.containsKey(key)).count();
        // Only authoritative discovery can retire absent objects. Failed/partial inventory must retain old evidence.
        List<MetricKey> retired = result.inventoryComplete() ? state.metrics.keySet().stream()
                .filter(key -> key.bindingId.equals(request.bindingId()) && key.kind == request.kind() && !updates.containsKey(key)).toList()
                : List.of();
        if (state.metrics.size() - retired.size() + additions > maxMetricsPerTarget) return Acceptance.CAPACITY_EXCEEDED;

        state.attempts.put(bindingKey, new Attempt(request.source(), request.bindingId(), request.kind(), request.sequence(),
                result.startedAt(), result.completedAt(), result.status(), result.reason()));
        retired.forEach(state.metrics::remove);
        state.metrics.putAll(updates);
        ServiceProbe probe = result.serviceProbe();
        ServiceProbe previousProbe = state.probes.get(request.bindingId());
        if (probe != null && (previousProbe == null || !probe.sampledAt().isBefore(previousProbe.sampledAt()))) {
            state.probes.put(request.bindingId(), probe);
        }
        return Acceptance.ACCEPTED;
    }

    public synchronized Optional<TargetSnapshot> snapshot(String targetId) {
        State state = targets.get(targetId);
        return state == null ? Optional.empty() : Optional.of(snapshot(targetId, state));
    }

    public synchronized List<TargetSnapshot> snapshots() {
        return targets.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> snapshot(entry.getKey(), entry.getValue())).toList();
    }

    public synchronized boolean removeTarget(String targetId) { return targets.remove(targetId) != null; }
    public synchronized void clear() { targets.clear(); }
    public synchronized int targetCount() { return targets.size(); }

    private static TargetSnapshot snapshot(String targetId, State state) {
        List<Attempt> attempts = state.attempts.values().stream()
                .sorted(Comparator.comparing(Attempt::bindingId).thenComparing(Attempt::kind)).toList();
        List<StoredMetric> metrics = new ArrayList<>(state.metrics.values());
        metrics.sort(Comparator.comparing(StoredMetric::bindingId).thenComparing(StoredMetric::kind)
                .thenComparing(metric -> metric.latestAttempt().definition().key())
                .thenComparing(metric -> metric.latestAttempt().definition().scope().kind())
                .thenComparing(metric -> metric.latestAttempt().definition().scope().id())
                .thenComparing(metric -> metric.latestAttempt().definition().scope().nodeId(), Comparator.nullsFirst(String::compareTo)));
        Map<String, ServiceProbe> probes = new LinkedHashMap<>();
        state.probes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> probes.put(entry.getKey(), entry.getValue()));
        return new TargetSnapshot(targetId, state.generation, attempts, metrics, probes);
    }

    private record BindingKey(String bindingId, CollectionKind kind) { }
    private record MetricKey(String bindingId, CollectionKind kind, String key, Scope scope) { }

    private static final class State {
        private final long generation;
        private final Map<BindingKey, Attempt> attempts = new LinkedHashMap<>();
        private final Map<MetricKey, StoredMetric> metrics = new LinkedHashMap<>();
        private final Map<String, ServiceProbe> probes = new LinkedHashMap<>();
        private State(long generation) { this.generation = generation; }
    }
}
