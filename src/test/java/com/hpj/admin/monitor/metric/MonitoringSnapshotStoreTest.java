package com.hpj.admin.monitor.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.monitor.MonitoringTarget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringSnapshotStore.Acceptance.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringSnapshotStoreTest {
    private static final Instant START = Instant.parse("2026-09-10T00:00:00Z");
    private static final MonitoringTarget.Scope ALLOWED = new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of());
    private static final Scope PROCESS = new Scope(ScopeKind.PROCESS, "main", null);
    private final MonitoringSnapshotStore store = new MonitoringSnapshotStore(2, 4, 2);

    @Test
    void failureRetainsOnlyLastSuccessAndDoesNotRefreshCapacityOrServiceTime() {
        store.activate("cache", 1);
        Definition rate = definition("commands.rate", Unit.COUNT_PER_SECOND);
        Definition bytes = definition("aof.bytes", Unit.BYTES);
        MetricSample ordinary = MetricSample.success(rate, 0, at(0), Duration.ofSeconds(45));
        MetricSample capacity = MetricSample.success(bytes, 1024, at(1), Duration.ofSeconds(180));
        ServiceProbe health = new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, at(0), at(45));
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 0, 0),
                result(0, CollectionStatus.SUCCESS, List.of(ordinary), health))).isEqualTo(ACCEPTED);
        assertThat(store.accept(request("cache", "factory", CollectionKind.CAPACITY, 1, 0, 1),
                result(1, CollectionStatus.SUCCESS, List.of(capacity), null))).isEqualTo(ACCEPTED);
        TargetSnapshot before = store.snapshot("cache").orElseThrow();

        MetricSample failed = MetricSample.missing(rate, MissingReason.TIMEOUT, at(15));
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 1, 15),
                result(15, CollectionStatus.FAILED, List.of(failed), null))).isEqualTo(ACCEPTED);
        TargetSnapshot current = store.snapshot("cache").orElseThrow();
        StoredMetric rateState = metric(current, "factory", "commands.rate");
        assertThat(rateState.latestAttempt()).isEqualTo(failed);
        assertThat(rateState.lastSuccess().value()).isEqualTo(BigDecimal.ZERO);
        assertThat(rateState.lastSuccess().lastSuccessAt()).isEqualTo(at(0));
        assertThat(rateState.lastSuccess().validUntil()).isEqualTo(at(45));
        assertThat(metric(current, "factory", "aof.bytes").lastSuccess()).isEqualTo(capacity);
        assertThat(current.serviceProbes().get("factory")).isEqualTo(health);
        assertThat(metric(before, "factory", "commands.rate").latestAttempt()).isEqualTo(ordinary);
        assertThat(current.attempts()).hasSize(2);
        assertThatThrownBy(() -> current.metrics().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sameSequenceOnSeparateSourcesKeepsTheirOwnPermissionAndHealthEvidence() {
        store.activate("cache", 1);
        Definition definition = definition("memory.bytes", Unit.BYTES);
        ServiceProbe available = new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, at(0), at(45));
        ServiceProbe unauthorized = new ServiceProbe(ServiceAvailability.UNKNOWN, MissingReason.UNAUTHORIZED, PROCESS, at(1), at(46));
        store.accept(request("cache", "reader", CollectionKind.ORDINARY, 1, 0, 0),
                result(0, CollectionStatus.SUCCESS, List.of(MetricSample.success(definition, 10, at(0), Duration.ofSeconds(45))), available));
        assertThat(store.accept(request("cache", "restricted", CollectionKind.ORDINARY, 1, 0, 1),
                result(1, CollectionStatus.UNAUTHORIZED, List.of(MetricSample.missing(definition, MissingReason.UNAUTHORIZED, at(1))), unauthorized)))
                .isEqualTo(ACCEPTED);
        TargetSnapshot snapshot = store.snapshot("cache").orElseThrow();
        assertThat(snapshot.attempts()).hasSize(2);
        assertThat(snapshot.serviceProbes()).containsEntry("reader", available).containsEntry("restricted", unauthorized);
        assertThat(metric(snapshot, "restricted", definition.key()).lastSuccess()).isNull();
        assertThat(metric(snapshot, "reader", definition.key()).lastSuccess().value()).isEqualTo(BigDecimal.TEN);
    }

    @Test
    void sameBeanBindingsKeepIndependentSequencesInventoryAndPermissionProbes() {
        store.activate("cache", 1);
        MonitoringTarget.Scope orders = new MonitoringTarget.Scope(List.of("orders"), List.of(), List.of(), List.of(), List.of());
        MonitoringTarget.Scope billing = new MonitoringTarget.Scope(List.of("billing"), List.of(), List.of(), List.of(), List.of());
        Definition sharedProcess = definition("memory.bytes", Unit.BYTES);
        ServiceProbe available = new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, at(0), at(45));
        ServiceProbe unauthorized = new ServiceProbe(ServiceAvailability.UNKNOWN, MissingReason.UNAUTHORIZED, PROCESS, at(0), at(45));
        Object sharedClient = new Object();
        CollectionRequest first = new CollectionRequest("cache", "factory", "orders-binding", CollectionKind.ORDINARY,
                1, 0, at(0), at(5), orders, sharedClient, Map.of());
        CollectionRequest second = new CollectionRequest("cache", "factory", "billing-binding", CollectionKind.ORDINARY,
                1, 0, at(0), at(5), billing, sharedClient, Map.of());
        assertThat(first.scope().databases()).containsExactly("orders");
        assertThat(second.scope().databases()).containsExactly("billing");
        assertThat(store.accept(first, new CollectionResult(CollectionStatus.SUCCESS, at(0), at(0),
                List.of(MetricSample.success(sharedProcess, 10, at(0), Duration.ofSeconds(45))), available, true)))
                .isEqualTo(ACCEPTED);
        assertThat(store.accept(second, new CollectionResult(CollectionStatus.UNAUTHORIZED, at(0), at(0),
                List.of(MetricSample.missing(sharedProcess, MissingReason.UNAUTHORIZED, at(0))), unauthorized,
                true, MissingReason.UNAUTHORIZED))).isEqualTo(ACCEPTED);
        TargetSnapshot initial = store.snapshot("cache").orElseThrow();
        assertThat(initial.attempts()).extracting(Attempt::bindingId).containsExactly("billing-binding", "orders-binding");
        assertThat(initial.attempts()).extracting(Attempt::source).containsOnly("factory");
        assertThat(initial.metrics()).hasSize(2).extracting(StoredMetric::bindingId)
                .containsExactly("billing-binding", "orders-binding");
        assertThat(initial.serviceProbes()).containsOnlyKeys("orders-binding", "billing-binding")
                .containsEntry("orders-binding", available).containsEntry("billing-binding", unauthorized);

        // A complete empty orders inventory must not retire billing, even though both use the same bean and series.
        CollectionRequest retired = new CollectionRequest("cache", "factory", "orders-binding", CollectionKind.ORDINARY,
                1, 2, at(15), at(20), orders, sharedClient, Map.of());
        assertThat(store.accept(retired, new CollectionResult(CollectionStatus.SUCCESS, at(15), at(15),
                List.of(), null, true))).isEqualTo(ACCEPTED);
        TargetSnapshot afterRetirement = store.snapshot("cache").orElseThrow();
        assertThat(afterRetirement.metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.bindingId()).isEqualTo("billing-binding");
            assertThat(metric.lastSuccess()).isNull();
        });
        assertThat(afterRetirement.serviceProbes()).containsEntry("billing-binding", unauthorized);

        // Billing sequence 1 is independent of the accepted orders sequence 2.
        CollectionRequest nextBilling = new CollectionRequest("cache", "factory", "billing-binding", CollectionKind.ORDINARY,
                1, 1, at(15), at(20), billing, sharedClient, Map.of());
        assertThat(store.accept(nextBilling, result(15, CollectionStatus.SUCCESS, List.of(
                MetricSample.success(sharedProcess, 20, at(15), Duration.ofSeconds(45))), null))).isEqualTo(ACCEPTED);
        assertThat(store.snapshot("cache").orElseThrow().metrics()).singleElement()
                .satisfies(metric -> assertThat(metric.lastSuccess().value()).isEqualTo(BigDecimal.valueOf(20)));
    }

    @Test
    void bindingBudgetStillAppliesWhenEveryBindingBorrowsTheSameBean() {
        MonitoringSnapshotStore bounded = new MonitoringSnapshotStore(1, 4, 1);
        bounded.activate("cache", 1);
        CollectionRequest first = new CollectionRequest("cache", "factory", "orders-binding", CollectionKind.ORDINARY,
                1, 0, at(0), at(5), ALLOWED, new Object(), Map.of());
        CollectionRequest second = new CollectionRequest("cache", "factory", "billing-binding", CollectionKind.ORDINARY,
                1, 0, at(0), at(5), ALLOWED, first.client(), Map.of());
        assertThat(bounded.accept(first, result(0, CollectionStatus.WAITING, List.of(), null))).isEqualTo(ACCEPTED);
        TargetSnapshot before = bounded.snapshot("cache").orElseThrow();
        assertThat(bounded.accept(second, result(0, CollectionStatus.WAITING, List.of(), null))).isEqualTo(CAPACITY_EXCEEDED);
        assertThat(bounded.snapshot("cache").orElseThrow()).isEqualTo(before);
    }

    @Test
    void firstTimeoutAndBusyAttemptsNeedNoInventedMetricOrConnectionFailure() {
        store.activate("cache", 1);
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 0, 0),
                new CollectionResult(CollectionStatus.FAILED, at(0), at(5), List.of(), null, false, MissingReason.TIMEOUT)))
                .isEqualTo(ACCEPTED);
        TargetSnapshot timedOut = store.snapshot("cache").orElseThrow();
        assertThat(timedOut.metrics()).isEmpty();
        assertThat(timedOut.serviceProbes()).isEmpty();
        assertThat(timedOut.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(attempt.bindingId()).isEqualTo("factory");
        });
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 1, 15),
                new CollectionResult(CollectionStatus.BUSY, at(15), at(15), List.of(), null, false, MissingReason.BUSY)))
                .isEqualTo(ACCEPTED);
        assertThat(store.snapshot("cache").orElseThrow().attempts()).singleElement()
                .satisfies(attempt -> assertThat(attempt.reason()).isEqualTo(MissingReason.BUSY));
    }

    @Test
    void retiredLateAndOutOfOrderResultsCannotRecreateOrOverwriteCurrentState() {
        store.activate("cache", 1);
        CollectionRequest original = request("cache", "factory", CollectionKind.ORDINARY, 1, 3, 10);
        assertThat(store.accept(original, result(10, CollectionStatus.WAITING, List.of(), null))).isEqualTo(ACCEPTED);
        assertThat(store.accept(original, result(10, CollectionStatus.SUCCESS, List.of(), null))).isEqualTo(OUT_OF_ORDER);
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 4, 20),
                result(26, CollectionStatus.SUCCESS, List.of(), null))).isEqualTo(DEADLINE_EXCEEDED);
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 4, 20),
                result(19, CollectionStatus.SUCCESS, List.of(), null))).isEqualTo(INVALID_TIMING);
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 4, 0),
                result(0, CollectionStatus.SUCCESS, List.of(), null))).isEqualTo(OUT_OF_ORDER);
        // Exact deadline remains inside the queue-inclusive budget.
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 4, 20),
                result(25, CollectionStatus.SUCCESS, List.of(), null))).isEqualTo(ACCEPTED);

        assertThat(store.activate("cache", 2)).isTrue();
        assertThat(store.snapshot("cache").orElseThrow().attempts()).isEmpty();
        assertThat(store.accept(original, result(10, CollectionStatus.SUCCESS, List.of(), null))).isEqualTo(INACTIVE_GENERATION);
        assertThat(store.activate("cache", 1)).isFalse();
        assertThat(store.removeTarget("cache")).isTrue();
        assertThat(store.accept(request("cache", "factory", CollectionKind.ORDINARY, 2, 0, 0),
                result(0, CollectionStatus.SUCCESS, List.of(), null))).isEqualTo(INACTIVE_GENERATION);
    }

    @Test
    void targetSourceAndMetricLimitsRejectTheWholeUpdateWithoutPartialPublication() {
        MonitoringSnapshotStore bounded = new MonitoringSnapshotStore(1, 1, 1);
        assertThat(bounded.activate("cache", 1)).isTrue();
        assertThat(bounded.activate("other", 1)).isFalse();
        MetricSample first = MetricSample.success(definition("memory.bytes", Unit.BYTES), 1, at(0), Duration.ofSeconds(45));
        bounded.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 0, 0), result(0, CollectionStatus.SUCCESS, List.of(first), null));
        TargetSnapshot previous = bounded.snapshot("cache").orElseThrow();
        MetricSample changed = MetricSample.success(first.definition(), 2, at(15), Duration.ofSeconds(45));
        MetricSample extra = MetricSample.success(definition("other.bytes", Unit.BYTES), 3, at(15), Duration.ofSeconds(45));
        assertThat(bounded.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 1, 15),
                result(15, CollectionStatus.SUCCESS, List.of(changed, extra), null))).isEqualTo(CAPACITY_EXCEEDED);
        assertThat(bounded.snapshot("cache").orElseThrow()).isEqualTo(previous);
        assertThat(bounded.accept(request("cache", "extraSource", CollectionKind.ORDINARY, 1, 0, 15),
                result(15, CollectionStatus.SUCCESS, List.of(), null))).isEqualTo(CAPACITY_EXCEEDED);
        assertThat(bounded.snapshot("cache").orElseThrow()).isEqualTo(previous);
        assertThat(bounded.removeTarget("cache")).isTrue();
        assertThat(bounded.activate("other", 2)).isTrue();
        bounded.clear();
        assertThat(bounded.targetCount()).isZero();
    }

    @Test
    void definitionChangeCannotReuseAValueWithTheOldUnit() {
        store.activate("cache", 1);
        Definition old = definition("cpu", Unit.CORES);
        store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 0, 0), result(0, CollectionStatus.SUCCESS,
                List.of(MetricSample.success(old, 2, at(0), Duration.ofSeconds(45))), null));
        Definition changed = definition("cpu", Unit.PERCENT);
        store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 1, 15), result(15, CollectionStatus.WAITING,
                List.of(MetricSample.missing(changed, MissingReason.WAITING_SAMPLE, at(15))), null));
        assertThat(store.snapshot("cache").orElseThrow().metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.latestAttempt().definition().unit()).isEqualTo(Unit.PERCENT);
            assertThat(metric.lastSuccess()).isNull();
        });
    }

    @Test
    void completeInventoryRetiresReplacedNodesButUnknownInventoryPreservesEvidence() {
        MonitoringSnapshotStore bounded = new MonitoringSnapshotStore(1, 1, 1);
        bounded.activate("cache", 1);
        Definition old = new Definition("memory.bytes", "Memory", Unit.BYTES,
                new Scope(ScopeKind.NODE, "node-a", "node-a"), "native", "Node memory");
        Definition replacement = new Definition("memory.bytes", "Memory", Unit.BYTES,
                new Scope(ScopeKind.NODE, "node-b", "node-b"), "native", "Node memory");
        bounded.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 0, 0), result(0, CollectionStatus.SUCCESS,
                List.of(MetricSample.success(old, 100, at(0), Duration.ofSeconds(45))), null));
        // An incomplete discovery cannot prove that the old node is gone, even if the returned values succeeded.
        List<MetricSample> newSamples = List.of(MetricSample.success(replacement, 200, at(15), Duration.ofSeconds(45)));
        assertThat(bounded.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 1, 15),
                result(15, CollectionStatus.SUCCESS, newSamples, null))).isEqualTo(CAPACITY_EXCEEDED);
        assertThat(bounded.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 1, 15),
                new CollectionResult(CollectionStatus.SUCCESS, at(15), at(15), newSamples, null, true))).isEqualTo(ACCEPTED);
        assertThat(bounded.snapshot("cache").orElseThrow().metrics()).singleElement()
                .satisfies(metric -> assertThat(metric.latestAttempt().definition().scope().id()).isEqualTo("node-b"));
        // Complete identity discovery can accompany a failed value; its explicit placeholder keeps the old value.
        MetricSample missing = MetricSample.missing(replacement, MissingReason.UNAUTHORIZED, at(30));
        assertThat(bounded.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 2, 30),
                new CollectionResult(CollectionStatus.UNAUTHORIZED, at(30), at(30), List.of(missing), null, true))).isEqualTo(ACCEPTED);
        assertThat(bounded.snapshot("cache").orElseThrow().metrics()).singleElement()
                .satisfies(metric -> assertThat(metric.lastSuccess().value()).isEqualTo(BigDecimal.valueOf(200)));
    }

    @Test
    void collectionKindsRemainIndependentEvenForAnIdenticalSeriesKeyAndScope() {
        store.activate("cache", 1);
        Definition same = definition("same.bytes", Unit.BYTES);
        store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 0, 0), result(0, CollectionStatus.SUCCESS,
                List.of(MetricSample.success(same, 1, at(0), Duration.ofSeconds(45))), null));
        store.accept(request("cache", "factory", CollectionKind.CAPACITY, 1, 0, 0), result(0, CollectionStatus.SUCCESS,
                List.of(MetricSample.success(same, 2, at(0), Duration.ofSeconds(180))), null));
        store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 1, 15),
                new CollectionResult(CollectionStatus.SUCCESS, at(15), at(15), List.of(), null, true));
        assertThat(store.snapshot("cache").orElseThrow().metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.kind()).isEqualTo(CollectionKind.CAPACITY);
            assertThat(metric.lastSuccess().value()).isEqualTo(BigDecimal.valueOf(2));
            assertThat(metric.lastSuccess().validUntil()).isEqualTo(at(180));
        });
    }

    @Test
    void acceptedNewerSequenceCanReplaceAProbeWithTheSameSamplingTimestamp() {
        store.activate("cache", 1);
        ServiceProbe first = new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, at(0), at(45));
        ServiceProbe failed = new ServiceProbe(ServiceAvailability.CONNECTION_FAILED, MissingReason.FAILED, PROCESS, at(0), at(45));
        store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 0, 0), result(0, CollectionStatus.SUCCESS, List.of(), first));
        store.accept(request("cache", "factory", CollectionKind.ORDINARY, 1, 1, 0), result(0, CollectionStatus.FAILED, List.of(), failed));
        assertThat(store.snapshot("cache").orElseThrow().serviceProbes().get("factory")).isEqualTo(failed);
    }

    @Test
    void snapshotNeverCarriesTheBorrowedClientOrItsSecretSettings() throws Exception {
        store.activate("cache", 1);
        CollectionRequest request = new CollectionRequest("cache", "factory", CollectionKind.ORDINARY, 1, 0, at(0), at(5),
                ALLOWED, new Object() { @Override public String toString() { return "private-client-secret"; } },
                Map.of("password", "private-password-secret"));
        store.accept(request, result(0, CollectionStatus.WAITING, List.of(MetricSample.missing(
                definition("memory.bytes", Unit.BYTES), MissingReason.WAITING_SAMPLE, at(0))), null));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(store.snapshots());
        assertThat(json).contains("WAITING_SAMPLE", "memory.bytes", "factory")
                .doesNotContain("private-client-secret", "private-password-secret", "\"settings\"", "\"client\"");
    }

    @Test
    void concurrentActivationCannotExceedTargetBudget() throws Exception {
        MonitoringSnapshotStore bounded = new MonitoringSnapshotStore(1, 1, 1);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String target = "target-" + i;
                requests.add(() -> bounded.activate(target, 1));
            }
            long accepted = 0;
            for (var future : executor.invokeAll(requests)) if (future.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
            assertThat(bounded.targetCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Instant at(long seconds) { return START.plusSeconds(seconds); }
    private static Definition definition(String key, Unit unit) {
        return new Definition(key, "Metric", unit, PROCESS, "INFO", "Latest native sample");
    }
    private static CollectionRequest request(String target, String source, CollectionKind kind, long generation, long sequence, long seconds) {
        return new CollectionRequest(target, source, kind, generation, sequence, at(seconds), at(seconds + 5), ALLOWED, new Object(), Map.of());
    }
    private static CollectionResult result(long seconds, CollectionStatus status, List<MetricSample> metrics, ServiceProbe probe) {
        return new CollectionResult(status, at(seconds), at(seconds), metrics, probe);
    }
    private static StoredMetric metric(TargetSnapshot snapshot, String source, String key) {
        return snapshot.metrics().stream().filter(metric -> metric.source().equals(source)
                && metric.latestAttempt().definition().key().equals(key)).findFirst().orElseThrow();
    }
}
