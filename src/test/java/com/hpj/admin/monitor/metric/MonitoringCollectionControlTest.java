package com.hpj.admin.monitor.metric;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.MonitoringCalculations.CounterSample;
import com.hpj.admin.monitor.metric.MonitoringCalculations.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringCalculations.Calculation.RATE;
import static com.hpj.admin.monitor.metric.MonitoringSnapshotStore.Acceptance.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MonitoringCollectionControlTest {
    private static final Instant START = Instant.parse("2026-09-10T00:00:00Z");
    private static final long BUDGET = Duration.ofSeconds(5).toNanos();
    private static final Duration INTERVAL = Duration.ofSeconds(15);
    private static final Object EPOCH = new Object();
    private static final Object BORROWED = new Object() {
        @Override public String toString() { throw new AssertionError("Borrowed client must not be inspected"); }
    };
    private static final MonitoringTarget.Scope ALLOWED = new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of());
    private static final Definition COMMANDS = definition("commands.rate", "Commands");

    @Test
    void monotonicDeadlineWinsEvenWhenTheWallClockMovesBackwards() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            CollectionControl control = fixture.control("binding-a", CollectionKind.ORDINARY);
            assertThat(control.remaining()).isEqualTo(Duration.ofSeconds(5));
            fixture.clock.at = START.minus(Duration.ofDays(1));
            fixture.ticker.set(BUDGET - 1);
            assertThat(control.remaining()).isEqualTo(Duration.ofNanos(1));
            fixture.ticker.incrementAndGet();
            assertThat(control.remaining()).isZero();
            assertThatThrownBy(control::checkActive).isInstanceOf(CollectionControl.InactiveCollectionException.class)
                    .hasMessageContaining("TIMEOUT");
            assertThat(control.isCancelled()).isTrue();
            assertThat(control.isActive()).isFalse();
            assertThat(control.isCleanupComplete()).isTrue();
        }
        CollectionControl detached = CollectionControl.detached(Instant.EPOCH);
        assertThat(detached.isActive()).isFalse();
        assertThat(detached.isCleanupComplete()).isTrue();
        assertThatThrownBy(detached::reserveOwnedCancellation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellationRunsOnceOutsideTheLifecycleLockAndTheCallingThread() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            CollectionControl control = fixture.control("binding-a", CollectionKind.ORDINARY);
            AtomicInteger calls = new AtomicInteger();
            AtomicBoolean heldLock = new AtomicBoolean();
            AtomicReference<Thread> callbackThread = new AtomicReference<>();
            control.registerOwnedCancellation(new Object(), () -> {
                heldLock.set(Thread.holdsLock(fixture.gate));
                callbackThread.set(Thread.currentThread());
                calls.incrementAndGet();
            });
            synchronized (fixture.gate) {
                control.cancel(MissingReason.TIMEOUT);
                assertThat(calls).hasValue(0);
            }
            await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
            control.cancel(MissingReason.FAILED);
            control.retryCleanup();
            assertThat(calls).hasValue(1);
            assertThat(heldLock).isFalse();
            assertThat(callbackThread.get()).isNotSameAs(Thread.currentThread());
            assertThat(control.cancellationReason()).isEqualTo(MissingReason.TIMEOUT);
        }
    }

    @Test
    void cancellationBetweenReservationAndAttachmentKeepsTheResourceAccountedFor() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            CollectionControl control = fixture.control("binding-a", CollectionKind.ORDINARY);
            CollectionControl.Registration reservation = control.reserveOwnedCancellation();
            control.cancel(MissingReason.TIMEOUT);
            assertThat(control.isCleanupComplete()).isFalse();
            AtomicInteger calls = new AtomicInteger();
            reservation.attach(new Object(), calls::incrementAndGet);
            await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
            assertThat(calls).hasValue(1);

            // An adapter may also register a request which was acquired immediately before cancellation.
            control.registerOwnedCancellation(new Object(), calls::incrementAndGet);
            await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
            assertThat(calls).hasValue(2);
            CollectionControl unused = fixture.control("binding-b", CollectionKind.ORDINARY);
            var notAcquired = unused.reserveOwnedCancellation();
            unused.cancel(MissingReason.FAILED);
            assertThat(unused.isCleanupComplete()).isFalse();
            notAcquired.close();
            assertThat(unused.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void reservationsAreBoundedAndBorrowedClientsCannotBeRegisteredAsOwned() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            CollectionControl control = fixture.control("binding-a", CollectionKind.ORDINARY);
            AtomicInteger borrowedClose = new AtomicInteger();
            assertThatThrownBy(() -> control.registerOwnedCancellation(BORROWED, borrowedClose::incrementAndGet))
                    .isInstanceOf(IllegalArgumentException.class);
            List<CollectionControl.Registration> reservations = new ArrayList<>();
            for (int index = 0; index < 64; index++) reservations.add(control.reserveOwnedCancellation());
            assertThatThrownBy(control::reserveOwnedCancellation).isInstanceOf(IllegalStateException.class);
            control.cancel(MissingReason.FAILED);
            assertThat(control.isCleanupComplete()).isFalse();
            reservations.forEach(CollectionControl.Registration::close);
            assertThat(control.isCleanupComplete()).isTrue();
            assertThat(borrowedClose).hasValue(0);
        }
    }

    @Test
    void executorSaturationRetainsCleanupForRetryWithoutCallerRuns() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                fixture.cleanup.execute(() -> { occupied.countDown(); awaitLatch(release); });
                assertThat(occupied.await(3, TimeUnit.SECONDS)).isTrue();
                fixture.cleanup.execute(() -> { }); // Fill the one-slot queue.
                CollectionControl control = fixture.control("binding-a", CollectionKind.ORDINARY);
                AtomicInteger calls = new AtomicInteger();
                control.registerOwnedCancellation(new Object(), calls::incrementAndGet);
                control.cancel(MissingReason.TIMEOUT);
                assertThat(calls).hasValue(0);
                assertThat(control.isCleanupComplete()).isFalse();
                release.countDown();
                await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
                assertThat(calls).hasValue(1);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void unregisterDoesNotHideRunningCleanupAndCleanupFailureRetainsTheSingleFlightSlot() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            CollectionControl control = fixture.control("binding-a", CollectionKind.ORDINARY);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                var registration = control.registerOwnedCancellation(new Object(), () -> {
                    entered.countDown();
                    awaitLatch(release);
                });
                control.cancel(MissingReason.TIMEOUT);
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                registration.close();
                assertThat(control.isCleanupComplete()).isFalse();
                release.countDown();
                await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
            } finally {
                release.countDown();
            }
            CollectionControl failed = fixture.control("binding-b", CollectionKind.ORDINARY);
            AtomicInteger failures = new AtomicInteger();
            failed.registerOwnedCancellation(new Object(), () -> {
                failures.incrementAndGet();
                throw new IllegalStateException("private-cleanup-password");
            });
            failed.cancel(MissingReason.FAILED);
            await().atMost(Duration.ofSeconds(3)).until(failed::hasCleanupFailure);
            assertThat(failed.isCleanupComplete()).isFalse();
            failed.retryCleanup();
            assertThat(failures).hasValue(1);
            assertThat(failed.toString()).doesNotContain("private-cleanup-password");
        }
    }

    @Test
    void onlyAnAcceptedResultCommitsItsRepresentedCounterAndFinishesOwnedCleanup() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            CollectionControl first = fixture.control("binding-a", CollectionKind.ORDINARY);
            Result initial = first.calculate(COMMANDS, RATE, counter(0, 100));
            assertThat(initial.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
            AtomicInteger cleanup = new AtomicInteger();
            first.registerOwnedCancellation(new Object(), cleanup::incrementAndGet);
            var alreadyFinished = first.registerOwnedCancellation(new Object(), () -> { throw new AssertionError("Already finished"); });
            alreadyFinished.close();
            assertThat(fixture.complete(first, "binding-a", CollectionKind.ORDINARY, 0, 0,
                    List.of(metric(COMMANDS, initial, 0)), false)).isEqualTo(ACCEPTED);
            await().atMost(Duration.ofSeconds(3)).until(first::isCleanupComplete);
            assertThat(cleanup).hasValue(1);
            assertThat(first.isActive()).isFalse();
            assertThat(fixture.counters.seriesCount("cache")).isEqualTo(1);
            CollectionControl next = fixture.control("binding-a", CollectionKind.ORDINARY);
            Result rate = next.calculate(COMMANDS, RATE, counter(15, 130));
            assertThat(rate.value()).isEqualByComparingTo("2");
            // An equivalent decimal scale is still the calculated value.
            MetricSample sample = MetricSample.success(COMMANDS, rate.value().setScale(2), at(15), Duration.ofSeconds(45));
            assertThat(fixture.complete(next, "binding-a", CollectionKind.ORDINARY, 1, 15, List.of(sample), false))
                    .isEqualTo(ACCEPTED);
            CollectionControl third = fixture.control("binding-a", CollectionKind.ORDINARY);
            assertThat(third.calculate(COMMANDS, RATE, counter(30, 160)).value()).isEqualByComparingTo("2");
        }
    }

    @Test
    void actualDeadlineOrRetiredGenerationDropsCandidatesEvenWithAnOnTimeReportedCompletion() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            CollectionControl late = fixture.control("binding-a", CollectionKind.ORDINARY);
            Result staged = late.calculate(COMMANDS, RATE, counter(0, 100));
            fixture.ticker.set(BUDGET);
            assertThat(fixture.complete(late, "binding-a", CollectionKind.ORDINARY, 0, 0,
                    List.of(metric(COMMANDS, staged, 0)), false)).isEqualTo(DEADLINE_EXCEEDED);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
            assertThat(fixture.snapshots.snapshot("cache").orElseThrow().attempts()).isEmpty();

            CollectionControl retired = fixture.control("binding-a", CollectionKind.ORDINARY);
            Result old = retired.calculate(COMMANDS, RATE, counter(15, 130));
            fixture.generationActive.set(false);
            assertThat(fixture.complete(retired, "binding-a", CollectionKind.ORDINARY, 1, 15,
                    List.of(metric(COMMANDS, old, 15)), false)).isEqualTo(INACTIVE_GENERATION);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
            assertThatThrownBy(() -> retired.calculate(COMMANDS, RATE, counter(30, 160)))
                    .isInstanceOf(CollectionControl.InactiveCollectionException.class);
            fixture.generationActive.set(true);
            assertThat(fixture.control("binding-a", CollectionKind.ORDINARY).calculate(COMMANDS, RATE, counter(30, 160)).reason())
                    .isEqualTo(MissingReason.WAITING_SAMPLE);
        }
    }

    @Test
    void snapshotAndCounterCapacityRefusalsCannotPublishOnlyHalfOfATransaction() throws Exception {
        try (Fixture fixture = new Fixture(4, 1)) {
            fixture.seed("binding-a", CollectionKind.ORDINARY, COMMANDS, 100);
            CollectionControl rejected = fixture.control("binding-a", CollectionKind.ORDINARY);
            Definition extra = definition("extra.rate", "Extra");
            Result changed = rejected.calculate(COMMANDS, RATE, counter(15, 130));
            Result added = rejected.calculate(extra, RATE, counter(15, 20));
            assertThat(fixture.complete(rejected, "binding-a", CollectionKind.ORDINARY, 1, 15,
                    List.of(metric(COMMANDS, changed, 15), metric(extra, added, 15)), false)).isEqualTo(CAPACITY_EXCEEDED);
            assertThat(fixture.counters.seriesCount("cache")).isEqualTo(1);
            assertThat(fixture.snapshots.snapshot("cache").orElseThrow().attempts().get(0).sequence()).isZero();
            assertThat(fixture.control("binding-a", CollectionKind.ORDINARY).calculate(COMMANDS, RATE, counter(30, 130)).value())
                    .isEqualByComparingTo("1");
        }
        try (Fixture fixture = new Fixture(1, 4)) {
            fixture.counters.update("cache", "existing-legacy-series", RATE, counter(0, 100), INTERVAL);
            CollectionControl rejected = fixture.control("binding-a", CollectionKind.ORDINARY);
            Result staged = rejected.calculate(COMMANDS, RATE, counter(15, 130));
            assertThat(fixture.complete(rejected, "binding-a", CollectionKind.ORDINARY, 0, 15,
                    List.of(metric(COMMANDS, staged, 15)), false)).isEqualTo(CAPACITY_EXCEEDED);
            assertThat(fixture.snapshots.snapshot("cache").orElseThrow().attempts()).isEmpty();
            assertThat(fixture.counters.seriesCount("cache")).isEqualTo(1);
        }
    }

    @Test
    void aChangedCommittedPreviousSampleRejectsTheWholeStaleCalculation() throws Exception {
        try (Fixture fixture = new Fixture(4, 4)) {
            fixture.seed("binding-a", CollectionKind.ORDINARY, COMMANDS, 100);
            CollectionControl first = fixture.control("binding-a", CollectionKind.ORDINARY);
            CollectionControl stale = fixture.control("binding-a", CollectionKind.ORDINARY);
            Result newer = first.calculate(COMMANDS, RATE, counter(15, 130));
            Result oldPrevious = stale.calculate(COMMANDS, RATE, counter(30, 200));
            assertThat(fixture.complete(first, "binding-a", CollectionKind.ORDINARY, 1, 15,
                    List.of(metric(COMMANDS, newer, 15)), false)).isEqualTo(ACCEPTED);
            assertThat(fixture.complete(stale, "binding-a", CollectionKind.ORDINARY, 2, 30,
                    List.of(metric(COMMANDS, oldPrevious, 30)), false)).isEqualTo(OUT_OF_ORDER);
            assertThat(fixture.snapshots.snapshot("cache").orElseThrow().attempts().get(0).sequence()).isEqualTo(1);
            assertThat(fixture.control("binding-a", CollectionKind.ORDINARY).calculate(COMMANDS, RATE, counter(30, 160)).value())
                    .isEqualByComparingTo("2");
        }
    }

    @Test
    void completeInventoryRetiresOnlyItsBindingAndKindAndKeepsOtherBaselines() throws Exception {
        try (Fixture fixture = new Fixture(3, 3)) {
            fixture.seed("binding-a", CollectionKind.ORDINARY, COMMANDS, 100);
            fixture.seed("binding-b", CollectionKind.ORDINARY, COMMANDS, 1000);
            fixture.seed("binding-a", CollectionKind.CAPACITY, COMMANDS, 2000);
            CollectionControl empty = fixture.control("binding-a", CollectionKind.ORDINARY);
            assertThat(fixture.complete(empty, "binding-a", CollectionKind.ORDINARY, 1, 15, List.of(), true)).isEqualTo(ACCEPTED);
            assertThat(fixture.counters.seriesCount("cache")).isEqualTo(2);
            assertThat(fixture.snapshots.snapshot("cache").orElseThrow().metrics()).hasSize(2);
            assertThat(fixture.control("binding-b", CollectionKind.ORDINARY).calculate(COMMANDS, RATE, counter(15, 1030)).value())
                    .isEqualByComparingTo("2");
            assertThat(fixture.control("binding-a", CollectionKind.CAPACITY).calculate(COMMANDS, RATE, counter(15, 2015)).value())
                    .isEqualByComparingTo("1");
            assertThat(fixture.control("binding-a", CollectionKind.ORDINARY).calculate(COMMANDS, RATE, counter(15, 130)).reason())
                    .isEqualTo(MissingReason.WAITING_SAMPLE);
        }
    }

    @Test
    void nullAndUnreportedCalculationsNeverBecomeCommittedBaselines() throws Exception {
        try (Fixture fixture = new Fixture(2, 2)) {
            CollectionControl omitted = fixture.control("binding-a", CollectionKind.ORDINARY);
            assertThat(omitted.calculate(COMMANDS, RATE, null).reason()).isEqualTo(MissingReason.INVALID_VALUE);
            omitted.calculate(COMMANDS, RATE, counter(0, 100));
            assertThat(fixture.complete(omitted, "binding-a", CollectionKind.ORDINARY, 0, 0, List.of(), false)).isEqualTo(ACCEPTED);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
            CollectionControl failed = fixture.control("binding-a", CollectionKind.ORDINARY);
            failed.calculate(COMMANDS, RATE, counter(15, 130));
            assertThat(fixture.complete(failed, "binding-a", CollectionKind.ORDINARY, 1, 15,
                    List.of(MetricSample.missing(COMMANDS, MissingReason.TIMEOUT, at(15))), false)).isEqualTo(ACCEPTED);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
        }
    }

    @Test
    void pendingCalculationsUseTheSameBoundWithoutReservingGlobalCounterSlots() throws Exception {
        try (Fixture fixture = new Fixture(1, 2)) {
            CollectionControl control = fixture.control("binding-a", CollectionKind.ORDINARY);
            control.calculate(COMMANDS, RATE, counter(0, 100));
            Definition extra = definition("extra.rate", "Extra");
            Result overflow = control.calculate(extra, RATE, counter(0, 200));
            assertThat(overflow.reason()).isEqualTo(MissingReason.BUSY);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
            control.cancel(MissingReason.TIMEOUT);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
        }
    }

    @Test
    void metadataChangesReplaceThePreviousDefinitionEvenWithoutCompleteInventory() throws Exception {
        try (Fixture fixture = new Fixture(1, 1)) {
            fixture.seed("binding-a", CollectionKind.ORDINARY, COMMANDS, 100);
            for (int revision = 1; revision <= 10; revision++) {
                Definition changed = definition(COMMANDS.key(), "Commands revision " + revision);
                CollectionControl control = fixture.control("binding-a", CollectionKind.ORDINARY);
                Result first = control.calculate(changed, RATE, counter(revision * 15L, 100 + revision * 30L));
                assertThat(first.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
                assertThat(fixture.complete(control, "binding-a", CollectionKind.ORDINARY, revision, revision * 15L,
                        List.of(metric(changed, first, revision * 15L)), false)).isEqualTo(ACCEPTED);
                assertThat(fixture.counters.seriesCount("cache")).isEqualTo(1);
            }
        }
    }

    private static Definition definition(String key, String label) {
        return new Definition(key, label, Unit.COUNT_PER_SECOND, new Scope(ScopeKind.PROCESS, "redis", null),
                "INFO stats", "Native counter delta / elapsed seconds");
    }

    private static Instant at(long seconds) { return START.plusSeconds(seconds); }
    private static CounterSample counter(long seconds, long value) {
        return new CounterSample(at(seconds), EPOCH, List.of(BigDecimal.valueOf(value)));
    }
    private static MetricSample metric(Definition definition, Result result, long seconds) {
        return result.value() == null ? MetricSample.missing(definition, result.reason(), at(seconds))
                : MetricSample.success(definition, result.value(), at(seconds), Duration.ofSeconds(45));
    }
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test resource was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Unexpected cleanup interruption");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant at = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }

    private static final class Fixture implements AutoCloseable {
        private final Object gate = new Object();
        private final AtomicBoolean generationActive = new AtomicBoolean(true);
        private final AtomicLong ticker = new AtomicLong();
        private final MutableClock clock = new MutableClock();
        private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread worker = new Thread(task, "monitor-control-cleanup-test");
                    worker.setDaemon(true);
                    return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
        private final MonitoringCounterStore counters;
        private final MonitoringSnapshotStore snapshots;

        private Fixture(int counterLimit, int snapshotLimit) {
            counters = new MonitoringCounterStore(2, counterLimit);
            snapshots = new MonitoringSnapshotStore(2, snapshotLimit, 4);
            snapshots.activate("cache", 1);
        }

        private CollectionControl control(String binding, CollectionKind kind) {
            return new CollectionControl(gate, generationActive::get, clock, ticker::get, ticker.get() + BUDGET,
                    cleanup, counters, "cache", binding, kind, INTERVAL, BORROWED);
        }

        private MonitoringSnapshotStore.Acceptance complete(CollectionControl control, String binding, CollectionKind kind,
                long sequence, long seconds, List<MetricSample> metrics, boolean complete) {
            CollectionRequest request = new CollectionRequest("cache", "factory", binding, kind, 1, sequence,
                    at(seconds), at(seconds + 5), ALLOWED, BORROWED, Map.of(), control);
            CollectionResult result = new CollectionResult(CollectionStatus.PARTIAL, at(seconds), at(seconds), metrics, null, complete);
            return control.complete(request, result, snapshots);
        }

        private void seed(String binding, CollectionKind kind, Definition definition, long count) {
            CollectionControl control = control(binding, kind);
            Result first = control.calculate(definition, RATE, counter(0, count));
            assertThat(complete(control, binding, kind, 0, 0, List.of(metric(definition, first, 0)), false)).isEqualTo(ACCEPTED);
        }

        @Override public void close() throws InterruptedException {
            cleanup.shutdownNow();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
