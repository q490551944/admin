package com.hpj.admin.monitor.scheduling;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.connection.ResolvedConnection;
import com.hpj.admin.monitor.connection.ConnectionIdentity;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapterRegistry;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.*;
import static com.hpj.admin.monitor.scheduling.MonitoringSchedulerFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MonitoringSchedulerTest {
    @Test
    void twentyOrdinaryTargetsPublishWithinFiveRealSecondsWhileCapacityAndSlowCleanupAreBusy() {
        List<ResolvedTarget> targets = IntStream.range(0, 20)
                .mapToObj(index -> target("target" + index, new BorrowedClient())).toList();
        try (Harness h = new Harness(Duration.ofSeconds(5), targets)) {
            // Keep actual workers occupied until every deadline conclusion is visible. This also verifies
            // that a blocked cleanup callback cannot serialize deadline publication behind it.
            h.adapter.behavior = request -> {
                OwnedOperation operation = h.operation(request, true);
                operation.awaitReleaseIgnoringInterrupt();
                return success(request);
            };
            Map<String, Long> submitted = new HashMap<>();
            for (ResolvedTarget target : targets) {
                submitted.put(target.display().id(), System.nanoTime());
                assertThat(h.scheduler.submitNow(target.display().id(), CollectionKind.ORDINARY)).isTrue();
            }
            assertThat(h.scheduler.submitNow("target0", CollectionKind.CAPACITY)).isTrue();
            assertThat(h.scheduler.submitNow("target1", CollectionKind.CAPACITY)).isTrue();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(h.adapter.ordinaryActive).hasValue(8);
                assertThat(h.adapter.capacityActive).hasValue(2);
                assertThat(h.scheduler.diagnostics().ordinaryQueued()).isEqualTo(12);
            });
            Map<String, Long> published = new HashMap<>();
            await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(6)).until(() -> {
                for (TargetSnapshot snapshot : h.snapshots.snapshots()) {
                    if (snapshot.attempts().stream().anyMatch(attempt -> attempt.kind() == CollectionKind.ORDINARY)) {
                        published.putIfAbsent(snapshot.targetId(), System.nanoTime());
                    }
                }
                return published.size() == 20;
            });
            published.forEach((target, at) -> assertThat(Duration.ofNanos(at - submitted.get(target)))
                    .as("actual snapshot publication budget for %s", target).isLessThanOrEqualTo(Duration.ofSeconds(5)));
            List<Attempt> attempts = h.snapshots.snapshots().stream().flatMap(snapshot -> snapshot.attempts().stream())
                    .filter(attempt -> attempt.kind() == CollectionKind.ORDINARY).toList();
            assertThat(attempts.stream().filter(attempt -> attempt.status() == CollectionStatus.FAILED
                    && attempt.reason() == MissingReason.TIMEOUT).count()).isEqualTo(8);
            assertThat(attempts.stream().filter(attempt -> attempt.status() == CollectionStatus.BUSY
                    && attempt.reason() == MissingReason.BUSY).count()).isEqualTo(12);
            assertThat(h.adapter.requests.stream().filter(request -> request.kind() == CollectionKind.ORDINARY)).hasSize(8);
            assertThat(h.adapter.ordinaryPeak).hasValue(8);
            assertThat(h.adapter.capacityPeak).hasValue(2);
            assertThat(h.scheduler.diagnostics().workerThreads()).isLessThanOrEqualTo(10);
            assertThat(h.scheduler.diagnostics().ordinaryQueued()).isZero();
            h.operations.forEach(OwnedOperation::releaseForTestCleanup);
            h.awaitNoActiveOperations();
            await().untilAsserted(() -> assertThat(h.operations).allSatisfy(operation -> {
                assertThat(operation.closed).isTrue();
                assertThat(operation.closeCalls).hasValue(1);
            }));
            h.assertBorrowedClientsOpen();
        }
    }

    @Test
    void waitingTimeConsumesTheBudgetOfARequestThatStartsLate() {
        MonitoringProperties properties = properties(Duration.ofMillis(800));
        properties.setOrdinaryConcurrency(1);
        try (Harness h = new Harness(properties, List.of(target("blocked", new BorrowedClient()),
                target("queued", new BorrowedClient())))) {
            AtomicReference<Duration> remaining = new AtomicReference<>();
            h.adapter.behavior = request -> {
                if (request.targetId().equals("queued")) remaining.set(request.control().remaining());
                OwnedOperation operation = h.operation(request, false);
                operation.awaitReleaseIgnoringInterrupt();
                return success(request);
            };
            assertThat(h.scheduler.submitNow("blocked", CollectionKind.ORDINARY)).isTrue();
            await().untilAsserted(() -> assertThat(h.adapter.ordinaryActive).hasValue(1));
            long queuedAt = System.nanoTime();
            assertThat(h.scheduler.submitNow("queued", CollectionKind.ORDINARY)).isTrue();
            await().pollInterval(Duration.ofMillis(5)).until(() -> System.nanoTime() - queuedAt >= Duration.ofMillis(450).toNanos());
            h.operations.get(0).releaseForTestCleanup();
            await().untilAsserted(() -> assertThat(remaining.get()).isNotNull().isLessThan(Duration.ofMillis(400)));
            Attempt queued = h.awaitAttempt("queued", CollectionKind.ORDINARY);
            assertThat(queued.status()).isEqualTo(CollectionStatus.FAILED);
            assertThat(queued.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - queuedAt)).isLessThan(Duration.ofMillis(1000));
        }
    }

    @Test
    void cancellationClosesOwnedOperationDespiteIgnoredInterruptAndAllowsTheNextCycle() {
        try (Harness h = new Harness(Duration.ofMillis(300), List.of(target("cache", new BorrowedClient())))) {
            h.adapter.behavior = request -> {
                OwnedOperation operation = h.operation(request, false);
                operation.awaitReleaseIgnoringInterrupt();
                return success(request);
            };
            assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isTrue();
            Attempt timeout = h.awaitAttempt("cache", CollectionKind.ORDINARY);
            assertThat(timeout.reason()).isEqualTo(MissingReason.TIMEOUT);
            h.awaitNoActiveOperations();
            assertThat(h.operations).hasSize(1);
            assertThat(h.operations.get(0).closed).isTrue();
            assertThat(h.operations.get(0).closeCalls).hasValue(1);
            h.adapter.behavior = MonitoringSchedulerFixtures::success;
            await().until(() -> h.scheduler.submitNow("cache", CollectionKind.ORDINARY));
            await().untilAsserted(() -> assertThat(h.attempt("cache", CollectionKind.ORDINARY).sequence()).isGreaterThan(timeout.sequence()));
            assertThat(h.attempt("cache", CollectionKind.ORDINARY).status()).isEqualTo(CollectionStatus.SUCCESS);
            h.assertBorrowedClientsOpen();
        }
    }

    @Test
    void aNonCooperativeTaskKeepsSingleFlightBoundedWithoutBlockingAnotherCategory() {
        MonitoringProperties properties = properties(Duration.ofMillis(300));
        properties.setOrdinaryConcurrency(1);
        try (Harness h = new Harness(properties, List.of(target("cache", new BorrowedClient())))) {
            h.adapter.behavior = request -> {
                if (request.kind() == CollectionKind.CAPACITY) return success(request);
                OwnedOperation operation = h.operation(request, true);
                operation.awaitReleaseIgnoringInterrupt();
                return success(request);
            };
            assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isTrue();
            assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isFalse();
            assertThat(h.snapshots.snapshot("cache").orElseThrow().attempts()).isEmpty();
            h.awaitAttempt("cache", CollectionKind.ORDINARY);
            for (int i = 0; i < 100; i++) assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isFalse();
            assertThat(h.adapter.requests.stream().filter(request -> request.kind() == CollectionKind.ORDINARY)).hasSize(1);
            assertThat(h.scheduler.diagnostics().ordinaryQueued()).isZero();
            assertThat(h.scheduler.diagnostics().workerThreads()).isLessThanOrEqualTo(3);
            assertThat(h.scheduler.submitNow("cache", CollectionKind.CAPACITY)).isTrue();
            assertThat(h.awaitAttempt("cache", CollectionKind.CAPACITY).status()).isEqualTo(CollectionStatus.SUCCESS);
            h.operations.forEach(OwnedOperation::releaseForTestCleanup);
            h.awaitNoActiveOperations();
        }
    }

    @Test
    void closingCancelsOwnedWorkAndDropsQueuedTasksWithoutClosingBorrowedClients() {
        MonitoringProperties properties = properties(Duration.ofSeconds(3));
        properties.setOrdinaryConcurrency(1);
        try (Harness h = new Harness(properties, List.of(target("running", new BorrowedClient()),
                target("queued", new BorrowedClient())))) {
            h.adapter.behavior = request -> {
                h.operation(request, false).awaitReleaseIgnoringInterrupt();
                return success(request);
            };
            assertThat(h.scheduler.submitNow("running", CollectionKind.ORDINARY)).isTrue();
            await().untilAsserted(() -> assertThat(h.operations).hasSize(1));
            assertThat(h.scheduler.submitNow("queued", CollectionKind.ORDINARY)).isTrue();
            h.scheduler.close();
            h.scheduler.close();
            assertThat(h.scheduler.isRunning()).isFalse();
            assertThat(h.scheduler.targets()).isEmpty();
            assertThat(h.snapshots.snapshots()).isEmpty();
            assertThat(h.counters.targetCount()).isZero();
            h.awaitNoActiveOperations();
            assertThat(h.adapter.requests).hasSize(1);
            assertThat(h.operations.get(0).closed).isTrue();
            assertThat(h.operations.get(0).closeCalls).hasValue(1);
            await().untilAsserted(() -> assertThat(h.scheduler.diagnostics().workerThreads()).isZero());
            h.assertBorrowedClientsOpen();
        }
    }

    @Test
    void protocolFailureUsesItsOwnReasonAndDoesNotBlockAnotherTarget() {
        try (Harness h = new Harness(Duration.ofSeconds(1), List.of(target("failed", new BorrowedClient()),
                target("healthy", new BorrowedClient())))) {
            h.adapter.behavior = request -> {
                if (!request.targetId().equals("failed")) return success(request);
                Instant now = Instant.now();
                return new CollectionResult(CollectionStatus.FAILED, request.scheduledAt(), now, List.of(),
                        new ServiceProbe(ServiceAvailability.CONNECTION_FAILED, MissingReason.FAILED,
                                new Scope(ScopeKind.ENDPOINT, "endpoint", null), now, now.plusSeconds(45)),
                        false, MissingReason.FAILED);
            };
            assertThat(h.scheduler.submitNow("failed", CollectionKind.ORDINARY)).isTrue();
            assertThat(h.scheduler.submitNow("healthy", CollectionKind.ORDINARY)).isTrue();
            assertThat(h.awaitAttempt("failed", CollectionKind.ORDINARY).reason()).isEqualTo(MissingReason.FAILED);
            assertThat(h.awaitAttempt("healthy", CollectionKind.ORDINARY).status()).isEqualTo(CollectionStatus.SUCCESS);
        }
    }

    @Test
    void twoBindingsOfTheSameBeanKeepTheirOwnScopesAndIndependentAttempts() {
        BorrowedClient client = new BorrowedClient();
        ResolvedTarget merged = target("merged", List.of(binding("databaseA", "sharedRedis", client, scope("A")),
                binding("databaseB", "sharedRedis", client, scope("B"))));
        try (Harness h = new Harness(Duration.ofSeconds(1), List.of(merged))) {
            h.adapter.behavior = request -> {
                assertThat(request.scope().databases()).hasSize(1);
                if (request.scope().databases().contains("A")) {
                    return new CollectionResult(CollectionStatus.UNAUTHORIZED, request.scheduledAt(), Instant.now(),
                            List.of(), null, false, MissingReason.UNAUTHORIZED);
                }
                return success(request);
            };
            assertThat(h.scheduler.submitNow("merged", CollectionKind.ORDINARY)).isTrue();
            await().untilAsserted(() -> assertThat(h.snapshots.snapshot("merged").orElseThrow().attempts()).hasSize(2));
            assertThat(h.adapter.requests).hasSize(2);
            assertThat(h.adapter.requests).extracting(CollectionRequest::source).containsOnly("sharedRedis");
            assertThat(h.adapter.requests).extracting(CollectionRequest::bindingId).doesNotHaveDuplicates();
            assertThat(h.adapter.requests).extracting(request -> request.scope().databases()).containsExactlyInAnyOrder(List.of("A"), List.of("B"));
            assertThat(h.adapter.requests).extracting(CollectionRequest::client).containsOnly(client);
            assertThat(h.adapter.requests).extracting(CollectionRequest::deadline).containsOnly(h.adapter.requests.get(0).deadline());
            assertThat(h.snapshots.snapshot("merged").orElseThrow().attempts()).extracting(Attempt::status)
                    .containsExactlyInAnyOrder(CollectionStatus.UNAUTHORIZED, CollectionStatus.SUCCESS);
        }
    }

    @Test
    void ordinaryTimeoutRetainsOldValuesWithoutRefreshingCapacityOrServiceFreshness() {
        Definition ordinary = new Definition("commands.total", "Commands", Unit.COUNT,
                new Scope(ScopeKind.PROCESS, "main", null), "native commands", "native counter");
        Definition capacity = new Definition("storage.bytes", "Storage", Unit.BYTES,
                new Scope(ScopeKind.DATABASE, "0", null), "native storage", "native byte count");
        try (Harness h = new Harness(Duration.ofMillis(300), List.of(target("cache", new BorrowedClient())))) {
            h.adapter.behavior = request -> {
                Instant now = Instant.now();
                boolean regular = request.kind() == CollectionKind.ORDINARY;
                MetricSample sample = MetricSample.success(regular ? ordinary : capacity, regular ? 0 : 1024, now,
                        Duration.ofSeconds(regular ? 45 : 180));
                ServiceProbe probe = regular ? new ServiceProbe(ServiceAvailability.AVAILABLE, null,
                        ordinary.scope(), now, now.plusSeconds(45)) : null;
                return new CollectionResult(CollectionStatus.SUCCESS, request.scheduledAt(), now, List.of(sample), probe, true);
            };
            assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isTrue();
            assertThat(h.scheduler.submitNow("cache", CollectionKind.CAPACITY)).isTrue();
            h.awaitAttempt("cache", CollectionKind.ORDINARY);
            h.awaitAttempt("cache", CollectionKind.CAPACITY);
            h.awaitNoActiveOperations();
            TargetSnapshot before = h.snapshots.snapshot("cache").orElseThrow();
            StoredMetric previousOrdinary = before.metrics().stream().filter(metric -> metric.kind() == CollectionKind.ORDINARY).findFirst().orElseThrow();
            StoredMetric previousCapacity = before.metrics().stream().filter(metric -> metric.kind() == CollectionKind.CAPACITY).findFirst().orElseThrow();
            long previousSequence = h.attempt("cache", CollectionKind.ORDINARY).sequence();
            h.adapter.behavior = request -> {
                h.operation(request, false).awaitReleaseIgnoringInterrupt();
                return success(request);
            };
            assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isTrue();
            await().untilAsserted(() -> assertThat(h.attempt("cache", CollectionKind.ORDINARY).sequence()).isGreaterThan(previousSequence));
            TargetSnapshot after = h.snapshots.snapshot("cache").orElseThrow();
            StoredMetric failed = after.metrics().stream().filter(metric -> metric.kind() == CollectionKind.ORDINARY).findFirst().orElseThrow();
            assertThat(failed.latestAttempt().missingReason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(failed.lastSuccess()).isEqualTo(previousOrdinary.lastSuccess());
            assertThat(after.metrics().stream().filter(metric -> metric.kind() == CollectionKind.CAPACITY).findFirst().orElseThrow())
                    .isEqualTo(previousCapacity);
            assertThat(after.serviceProbes()).isEqualTo(before.serviceProbes());
        }
    }

    @Test
    void replacingGenerationRejectsLateResultsAndStagedCountersBeforeNewBaseline() {
        BorrowedClient original = new BorrowedClient();
        BorrowedClient replacement = new BorrowedClient();
        CountDownLatch releaseOld = new CountDownLatch(1);
        AtomicReference<CollectionRequest> oldRequest = new AtomicReference<>();
        AtomicInteger newSamples = new AtomicInteger();
        AtomicReference<Instant> newBaselineAt = new AtomicReference<>();
        Definition rate = new Definition("commands.rate", "Commands", Unit.COUNT_PER_SECOND,
                new Scope(ScopeKind.PROCESS, "main", null), "native commands", "counter delta / elapsed seconds");
        try (Harness h = new Harness(Duration.ofSeconds(3), List.of(target("cache", original)))) {
            h.adapter.behavior = request -> {
                if (request.client() == original) {
                    oldRequest.set(request);
                    waitForTestRelease(releaseOld);
                    try {
                        request.control().calculate(rate, Calculation.RATE,
                                new CounterSample(Instant.now(), "old-epoch", List.of(BigDecimal.valueOf(999))));
                    } catch (RuntimeException rejected) {
                        // A cancelled lifecycle may reject synchronously; it must never commit the staged sample.
                    }
                    return new CollectionResult(CollectionStatus.SUCCESS, request.scheduledAt(), request.scheduledAt(), List.of(), null);
                }
                int index = newSamples.getAndIncrement();
                Instant now = Instant.now();
                if (index == 0) newBaselineAt.set(now);
                BigDecimal elapsed = BigDecimal.valueOf(Duration.between(newBaselineAt.get(), now).toNanos(), 9);
                BigDecimal counter = BigDecimal.valueOf(100).add(elapsed.multiply(BigDecimal.TEN));
                Result value = request.control().calculate(rate, Calculation.RATE,
                        new CounterSample(now, "new-epoch", List.of(counter)));
                MetricSample sample = value.reason() == null ? MetricSample.success(rate, value.value(), now, Duration.ofSeconds(45))
                        : MetricSample.missing(rate, value.reason(), now);
                return new CollectionResult(CollectionStatus.SUCCESS, request.scheduledAt(), now, List.of(sample), null);
            };
            assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isTrue();
            await().until(() -> oldRequest.get() != null);
            long oldGeneration = oldRequest.get().generation();
            h.scheduler.replaceTargets(List.of(target("cache", replacement)));
            assertThat(h.snapshots.snapshot("cache").orElseThrow().generation()).isGreaterThan(oldGeneration);
            assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isFalse();
            Attempt occupied = h.attempt("cache", CollectionKind.ORDINARY);
            assertThat(occupied.status()).isEqualTo(CollectionStatus.BUSY);
            releaseOld.countDown();
            h.awaitNoActiveOperations();
            assertThat(h.attempt("cache", CollectionKind.ORDINARY)).isEqualTo(occupied);
            assertThat(h.counters.seriesCount("cache")).isZero();
            await().until(() -> h.scheduler.submitNow("cache", CollectionKind.ORDINARY));
            await().untilAsserted(() -> assertThat(h.attempt("cache", CollectionKind.ORDINARY).sequence()).isGreaterThan(occupied.sequence()));
            assertThat(h.snapshots.snapshot("cache").orElseThrow().metrics().get(0).latestAttempt().missingReason())
                    .isEqualTo(MissingReason.WAITING_SAMPLE);
            h.awaitNoActiveOperations();
            assertThat(h.scheduler.submitNow("cache", CollectionKind.ORDINARY)).isTrue();
            await().untilAsserted(() -> assertThat((BigDecimal) h.snapshots.snapshot("cache").orElseThrow().metrics().get(0).latestAttempt().value())
                    .isEqualByComparingTo(BigDecimal.TEN));
            h.assertBorrowedClientsOpen();
        } finally {
            releaseOld.countDown();
        }
    }

    @Test
    void disabledSchedulerDoesNotResolveCreateWorkersOrCollect() {
        MonitoringProperties properties = properties(Duration.ofSeconds(1));
        properties.setEnabled(false);
        AtomicInteger resolutions = new AtomicInteger();
        ScriptedAdapter adapter = new ScriptedAdapter(request -> { throw new AssertionError("Disabled collection"); });
        try (MonitoringScheduler scheduler = new MonitoringScheduler(properties, () -> {
            resolutions.incrementAndGet();
            throw new AssertionError("Disabled resolver");
        }, new MonitoringAdapterRegistry(List.of(adapter)), new MonitoringSnapshotStore(20, 50, 20),
                new MonitoringCounterStore(20, 50), false)) {
            scheduler.start();
            assertThat(scheduler.isRunning()).isFalse();
            assertThat(scheduler.submitNow("cache", CollectionKind.ORDINARY)).isFalse();
            assertThat(resolutions).hasValue(0);
            assertThat(scheduler.targets()).isEmpty();
            assertThat(scheduler.diagnostics().workerThreads()).isZero();
            assertThat(scheduler.diagnostics().cleanupThreads()).isZero();
        }
    }

    @Test
    void missingOptionalSourcesAndAdaptersDoNotPreventHealthyStartup() {
        BorrowedClient healthyClient = new BorrowedClient();
        ResolvedTarget absent = new ResolvedTarget(new MonitoringTarget("absent", MiddlewareType.MONGODB, "absent",
                MonitoringTarget.ConfigurationStatus.CONFIGURATION_MISSING, List.of(), scope("0")), List.of("absent"),
                List.of(), ResolvedTarget.Reason.SOURCE_MISSING);
        BorrowedClient jdbcClient = new BorrowedClient();
        ResolvedTarget noAdapter = new ResolvedTarget(new MonitoringTarget("noAdapter", MiddlewareType.MYSQL, "noAdapter",
                MonitoringTarget.ConfigurationStatus.CONFIGURED, List.of("jdbc"), scope("0")), List.of("noAdapter"),
                List.of(new ResolvedTarget.SourceBinding("noAdapter", "jdbc",
                        new ResolvedConnection(MiddlewareType.MYSQL, "jdbc", jdbcClient, ConnectionIdentity.forOwner(jdbcClient), Map.of()),
                        scope("0"))), ResolvedTarget.Reason.CONFIGURED);
        try (Harness h = new Harness(Duration.ofSeconds(1), List.of(target("healthy", healthyClient), absent, noAdapter))) {
            assertThat(h.scheduler.isRunning()).isTrue();
            assertThat(h.scheduler.submitNow("healthy", CollectionKind.ORDINARY)).isTrue();
            h.scheduler.submitNow("absent", CollectionKind.ORDINARY);
            h.scheduler.submitNow("noAdapter", CollectionKind.ORDINARY);
            assertThat(h.awaitAttempt("healthy", CollectionKind.ORDINARY).status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(h.adapter.requests).allSatisfy(request -> assertThat(request.targetId()).isEqualTo("healthy"));
        }
    }

    private static MonitoringProperties properties(Duration timeout) {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setCollectionTimeout(timeout);
        return properties;
    }

    private static void waitForTestRelease(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Old request was not released");
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static final class Harness implements AutoCloseable {
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(20, 100, 40);
        final MonitoringCounterStore counters = new MonitoringCounterStore(20, 100);
        final ScriptedAdapter adapter = new ScriptedAdapter(MonitoringSchedulerFixtures::success);
        final List<OwnedOperation> operations = new CopyOnWriteArrayList<>();
        final List<ResolvedTarget> initialTargets;
        final MonitoringScheduler scheduler;

        Harness(Duration timeout, List<ResolvedTarget> targets) { this(properties(timeout), targets); }

        Harness(MonitoringProperties properties, List<ResolvedTarget> targets) {
            initialTargets = targets;
            scheduler = new MonitoringScheduler(properties, () -> targets, new MonitoringAdapterRegistry(List.of(adapter)),
                    snapshots, counters, false);
            scheduler.start();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(scheduler.targets()).hasSize(targets.size()));
        }

        OwnedOperation operation(CollectionRequest request, boolean slowClose) {
            OwnedOperation operation = new OwnedOperation(slowClose);
            operations.add(operation);
            var registration = request.control().registerOwnedCancellation(operation, operation::close);
            operation.finished = registration::close;
            return operation;
        }

        Attempt attempt(String targetId, CollectionKind kind) {
            return snapshots.snapshot(targetId).orElseThrow().attempts().stream().filter(attempt -> attempt.kind() == kind)
                    .findFirst().orElseThrow();
        }

        Attempt awaitAttempt(String targetId, CollectionKind kind) {
            await().pollInterval(Duration.ofMillis(5)).atMost(Duration.ofSeconds(4)).until(() ->
                    snapshots.snapshot(targetId).stream().flatMap(snapshot -> snapshot.attempts().stream())
                            .anyMatch(attempt -> attempt.kind() == kind));
            return attempt(targetId, kind);
        }

        void awaitNoActiveOperations() {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(adapter.ordinaryActive).hasValue(0);
                assertThat(adapter.capacityActive).hasValue(0);
                assertThat(scheduler.diagnostics().inFlightTasks()).isZero();
            });
        }

        void assertBorrowedClientsOpen() {
            initialTargets.stream().flatMap(target -> target.bindings().stream()).map(binding -> binding.connection().client())
                    .distinct().forEach(client -> assertThat(((BorrowedClient) client).closes).hasValue(0));
        }

        @Override public void close() {
            operations.forEach(OwnedOperation::releaseForTestCleanup);
            scheduler.close();
            assertBorrowedClientsOpen();
        }
    }
}
