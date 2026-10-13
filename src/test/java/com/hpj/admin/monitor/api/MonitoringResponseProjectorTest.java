package com.hpj.admin.monitor.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringConnectionSource;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ReadBinding;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ReadState;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ReadTarget;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ResolutionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.hpj.admin.common.config.monitor.MonitoringProperties;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus.*;
import static com.hpj.admin.monitor.api.MonitoringResponses.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MonitoringResponseProjectorTest {
    private static final Instant SAMPLE_TIME = Instant.parse("2026-09-14T02:00:00Z");
    private static final Instant SERVER_TIME = SAMPLE_TIME.plusSeconds(20);
    private static final long GENERATION = 7;
    private static final String SOURCE = "sharedRedis";
    private static final MonitoringTarget.Scope EMPTY_SCOPE = scope();
    private final MonitoringResponseProjector projector = new MonitoringResponseProjector();

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void metricAndServiceExpiryUseStrictOriginalBoundaries(long offsetNanos) {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        StoredMetric ordinary = success("redis-a", CollectionKind.ORDINARY, "redis.commands.rate", "process", 0, SAMPLE_TIME, 45);
        StoredMetric capacity = success("redis-a", CollectionKind.CAPACITY, "aof.bytes", "volume", 123, SAMPLE_TIME, 180);
        ReadState state = state(List.of(target), snapshot("redis-a", List.of(
                attempt("redis-a", CollectionKind.ORDINARY, CollectionStatus.SUCCESS, null),
                attempt("redis-a", CollectionKind.CAPACITY, CollectionStatus.SUCCESS, null)), List.of(ordinary, capacity),
                Map.of("redis-a", probe(ServiceAvailability.AVAILABLE, null, "endpoint"))));
        for (int ttl : List.of(45, 180)) {
            Instant now = SAMPLE_TIME.plusSeconds(ttl).plusNanos(offsetNanos);
            DetailResponse detail = projector.detail(state, now, "redis-a").orElseThrow();
            MetricObservation metric = detail.metrics().get(ttl == 45 ? 0 : 1);
            assertThat(metric.valueState()).isEqualTo(offsetNanos > 0 ? ValueState.EXPIRED : ValueState.CURRENT);
            assertThat(metric.validUntil()).isEqualTo(SAMPLE_TIME.plusSeconds(ttl));
            assertThat(metric.lastSuccess().sampledAt()).isEqualTo(SAMPLE_TIME);
            assertThat(projector.project(state, now).targets().get(0)).isEqualTo(detail.target());
            assertThat(detail.capabilities().get(ttl == 45 ? 0 : 1).valueState()).isEqualTo(metric.valueState());
            if (ttl == 45) {
                assertThat(detail.metrics().get(1).valueState()).isEqualTo(ValueState.CURRENT);
                assertThat(detail.target().serviceAvailability()).isEqualTo(offsetNanos > 0 ? ServiceAvailability.UNKNOWN : ServiceAvailability.AVAILABLE);
                assertThat(detail.target().serviceObservations().get(0).lastObservation().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
                assertThat(detail.target().serviceObservations().get(0).expired()).isEqualTo(offsetNanos > 0);
            }
        }
        assertThat(state.snapshots().get(0).metrics()).containsExactly(ordinary, capacity);
    }

    @ParameterizedTest
    @EnumSource(value = ServiceAvailability.class, names = {"AVAILABLE", "DEGRADED", "CONNECTION_FAILED"})
    void everyExpiredKnownServiceConclusionBecomesUnknownWithoutLosingTheOriginal(ServiceAvailability availability) {
        ReadTarget target = configured("redis-a", binding("redis-a"));
        ServiceProbe previous = probe(availability, availability == ServiceAvailability.CONNECTION_FAILED ? MissingReason.TIMEOUT : null, "endpoint");
        ReadState state = state(List.of(target), snapshot("redis-a", List.of(), List.of(), Map.of("redis-a", previous)));
        SnapshotResponse response = projector.project(state, SAMPLE_TIME.plusSeconds(45).plusNanos(1));
        assertThat(response.targets().get(0).serviceAvailability()).isEqualTo(ServiceAvailability.UNKNOWN);
        assertThat(response.targets().get(0).serviceObservations().get(0).lastObservation()).isSameAs(previous);
        assertThat(response.serviceSummary().get(ServiceAvailability.UNKNOWN)).isEqualTo(1);
        assertThat(response.serviceSummary().get(availability)).isZero();
    }

    @Test
    void wholeCollectionFailureMarksOmittedValuesImmediatelyWithoutChangingStoredClocks() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        StoredMetric ordinary = success("redis-a", CollectionKind.ORDINARY, "redis.memory.used.bytes", "process", 12, SAMPLE_TIME, 45);
        StoredMetric capacity = success("redis-a", CollectionKind.CAPACITY, "aof.bytes", "volume", 32, SAMPLE_TIME, 180);
        Instant failureTime = SAMPLE_TIME.plusSeconds(15);
        Attempt failed = new Attempt(SOURCE, "redis-a", CollectionKind.ORDINARY, 2, failureTime, failureTime, CollectionStatus.FAILED, MissingReason.TIMEOUT);
        ReadState state = state(List.of(target), snapshot("redis-a", List.of(failed), List.of(ordinary, capacity), Map.of()));
        DetailResponse detail = projector.detail(state, failureTime, "redis-a").orElseThrow();
        MetricObservation retained = detail.metrics().get(0);
        assertThat(retained.valueState()).isEqualTo(ValueState.LAST_SUCCESS);
        assertThat(retained.latestAttempt().missingReason()).isEqualTo(MissingReason.TIMEOUT);
        assertThat(retained.latestAttempt().sampledAt()).isEqualTo(failureTime);
        assertThat(retained.lastSuccess()).isSameAs(ordinary.lastSuccess());
        assertThat(retained.validUntil()).isEqualTo(SAMPLE_TIME.plusSeconds(45));
        assertThat(detail.metrics().get(1).valueState()).isEqualTo(ValueState.CURRENT);
        assertThat(projector.detail(state, failureTime.plusSeconds(1), "redis-a").orElseThrow().metrics()).isEqualTo(detail.metrics());
        assertThat(state.snapshots().get(0).metrics()).containsExactly(ordinary, capacity);
    }

    @Test
    void partialProbeSuccessCannotRefreshOmittedResourcesOrCapacityAndRecoveryUsesOnlyNewState() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        Instant next = SAMPLE_TIME.plusSeconds(30);
        StoredMetric probe = success("redis-a", CollectionKind.ORDINARY, "redis.probe.duration", "endpoint", 1, next, 45);
        StoredMetric memory = success("redis-a", CollectionKind.ORDINARY, "redis.memory.used.bytes", "process", 10, SAMPLE_TIME, 45);
        StoredMetric capacity = success("redis-a", CollectionKind.CAPACITY, "aof.bytes", "volume", 30, SAMPLE_TIME, 180);
        Attempt partial = new Attempt(SOURCE, "redis-a", CollectionKind.ORDINARY, 2, next, next, CollectionStatus.PARTIAL, MissingReason.UNAUTHORIZED);
        ReadState state = state(List.of(target), snapshot("redis-a", List.of(partial), List.of(probe, memory, capacity),
                Map.of("redis-a", new ServiceProbe(ServiceAvailability.AVAILABLE, null, new Scope(ScopeKind.ENDPOINT, "endpoint", null), next, next.plusSeconds(45)))));
        DetailResponse detail = projector.detail(state, next.plusSeconds(16), "redis-a").orElseThrow();
        assertThat(detail.metrics()).extracting(MetricObservation::valueState).containsExactly(ValueState.CURRENT, ValueState.EXPIRED, ValueState.CURRENT);
        assertThat(detail.metrics().get(1).latestAttempt().missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
        assertThat(detail.metrics().get(1).lastSuccess().sampledAt()).isEqualTo(SAMPLE_TIME);
        assertThat(detail.metrics().get(2).validUntil()).isEqualTo(SAMPLE_TIME.plusSeconds(180));
        assertThat(detail.target().serviceAvailability()).isEqualTo(ServiceAvailability.AVAILABLE);
        assertThat(detail.target().exceptional()).isTrue();
        StoredMetric recovered = success("redis-a", CollectionKind.ORDINARY, "redis.memory.used.bytes", "process", 11, next.plusSeconds(20), 45);
        ReadState recoveredState = state(List.of(target), snapshot("redis-a", List.of(), List.of(recovered), Map.of()));
        assertThat(projector.detail(recoveredState, next.plusSeconds(20), "redis-a").orElseThrow().metrics())
                .singleElement().satisfies(metric -> {
                    assertThat(metric.valueState()).isEqualTo(ValueState.CURRENT);
                    assertThat(metric.lastSuccess().value()).isEqualTo(BigDecimal.valueOf(11));
                });
        ReadState restarted = new ReadState(GENERATION + 1, ResolutionState.READY, List.of(target), List.of());
        assertThat(projector.detail(restarted, next, "redis-a").orElseThrow().metrics()).isEmpty();
    }

    @Test
    void noSuccessHasNoDataAndNoValidityEvenWhenThereIsARecentFailure() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        StoredMetric unavailable = new StoredMetric(SOURCE, "redis-a", CollectionKind.ORDINARY,
                MetricSample.missing(definition("cpu", "process"), MissingReason.UNSUPPORTED, SAMPLE_TIME), null);
        MetricObservation metric = projector.detail(state(List.of(target), snapshot("redis-a", List.of(), List.of(unavailable), Map.of())),
                SAMPLE_TIME.plusSeconds(1000), "redis-a").orElseThrow().metrics().get(0);
        assertThat(metric.valueState()).isEqualTo(ValueState.NO_DATA);
        assertThat(metric.validUntil()).isNull(); assertThat(metric.lastSuccess()).isNull();
        assertThat(metric.latestAttempt().missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
    }

    @Test
    void successfulLightweightAttemptKeepsAnOlderResourceDeadlineInTheBoundedOverview() {
        ReadTarget target = configured("redis-a", binding("redis-a"));
        Instant next = SAMPLE_TIME.plusSeconds(30);
        StoredMetric current = success("redis-a", CollectionKind.ORDINARY, "redis.probe.duration", "endpoint", 1, next, 45);
        StoredMetric older = success("redis-a", CollectionKind.ORDINARY, "redis.memory.used.bytes", "process", 4, SAMPLE_TIME, 45);
        Attempt attempt = new Attempt(SOURCE, "redis-a", CollectionKind.ORDINARY, 2, next, next, CollectionStatus.SUCCESS, null);
        ReadState state = state(List.of(target), snapshot("redis-a", List.of(attempt), List.of(current, older), Map.of()));
        CollectionObservation collection = projector.project(state, next).targets().get(0).collections().get(0);
        assertThat(collection.validUntil()).isEqualTo(next.plusSeconds(45));
        assertThat(collection.valuesValidUntil()).isEqualTo(SAMPLE_TIME.plusSeconds(45));
        TargetOverview expired = projector.project(state, SAMPLE_TIME.plusSeconds(46)).targets().get(0);
        assertThat(expired.collections().get(0).status()).isEqualTo(CollectionStatus.STALE);
        assertThat(expired.coreMetrics()).extracting(MetricObservation::valueState).containsExactly(ValueState.CURRENT, ValueState.EXPIRED);
    }

    @Test
    void publicRulesUseConfiguredPeriodsAndMutuallyExclusiveSummaryUsesCurrentServiceProjection() {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setRefreshInterval(Duration.ofSeconds(4)); properties.setOrdinaryInterval(Duration.ofSeconds(2));
        properties.setCapacityInterval(Duration.ofSeconds(10)); properties.setServiceTtl(Duration.ofSeconds(9));
        MonitoringResponseProjector configuredProjector = new MonitoringResponseProjector(properties);
        ReadTarget a = configured("a", binding("a")), b = configured("b", binding("b"));
        ReadTarget disabled = target("disabled", MiddlewareType.MINIO, DISABLED, ResolvedTarget.Reason.DISABLED, List.of());
        ReadState state = state(List.of(a, b, disabled), snapshot("a", List.of(), List.of(), Map.of("a", probe(ServiceAvailability.AVAILABLE, null, "endpoint"))));
        SnapshotResponse snapshot = configuredProjector.project(state, SAMPLE_TIME.plusSeconds(46));
        assertThat(snapshot.freshnessRules()).isEqualTo(new FreshnessRules(4000, 2000, 10000, 9000, 3, true));
        assertThat(snapshot.serviceSummary().get(ServiceAvailability.UNKNOWN)).isEqualTo(2);
        assertThat(snapshot.serviceSummary().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(snapshot.targets().get(2).exceptional()).isFalse();
    }

    @Test
    void overviewPreviewIsBoundedAndKeepsFailedSeriesOriginalSuccessAndScope() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        StoredMetric old = success("redis-a", CollectionKind.ORDINARY, "redis.commands.rate", "native-node", 0, SAMPLE_TIME, 45);
        StoredMetric failed = new StoredMetric(SOURCE, "redis-a", CollectionKind.ORDINARY,
                MetricSample.missing(old.latestAttempt().definition(), MissingReason.TIMEOUT, SAMPLE_TIME.plusSeconds(10)), old.lastSuccess());
        List<StoredMetric> metrics = new ArrayList<>();
        metrics.add(success("redis-a", CollectionKind.CAPACITY, "capacity.bytes", "disk", 99, SAMPLE_TIME, 180));
        metrics.add(success("redis-a", CollectionKind.ORDINARY, "redis.memory.used.bytes", "native-node", 512, SAMPLE_TIME, 45));
        metrics.add(failed);
        metrics.add(success("redis-a", CollectionKind.ORDINARY, "redis.probe.duration", "endpoint", 7, SAMPLE_TIME, 45));
        for (int i = 0; i < 50; i++) metrics.add(success("redis-a", CollectionKind.ORDINARY, "other." + i, "scope", i, SAMPLE_TIME, 45));
        ReadState state = state(List.of(target), snapshot("redis-a", List.of(), metrics, Map.of()));

        TargetOverview overview = projector.project(state, SERVER_TIME).targets().get(0);
        assertThat(overview.metricCount()).isEqualTo(54);
        assertThat(overview.coreMetrics()).extracting(metric -> metric.latestAttempt().definition().key())
                .containsExactly("redis.probe.duration", "redis.commands.rate", "redis.memory.used.bytes");
        assertThat(overview.coreMetrics().get(1).latestAttempt()).isSameAs(failed.latestAttempt());
        assertThat(overview.coreMetrics().get(1).lastSuccess().value()).isEqualTo(BigDecimal.ZERO);
        assertThat(overview.coreMetrics().get(1).lastSuccess().sampledAt()).isEqualTo(SAMPLE_TIME);
        assertThat(overview.coreMetrics().get(1).latestAttempt().definition().scope().id()).isEqualTo("native-node");
        assertThat(projector.detail(state, SERVER_TIME, "redis-a").orElseThrow().target()).isEqualTo(overview);
        assertThatThrownBy(() -> overview.coreMetrics().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(MiddlewareType.class)
    void everyTypeCanPreviewItsOriginalOrdinaryValuesWithoutCapacityOrInventedFields(MiddlewareType type) {
        ReadTarget target = target("target", type, CONFIGURED, ResolvedTarget.Reason.CONFIGURED, List.of(binding("target")));
        StoredMetric original = success("target", CollectionKind.ORDINARY, "native.value", "node-a", 0, SAMPLE_TIME, 45);
        StoredMetric capacity = success("target", CollectionKind.CAPACITY, "storage.bytes", "volume-a", 1, SAMPLE_TIME, 180);
        ReadState state = state(List.of(target), snapshot("target", List.of(), List.of(capacity, original), Map.of()));
        assertThat(projector.project(state, SERVER_TIME).targets().get(0).coreMetrics()).extracting(MetricObservation::latestAttempt).containsExactly(original.latestAttempt());
        assertThat(projector.project(state(List.of(target)), SERVER_TIME).targets().get(0).coreMetrics()).isEmpty();
    }

    @Test
    void catalogAlwaysContainsSixTypesAndKeepsConfigurationCountsSeparate() {
        ReadTarget configured = configured("redis-a", binding("redis-a", "0"));
        ReadTarget missing = target("redis-b", MiddlewareType.REDIS, CONFIGURATION_MISSING,
                ResolvedTarget.Reason.AMBIGUOUS_SOURCE, List.of());
        ReadTarget disabled = target("redis-c", MiddlewareType.REDIS, DISABLED,
                ResolvedTarget.Reason.DISABLED, List.of());
        ReadState state = state(List.of(configured, missing, disabled));

        CatalogResponse response = projector.catalog(state, SERVER_TIME);

        assertThat(response.serverTime()).isEqualTo(SERVER_TIME);
        assertThat(response.generation()).isEqualTo(GENERATION);
        assertThat(response.resolutionState()).isEqualTo(ResolutionState.READY);
        assertThat(response.types()).extracting(TypeCatalog::type).containsExactly(MiddlewareType.values());
        TypeCatalog redis = response.types().stream().filter(type -> type.type() == MiddlewareType.REDIS).findFirst().orElseThrow();
        assertThat(redis.configurationStatus()).isEqualTo(CONFIGURED);
        assertThat(redis.configuredCount()).isEqualTo(1);
        assertThat(redis.missingCount()).isEqualTo(1);
        assertThat(redis.disabledCount()).isEqualTo(1);
        assertThat(redis.resolvingCount()).isZero();
        assertThat(redis.targets()).extracting(DirectoryTarget::id).containsExactly("redis-a", "redis-b", "redis-c");
        assertThat(redis.targets().get(1).resolutionReason()).isEqualTo(ResolvedTarget.Reason.AMBIGUOUS_SOURCE);
        response.types().stream().filter(type -> type.type() != MiddlewareType.REDIS).forEach(type -> {
            assertThat(type.configurationStatus()).isEqualTo(DISABLED);
            assertThat(type.targets()).isEmpty();
        });
    }

    @Test
    void unresolvedDeclarationsRemainUnknownWithoutInventingMissingConfigurationOrSamples() {
        ReadTarget resolving = target("redis-a", MiddlewareType.REDIS, null, null, List.of());
        ReadTarget disabled = target("redis-b", MiddlewareType.REDIS, DISABLED,
                ResolvedTarget.Reason.DISABLED, List.of());
        ReadState state = new ReadState(0, ResolutionState.RESOLVING, List.of(resolving, disabled), List.of());

        TypeCatalog redis = projector.catalog(state, SERVER_TIME).types().stream()
                .filter(type -> type.type() == MiddlewareType.REDIS).findFirst().orElseThrow();
        assertThat(redis.configurationStatus()).isNull();
        assertThat(redis.resolvingCount()).isEqualTo(1);
        assertThat(redis.missingCount()).isZero();
        assertThat(redis.disabledCount()).isEqualTo(1);
        DetailResponse detail = projector.detail(state, SERVER_TIME, "redis-a").orElseThrow();
        assertThat(detail.target().schedulingState()).isEqualTo(SchedulingState.RESOLVING);
        assertThat(detail.target().target().configurationStatus()).isNull();
        assertThat(detail.target().target().resolutionReason()).isNull();
        assertThat(detail.target().collections()).isEmpty();
        assertThat(detail.target().serviceObservations()).isEmpty();
        assertThat(detail.metrics()).isEmpty();
        assertThat(detail.capabilities()).isEmpty();
        assertThat(detail.capabilityState()).isEqualTo(CapabilityState.UNKNOWN);
        assertThat(projector.detail(state, SERVER_TIME, "not-declared")).isEmpty();
    }

    @Test
    void activeTargetWithoutSamplesWaitsWithoutInventingObservationTimestamps() {
        ReadState state = state(List.of(configured("redis-a", binding("redis-a", "0"))));

        DetailResponse detail = projector.detail(state, SERVER_TIME, "redis-a").orElseThrow();

        assertThat(detail.target().schedulingState()).isEqualTo(SchedulingState.ACTIVE);
        assertThat(detail.target().collections()).extracting(CollectionObservation::kind)
                .containsExactly(CollectionKind.ORDINARY, CollectionKind.CAPACITY);
        assertThat(detail.target().collections()).allSatisfy(collection -> {
            assertThat(collection.status()).isEqualTo(CollectionStatus.WAITING);
            assertThat(collection.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(collection.lastAttempt()).isNull();
        });
        assertThat(detail.target().serviceObservations()).singleElement().satisfies(service -> {
            assertThat(service.availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(service.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(service.lastObservation()).isNull();
        });
        assertThat(detail.target().metricCount()).isZero();
        assertThat(detail.target().missingMetricCount()).isZero();
        assertThat(detail.metrics()).isEmpty();
        assertThat(detail.capabilities()).isEmpty();
        assertThat(detail.capabilityState()).isEqualTo(CapabilityState.UNKNOWN);
    }

    @Test
    void disabledAndMissingTargetsStayVisibleWithoutPretendingTheyWillBeSampled() {
        ReadTarget disabled = target("redis-disabled", MiddlewareType.REDIS, DISABLED,
                ResolvedTarget.Reason.DISABLED, List.of());
        ReadTarget missing = target("redis-missing", MiddlewareType.REDIS, CONFIGURATION_MISSING,
                ResolvedTarget.Reason.SOURCE_MISSING, List.of());

        SnapshotResponse response = projector.project(state(List.of(disabled, missing)), SERVER_TIME);

        assertThat(response.targets()).extracting(TargetOverview::schedulingState)
                .containsExactly(SchedulingState.DISABLED, SchedulingState.CONFIGURATION_MISSING);
        assertThat(response.targets()).allSatisfy(target -> {
            assertThat(target.collections()).isEmpty();
            assertThat(target.serviceObservations()).isEmpty();
            assertThat(target.metricCount()).isZero();
        });
    }

    @Test
    void sameSourceAndSequenceKeepEachBindingScopePermissionAndServiceObservation() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"), binding("redis-b", "1"));
        Attempt permitted = attempt("redis-a", CollectionKind.ORDINARY, CollectionStatus.SUCCESS, null);
        Attempt denied = attempt("redis-b", CollectionKind.ORDINARY, CollectionStatus.UNAUTHORIZED, MissingReason.UNAUTHORIZED);
        ServiceProbe available = probe(ServiceAvailability.AVAILABLE, null, "db-0");
        ServiceProbe unknown = probe(ServiceAvailability.UNKNOWN, MissingReason.UNAUTHORIZED, "db-1");
        StoredMetric count = success("redis-a", CollectionKind.ORDINARY, "connections", "db-0", 4, SAMPLE_TIME, 45);
        Definition deniedDefinition = definition("connections", "db-1");
        StoredMetric missing = new StoredMetric(SOURCE, "redis-b", CollectionKind.ORDINARY,
                MetricSample.missing(deniedDefinition, MissingReason.UNAUTHORIZED, SAMPLE_TIME), null);
        TargetSnapshot sample = snapshot("redis-a", List.of(permitted, denied), List.of(count, missing),
                Map.of("redis-a", available, "redis-b", unknown));

        DetailResponse response = projector.detail(state(List.of(target), sample), SERVER_TIME, "redis-a").orElseThrow();

        assertThat(response.target().target().memberIds()).containsExactly("redis-a", "redis-b");
        assertThat(response.target().target().bindings()).extracting(Binding::source).containsExactly(SOURCE, SOURCE);
        assertThat(response.target().target().bindings().get(0).scope().databases()).containsExactly("0");
        assertThat(response.target().target().bindings().get(1).scope().databases()).containsExactly("1");
        List<CollectionObservation> ordinary = response.target().collections().stream()
                .filter(collection -> collection.kind() == CollectionKind.ORDINARY).toList();
        assertThat(ordinary).extracting(CollectionObservation::lastAttempt).containsExactly(permitted, denied);
        assertThat(permitted.sequence()).isEqualTo(denied.sequence());
        assertThat(response.target().serviceObservations()).extracting(ServiceObservation::lastObservation)
                .containsExactly(available, unknown);
        assertThat(response.capabilities()).extracting(MetricCapability::capability)
                .containsExactly(Capability.AVAILABLE, Capability.UNAUTHORIZED);
        assertThat(response.metrics()).extracting(MetricObservation::bindingId).containsExactly("redis-a", "redis-b");
        assertThat(response.target().missingMetricCount()).isEqualTo(1);
        // Only the canonical display target is addressable, even when a merged member has observations.
        assertThat(projector.detail(state(List.of(target), sample), SERVER_TIME, "redis-b")).isEmpty();
    }

    @Test
    void ordinaryRefreshDoesNotRewriteOldCapacitySuccessOrTurnTimeoutIntoCurrentCapability() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        Instant capacityTime = SAMPLE_TIME.minusSeconds(60);
        StoredMetric oldCapacity = success("redis-a", CollectionKind.CAPACITY, "aof.bytes", "redis-process", 1234, capacityTime, 180);
        StoredMetric timedOut = new StoredMetric(SOURCE, "redis-a", CollectionKind.CAPACITY,
                MetricSample.missing(oldCapacity.latestAttempt().definition(), MissingReason.TIMEOUT, SAMPLE_TIME),
                oldCapacity.lastSuccess());
        StoredMetric ordinaryZero = success("redis-a", CollectionKind.ORDINARY, "connections", "db-0", 0, SAMPLE_TIME, 45);
        TargetSnapshot sample = snapshot("redis-a", List.of(
                attempt("redis-a", CollectionKind.ORDINARY, CollectionStatus.SUCCESS, null),
                attempt("redis-a", CollectionKind.CAPACITY, CollectionStatus.FAILED, MissingReason.TIMEOUT)),
                List.of(ordinaryZero, timedOut), Map.of("redis-a", probe(ServiceAvailability.AVAILABLE, null, "db-0")));
        ReadState state = state(List.of(target), sample);

        DetailResponse response = projector.detail(state, SERVER_TIME, "redis-a").orElseThrow();
        DetailResponse later = projector.detail(state, SERVER_TIME.plusSeconds(1000), "redis-a").orElseThrow();

        assertThat(response.metrics().get(0).latestAttempt().value()).isEqualTo(BigDecimal.ZERO);
        assertThat(response.metrics().get(0).latestAttempt().missingReason()).isNull();
        MetricObservation capacity = response.metrics().get(1);
        assertThat(capacity.latestAttempt().value()).isNull();
        assertThat(capacity.latestAttempt().missingReason()).isEqualTo(MissingReason.TIMEOUT);
        assertThat(capacity.lastSuccess().sampledAt()).isEqualTo(capacityTime);
        assertThat(capacity.lastSuccess().lastSuccessAt()).isEqualTo(capacityTime);
        assertThat(capacity.lastSuccess().validUntil()).isEqualTo(capacityTime.plusSeconds(180));
        assertThat(response.capabilities().get(1).capability()).isEqualTo(Capability.UNKNOWN);
        assertThat(response.capabilities().get(1).lastSuccessAt()).isEqualTo(capacityTime);
        assertThat(response.capabilities().get(1).sampledAt()).isEqualTo(SAMPLE_TIME);
        assertThat(response.capabilities().get(1).latestMissingReason()).isEqualTo(MissingReason.TIMEOUT);
        assertThat(later.metrics()).extracting(MetricObservation::lastSuccess)
                .containsExactlyElementsOf(response.metrics().stream().map(MetricObservation::lastSuccess).toList());
        assertThat(later.metrics()).extracting(MetricObservation::latestAttempt)
                .containsExactlyElementsOf(response.metrics().stream().map(MetricObservation::latestAttempt).toList());
        assertThat(later.metrics()).extracting(MetricObservation::valueState).containsOnly(ValueState.EXPIRED);
        assertThat(later.capabilities()).extracting(MetricCapability::valueState).containsOnly(ValueState.EXPIRED);
        assertThat(sample.metrics()).containsExactly(ordinaryZero, timedOut);
    }

    @ParameterizedTest
    @EnumSource(MissingReason.class)
    void capabilityUsesTheSpecificMetricEvidenceAndPreservesEveryMissingReason(MissingReason reason) {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        MetricSample missing = MetricSample.missing(definition("memory.bytes", "db-0"), reason, SAMPLE_TIME);
        StoredMetric stored = new StoredMetric(SOURCE, "redis-a", CollectionKind.ORDINARY, missing, null);
        TargetSnapshot sample = snapshot("redis-a", List.of(), List.of(stored),
                Map.of("redis-a", probe(ServiceAvailability.AVAILABLE, null, "db-0")));

        DetailResponse response = projector.detail(state(List.of(target), sample), SERVER_TIME, "redis-a").orElseThrow();

        Capability expected = switch (reason) {
            case UNSUPPORTED -> Capability.UNSUPPORTED;
            case UNAUTHORIZED -> Capability.UNAUTHORIZED;
            case NOT_APPLICABLE -> Capability.NOT_APPLICABLE;
            default -> Capability.UNKNOWN;
        };
        assertThat(response.capabilities()).singleElement().satisfies(capability -> {
            assertThat(capability.capability()).isEqualTo(expected);
            assertThat(capability.latestMissingReason()).isEqualTo(reason);
            assertThat(capability.lastSuccessAt()).isNull();
            assertThat(capability.definition()).isEqualTo(missing.definition());
        });
        assertThat(response.metrics()).singleElement().satisfies(metric -> assertThat(metric.latestAttempt().value()).isNull());
    }

    @Test
    void successfulServiceProbeAndCollectionDoNotInventCpuMemoryOrStorageCapabilities() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        TargetSnapshot sample = snapshot("redis-a",
                List.of(attempt("redis-a", CollectionKind.ORDINARY, CollectionStatus.SUCCESS, null)), List.of(),
                Map.of("redis-a", probe(ServiceAvailability.AVAILABLE, null, "db-0")));

        DetailResponse response = projector.detail(state(List.of(target), sample), SERVER_TIME, "redis-a").orElseThrow();

        assertThat(response.target().serviceObservations().get(0).availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        assertThat(response.target().collections().get(0).status()).isEqualTo(CollectionStatus.SUCCESS);
        assertThat(response.capabilityState()).isEqualTo(CapabilityState.UNKNOWN);
        assertThat(response.capabilities()).isEmpty();
        assertThat(response.metrics()).isEmpty();
    }

    @Test
    void tlsFailureSurvivesThePublicReadModelWithoutBecomingAnAuthorizationFailure() throws Exception {
        ReadTarget target = configured("es", binding("es", "0"));
        StoredMetric missing = new StoredMetric(SOURCE, "es", CollectionKind.ORDINARY,
                MetricSample.missing(definition("cluster.status", "cluster"), MissingReason.TLS_FAILED, SAMPLE_TIME), null);
        TargetSnapshot sample = snapshot("es",
                List.of(attempt("es", CollectionKind.ORDINARY, CollectionStatus.FAILED, MissingReason.TLS_FAILED)),
                List.of(missing), Map.of("es", probe(ServiceAvailability.UNKNOWN, MissingReason.TLS_FAILED, "cluster")));
        DetailResponse response = projector.detail(state(List.of(target), sample), SERVER_TIME, "es").orElseThrow();
        assertThat(response.target().collections().get(0).reason()).isEqualTo(MissingReason.TLS_FAILED);
        assertThat(response.capabilities().get(0).capability()).isEqualTo(Capability.UNKNOWN);
        assertThat(response.capabilities().get(0).latestMissingReason()).isEqualTo(MissingReason.TLS_FAILED);
        assertThat(new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(response))
                .contains("TLS_FAILED").doesNotContain("UNAUTHORIZED", "SSLHandshakeException", "CertificateException");
    }

    @Test
    void partialMetricsKeepOriginalScopesWithoutInventingClusterTotalsOrCoverage() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0", "1", "2"));
        StoredMetric first = success("redis-a", CollectionKind.CAPACITY, "database.bytes", "db-0", 100, SAMPLE_TIME, 180);
        StoredMetric second = success("redis-a", CollectionKind.CAPACITY, "database.bytes", "db-1", 200, SAMPLE_TIME, 180);
        TargetSnapshot sample = snapshot("redis-a",
                List.of(attempt("redis-a", CollectionKind.CAPACITY, CollectionStatus.PARTIAL, MissingReason.UNAUTHORIZED)),
                List.of(first, second), Map.of());

        DetailResponse response = projector.detail(state(List.of(target), sample), SERVER_TIME, "redis-a").orElseThrow();

        assertThat(response.metrics()).extracting(MetricObservation::latestAttempt).containsExactly(first.latestAttempt(), second.latestAttempt());
        assertThat(response.metrics()).extracting(metric -> metric.latestAttempt().definition().scope().id())
                .containsExactly("db-0", "db-1");
        assertThat(response.target().metricCount()).isEqualTo(2);
        assertThat(response.target().collections().stream().filter(collection -> collection.kind() == CollectionKind.CAPACITY))
                .singleElement().satisfies(collection -> {
                    assertThat(collection.status()).isEqualTo(CollectionStatus.PARTIAL);
                    assertThat(collection.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
                });
        assertThat(response.capabilities()).hasSize(2);
    }

    @Test
    void oldGenerationCannotBeJoinedToTheCurrentDirectory() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        TargetSnapshot old = new TargetSnapshot("redis-a", GENERATION - 1, List.of(), List.of(), Map.of());
        ReadState mixed = state(List.of(target), old);

        assertThatThrownBy(() -> projector.project(mixed, SERVER_TIME)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> projector.detail(mixed, SERVER_TIME, "redis-a")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishedReadModelsRemainImmutableAndNeverMutateTheInputSnapshot() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        StoredMetric metric = success("redis-a", CollectionKind.ORDINARY, "connections", "db-0", 3, SAMPLE_TIME, 45);
        List<StoredMetric> mutableMetrics = new ArrayList<>(List.of(metric));
        TargetSnapshot snapshot = snapshot("redis-a", List.of(), mutableMetrics, Map.of());
        List<ReadTarget> mutableTargets = new ArrayList<>(List.of(target));
        ReadState state = state(mutableTargets, snapshot);
        CatalogResponse catalog = projector.catalog(state, SERVER_TIME);
        SnapshotResponse overview = projector.project(state, SERVER_TIME);
        DetailResponse detail = projector.detail(state, SERVER_TIME, "redis-a").orElseThrow();
        mutableMetrics.clear();
        mutableTargets.clear();

        assertThat(state.targets()).containsExactly(target);
        assertThat(snapshot.metrics()).containsExactly(metric);
        assertThat(detail.metrics()).extracting(MetricObservation::latestAttempt).containsExactly(metric.latestAttempt());
        assertThat(projector.detail(state, SERVER_TIME, "redis-a")).contains(detail);
        assertThatThrownBy(() -> catalog.types().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> overview.targets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.metrics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.capabilities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.target().collections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.target().serviceObservations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.target().target().bindings().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.target().target().memberIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.target().target().bindings().get(0).scope().databases().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readServiceUsesOneMemoryReadAndOneServerTimePerResponseWithoutSchedulingAnyWork() {
        MonitoringScheduler scheduler = mock(MonitoringScheduler.class);
        Clock clock = mock(Clock.class);
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        StoredMetric metric = success("redis-a", CollectionKind.ORDINARY, "connections", "db-0", 0, SAMPLE_TIME, 45);
        ReadState state = state(List.of(target), snapshot("redis-a", List.of(), List.of(metric), Map.of()));
        when(scheduler.readState()).thenReturn(state);
        when(clock.instant()).thenReturn(SERVER_TIME, SERVER_TIME.plusSeconds(1), SERVER_TIME.plusSeconds(2));
        MonitoringReadService reads = new MonitoringReadService(scheduler, projector, clock);

        assertThat(reads.catalog().serverTime()).isEqualTo(SERVER_TIME);
        assertThat(reads.snapshots().serverTime()).isEqualTo(SERVER_TIME.plusSeconds(1));
        DetailResponse detail = reads.detail("redis-a").orElseThrow();
        assertThat(detail.serverTime()).isEqualTo(SERVER_TIME.plusSeconds(2));
        assertThat(detail.metrics()).extracting(MetricObservation::latestAttempt).containsExactly(metric.latestAttempt());
        assertThat(detail.metrics().get(0).latestAttempt().sampledAt()).isEqualTo(SAMPLE_TIME);
        assertThat(detail.metrics().get(0).latestAttempt().validUntil()).isEqualTo(SAMPLE_TIME.plusSeconds(45));
        verify(scheduler, times(3)).readState();
        verify(clock, times(3)).instant();
        verifyNoMoreInteractions(scheduler, clock);
    }

    @Test
    void writeCompleteResponseExamplesUsingTheActualProjectorAndJackson() throws Exception {
        ReadTarget target = configured("redis-a", binding("redis-a", "0"));
        Definition connectionDefinition = new Definition("connections", "Connected clients", Unit.COUNT,
                new Scope(ScopeKind.PROCESS, "redis-process", null), "INFO clients", "Native connected_clients");
        MetricSample connectionSample = MetricSample.success(connectionDefinition, 0, SAMPLE_TIME, Duration.ofSeconds(45));
        StoredMetric connections = new StoredMetric(SOURCE, "redis-a", CollectionKind.ORDINARY, connectionSample, connectionSample);
        Definition capacityDefinition = new Definition("aof.bytes", "AOF bytes", Unit.BYTES,
                new Scope(ScopeKind.PROCESS, "redis-process", null), "INFO persistence", "Native aof_current_size");
        MetricSample capacitySample = MetricSample.success(capacityDefinition, 4096, SAMPLE_TIME.minusSeconds(60), Duration.ofSeconds(180));
        StoredMetric priorCapacity = new StoredMetric(SOURCE, "redis-a", CollectionKind.CAPACITY, capacitySample, capacitySample);
        StoredMetric capacity = new StoredMetric(SOURCE, "redis-a", CollectionKind.CAPACITY,
                MetricSample.missing(priorCapacity.latestAttempt().definition(), MissingReason.TIMEOUT, SAMPLE_TIME),
                priorCapacity.lastSuccess());
        ReadState state = state(List.of(target), snapshot("redis-a", List.of(
                attempt("redis-a", CollectionKind.ORDINARY, CollectionStatus.SUCCESS, null),
                attempt("redis-a", CollectionKind.CAPACITY, CollectionStatus.FAILED, MissingReason.TIMEOUT)),
                List.of(connections, capacity), Map.of("redis-a", probe(ServiceAvailability.AVAILABLE, null, "db-0"))));
        Map<String, Object> examples = new LinkedHashMap<>();
        examples.put("catalog", projector.catalog(state, SERVER_TIME));
        examples.put("snapshots", projector.project(state, SERVER_TIME));
        examples.put("detail", projector.detail(state, SERVER_TIME, "redis-a").orElseThrow());
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String document = json.writerWithDefaultPrettyPrinter().writeValueAsString(examples);

        var tree = json.readTree(document);
        assertThat(tree.path("catalog").path("types").size()).isEqualTo(6);
        assertThat(tree.path("snapshots").path("targets").get(0).path("missingMetricCount").asInt()).isEqualTo(1);
        assertThat(tree.path("detail").path("metrics").get(0).path("latestAttempt").path("value").asInt()).isZero();
        assertThat(tree.path("detail").path("metrics").get(1).path("lastSuccess").path("sampledAt").asText())
                .isEqualTo(SAMPLE_TIME.minusSeconds(60).toString());
        assertThat(document).doesNotContain("\"connectionSettings\"", "\"password\"", "\"stackTrace\"", "\"client\"");
        Path output = Path.of("target", "monitor-api-examples.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, document + System.lineSeparator());
    }

    private static ReadState state(List<ReadTarget> targets, TargetSnapshot... snapshots) {
        return new ReadState(GENERATION, ResolutionState.READY, targets, List.of(snapshots));
    }

    private static ReadTarget configured(String id, ReadBinding... bindings) {
        return target(id, MiddlewareType.REDIS, CONFIGURED, ResolvedTarget.Reason.CONFIGURED, List.of(bindings));
    }

    private static ReadTarget target(String id, MiddlewareType type, MonitoringTarget.ConfigurationStatus status,
                                     ResolvedTarget.Reason reason, List<ReadBinding> bindings) {
        List<String> databases = bindings.stream().flatMap(binding -> binding.scope().databases().stream()).distinct().toList();
        MonitoringTarget display = new MonitoringTarget(id, type, id + " display", status,
                bindings.isEmpty() ? List.of() : List.of(SOURCE), bindings.isEmpty() ? EMPTY_SCOPE : scope(databases.toArray(String[]::new)));
        List<String> memberIds = bindings.isEmpty() ? List.of(id) : bindings.stream().map(ReadBinding::bindingId).toList();
        return new ReadTarget(display, reason, memberIds, bindings);
    }

    private static ReadBinding binding(String id, String... databases) {
        return new ReadBinding(id, new MonitoringConnectionSource(MiddlewareType.REDIS, SOURCE), scope(databases));
    }

    private static MonitoringTarget.Scope scope(String... databases) {
        return new MonitoringTarget.Scope(List.of(databases), List.of(), List.of(), List.of(), List.of());
    }

    private static Definition definition(String key, String scopeId) {
        return new Definition(key, key, key.endsWith(".bytes") ? Unit.BYTES : Unit.COUNT,
                new Scope(key.equals("aof.bytes") ? ScopeKind.PROCESS : ScopeKind.DATABASE, scopeId, null),
                "Native read-only response", "Original source value; no cross-scope aggregation");
    }

    private static StoredMetric success(String bindingId, CollectionKind kind, String key, String scopeId,
                                        long value, Instant at, int ttlSeconds) {
        MetricSample metric = MetricSample.success(definition(key, scopeId), value, at, Duration.ofSeconds(ttlSeconds));
        return new StoredMetric(SOURCE, bindingId, kind, metric, metric);
    }

    private static Attempt attempt(String bindingId, CollectionKind kind, CollectionStatus status, MissingReason reason) {
        return new Attempt(SOURCE, bindingId, kind, 1, SAMPLE_TIME.minusSeconds(1), SAMPLE_TIME, status, reason);
    }

    private static ServiceProbe probe(ServiceAvailability availability, MissingReason reason, String scopeId) {
        return new ServiceProbe(availability, reason, new Scope(ScopeKind.DATABASE, scopeId, null),
                SAMPLE_TIME, SAMPLE_TIME.plusSeconds(45));
    }

    private static TargetSnapshot snapshot(String id, List<Attempt> attempts, List<StoredMetric> metrics,
                                           Map<String, ServiceProbe> probes) {
        return new TargetSnapshot(id, GENERATION, attempts, metrics, probes);
    }
}
