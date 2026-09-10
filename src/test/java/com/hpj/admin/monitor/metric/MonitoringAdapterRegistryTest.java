package com.hpj.admin.monitor.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringAdapterRegistryTest {
    private static final Instant SCHEDULED = Instant.parse("2026-09-10T12:00:00Z");
    private static final MonitoringTarget.Scope SCOPE = new MonitoringTarget.Scope(
            List.of("authorized_database"), List.of("authorized_topic"), List.of("authorized_group"),
            List.of("authorized_bucket"), List.of("authorized_index"));
    private static final Object CLIENT = new Object() {
        @Override public String toString() { throw new AssertionError("Client must not be inspected or printed"); }
    };

    @Test
    void allSixTypesAreDiscoveredWithoutCollectingOrRecreatingAdapters() {
        List<MonitoringAdapter> adapters = new ArrayList<>();
        for (MiddlewareType type : MiddlewareType.values()) adapters.add(new ProbeAdapter(type));
        MonitoringAdapterRegistry registry = new MonitoringAdapterRegistry(adapters);

        assertThat(registry.registeredTypes()).containsExactlyInAnyOrder(MiddlewareType.values());
        for (MonitoringAdapter adapter : adapters) {
            ProbeAdapter probe = (ProbeAdapter) adapter;
            assertThat(registry.find(probe.type).orElseThrow()).isSameAs(adapter);
            assertThat(probe.typeCalls).hasValue(1);
            assertThat(probe.collectCalls).hasValue(0);
        }
        adapters.clear();
        assertThat(registry.registeredTypes()).hasSize(6);
        assertThat(registry.find(MiddlewareType.MYSQL)).isPresent();
        assertThatThrownBy(() -> registry.registeredTypes().remove(MiddlewareType.MYSQL))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unregisteredTypesRemainMissingAndEmptyRegistryIsValid() {
        MonitoringAdapterRegistry registry = new MonitoringAdapterRegistry(List.of(new ProbeAdapter(MiddlewareType.MYSQL)));
        assertThat(registry.find(MiddlewareType.REDIS)).isEmpty();
        assertThat(registry.registeredTypes()).containsExactly(MiddlewareType.MYSQL);
        MonitoringAdapterRegistry empty = new MonitoringAdapterRegistry(List.of());
        assertThat(empty.registeredTypes()).isEmpty();
        for (MiddlewareType type : MiddlewareType.values()) assertThat(empty.find(type)).isEmpty();
    }

    @Test
    void duplicateTypesFailWithoutPrintingEitherAdapterOrInvokingCollect() {
        ProbeAdapter first = new ProbeAdapter(MiddlewareType.REDIS);
        ProbeAdapter duplicate = new ProbeAdapter(MiddlewareType.REDIS);
        assertThatThrownBy(() -> new MonitoringAdapterRegistry(List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate monitoring adapter type: REDIS");
        assertThat(first.collectCalls).hasValue(0);
        assertThat(duplicate.collectCalls).hasValue(0);
    }

    @Test
    void invalidAdapterDeclarationsFailAsConfigurationErrors() {
        assertThatThrownBy(() -> new MonitoringAdapterRegistry(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringAdapterRegistry(Arrays.asList(new ProbeAdapter(MiddlewareType.MYSQL), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MonitoringAdapterRegistry(List.of(new ProbeAdapter(null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Monitoring adapter type is required");
    }

    @ParameterizedTest
    @EnumSource(MetricContract.CollectionKind.class)
    void requestPreservesTheGivenSourceScopeAndBorrowedClientForEitherCollectionKind(MetricContract.CollectionKind kind) {
        CollectionRequest request = new CollectionRequest("primary", "dataSource", kind, 2, 9,
                SCHEDULED, SCHEDULED.plusSeconds(5), SCOPE, CLIENT, Map.of());
        assertThat(request.targetId()).isEqualTo("primary");
        assertThat(request.source()).isEqualTo("dataSource");
        assertThat(request.kind()).isEqualTo(kind);
        assertThat(request.generation()).isEqualTo(2);
        assertThat(request.sequence()).isEqualTo(9);
        assertThat(request.scheduledAt()).isEqualTo(SCHEDULED);
        assertThat(request.deadline()).isEqualTo(SCHEDULED.plusSeconds(5));
        assertThat(request.client() == CLIENT).isTrue();
        assertThat(request.scope()).isSameAs(SCOPE);
        assertThat(request.scope().databases()).containsExactly("authorized_database");
        assertThat(request.scope().topics()).containsExactly("authorized_topic");
        assertThat(request.scope().consumerGroups()).containsExactly("authorized_group");
        assertThat(request.scope().buckets()).containsExactly("authorized_bucket");
        assertThat(request.scope().indices()).containsExactly("authorized_index");
    }

    @Test
    void requestCopiesItsSettingsContainerPreservingNullOptionsAndOpaqueAuthenticationReferences() {
        Object credentialReference = new Object();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("password", "private-password");
        settings.put("unsetOption", null);
        settings.put("credential", credentialReference);
        CollectionRequest request = request("primary", "dataSource", 0, 0, SCHEDULED,
                SCHEDULED.plusSeconds(5), SCOPE, CLIENT, settings);

        settings.put("password", "changed-password");
        settings.remove("unsetOption");
        assertThat(request.settings()).containsEntry("password", "private-password").containsEntry("unsetOption", null);
        assertThat(request.settings().get("credential")).isSameAs(credentialReference);
        assertThatThrownBy(() -> request.settings().put("added", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(settings).containsEntry("password", "changed-password");
        assertThat(settings.containsKey("unsetOption")).isFalse();
    }

    @Test
    void separateSourceRequestsNeverCombineTheirAuthorizedScopes() {
        MonitoringTarget.Scope empty = new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of());
        CollectionRequest scoped = request("primary", "firstSource", 0, 1, SCHEDULED,
                SCHEDULED.plusSeconds(5), SCOPE, CLIENT, Map.of());
        CollectionRequest unscoped = request("primary", "secondSource", 0, 1, SCHEDULED,
                SCHEDULED.plusSeconds(5), empty, CLIENT, Map.of());
        assertThat(unscoped.scope()).isSameAs(empty);
        assertThat(unscoped.scope().databases()).isEmpty();
        assertThat(unscoped.scope().topics()).isEmpty();
        assertThat(unscoped.scope().consumerGroups()).isEmpty();
        assertThat(unscoped.scope().buckets()).isEmpty();
        assertThat(unscoped.scope().indices()).isEmpty();
        assertThat(scoped.scope()).isSameAs(SCOPE);
    }

    @Test
    void oneNanosecondBudgetAndNonnegativeCounterBoundariesAreAccepted() {
        CollectionRequest zero = request("primary", "dataSource", 0, 0, SCHEDULED,
                SCHEDULED.plusNanos(1), SCOPE, CLIENT, Map.of());
        CollectionRequest maximum = request("primary", "dataSource", Long.MAX_VALUE, Long.MAX_VALUE,
                SCHEDULED, Instant.MAX, SCOPE, CLIENT, Map.of());
        assertThat(zero.deadline()).isEqualTo(SCHEDULED.plusNanos(1));
        assertThat(maximum.generation()).isEqualTo(Long.MAX_VALUE);
        assertThat(maximum.sequence()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void invalidRequestsFailWithoutEchoingSensitiveInput(String reason, Supplier<CollectionRequest> constructor) {
        assertThatThrownBy(constructor::get).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid monitoring collection request:")
                .hasMessageNotContaining("private-user").hasMessageNotContaining("private-password");
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                invalid("unsafe target ID", () -> request("redis://private-user:private-password@host", "source", 0, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, Map.of())),
                invalid("missing target ID", () -> request(null, "source", 0, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, Map.of())),
                invalid("unsafe source", () -> request("primary", "redis://private-user:private-password@host", 0, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, Map.of())),
                invalid("blank source", () -> request("primary", " ", 0, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, Map.of())),
                invalid("negative generation", () -> request("primary", "source", -1, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, Map.of())),
                invalid("negative sequence", () -> request("primary", "source", 0, -1,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, Map.of())),
                invalid("missing scheduled timestamp", () -> request("primary", "source", 0, 0,
                        null, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, Map.of())),
                invalid("missing deadline", () -> request("primary", "source", 0, 0,
                        SCHEDULED, null, SCOPE, CLIENT, Map.of())),
                invalid("deadline equals schedule", () -> request("primary", "source", 0, 0,
                        SCHEDULED, SCHEDULED, SCOPE, CLIENT, Map.of())),
                invalid("deadline before schedule", () -> request("primary", "source", 0, 0,
                        SCHEDULED, SCHEDULED.minusNanos(1), SCOPE, CLIENT, Map.of())),
                invalid("missing scope", () -> request("primary", "source", 0, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), null, CLIENT, Map.of())),
                invalid("missing client", () -> request("primary", "source", 0, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, null, Map.of())),
                invalid("missing settings", () -> request("primary", "source", 0, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, null)),
                invalid("missing collection kind", () -> new CollectionRequest("primary", "source", null, 0, 0,
                        SCHEDULED, SCHEDULED.plusSeconds(1), SCOPE, CLIENT, Map.of()))
        );
    }

    @Test
    void stringAndJacksonRepresentationsCannotExposeClientSettingsOrScope() throws Exception {
        CollectionRequest request = request("primary", "dataSource", 4, 7, SCHEDULED,
                SCHEDULED.plusSeconds(5), SCOPE, CLIENT,
                Map.of("endpoint", "redis://private-user:private-password@host", "password", "private-password"));
        assertThat(request.toString()).contains("primary", "dataSource", "sequence=7")
                .doesNotContain("private-user", "private-password", "authorized_database", SCHEDULED.toString());
        ObjectMapper mapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        assertThat(mapper.writeValueAsString(request)).isEqualTo("{}");
        assertThat(mapper.writeValueAsString(new Envelope("ready", request))).isEqualTo("{\"status\":\"ready\"}");
    }

    private static CollectionRequest request(String id, String source, long generation, long sequence,
                                             Instant scheduledAt, Instant deadline, MonitoringTarget.Scope scope,
                                             Object client, Map<String, Object> settings) {
        return new CollectionRequest(id, source, MetricContract.CollectionKind.ORDINARY, generation, sequence,
                scheduledAt, deadline, scope, client, settings);
    }

    private static Arguments invalid(String reason, Supplier<CollectionRequest> constructor) {
        return Arguments.of(reason, constructor);
    }

    private record Envelope(String status, CollectionRequest request) {}

    private static final class ProbeAdapter implements MonitoringAdapter {
        private final MiddlewareType type;
        private final AtomicInteger typeCalls = new AtomicInteger();
        private final AtomicInteger collectCalls = new AtomicInteger();

        private ProbeAdapter(MiddlewareType type) { this.type = type; }

        @Override public MiddlewareType type() { typeCalls.incrementAndGet(); return type; }

        @Override public MetricContract.CollectionResult collect(CollectionRequest request) {
            collectCalls.incrementAndGet();
            throw new AssertionError("Registry must not collect metrics");
        }

        @Override public String toString() { throw new AssertionError("Registry must not print adapter beans"); }
    }
}
