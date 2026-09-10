package com.hpj.admin.monitor.metric;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MetricContract.CollectionKind;
import com.hpj.admin.monitor.metric.MetricContract.Definition;
import com.hpj.admin.monitor.metric.MonitoringCalculations.BaselineAction;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation;
import com.hpj.admin.monitor.metric.MonitoringCalculations.CounterSample;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Result;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Bounded, in-memory previous samples. A series id must identify its collection kind and native object scope. */
public final class MonitoringCounterStore {
    private final int maxTargets;
    private final int maxSeriesPerTarget;
    private final Map<String, Map<CounterKey, CounterSample>> targets = new HashMap<>();

    public MonitoringCounterStore(int maxTargets, int maxSeriesPerTarget) {
        if (maxTargets <= 0 || maxSeriesPerTarget <= 0) throw new IllegalArgumentException("Counter limits must be positive");
        this.maxTargets = maxTargets;
        this.maxSeriesPerTarget = maxSeriesPerTarget;
    }

    /**
     * Updates and calculates under one lock so same-epoch completions cannot roll back a newer baseline.
     * The caller must discard completions from retired connection/service generations before calling update.
     */
    public synchronized Result update(String targetId, String seriesId, Calculation calculation,
                                      CounterSample current, Duration period) {
        requireTargetId(targetId);
        requireSeriesId(seriesId);
        Objects.requireNonNull(calculation, "calculation");
        SeriesKey key = new SeriesKey(seriesId, calculation);
        Map<CounterKey, CounterSample> series = targets.get(targetId);
        CounterSample previous = series == null ? null : series.get(key);
        Result result = MonitoringCalculations.evaluate(calculation, previous, current, period);
        if (result.baselineAction() == BaselineAction.KEEP) return result;
        if (series == null) {
            if (targets.size() >= maxTargets) return busy();
            series = new HashMap<>();
            targets.put(targetId, series);
        } else if (previous == null && series.size() >= maxSeriesPerTarget) {
            return busy();
        }
        series.put(key, current);
        return result;
    }

    public synchronized void removeTarget(String targetId) {
        requireTargetId(targetId);
        targets.remove(targetId);
    }

    /**
     * Retires one series only after an authoritative inventory confirms its removal.
     * The caller must reject old in-flight results for the retired series before they can call update again.
     */
    public synchronized boolean removeSeries(String targetId, String seriesId, Calculation calculation) {
        requireTargetId(targetId);
        requireSeriesId(seriesId);
        Objects.requireNonNull(calculation, "calculation");
        Map<CounterKey, CounterSample> series = targets.get(targetId);
        if (series == null || series.remove(new SeriesKey(seriesId, calculation)) == null) return false;
        if (series.isEmpty()) targets.remove(targetId);
        return true;
    }

    public synchronized void clear() { targets.clear(); }

    public synchronized int targetCount() { return targets.size(); }

    public synchronized int seriesCount(String targetId) {
        requireTargetId(targetId);
        Map<CounterKey, CounterSample> series = targets.get(targetId);
        return series == null ? 0 : series.size();
    }

    int maximumPendingSeries() { return maxSeriesPerTarget; }

    /** Reads one committed baseline; preparing a calculation never reserves storage or advances that baseline. */
    synchronized PendingCalculation prepare(String targetId, ManagedSeries key, CounterSample current, Duration period) {
        requireTargetId(targetId);
        Objects.requireNonNull(key, "counter series");
        Map<CounterKey, CounterSample> series = targets.get(targetId);
        CounterSample previous = series == null ? null : series.get(key);
        return new PendingCalculation(key, previous, current,
                MonitoringCalculations.evaluate(key.calculation(), previous, current, period));
    }

    /**
     * Called under the scheduler's lifecycle lock. Capacity and the exact baselines used by calculations are
     * checked before accepting a snapshot; no counter mutation occurs unless that same snapshot is accepted.
     * The callback must only perform the lifecycle fence and in-memory snapshot acceptance, never external work.
     */
    synchronized MonitoringSnapshotStore.Acceptance commit(String targetId, String bindingId, CollectionKind kind,
            Map<ManagedSeries, PendingCalculation> pending, Set<Definition> reported, boolean inventoryComplete,
            Supplier<MonitoringSnapshotStore.Acceptance> acceptSnapshot) {
        requireTargetId(targetId);
        requireTargetId(bindingId);
        Objects.requireNonNull(kind, "collection kind");
        Map<CounterKey, CounterSample> current = targets.get(targetId);
        for (var entry : pending.entrySet()) {
            ManagedSeries key = entry.getKey();
            PendingCalculation candidate = entry.getValue();
            if (!key.bindingId().equals(bindingId) || key.kind() != kind || !key.equals(candidate.series())) {
                throw new IllegalArgumentException("Counter transaction contains a different binding");
            }
            CounterSample previous = current == null ? null : current.get(key);
            if (previous != candidate.previous()) return MonitoringSnapshotStore.Acceptance.OUT_OF_ORDER;
        }

        Map<CounterKey, CounterSample> next = current == null ? new HashMap<>() : new HashMap<>(current);
        Map<MetricIdentity, Definition> reportedIdentities = new HashMap<>();
        reported.forEach(definition -> reportedIdentities.put(identity(definition), definition));
        next.keySet().removeIf(key -> key instanceof ManagedSeries managed
                && managed.bindingId().equals(bindingId) && managed.kind() == kind
                && (inventoryComplete && !reported.contains(managed.definition())
                || reportedIdentities.containsKey(identity(managed.definition()))
                && !managed.definition().equals(reportedIdentities.get(identity(managed.definition())))));
        pending.forEach((key, candidate) -> {
            if (candidate.result().baselineAction() == BaselineAction.REPLACE) next.put(key, candidate.current());
        });
        if (next.size() > maxSeriesPerTarget
                || current == null && !next.isEmpty() && targets.size() >= maxTargets) {
            return MonitoringSnapshotStore.Acceptance.CAPACITY_EXCEEDED;
        }
        MonitoringSnapshotStore.Acceptance accepted = acceptSnapshot.get();
        if (accepted == MonitoringSnapshotStore.Acceptance.ACCEPTED) {
            if (next.isEmpty()) targets.remove(targetId);
            else targets.put(targetId, next);
        }
        return accepted;
    }

    @Override public synchronized String toString() {
        return "MonitoringCounterStore[targetCount=" + targets.size() + "]";
    }

    private static Result busy() { return new Result(null, MissingReason.BUSY, BaselineAction.KEEP); }

    private static void requireTargetId(String value) {
        if (!MonitoringProperties.safeReference(value)) throw new IllegalArgumentException("Counter target ID must be a safe reference");
    }

    private static void requireSeriesId(String value) {
        if (value == null || value.isBlank() || value.length() > 2048 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Counter series ID must be nonblank bounded text without control characters");
        }
    }

    // Epoch deliberately stays in the value: replacing a connection cannot allocate a new series entry.
    private sealed interface CounterKey permits SeriesKey, ManagedSeries { }
    private record MetricIdentity(String key, MetricContract.Scope scope) { }
    private static MetricIdentity identity(Definition definition) { return new MetricIdentity(definition.key(), definition.scope()); }
    private record SeriesKey(String id, Calculation calculation) implements CounterKey { }

    record ManagedSeries(String bindingId, CollectionKind kind, Definition definition,
                         Calculation calculation) implements CounterKey {
        ManagedSeries {
            requireTargetId(bindingId);
            Objects.requireNonNull(kind, "collection kind");
            Objects.requireNonNull(definition, "metric definition");
            Objects.requireNonNull(calculation, "calculation");
        }
        @Override public String toString() { return "ManagedCounterSeries[" + bindingId + ", " + kind + "]"; }
    }

    record PendingCalculation(ManagedSeries series, CounterSample previous, CounterSample current, Result result) {
        @Override public String toString() { return "PendingCounterCalculation[staged]"; }
    }
}
