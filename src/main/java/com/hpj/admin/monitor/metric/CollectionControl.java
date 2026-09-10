package com.hpj.admin.monitor.metric;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.metric.MetricContract.CollectionKind;
import com.hpj.admin.monitor.metric.MetricContract.CollectionResult;
import com.hpj.admin.monitor.metric.MetricContract.Definition;
import com.hpj.admin.monitor.metric.MetricContract.MetricSample;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation;
import com.hpj.admin.monitor.metric.MonitoringCalculations.CounterSample;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Result;
import com.hpj.admin.monitor.metric.MonitoringCounterStore.ManagedSeries;
import com.hpj.admin.monitor.metric.MonitoringCounterStore.PendingCalculation;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore.Acceptance;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Server-only lifecycle for one binding attempt. The scheduler supplies its shared lifecycle lock and bounded
 * cleanup executor. All protocol work and cleanup actions run outside that lock; the borrowed client is never closed.
 */
@JsonIgnoreType
public final class CollectionControl {
    private static final int MAX_OWNED_REGISTRATIONS = 64;
    private enum State { ACTIVE, FINISHED, CANCELLED }
    private enum ResourceState { RESERVED, ATTACHED, RUNNING, DONE }

    private final Object lifecycleLock;
    private final BooleanSupplier generationActive;
    private final Clock clock;
    private final LongSupplier ticker;
    private final long deadlineNanos;
    private final ThreadPoolExecutor cleanupExecutor;
    private final MonitoringCounterStore counters;
    private final String targetId;
    private final String bindingId;
    private final CollectionKind kind;
    private final Duration interval;
    private final Object borrowedClient;
    private final boolean detached;
    private final Map<ManagedSeries, PendingCalculation> pending = new LinkedHashMap<>();
    private final Set<Registration> registrations = new LinkedHashSet<>();
    private State state = State.ACTIVE;
    private MissingReason cancellationReason;
    private boolean cleanupScheduled;
    private boolean cleanupFailed;

    public CollectionControl(Object lifecycleLock, BooleanSupplier generationActive, Clock clock, LongSupplier ticker,
            long deadlineNanos, ThreadPoolExecutor cleanupExecutor, MonitoringCounterStore counters,
            String targetId, String bindingId, CollectionKind kind, Duration interval, Object borrowedClient) {
        this.lifecycleLock = Objects.requireNonNull(lifecycleLock, "lifecycle lock");
        this.generationActive = Objects.requireNonNull(generationActive, "generation fence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ticker = Objects.requireNonNull(ticker, "monotonic ticker");
        this.deadlineNanos = deadlineNanos;
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanup executor");
        if (!(cleanupExecutor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy)
                || cleanupExecutor.getCorePoolSize() != cleanupExecutor.getMaximumPoolSize()
                || (long) cleanupExecutor.getQueue().remainingCapacity() + cleanupExecutor.getQueue().size() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cleanup requires a bounded executor with AbortPolicy");
        }
        this.counters = Objects.requireNonNull(counters, "counter store");
        if (!MonitoringProperties.safeReference(targetId) || !MonitoringProperties.safeReference(bindingId)) {
            throw new IllegalArgumentException("Collection identity must contain safe references");
        }
        this.targetId = targetId;
        this.bindingId = bindingId;
        this.kind = Objects.requireNonNull(kind, "collection kind");
        this.interval = Objects.requireNonNull(interval, "collection interval");
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("Collection interval must be positive");
        this.borrowedClient = Objects.requireNonNull(borrowedClient, "borrowed client");
        this.detached = false;
    }

    private CollectionControl(Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        lifecycleLock = new Object();
        generationActive = () -> false;
        clock = Clock.systemUTC();
        ticker = System::nanoTime;
        deadlineNanos = 0;
        cleanupExecutor = null;
        counters = null;
        targetId = bindingId = "detached";
        kind = CollectionKind.ORDINARY;
        interval = Duration.ofSeconds(1);
        borrowedClient = null;
        detached = true;
        state = State.FINISHED;
    }

    /** Compatibility requests carry no scheduler, threads, clients, or ability to commit metrics. */
    public static CollectionControl detached(Instant deadline) { return new CollectionControl(deadline); }

    public Instant now() { return clock.instant(); }

    public Duration remaining() {
        synchronized (lifecycleLock) {
            return activeLocked() ? Duration.ofNanos(nanosRemaining()) : Duration.ZERO;
        }
    }

    public boolean isActive() { synchronized (lifecycleLock) { return activeLocked(); } }
    public boolean active() { return isActive(); }

    public boolean isCancelled() {
        synchronized (lifecycleLock) {
            return state == State.CANCELLED || state == State.ACTIVE && (!generationActive.getAsBoolean() || nanosRemaining() <= 0);
        }
    }

    public MissingReason cancellationReason() {
        synchronized (lifecycleLock) { return cancellationReason; }
    }

    public void checkActive() {
        try {
            synchronized (lifecycleLock) { ensureActiveLocked(); }
        } finally {
            retryCleanup();
        }
    }

    /**
     * Reserve before creating an owned request/resource. The reservation remains outstanding across cancellation
     * until attach schedules its cleanup or close confirms that no resource was created.
     */
    public Registration reserveOwnedCancellation() {
        try {
            synchronized (lifecycleLock) {
                ensureActiveLocked();
                if (registrations.size() >= MAX_OWNED_REGISTRATIONS) {
                    throw new IllegalStateException("Collection owned-resource limit reached");
                }
                Registration registration = new Registration();
                registrations.add(registration);
                return registration;
            }
        } finally {
            retryCleanup();
        }
    }

    /** Convenience for an already-created resource when capacity is known; prefer reservation before acquisition. */
    public Registration registerOwnedCancellation(Object ownedResource, Runnable cancellation) {
        Registration registration;
        synchronized (lifecycleLock) {
            if (detached || registrations.size() >= MAX_OWNED_REGISTRATIONS) {
                throw new IllegalStateException("Collection owned-resource registration unavailable");
            }
            registration = new Registration();
            registrations.add(registration);
        }
        registration.attach(ownedResource, cancellation);
        return registration;
    }

    public Result calculate(Definition definition, Calculation calculation, CounterSample sample) {
        try {
            synchronized (lifecycleLock) {
                ensureActiveLocked();
                ManagedSeries series = new ManagedSeries(bindingId, kind, definition, calculation);
                if (sample == null) return new Result(null, MissingReason.INVALID_VALUE, MonitoringCalculations.BaselineAction.KEEP);
                PendingCalculation existing = pending.get(series);
                if (existing != null) {
                    if (existing.current() != sample) throw new IllegalStateException("A counter series may be sampled only once per collection");
                    return existing.result();
                }
                if (pending.size() >= counters.maximumPendingSeries()) {
                    return new Result(null, MissingReason.BUSY, MonitoringCalculations.BaselineAction.KEEP);
                }
                PendingCalculation candidate = counters.prepare(targetId, series, sample, interval);
                pending.put(series, candidate);
                return candidate.result();
            }
        } finally {
            retryCleanup();
        }
    }

    /** Atomically publishes an accepted binding snapshot and only the counters represented by that result. */
    public Acceptance complete(CollectionRequest request, CollectionResult result, MonitoringSnapshotStore snapshots) {
        Objects.requireNonNull(request, "collection request");
        Objects.requireNonNull(result, "collection result");
        Objects.requireNonNull(snapshots, "snapshot store");
        try {
            synchronized (lifecycleLock) {
                if (detached || request.control() != this || !targetId.equals(request.targetId())
                        || !bindingId.equals(request.bindingId()) || kind != request.kind() || request.client() != borrowedClient) {
                    throw new IllegalArgumentException("Collection completion does not match its lifecycle");
                }
                if (state == State.FINISHED) return Acceptance.OUT_OF_ORDER;
                if (!activeLocked()) {
                    MissingReason reason = inactiveReasonLocked();
                    cancelLocked(reason);
                    return reason == MissingReason.TIMEOUT ? Acceptance.DEADLINE_EXCEEDED : Acceptance.INACTIVE_GENERATION;
                }
                Map<Definition, MetricSample> reported = result.metrics().stream()
                        .collect(Collectors.toMap(MetricSample::definition, sample -> sample));
                Map<ManagedSeries, PendingCalculation> represented = new LinkedHashMap<>();
                pending.forEach((key, candidate) -> {
                    MetricSample sample = reported.get(key.definition());
                    if (sample != null && candidate.current() != null && sample.sampledAt().equals(candidate.current().at())
                            && sameValue(sample.value(), candidate.result().value())
                            && sample.missingReason() == candidate.result().reason()) represented.put(key, candidate);
                });
                Acceptance accepted = counters.commit(targetId, bindingId, kind, represented, reported.keySet(), result.inventoryComplete(), () -> {
                    if (!activeLocked()) return inactiveReasonLocked() == MissingReason.TIMEOUT
                            ? Acceptance.DEADLINE_EXCEEDED : Acceptance.INACTIVE_GENERATION;
                    return snapshots.accept(request, result);
                });
                state = State.FINISHED;
                pending.clear();
                return accepted;
            }
        } finally {
            synchronized (lifecycleLock) {
                if (state == State.ACTIVE) state = State.FINISHED;
                pending.clear();
            }
            retryCleanup();
        }
    }

    private static boolean sameValue(Object reported, BigDecimal calculated) {
        return reported == null ? calculated == null
                : reported instanceof BigDecimal number && calculated != null && number.compareTo(calculated) == 0;
    }

    public void cancel(MissingReason reason) {
        Objects.requireNonNull(reason, "cancellation reason");
        synchronized (lifecycleLock) { cancelLocked(reason); }
        retryCleanup();
    }

    /** A rejected executor submission retains the bounded registrations for the scheduler's next retry. */
    public void retryCleanup() {
        synchronized (lifecycleLock) {
            if (detached || state == State.ACTIVE || cleanupScheduled
                    || registrations.stream().noneMatch(registration -> registration.resourceState == ResourceState.ATTACHED)) return;
            cleanupScheduled = true;
        }
        try {
            cleanupExecutor.execute(this::drainCleanup);
        } catch (RejectedExecutionException saturated) {
            synchronized (lifecycleLock) { cleanupScheduled = false; }
        }
    }

    /** Fail closed after a cleanup exception: the scheduler must retain the single-flight slot. */
    public boolean isCleanupComplete() {
        retryCleanup();
        synchronized (lifecycleLock) { return registrations.isEmpty() && !cleanupScheduled && !cleanupFailed; }
    }

    public boolean hasCleanupFailure() { synchronized (lifecycleLock) { return cleanupFailed; } }

    private void drainCleanup() {
        while (true) {
            Registration registration;
            Runnable action;
            synchronized (lifecycleLock) {
                registration = registrations.stream().filter(value -> value.resourceState == ResourceState.ATTACHED)
                        .findFirst().orElse(null);
                if (registration == null) {
                    cleanupScheduled = false;
                    return;
                }
                registration.resourceState = ResourceState.RUNNING;
                action = registration.cancellation;
            }
            try {
                action.run();
            } catch (Throwable failure) {
                synchronized (lifecycleLock) { cleanupFailed = true; }
            } finally {
                synchronized (lifecycleLock) {
                    registration.resourceState = ResourceState.DONE;
                    registration.cancellation = null;
                    registrations.remove(registration);
                }
            }
        }
    }

    private long nanosRemaining() { return deadlineNanos - ticker.getAsLong(); }

    private boolean activeLocked() {
        return !detached && state == State.ACTIVE && generationActive.getAsBoolean() && nanosRemaining() > 0;
    }

    private MissingReason inactiveReasonLocked() {
        if (cancellationReason != null) return cancellationReason;
        return nanosRemaining() <= 0 ? MissingReason.TIMEOUT : MissingReason.FAILED;
    }

    private void ensureActiveLocked() {
        if (!activeLocked()) {
            MissingReason reason = inactiveReasonLocked();
            cancelLocked(reason);
            throw new InactiveCollectionException(reason);
        }
    }

    private void cancelLocked(MissingReason reason) {
        if (state == State.ACTIVE) {
            state = State.CANCELLED;
            cancellationReason = reason;
            pending.clear();
        }
    }

    @Override public String toString() {
        return "CollectionControl[" + targetId + ", " + bindingId + ", " + kind + "]";
    }

    public static final class InactiveCollectionException extends IllegalStateException {
        private final MissingReason reason;
        private InactiveCollectionException(MissingReason reason) {
            super("Monitoring collection is inactive: " + reason);
            this.reason = reason;
        }
        public MissingReason reason() { return reason; }
    }

    public final class Registration implements AutoCloseable {
        private ResourceState resourceState = ResourceState.RESERVED;
        private Runnable cancellation;
        private Registration() { }

        /** Attach after acquisition even if cancellation won the race; its cleanup will then run asynchronously. */
        public void attach(Object ownedResource, Runnable action) {
            synchronized (lifecycleLock) {
                if (resourceState != ResourceState.RESERVED) throw new IllegalStateException("Owned resource is already attached or released");
                if (ownedResource == null || action == null || ownedResource == borrowedClient) {
                    resourceState = ResourceState.DONE;
                    registrations.remove(this);
                    throw new IllegalArgumentException("Cancellation must belong to a monitoring-owned resource");
                }
                cancellation = action;
                resourceState = ResourceState.ATTACHED;
                if (state == State.ACTIVE && !activeLocked()) cancelLocked(inactiveReasonLocked());
            }
            retryCleanup();
        }

        /** Unregister only after normal resource termination, or when acquisition never created a resource. */
        @Override public void close() {
            synchronized (lifecycleLock) {
                if (resourceState == ResourceState.RUNNING || resourceState == ResourceState.DONE) return;
                resourceState = ResourceState.DONE;
                cancellation = null;
                registrations.remove(this);
            }
        }

        @Override public String toString() { return "MonitoringOwnedCancellation[registration]"; }
    }
}
