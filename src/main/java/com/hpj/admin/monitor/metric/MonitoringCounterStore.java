package com.hpj.admin.monitor.metric;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MonitoringCalculations.BaselineAction;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation;
import com.hpj.admin.monitor.metric.MonitoringCalculations.CounterSample;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Result;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, in-memory previous samples. A series id must identify its collection kind and native object scope. */
public final class MonitoringCounterStore {
    private final int maxTargets;
    private final int maxSeriesPerTarget;
    private final Map<String, Map<SeriesKey, CounterSample>> targets = new HashMap<>();

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
        Map<SeriesKey, CounterSample> series = targets.get(targetId);
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
        Map<SeriesKey, CounterSample> series = targets.get(targetId);
        if (series == null || series.remove(new SeriesKey(seriesId, calculation)) == null) return false;
        if (series.isEmpty()) targets.remove(targetId);
        return true;
    }

    public synchronized void clear() { targets.clear(); }

    public synchronized int targetCount() { return targets.size(); }

    public synchronized int seriesCount(String targetId) {
        requireTargetId(targetId);
        Map<SeriesKey, CounterSample> series = targets.get(targetId);
        return series == null ? 0 : series.size();
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
    private record SeriesKey(String id, Calculation calculation) {}
}
