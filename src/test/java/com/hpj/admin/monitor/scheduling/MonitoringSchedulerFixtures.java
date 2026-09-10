package com.hpj.admin.monitor.scheduling;

import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.connection.ConnectionIdentity;
import com.hpj.admin.monitor.connection.ResolvedConnection;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Small protocol substitutes that expose actual operation lifetime, not just Future cancellation. */
final class MonitoringSchedulerFixtures {
    private MonitoringSchedulerFixtures() { }

    static MonitoringTarget.Scope scope(String database) {
        return new MonitoringTarget.Scope(List.of(database), List.of(), List.of(), List.of(), List.of());
    }

    static ResolvedTarget target(String id, BorrowedClient client) {
        return target(id, List.of(binding(id, id + "Source", client, scope("0"))));
    }

    static ResolvedTarget.SourceBinding binding(String id, String source, BorrowedClient client, MonitoringTarget.Scope scope) {
        return new ResolvedTarget.SourceBinding(id, source,
                new ResolvedConnection(MiddlewareType.REDIS, source, client, ConnectionIdentity.forOwner(client),
                        Map.of("opaqueOption", "private-configuration-sentinel")), scope);
    }

    static ResolvedTarget target(String id, List<ResolvedTarget.SourceBinding> bindings) {
        List<String> databases = bindings.stream().flatMap(binding -> binding.scope().databases().stream()).distinct().toList();
        var display = new MonitoringTarget(id, MiddlewareType.REDIS, id, MonitoringTarget.ConfigurationStatus.CONFIGURED,
                bindings.stream().map(binding -> binding.connection().source()).distinct().toList(),
                new MonitoringTarget.Scope(databases, List.of(), List.of(), List.of(), List.of()));
        return new ResolvedTarget(display, bindings.stream().map(ResolvedTarget.SourceBinding::targetId).toList(),
                bindings, ResolvedTarget.Reason.CONFIGURED);
    }

    static CollectionResult success(CollectionRequest request) {
        Instant now = Instant.now();
        return new CollectionResult(CollectionStatus.SUCCESS, request.scheduledAt(), now, List.of(), null);
    }

    static final class BorrowedClient implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();
        @Override public void close() { closes.incrementAndGet(); }
    }

    static final class OwnedOperation implements AutoCloseable {
        final CountDownLatch released = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch allowClose;
        final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        Runnable finished = () -> { };

        OwnedOperation() { this(false); }
        OwnedOperation(boolean blockClose) { allowClose = new CountDownLatch(blockClose ? 1 : 0); }

        void awaitReleaseIgnoringInterrupt() {
            try {
                waitIgnoringInterrupt(released);
            } finally {
                // Natural termination unregisters; a cancellation callback already running still owns cleanup.
                finished.run();
            }
        }

        @Override public void close() {
            closeCalls.incrementAndGet();
            closeEntered.countDown();
            waitIgnoringInterrupt(allowClose);
            if (closed.compareAndSet(false, true)) released.countDown();
        }

        void releaseForTestCleanup() {
            allowClose.countDown();
            released.countDown();
        }

        private static void waitIgnoringInterrupt(CountDownLatch latch) {
            boolean interrupted = false;
            try {
                for (;;) {
                    try {
                        if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Test operation was not released");
                        return;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    static final class ScriptedAdapter implements MonitoringAdapter {
        final List<CollectionRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger ordinaryActive = new AtomicInteger();
        final AtomicInteger ordinaryPeak = new AtomicInteger();
        final AtomicInteger capacityActive = new AtomicInteger();
        final AtomicInteger capacityPeak = new AtomicInteger();
        volatile Function<CollectionRequest, CollectionResult> behavior;

        ScriptedAdapter(Function<CollectionRequest, CollectionResult> behavior) { this.behavior = behavior; }

        @Override public MiddlewareType type() { return MiddlewareType.REDIS; }

        @Override public CollectionResult collect(CollectionRequest request) {
            requests.add(request);
            AtomicInteger active = request.kind() == CollectionKind.ORDINARY ? ordinaryActive : capacityActive;
            AtomicInteger peak = request.kind() == CollectionKind.ORDINARY ? ordinaryPeak : capacityPeak;
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            try {
                return behavior.apply(request);
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
