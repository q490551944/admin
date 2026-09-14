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
        assertThat(response.metrics()).extracting(StoredMetric::bindingId).containsExactly("redis-a", "redis-b");
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
        StoredMetric capacity = response.metrics().get(1);
        assertThat(capacity.latestAttempt().value()).isNull();
        assertThat(capacity.latestAttempt().missingReason()).isEqualTo(MissingReason.TIMEOUT);
        assertThat(capacity.lastSuccess().sampledAt()).isEqualTo(capacityTime);
        assertThat(capacity.lastSuccess().lastSuccessAt()).isEqualTo(capacityTime);
        assertThat(capacity.lastSuccess().validUntil()).isEqualTo(capacityTime.plusSeconds(180));
        assertThat(response.capabilities().get(1).capability()).isEqualTo(Capability.UNKNOWN);
        assertThat(response.capabilities().get(1).lastSuccessAt()).isEqualTo(capacityTime);
        assertThat(response.capabilities().get(1).sampledAt()).isEqualTo(SAMPLE_TIME);
        assertThat(response.capabilities().get(1).latestMissingReason()).isEqualTo(MissingReason.TIMEOUT);
        assertThat(later.metrics()).isEqualTo(response.metrics());
        assertThat(later.capabilities()).isEqualTo(response.capabilities());
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
    void partialMetricsKeepOriginalScopesWithoutInventingClusterTotalsOrCoverage() {
        ReadTarget target = configured("redis-a", binding("redis-a", "0", "1", "2"));
        StoredMetric first = success("redis-a", CollectionKind.CAPACITY, "database.bytes", "db-0", 100, SAMPLE_TIME, 180);
        StoredMetric second = success("redis-a", CollectionKind.CAPACITY, "database.bytes", "db-1", 200, SAMPLE_TIME, 180);
        TargetSnapshot sample = snapshot("redis-a",
                List.of(attempt("redis-a", CollectionKind.CAPACITY, CollectionStatus.PARTIAL, MissingReason.UNAUTHORIZED)),
                List.of(first, second), Map.of());

        DetailResponse response = projector.detail(state(List.of(target), sample), SERVER_TIME, "redis-a").orElseThrow();

        assertThat(response.metrics()).containsExactly(first, second);
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
        assertThat(detail.metrics()).containsExactly(metric);
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
        assertThat(detail.metrics()).containsExactly(metric);
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
