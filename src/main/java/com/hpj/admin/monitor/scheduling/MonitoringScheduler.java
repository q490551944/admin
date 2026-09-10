package com.hpj.admin.monitor.scheduling;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import com.hpj.admin.monitor.metric.MonitoringAdapterRegistry;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Shared bounded collection, independent of page requests and of business client ownership. */
public final class MonitoringScheduler implements AutoCloseable {
    public record Diagnostics(int ordinaryActive, int ordinaryQueued, int capacityActive, int capacityQueued,
                              int inFlightTasks, int cleanupQueued, int workerThreads, int cleanupThreads) { }

    private final Object gate = new Object();
    private final MonitoringProperties properties;
    private final Supplier<List<ResolvedTarget>> resolver;
    private final MonitoringAdapterRegistry adapters;
    private final MonitoringSnapshotStore snapshots;
    private final MonitoringCounterStore counters;
    private final boolean periodic;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, TargetState> states = new LinkedHashMap<>();
    private final Map<Lane, Task> occupied = new LinkedHashMap<>();
    private final Map<Long, Task> tasks = new LinkedHashMap<>();
    private List<ResolvedTarget> targets = List.of();
    private ThreadPoolExecutor ordinary;
    private ThreadPoolExecutor capacity;
    private ThreadPoolExecutor cleanup;
    private ScheduledThreadPoolExecutor coordinator;
    private boolean running;
    private boolean closed;
    private long generation;
    private long taskIds;

    public MonitoringScheduler(MonitoringProperties properties, Supplier<List<ResolvedTarget>> resolver,
                               MonitoringAdapterRegistry adapters, MonitoringSnapshotStore snapshots,
                               MonitoringCounterStore counters) {
        this(properties, resolver, adapters, snapshots, counters, true);
    }

    /** Manual driving still runs deadline/cleanup coordination, but does not schedule periodic collections. */
    public MonitoringScheduler(MonitoringProperties properties, Supplier<List<ResolvedTarget>> resolver,
                               MonitoringAdapterRegistry adapters, MonitoringSnapshotStore snapshots,
                               MonitoringCounterStore counters, boolean periodic) {
        this.properties = Objects.requireNonNull(properties);
        properties.validate();
        this.resolver = Objects.requireNonNull(resolver);
        this.adapters = Objects.requireNonNull(adapters);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.counters = Objects.requireNonNull(counters);
        this.periodic = periodic;
    }

    /** Called after application readiness. Optional connection resolution never blocks startup. */
    public void start() {
        synchronized (gate) {
            if (running || closed || !properties.isEnabled()) return;
            ordinary = pool(properties.getOrdinaryConcurrency(), properties.getMaxTargets(), "monitor-ordinary-");
            capacity = pool(properties.getCapacityConcurrency(), properties.getMaxTargets(), "monitor-capacity-");
            cleanup = pool(properties.getOrdinaryConcurrency() + properties.getCapacityConcurrency(),
                    Math.multiplyExact(2, properties.getMaxTargets()), "monitor-cleanup-");
            coordinator = new ScheduledThreadPoolExecutor(1, threads("monitor-coordinator-"));
            coordinator.setRemoveOnCancelPolicy(true);
            running = true;
            long tickNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(10),
                    Math.max(1, properties.getCollectionTimeout().toNanos() / 20));
            coordinator.scheduleAtFixedRate(this::tickSafely, tickNanos, tickNanos, TimeUnit.NANOSECONDS);
            long resolvingGeneration = generation;
            // Resolve once. No network/client creation occurs in the resolver, and no per-page work is queued here.
            ordinary.execute(() -> {
                List<ResolvedTarget> resolved;
                try {
                    resolved = resolver.get();
                } catch (RuntimeException | LinkageError unavailable) {
                    resolved = unresolvedCatalog();
                }
                synchronized (gate) {
                    // An explicit replacement can win while initial discovery is still in progress.
                    if (running && generation == resolvingGeneration) replaceTargets(resolved);
                }
            });
        }
    }

    /** Explicit replacement, not automatic configuration hot reload. Old execution remains bounded until it ends. */
    public void replaceTargets(List<ResolvedTarget> replacement) {
        Objects.requireNonNull(replacement, "Monitoring targets are required");
        List<ResolvedTarget> copy = replacement.stream().sorted(Comparator.comparing(value -> value.display().id())).toList();
        long bindingCount = copy.stream().mapToLong(value -> value.bindings().size()).sum();
        if (copy.size() > properties.getMaxTargets() || bindingCount > properties.getMaxTargets()
                || copy.stream().map(value -> value.display().id()).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("Monitoring target limits exceeded");
        }
        synchronized (gate) {
            if (closed) return;
            long nextGeneration = Math.incrementExact(generation);
            generation = nextGeneration;
            for (Task task : tasks.values()) cancelTask(task, MissingReason.FAILED);
            states.clear();
            snapshots.clear();
            counters.clear();
            targets = List.copyOf(copy);
            long now = System.nanoTime();
            int count = copy.size();
            for (int index = 0; index < count; index++) {
                ResolvedTarget target = copy.get(index);
                TargetState state = new TargetState(target, nextGeneration);
                for (CollectionKind kind : CollectionKind.values()) {
                    state.nextDue.put(kind, now + interval(kind).toNanos() * index / Math.max(1, count));
                }
                states.put(target.display().id(), state);
                if (target.reason() == ResolvedTarget.Reason.CONFIGURED) snapshots.activate(target.display().id(), nextGeneration);
            }
        }
    }

    /** Returns whether an actual worker task was admitted; no public HTTP endpoint invokes this method. */
    public boolean submitNow(String targetId, CollectionKind kind) {
        Objects.requireNonNull(kind, "Collection kind is required");
        synchronized (gate) {
            TargetState state = states.get(targetId);
            return running && state != null && submit(state, kind, System.nanoTime());
        }
    }

    public boolean isRunning() { synchronized (gate) { return running; } }
    public List<ResolvedTarget> targets() { synchronized (gate) { return targets; } }

    public Diagnostics diagnostics() {
        synchronized (gate) {
            return new Diagnostics(active(ordinary), queued(ordinary), active(capacity), queued(capacity),
                    tasks.size(), queued(cleanup), size(ordinary) + size(capacity), size(cleanup));
        }
    }

    private boolean submit(TargetState state, CollectionKind kind, long now) {
        if (state.target.reason() != ResolvedTarget.Reason.CONFIGURED || state.target.bindings().isEmpty()) return false;
        Lane lane = new Lane(state.target.display().id(), kind);
        Task previous = occupied.get(lane);
        // A duplicate trigger must not supersede the still-valid in-flight result with a newer BUSY sequence.
        if (previous != null && current(previous) && now - previous.executionDeadline < 0) return false;
        long sequence = state.sequence.merge(kind, 1L, Math::addExact);
        if (occupied.containsKey(lane) || tasks.values().stream().filter(task -> task.kind == kind).count() >= properties.getMaxTargets()) {
            publishImmediate(state, kind, sequence, CollectionStatus.BUSY, MissingReason.BUSY);
            return false;
        }
        MonitoringAdapter adapter = adapters.find(state.target.display().type()).orElse(null);
        if (adapter == null) {
            publishImmediate(state, kind, sequence, CollectionStatus.UNSUPPORTED, MissingReason.UNSUPPORTED);
            return false;
        }
        Instant scheduled = clock.instant();
        long budget = properties.getCollectionTimeout().toNanos();
        // Leave publication/dispatch headroom inside the externally visible queue-inclusive budget.
        long publicationReserve = Math.min(TimeUnit.MILLISECONDS.toNanos(100), budget / 10);
        Task task = new Task(++taskIds, state, kind, sequence, scheduled,
                scheduled.plus(properties.getCollectionTimeout()), now + budget - publicationReserve, adapter);
        task.future = new FutureTask<>(() -> { run(task); return null; });
        tasks.put(task.id, task);
        occupied.put(lane, task);
        try {
            executor(kind).execute(task.future);
            return true;
        } catch (RejectedExecutionException full) {
            tasks.remove(task.id);
            occupied.remove(lane, task);
            publishImmediate(state, kind, sequence, CollectionStatus.BUSY, MissingReason.BUSY);
            return false;
        }
    }

    private void run(Task task) {
        try {
            synchronized (gate) {
                task.started = true;
                if (!current(task)) return;
            }
            for (int index = 0; index < task.state.target.bindings().size(); index++) {
                CollectionRequest request;
                CollectionControl control;
                synchronized (gate) {
                    if (!current(task)) break;
                    if (System.nanoTime() - task.executionDeadline >= 0) { expire(task); break; }
                    task.activeBinding = index;
                    var binding = task.state.target.bindings().get(index);
                    control = new CollectionControl(gate, () -> current(task), clock, System::nanoTime,
                            task.executionDeadline, cleanup, counters, task.state.target.display().id(),
                            binding.targetId(), task.kind, interval(task.kind), binding.connection().client());
                    task.controls.add(control);
                    request = request(task.state, binding, task.kind, task.sequence, task.scheduledAt, task.deadline, control);
                }
                CollectionResult result;
                try {
                    result = task.adapter.collect(request);
                } catch (RuntimeException | LinkageError failed) {
                    // Raw native exceptions can contain endpoint credentials. Publish only a fixed reason.
                    synchronized (gate) {
                        control.cancel(MissingReason.FAILED);
                        if (!current(task)) break;
                        if (System.nanoTime() - task.executionDeadline >= 0) { expire(task); break; }
                        failure(task, index, CollectionStatus.FAILED, MissingReason.FAILED, clock.instant());
                    }
                    continue;
                }
                synchronized (gate) {
                    if (!current(task)) { control.cancel(MissingReason.FAILED); break; }
                    if (System.nanoTime() - task.executionDeadline >= 0) { expire(task); break; }
                    MonitoringSnapshotStore.Acceptance acceptance;
                    try {
                        acceptance = control.complete(request, result, snapshots);
                    } catch (RuntimeException invalid) {
                        control.cancel(MissingReason.INVALID_VALUE);
                        acceptance = MonitoringSnapshotStore.Acceptance.INVALID_TIMING;
                    }
                    if (acceptance == MonitoringSnapshotStore.Acceptance.ACCEPTED) {
                        task.published[index] = true;
                    } else {
                        boolean busy = acceptance == MonitoringSnapshotStore.Acceptance.CAPACITY_EXCEEDED;
                        failure(task, index, busy ? CollectionStatus.BUSY : CollectionStatus.FAILED,
                                busy ? MissingReason.BUSY : MissingReason.INVALID_VALUE, clock.instant());
                    }
                    task.activeBinding = -1;
                }
            }
        } finally {
            synchronized (gate) { task.workerDone = true; }
        }
    }

    private void tickSafely() {
        synchronized (gate) {
            long now = System.nanoTime();
            for (Task task : new ArrayList<>(tasks.values())) {
                if (!task.cancelled && current(task) && now - task.executionDeadline >= 0) expire(task);
                for (CollectionControl control : task.controls) control.retryCleanup();
                if (task.workerDone && task.controls.stream().allMatch(CollectionControl::isCleanupComplete)) {
                    tasks.remove(task.id);
                    occupied.remove(new Lane(task.state.target.display().id(), task.kind), task);
                }
            }
            if (running && periodic) {
                for (TargetState state : states.values()) {
                    for (CollectionKind kind : CollectionKind.values()) {
                        long due = state.nextDue.get(kind);
                        if (now - due >= 0) {
                            // Skip missed periods instead of trying to catch up with a burst of old requests.
                            long period = interval(kind).toNanos();
                            state.nextDue.put(kind, due + ((now - due) / period + 1) * period);
                            submit(state, kind, now);
                        }
                    }
                }
            }
            if (closed && tasks.isEmpty()) shutdownExecutors();
        }
    }

    private void expire(Task task) {
        if (!current(task)) return;
        Instant at = clock.instant();
        if (at.isAfter(task.deadline)) at = task.deadline;
        for (int index = 0; index < task.published.length; index++) {
            if (!task.published[index]) {
                boolean executing = task.activeBinding == index;
                failure(task, index, executing ? CollectionStatus.FAILED : CollectionStatus.BUSY,
                        executing ? MissingReason.TIMEOUT : MissingReason.BUSY, at);
            }
        }
        cancelTask(task, MissingReason.TIMEOUT);
    }

    private void cancelTask(Task task, MissingReason reason) {
        task.cancelled = true;
        for (CollectionControl control : task.controls) control.cancel(reason);
        if (task.future != null) {
            task.future.cancel(true);
            executor(task.kind).remove(task.future);
        }
        if (!task.started) task.workerDone = true;
    }

    private boolean current(Task task) {
        return running && !task.cancelled && states.get(task.state.target.display().id()) == task.state;
    }

    private void failure(Task task, int index, CollectionStatus status, MissingReason reason, Instant at) {
        if (task.published[index]) return;
        var binding = task.state.target.bindings().get(index);
        CollectionRequest request = request(task.state, binding, task.kind, task.sequence, task.scheduledAt,
                task.deadline, CollectionControl.detached(task.deadline));
        snapshots.accept(request, failureResult(request, status, reason, at));
        task.published[index] = true;
    }

    private void publishImmediate(TargetState state, CollectionKind kind, long sequence,
                                  CollectionStatus status, MissingReason reason) {
        Instant now = clock.instant();
        Instant deadline = now.plus(properties.getCollectionTimeout());
        for (var binding : state.target.bindings()) {
            CollectionRequest request = request(state, binding, kind, sequence, now, deadline, CollectionControl.detached(deadline));
            snapshots.accept(request, failureResult(request, status, reason, now));
        }
    }

    private CollectionResult failureResult(CollectionRequest request, CollectionStatus status, MissingReason reason, Instant at) {
        if (at.isBefore(request.scheduledAt())) at = request.scheduledAt();
        if (at.isAfter(request.deadline())) at = request.deadline();
        Instant sampleTime = at;
        List<MetricSample> missing = snapshots.snapshot(request.targetId()).stream()
                .flatMap(snapshot -> snapshot.metrics().stream())
                .filter(metric -> metric.bindingId().equals(request.bindingId()) && metric.kind() == request.kind())
                .map(metric -> MetricSample.missing(metric.latestAttempt().definition(), reason, sampleTime)).toList();
        return new CollectionResult(status, request.scheduledAt(), sampleTime, missing, null, false, reason);
    }

    private CollectionRequest request(TargetState state, ResolvedTarget.SourceBinding binding, CollectionKind kind,
                                      long sequence, Instant scheduled, Instant deadline, CollectionControl control) {
        return new CollectionRequest(state.target.display().id(), binding.connection().source(), binding.targetId(), kind,
                state.generation, sequence, scheduled, deadline, binding.scope(), binding.connection().client(),
                binding.connection().settings(), control);
    }

    @Override
    public void close() {
        synchronized (gate) {
            if (closed) return;
            closed = true;
            running = false;
            for (Task task : tasks.values()) cancelTask(task, MissingReason.FAILED);
            states.clear();
            targets = List.of();
            snapshots.clear();
            counters.clear();
            if (ordinary != null) ordinary.shutdownNow();
            if (capacity != null) capacity.shutdownNow();
            // Cleanup remains available until registered resources really finish. All threads are bounded daemons.
            if (tasks.isEmpty()) shutdownExecutors();
        }
    }

    private void shutdownExecutors() {
        if (ordinary != null) ordinary.shutdownNow();
        if (capacity != null) capacity.shutdownNow();
        if (cleanup != null) cleanup.shutdown();
        if (coordinator != null) coordinator.shutdown();
    }

    private List<ResolvedTarget> unresolvedCatalog() {
        return properties.getTargets().stream().map(target -> {
            var scope = target.getScope();
            var display = new MonitoringTarget(target.getId(), target.getType(),
                    target.getName() == null ? target.getType().displayName() : target.getName(),
                    target.isEnabled() ? MonitoringTarget.ConfigurationStatus.CONFIGURATION_MISSING : MonitoringTarget.ConfigurationStatus.DISABLED,
                    target.getConnectionSource() == null ? List.<String>of() : List.of(target.getConnectionSource()),
                    new MonitoringTarget.Scope(scope.getDatabases(), scope.getTopics(), scope.getConsumerGroups(), scope.getBuckets(), scope.getIndices()));
            return new ResolvedTarget(display, List.of(target.getId()), List.of(),
                    target.isEnabled() ? ResolvedTarget.Reason.SOURCE_MISSING : ResolvedTarget.Reason.DISABLED);
        }).toList();
    }

    private Duration interval(CollectionKind kind) {
        return kind == CollectionKind.ORDINARY ? properties.getOrdinaryInterval() : properties.getCapacityInterval();
    }
    private ThreadPoolExecutor executor(CollectionKind kind) { return kind == CollectionKind.ORDINARY ? ordinary : capacity; }
    private static int active(ThreadPoolExecutor pool) { return pool == null ? 0 : pool.getActiveCount(); }
    private static int queued(ThreadPoolExecutor pool) { return pool == null ? 0 : pool.getQueue().size(); }
    private static int size(ThreadPoolExecutor pool) { return pool == null ? 0 : pool.getPoolSize(); }
    private static ThreadPoolExecutor pool(int threads, int queue, String name) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), threads(name), new ThreadPoolExecutor.AbortPolicy());
    }
    private static ThreadFactory threads(String prefix) {
        AtomicLong sequence = new AtomicLong();
        return action -> {
            Thread thread = new Thread(action, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
    private record Lane(String targetId, CollectionKind kind) { }
    private static final class TargetState {
        private final ResolvedTarget target;
        private final long generation;
        private final EnumMap<CollectionKind, Long> sequence = new EnumMap<>(CollectionKind.class);
        private final EnumMap<CollectionKind, Long> nextDue = new EnumMap<>(CollectionKind.class);
        private TargetState(ResolvedTarget target, long generation) { this.target = target; this.generation = generation; }
    }
    private static final class Task {
        private final long id;
        private final TargetState state;
        private final CollectionKind kind;
        private final long sequence;
        private final Instant scheduledAt;
        private final Instant deadline;
        private final long executionDeadline;
        private final MonitoringAdapter adapter;
        private final List<CollectionControl> controls = new ArrayList<>();
        private final boolean[] published;
        private FutureTask<Void> future;
        private boolean started;
        private boolean workerDone;
        private boolean cancelled;
        private int activeBinding = -1;
        private Task(long id, TargetState state, CollectionKind kind, long sequence, Instant scheduledAt,
                     Instant deadline, long executionDeadline, MonitoringAdapter adapter) {
            this.id = id;
            this.state = state;
            this.kind = kind;
            this.sequence = sequence;
            this.scheduledAt = scheduledAt;
            this.deadline = deadline;
            this.executionDeadline = executionDeadline;
            this.adapter = adapter;
            this.published = new boolean[state.target.bindings().size()];
        }
    }
}
