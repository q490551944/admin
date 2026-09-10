package com.hpj.admin.monitor.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hpj.admin.monitor.metric.MetricContract.Attempt;
import com.hpj.admin.monitor.metric.MetricContract.CollectionKind;
import com.hpj.admin.monitor.metric.MetricContract.CollectionResult;
import com.hpj.admin.monitor.metric.MetricContract.CollectionStatus;
import com.hpj.admin.monitor.metric.MetricContract.Definition;
import com.hpj.admin.monitor.metric.MetricContract.MetricSample;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MetricContract.Scope;
import com.hpj.admin.monitor.metric.MetricContract.ScopeKind;
import com.hpj.admin.monitor.metric.MetricContract.ServiceAvailability;
import com.hpj.admin.monitor.metric.MetricContract.ServiceProbe;
import com.hpj.admin.monitor.metric.MetricContract.StoredMetric;
import com.hpj.admin.monitor.metric.MetricContract.TargetSnapshot;
import com.hpj.admin.monitor.metric.MetricContract.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringMetricContractTest {
    private static final Instant NOW = Instant.parse("2026-09-10T08:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(45);
    private static final Scope PROCESS = new Scope(ScopeKind.PROCESS, "redis-process", null);

    @Test
    void realZeroAndMissingAreDistinctAndSuccessfulSamplesOwnTheirTimestamps() {
        Definition definition = definition("redis.memory.used_bytes", Unit.BYTES, PROCESS);
        MetricSample zero = MetricSample.success(definition, 0L, NOW, TTL);
        MetricSample missing = MetricSample.missing(definition, MissingReason.UNSUPPORTED, NOW);

        assertThat(zero.value()).isEqualTo(BigDecimal.ZERO);
        assertThat(zero.missingReason()).isNull();
        assertThat(zero.sampledAt()).isEqualTo(NOW);
        assertThat(zero.lastSuccessAt()).isEqualTo(NOW);
        assertThat(zero.validUntil()).isEqualTo(NOW.plus(TTL));
        assertThat(missing.value()).isNull();
        assertThat(missing.missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
        assertThat(missing.lastSuccessAt()).isNull();
        assertThat(missing.validUntil()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @MethodSource("validNumbers")
    void commonFiniteNumbersBecomeImmutableDecimalScalarsWithoutPrecisionLoss(Number value) {
        MetricSample sample = MetricSample.success(definition("operations.count", Unit.COUNT, PROCESS), value, NOW, TTL);
        assertThat(sample.value()).isExactlyInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) sample.value()).isEqualByComparingTo(new BigDecimal(value.toString()));
    }

    private static Stream<Number> validNumbers() {
        return Stream.of((byte) 0, (short) 42, 123, Long.MAX_VALUE, 0.125F, 1.25D,
                new BigInteger("18446744073709551615"), new BigDecimal("12345678901234567890.123456789"));
    }

    @Test
    void cpuAboveOneCoreAndTextAndBooleanValuesKeepTheirMeaning() {
        MetricSample cpu = MetricSample.success(definition("redis.cpu.percent", Unit.PERCENT, PROCESS), 250.5, NOW, TTL);
        assertThat(cpu.value()).isEqualTo(new BigDecimal("250.5"));
        assertThat(MetricSample.success(definition("redis.role", Unit.TEXT, PROCESS), "master", NOW, TTL).value())
                .isEqualTo("master");
        assertThat(MetricSample.success(definition("redis.aof.enabled", Unit.BOOLEAN, PROCESS), false, NOW, TTL).value())
                .isEqualTo(false);
    }

    @ParameterizedTest(name = "reject invalid scalar case {index}")
    @MethodSource("invalidValues")
    void rejectsNonFiniteNumbersMutableNumbersAndOpaqueValuesWithoutPrintingThem(Object value) {
        assertThatThrownBy(() -> MetricSample.success(definition("redis.metric", Unit.COUNT, PROCESS), value, NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid metric contract:")
                .hasMessageNotContaining("private-secret")
                .hasMessageNotContaining("private-host");
    }

    private static Stream<Object> invalidValues() {
        return Stream.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, new AtomicInteger(1), new int[]{1},
                Map.of("password", "private-secret"), URI.create("redis://user:private-secret@private-host:6379"),
                new Object() {
                    @Override public String toString() { throw new AssertionError("Rejected values must not be rendered"); }
                },
                new BigDecimal("1") {
                    @Override public String toString() { throw new AssertionError("Custom numeric objects must not be rendered"); }
                });
    }

    @Test
    void unitAndScalarTypeMustAgree() {
        assertThatThrownBy(() -> MetricSample.success(definition("count", Unit.COUNT, PROCESS), "0", NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricSample.success(definition("role", Unit.TEXT, PROCESS), 1, NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricSample.success(definition("enabled", Unit.BOOLEAN, PROCESS), "false", NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textScalarsAreBoundedAndCannotCarryControlCharacters() {
        Definition definition = definition("redis.role", Unit.TEXT, PROCESS);
        assertThat(MetricSample.success(definition, "", NOW, TTL).value()).isEqualTo("");
        assertThat(MetricSample.success(definition, "a".repeat(4096), NOW, TTL).value()).isEqualTo("a".repeat(4096));
        assertThatThrownBy(() -> MetricSample.success(definition, "a".repeat(4097), NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricSample.success(definition, "role\nprivate-secret", NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-secret");
    }

    @Test
    void sampleRequiresExactlyOneValueOrReasonAndCannotInventSuccessForMissingData() {
        Definition definition = definition("count", Unit.COUNT, PROCESS);
        assertThatThrownBy(() -> new MetricSample(definition, 0, MissingReason.UNSUPPORTED, NOW, NOW, NOW.plus(TTL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricSample(definition, null, null, NOW, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricSample(definition, null, MissingReason.FAILED, NOW, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricSample(definition, 0, null, NOW, NOW.minusSeconds(1), NOW.plus(TTL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricSample.success(definition, null, NOW, TTL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricSample.missing(definition, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dateOrderingAndValidityOverflowAreRejectedWhileEqualBoundariesRemainValid() {
        Definition definition = definition("count", Unit.COUNT, PROCESS);
        assertThat(MetricSample.success(definition, 1, NOW, Duration.ZERO).validUntil()).isEqualTo(NOW);
        assertThatThrownBy(() -> MetricSample.success(definition, 1, NOW, Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetricSample.success(definition, 1, Instant.MAX, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricSample(definition, 1, null, NOW, NOW, NOW.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, NOW, NOW.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW.minusNanos(1), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attempt("cache", CollectionKind.ORDINARY, 1, NOW, NOW.minusNanos(1), CollectionStatus.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = MissingReason.class, names = {"UNAUTHORIZED", "UNSUPPORTED"})
    void authorizationAndUnsupportedMetricsNeverBecomeConnectionFailures(MissingReason reason) {
        assertThatThrownBy(() -> new ServiceProbe(ServiceAvailability.CONNECTION_FAILED, reason, PROCESS, NOW, NOW.plus(TTL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ServiceProbe(ServiceAvailability.UNKNOWN, reason, PROCESS, NOW, NOW.plus(TTL)).availability())
                .isEqualTo(ServiceAvailability.UNKNOWN);
        assertThat(new CollectionResult(CollectionStatus.PARTIAL, NOW, NOW,
                List.of(MetricSample.missing(definition("cpu", Unit.PERCENT, PROCESS), reason, NOW)), null).serviceProbe()).isNull();
    }

    @Test
    void anExplicitUnknownServiceObservationRequiresAReason() {
        assertThatThrownBy(() -> new ServiceProbe(ServiceAvailability.UNKNOWN, null, PROCESS, NOW, NOW.plus(TTL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TargetSnapshot("redis-main", 0, List.of(), List.of(), Map.of()).serviceProbes()).isEmpty();
    }

    @Test
    void capacitySampleAndIndependentProbeKeepDifferentExpirations() {
        var capacity = MetricSample.success(definition("redis.aof.bytes", Unit.BYTES, PROCESS), 2048, NOW, Duration.ofSeconds(180));
        var probe = new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, NOW.plusSeconds(15), NOW.plusSeconds(60));
        var stored = new StoredMetric("cache", CollectionKind.CAPACITY, capacity, capacity);
        var snapshot = new TargetSnapshot("redis-main", 0, List.of(), List.of(stored), Map.of("cache", probe));

        assertThat(snapshot.metrics().get(0).lastSuccess().sampledAt()).isEqualTo(NOW);
        assertThat(snapshot.metrics().get(0).lastSuccess().validUntil()).isEqualTo(NOW.plusSeconds(180));
        assertThat(snapshot.serviceProbes().get("cache").sampledAt()).isEqualTo(NOW.plusSeconds(15));
    }

    @Test
    void latestFailurePreservesSeparateLastSuccessAndSourceBoundaries() {
        Definition definition = definition("redis.memory.bytes", Unit.BYTES, PROCESS);
        var success = MetricSample.success(definition, 1024, NOW, TTL);
        var failure = MetricSample.missing(definition, MissingReason.UNAUTHORIZED, NOW.plusSeconds(15));
        var stored = new StoredMetric("cacheRestricted", CollectionKind.ORDINARY, failure, success);

        assertThat(stored.latestAttempt().missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
        assertThat(stored.latestAttempt().lastSuccessAt()).isNull();
        assertThat(stored.lastSuccess().value()).isEqualTo(new BigDecimal("1024"));
        assertThat(stored.lastSuccess().validUntil()).isEqualTo(NOW.plusSeconds(45));
        assertThat(new StoredMetric("cacheRestricted", CollectionKind.ORDINARY, failure, null).lastSuccess()).isNull();
        assertThatThrownBy(() -> new StoredMetric("cacheRestricted", CollectionKind.ORDINARY, failure, failure))
                .isInstanceOf(IllegalArgumentException.class);
        var otherScope = MetricSample.success(definition("redis.memory.bytes", Unit.BYTES,
                new Scope(ScopeKind.PROCESS, "other-process", null)), 4096, NOW, TTL);
        assertThatThrownBy(() -> new StoredMetric("cacheRestricted", CollectionKind.ORDINARY, failure, otherScope))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainedSuccessMustMatchTheWholeDefinitionChronologyAndLatestSuccessfulAttempt() {
        Definition definition = definition("redis.memory.bytes", Unit.BYTES, PROCESS);
        var success = MetricSample.success(definition, 1024, NOW, TTL);
        var later = MetricSample.success(definition, 2048, NOW.plusSeconds(15), TTL);
        var failure = MetricSample.missing(definition, MissingReason.FAILED, NOW.plusSeconds(15));
        Definition changed = new Definition(definition.key(), definition.label(), definition.unit(), definition.scope(),
                "Redis INFO changed_source", "Different resource calculation");
        var changedFailure = MetricSample.missing(changed, MissingReason.FAILED, NOW.plusSeconds(15));

        assertThatThrownBy(() -> new StoredMetric("cache", CollectionKind.ORDINARY, success, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredMetric("cache", CollectionKind.ORDINARY, later, success))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredMetric("cache", CollectionKind.ORDINARY, changedFailure, success))
                .isInstanceOf(IllegalArgumentException.class);
        var earlierFailure = MetricSample.missing(definition, MissingReason.FAILED, NOW.minusNanos(1));
        assertThatThrownBy(() -> new StoredMetric("cache", CollectionKind.ORDINARY, earlierFailure, success))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new StoredMetric("cache", CollectionKind.ORDINARY, failure, success).lastSuccess()).isEqualTo(success);
        assertThat(new StoredMetric("cache", CollectionKind.ORDINARY, later, later).lastSuccess()).isEqualTo(later);
    }

    @Test
    void metricSeriesAreKeyedByScopeNotByLabelAndDifferentNodesCanCoexist() {
        Definition first = definition("node.memory", Unit.BYTES, new Scope(ScopeKind.NODE, "memory", "node-a"));
        Definition renamed = new Definition(first.key(), "Renamed label", first.unit(), first.scope(), first.source(), first.calculation());
        var sample = MetricSample.success(first, 10, NOW, TTL);
        var duplicate = MetricSample.success(renamed, 20, NOW, TTL);
        var otherNode = MetricSample.success(definition("node.memory", Unit.BYTES,
                new Scope(ScopeKind.NODE, "memory", "node-b")), 30, NOW, TTL);

        assertThatThrownBy(() -> new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW, List.of(sample, duplicate), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW, List.of(sample, otherNode), null).metrics())
                .hasSize(2);
    }

    @Test
    void authoritativeInventoryIsExplicitAndIndependentOfValueCollectionSuccess() {
        var definition = definition("node.memory", Unit.BYTES, new Scope(ScopeKind.NODE, "memory", "node-a"));
        var success = MetricSample.success(definition, 100, NOW, TTL);
        var missing = MetricSample.missing(definition, MissingReason.UNAUTHORIZED, NOW);

        assertThat(new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW, List.of(success), null).inventoryComplete())
                .isFalse();
        assertThat(new CollectionResult(CollectionStatus.FAILED, NOW, NOW, List.of(), null).inventoryComplete())
                .isFalse();
        var authoritative = new CollectionResult(CollectionStatus.UNAUTHORIZED, NOW, NOW, List.of(missing), null, true);
        assertThat(authoritative.inventoryComplete()).isTrue();
        assertThat(authoritative.metrics()).singleElement().satisfies(sample -> {
            assertThat(sample.definition().scope().nodeId()).isEqualTo("node-a");
            assertThat(sample.missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
        });
        assertThat(new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW, List.of(success), null, false).inventoryComplete())
                .isFalse();
    }

    @Test
    void previousConstructorsDefaultBindingToSourceAndLeaveCollectionReasonUnspecified() {
        var sample = MetricSample.success(definition("memory", Unit.BYTES, PROCESS), 1, NOW, TTL);
        StoredMetric stored = new StoredMetric("factory", CollectionKind.ORDINARY, sample, sample);
        Attempt attempt = new Attempt("factory", CollectionKind.ORDINARY, 1, NOW, NOW, CollectionStatus.FAILED);
        assertThat(stored.bindingId()).isEqualTo("factory");
        assertThat(attempt.bindingId()).isEqualTo("factory");
        assertThat(attempt.reason()).isNull();
        assertThat(new CollectionResult(CollectionStatus.FAILED, NOW, NOW, List.of(), null).reason()).isNull();
        assertThat(new CollectionResult(CollectionStatus.FAILED, NOW, NOW, List.of(), null, true).reason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(MissingReason.class)
    void partialCollectionCanRetainAnySpecificMissingReasonWithoutFabricatingMetrics(MissingReason reason) {
        CollectionResult result = new CollectionResult(CollectionStatus.PARTIAL, NOW, NOW, List.of(), null, false, reason);
        Attempt attempt = new Attempt("factory", "orders-binding", CollectionKind.ORDINARY, 1, NOW, NOW,
                CollectionStatus.PARTIAL, reason);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(attempt.reason()).isEqualTo(reason);
        assertThat(result.metrics()).isEmpty();
    }

    @Test
    void collectionLevelReasonsAgreeWithTerminalStatusWithoutConflatingTimeoutWithConnectionHealth() {
        var reasons = Map.of(CollectionStatus.FAILED, MissingReason.TIMEOUT,
                CollectionStatus.BUSY, MissingReason.BUSY, CollectionStatus.UNAUTHORIZED, MissingReason.UNAUTHORIZED,
                CollectionStatus.UNSUPPORTED, MissingReason.UNSUPPORTED, CollectionStatus.WAITING, MissingReason.WAITING_SAMPLE);
        reasons.forEach((status, reason) -> {
            assertThat(new CollectionResult(status, NOW, NOW, List.of(), null, false, reason).reason()).isEqualTo(reason);
            assertThat(new Attempt("factory", "orders-binding", CollectionKind.ORDINARY, 1, NOW, NOW, status, reason).reason())
                    .isEqualTo(reason);
        });
        assertThatThrownBy(() -> new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW, List.of(), null, false, MissingReason.TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectionResult(CollectionStatus.UNAUTHORIZED, NOW, NOW, List.of(), null, false, MissingReason.FAILED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attempt("factory", "orders-binding", CollectionKind.ORDINARY, 1, NOW, NOW,
                CollectionStatus.BUSY, MissingReason.UNSUPPORTED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotUniquenessUsesBindingRatherThanTheBorrowedBeanName() {
        var sample = MetricSample.success(definition("memory", Unit.BYTES, PROCESS), 1, NOW, TTL);
        var orders = new StoredMetric("factory", "orders-binding", CollectionKind.ORDINARY, sample, sample);
        var billing = new StoredMetric("factory", "billing-binding", CollectionKind.ORDINARY, sample, sample);
        var ordersAttempt = new Attempt("factory", "orders-binding", CollectionKind.ORDINARY, 1, NOW, NOW, CollectionStatus.SUCCESS);
        var billingAttempt = new Attempt("factory", "billing-binding", CollectionKind.ORDINARY, 1, NOW, NOW, CollectionStatus.SUCCESS);
        assertThat(new TargetSnapshot("cache", 1, List.of(ordersAttempt, billingAttempt), List.of(orders, billing), Map.of()).metrics())
                .hasSize(2);
        // A renamed source cannot make a duplicate entry in the same binding valid.
        var duplicateMetric = new StoredMetric("otherFactory", "orders-binding", CollectionKind.ORDINARY, sample, sample);
        var duplicateAttempt = new Attempt("otherFactory", "orders-binding", CollectionKind.ORDINARY, 1, NOW, NOW, CollectionStatus.SUCCESS);
        assertThatThrownBy(() -> new TargetSnapshot("cache", 1, List.of(ordersAttempt, duplicateAttempt), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSnapshot("cache", 1, List.of(), List.of(orders, duplicateMetric), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        String unsafe = "redis://private-user:private-password@host";
        assertThatThrownBy(() -> new StoredMetric("factory", unsafe, CollectionKind.ORDINARY, sample, sample))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-password");
        assertThatThrownBy(() -> new Attempt("factory", unsafe, CollectionKind.ORDINARY, 1, NOW, NOW, CollectionStatus.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-password");
    }

    @Test
    void targetSnapshotKeepsSeparateSourcesAndCollectionKindsButRejectsDuplicatesWithinOneLane() {
        var sample = MetricSample.success(definition("memory", Unit.BYTES, PROCESS), 1, NOW, TTL);
        var ordinary = new StoredMetric("cacheA", CollectionKind.ORDINARY, sample, sample);
        var capacity = new StoredMetric("cacheA", CollectionKind.CAPACITY, sample, sample);
        var otherSource = new StoredMetric("cacheB", CollectionKind.ORDINARY, sample, sample);
        var a = attempt("cacheA", CollectionKind.ORDINARY, CollectionStatus.SUCCESS);
        var b = attempt("cacheB", CollectionKind.ORDINARY, CollectionStatus.UNAUTHORIZED);
        var capacityAttempt = attempt("cacheA", CollectionKind.CAPACITY, CollectionStatus.PARTIAL);
        var available = new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, NOW, NOW.plus(TTL));
        var unknown = new ServiceProbe(ServiceAvailability.UNKNOWN, MissingReason.UNAUTHORIZED, PROCESS, NOW, NOW.plus(TTL));
        var snapshot = new TargetSnapshot("redis-main", 1, List.of(a, b, capacityAttempt),
                List.of(ordinary, capacity, otherSource), Map.of("cacheA", available, "cacheB", unknown));

        assertThat(snapshot.attempts()).hasSize(3);
        assertThat(snapshot.metrics()).hasSize(3);
        assertThat(snapshot.serviceProbes().get("cacheA").availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        assertThat(snapshot.serviceProbes().get("cacheB").availability()).isEqualTo(ServiceAvailability.UNKNOWN);
        assertThatThrownBy(() -> new TargetSnapshot("redis-main", 1, List.of(a, a), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSnapshot("redis-main", 1, List.of(), List.of(ordinary, ordinary), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicSnapshotsAndCollectionResultsDetachFromAllMutableInputContainers() {
        var sample = MetricSample.success(definition("memory", Unit.BYTES, PROCESS), 100, NOW, TTL);
        var probe = new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, NOW, NOW.plus(TTL));
        var samples = new ArrayList<>(List.of(sample));
        var result = new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW, samples, probe);
        var metrics = new ArrayList<>(List.of(new StoredMetric("cache", CollectionKind.ORDINARY, sample, sample)));
        var attempts = new ArrayList<>(List.of(attempt("cache", CollectionKind.ORDINARY, CollectionStatus.SUCCESS)));
        var probes = new HashMap<>(Map.of("cache", probe));
        var snapshot = new TargetSnapshot("redis-main", 0, attempts, metrics, probes);
        samples.clear();
        metrics.clear();
        attempts.clear();
        probes.clear();

        assertThat(result.metrics()).containsExactly(sample);
        assertThat(snapshot.metrics()).hasSize(1);
        assertThat(snapshot.attempts()).hasSize(1);
        assertThat(snapshot.serviceProbes()).containsOnlyKeys("cache");
        assertThatThrownBy(() -> result.metrics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.metrics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.attempts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.serviceProbes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidMetadataNullContainersAndNegativeIdentifiersFailWithoutEchoingInput() {
        String unsafe = "redis://private-user:private-secret@private-host";
        assertThatThrownBy(() -> definition(unsafe, Unit.COUNT, PROCESS)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-secret").hasMessageNotContaining("private-host");
        assertThatThrownBy(() -> new Scope(ScopeKind.NODE, "", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scope(ScopeKind.NODE, "node", "\nnode")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Definition("count", "label", null, PROCESS, "INFO", "instantaneous"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Definition("count", "label", Unit.COUNT, PROCESS, "", "instantaneous"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectionResult(CollectionStatus.SUCCESS, NOW, NOW, Arrays.asList((MetricSample) null), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attempt("cache", CollectionKind.ORDINARY, -1, NOW, NOW, CollectionStatus.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSnapshot("cache", -1, List.of(), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSnapshot("cache", 0, List.of(), List.of(), Map.of(unsafe,
                new ServiceProbe(ServiceAvailability.AVAILABLE, null, PROCESS, NOW, NOW.plus(TTL)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-secret");
    }

    @Test
    void jsonExposesOnlySafeMetricMetadataValuesReasonsAndIndependentSourceTimestamps() throws Exception {
        var definition = definition("redis.memory.used_bytes", Unit.BYTES, PROCESS);
        var sample = MetricSample.success(definition, 0, NOW, TTL);
        var missing = MetricSample.missing(definition("redis.cpu.percent", Unit.PERCENT, PROCESS), MissingReason.WAITING_SAMPLE, NOW);
        var snapshot = new TargetSnapshot("redis-main", 2,
                List.of(attempt("cache", CollectionKind.ORDINARY, CollectionStatus.PARTIAL)),
                List.of(new StoredMetric("cache", CollectionKind.ORDINARY, sample, sample),
                        new StoredMetric("cache", CollectionKind.ORDINARY, missing, null)),
                Map.of("cache", new ServiceProbe(ServiceAvailability.AVAILABLE, null,
                        new Scope(ScopeKind.ENDPOINT, "redis-main", null), NOW, NOW.plus(TTL))));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = mapper.writeValueAsString(snapshot);
        var tree = mapper.readTree(json);
        assertThat(tree.path("metrics").get(0).path("latestAttempt").path("value").decimalValue()).isEqualByComparingTo("0");
        assertThat(tree.path("metrics").get(1).path("latestAttempt").path("value").isNull()).isTrue();
        assertThat(json).contains("WAITING_SAMPLE", "BYTES", "PROCESS", "sampledAt", "lastSuccessAt", "validUntil")
                .doesNotContain("password", "credentials", "nativeClient", "connectionIdentity", "settings");
    }

    private static Definition definition(String key, Unit unit, Scope scope) {
        return new Definition(key, "Metric label", unit, scope, "Redis INFO", "Native instantaneous value");
    }

    private static Attempt attempt(String source, CollectionKind kind, CollectionStatus status) {
        return new Attempt(source, kind, 1, NOW, NOW, status);
    }
}
