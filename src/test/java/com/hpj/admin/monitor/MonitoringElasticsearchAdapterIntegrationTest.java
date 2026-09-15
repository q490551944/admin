package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.connection.StandardConnectionInspector;
import com.hpj.admin.monitor.elasticsearch.ElasticsearchMonitoringAdapter;
import com.hpj.admin.monitor.elasticsearch.ElasticsearchMonitoringConnections;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.net.ssl.SSLException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringSnapshotStore.Acceptance.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/**
 * Owned Elasticsearch 8.10.4 with real HTTPS, authentication, authorization and index generations.
 * Single-node primary/total values are compared with native data; allocated replicas and shard moves
 * are covered by contract tests, not claimed as deployment scenarios exercised by this fixture.
 */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "elasticsearch")
class MonitoringElasticsearchAdapterIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String PREFIX = "elasticsearch.";

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void trustedHttpsPreservesNativeYellowHealthIndexScopeBothPeriodsAndIndexGeneration() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.startElasticsearchWithTls();
             RestClient observer = environment.elasticsearchClient();
             RestClient business = environment.elasticsearchClient();
             Collector collector = new Collector()) {
            String index = environment.resourceName();
            String yellow = index + "_yellow";
            String excluded = index + "_excluded";
            createIndex(observer, index, 0, null);
            environment.prepareData();
            createIndex(observer, yellow, 1, null);
            createIndex(observer, excluded, 0, null);
            awaitHealth(observer, "yellow");
            ResolvedTarget target = resolve(business, List.of(index));
            var originalNodes = List.copyOf(business.getNodes());
            BusinessState before = state(observer, index);

            CollectionResult first = collector.collect(target, CollectionKind.ORDINARY);
            Observation firstRead = collector.last();
            assertNativeOrdinary(first, firstRead, index);
            assertThat(metric(first, "cluster.health").value()).isEqualTo("yellow");
            assertThat(indexMetric(first, "query.rate", index).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(indexMetric(first, "indexing.rate", index).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(firstRead.requestedIndices).containsExactly(List.of(index));
            assertThat(first.metrics()).noneSatisfy(sample -> assertThat(sample.definition().scope().id()).isIn(yellow, excluded));
            assertThat(first.metrics()).noneSatisfy(sample -> assertThat(sample.definition().key()).contains("store."));

            CollectionResult capacity = collector.collect(target, CollectionKind.CAPACITY);
            assertNativeCapacity(capacity, collector.last(), index);
            assertThat(state(observer, index)).isEqualTo(before);
            assertThat(collector.snapshots.snapshot("es-target").orElseThrow().metrics().size()).isGreaterThan(first.metrics().size());

            // Only the test deliberately writes data; collection boundaries below are independently read-only.
            for (int sequence = 0; sequence < 2; sequence++) {
                request(observer, "PUT", "/" + index + "/_doc/work-" + sequence + "?refresh=true", Map.of("payload", "owned-work-" + sequence));
            }
            for (int query = 0; query < 3; query++) request(business, "GET", "/" + index + "/_search?request_cache=false", null);
            BusinessState afterWorkload = state(observer, index);
            CollectionResult second = collector.collect(target, CollectionKind.ORDINARY);
            Observation secondRead = collector.last();
            assertNativeOrdinary(second, secondRead, index);
            assertRate(first, second, firstRead, secondRead, index, "query", "total", "search", "query_total");
            assertRate(first, second, firstRead, secondRead, index, "indexing", "primaries", "indexing", "index_total");
            assertThat(indexNumber(second, "docs.count", index)).isEqualByComparingTo("3");
            assertThat(indexNumber(second, "indexing.total", index).subtract(indexNumber(first, "indexing.total", index)))
                    .isGreaterThanOrEqualTo(BigDecimal.valueOf(2));
            CollectionResult secondCapacity = collector.collect(target, CollectionKind.CAPACITY);
            assertNativeCapacity(secondCapacity, collector.last(), index);
            assertThat(state(observer, index)).isEqualTo(afterWorkload);
            assertThat(business.getNodes()).isEqualTo(originalNodes);
            assertThat(status(business, "GET", "/")).isEqualTo(200);
            environment.verifyData();

            String previousUuid = secondRead.index(index).path("uuid").asText();
            assertThat(previousUuid).isNotBlank();
            request(observer, "DELETE", "/" + index, null);
            createIndex(observer, index, 0, null);
            environment.prepareData();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(request(observer, "GET", "/_cluster/health/" + index, null).path("status").asText()).isEqualTo("green"));
            BusinessState rebuiltBefore = state(observer, index);
            CollectionResult rebuilt = collector.collect(target, CollectionKind.ORDINARY);
            assertThat(collector.last().index(index).path("uuid").asText()).isNotEqualTo(previousUuid).isNotBlank();
            assertThat(indexMetric(rebuilt, "query.rate", index).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(indexMetric(rebuilt, "indexing.rate", index).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(state(observer, index)).isEqualTo(rebuiltBefore);
            assertThat(rebuilt.metrics()).filteredOn(sample -> sample.definition().scope().kind() == ScopeKind.INDEX)
                    .allSatisfy(sample -> assertThat(sample.definition().scope().id()).isEqualTo(index));
            assertThat(collector.connections.closed).isEqualTo(5);
            assertSafe(first, environment);
            assertSafe(second, environment);
            assertSafe(capacity, environment);
            assertSafe(rebuilt, environment);
        }
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void nativeWrongTrustAndWrongPasswordRemainDistinctAndIndexAclDoesNotEraseClusterHealth() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.startElasticsearchWithTls();
             RestClient observer = environment.elasticsearchClient()) {
            String index = environment.resourceName();
            createIndex(observer, index, 0, null);
            environment.prepareData();
            awaitHealth(observer, "green");
            BusinessState before = state(observer, index);
            try (RestClient wrongTrust = environment.elasticsearchClient(environment.username(), environment.password(), false);
                 RestClient wrongPassword = environment.elasticsearchClient(environment.username(), "owned-invalid-password", true)) {
                assertThat(nativeTlsFailure(wrongTrust)).isTrue();
                assertThat(status(wrongPassword, "GET", "/_cluster/health")).isEqualTo(401);
                try (Collector tlsCollector = new Collector(); Collector authCollector = new Collector()) {
                    CollectionResult tls = tlsCollector.collect(resolve(wrongTrust, List.of(index)), CollectionKind.ORDINARY);
                    CollectionResult unauthorized = authCollector.collect(resolve(wrongPassword, List.of(index)), CollectionKind.ORDINARY);
                    assertThat(tls.reason()).isEqualTo(MissingReason.TLS_FAILED);
                    assertThat(tls.serviceProbe().reason()).isEqualTo(MissingReason.TLS_FAILED);
                    assertThat(tls.metrics()).anySatisfy(sample -> assertThat(sample.missingReason()).isEqualTo(MissingReason.TLS_FAILED));
                    assertThat(unauthorized.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
                    assertThat(unauthorized.serviceProbe().availability()).isNotEqualTo(ServiceAvailability.CONNECTION_FAILED);
                    assertSafe(tls, environment);
                    assertSafe(unauthorized, environment);
                }
            }

            String role = "mon_role_" + UUID.randomUUID().toString().replace("-", "");
            String username = "mon_reader_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            request(observer, "PUT", "/_security/role/" + role, Map.of("cluster", List.of("monitor"), "indices", List.of()));
            request(observer, "PUT", "/_security/user/" + username, Map.of("password", password, "roles", List.of(role)));
            try (RestClient restricted = environment.elasticsearchClient(username, password, true);
                 Collector collector = new Collector()) {
                assertThat(status(restricted, "GET", "/_cluster/health")).isEqualTo(200);
                assertThat(status(restricted, "GET", "/" + index + "/_stats/docs")).isEqualTo(403);
                CollectionResult partial = collector.collect(resolve(restricted, List.of(index)), CollectionKind.ORDINARY);
                assertThat(partial.status()).isEqualTo(CollectionStatus.PARTIAL);
                assertThat(partial.serviceProbe().availability()).isIn(ServiceAvailability.AVAILABLE, ServiceAvailability.DEGRADED);
                assertThat(metric(partial, "cluster.health").value()).isEqualTo("green");
                assertThat(indexMetric(partial, "docs.count", index).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
                assertThat(indexMetric(partial, "query.rate", index).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
                assertThat(indexMetric(partial, "indexing.rate", index).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
                assertThat(partial.metrics()).filteredOn(sample -> sample.definition().key().contains("thread_pool."))
                        .isNotEmpty().allSatisfy(sample -> assertThat(sample.value()).isNotNull());
                assertThat(state(observer, index)).isEqualTo(before);
                assertSafe(partial, environment);
                assertThat(JSON.writeValueAsString(partial)).doesNotContain(username, password);
            } finally {
                request(observer, "DELETE", "/_security/user/" + username, null);
                request(observer, "DELETE", "/_security/role/" + role, null);
            }
        }
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void realUnassignedPrimaryProducesRedWithoutCollectionChangingAllocationOrIndexIdentity() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.startElasticsearchWithTls();
             RestClient observer = environment.elasticsearchClient();
             RestClient business = environment.elasticsearchClient();
             Collector collector = new Collector()) {
            String index = environment.resourceName();
            String allocation = "absent-owned-node-" + UUID.randomUUID().toString().replace("-", "");
            createIndex(observer, index, 0, allocation);
            awaitHealth(observer, "red");
            JsonNode metadata = request(observer, "GET", "/" + index + "/_settings?flat_settings=true", null);
            JsonNode mapping = request(observer, "GET", "/" + index + "/_mapping", null);
            CollectionResult red = collector.collect(resolve(business, List.of(index)), CollectionKind.ORDINARY);
            assertThat(metric(red, "cluster.health").value()).isEqualTo("red");
            assertThat(number(red, "cluster.nodes")).isEqualByComparingTo("1");
            assertThat(number(red, "cluster.unassigned_shards")).isPositive();
            assertThat(red.serviceProbe().availability()).isIn(ServiceAvailability.AVAILABLE, ServiceAvailability.DEGRADED);
            assertThat(collector.last().health.path("status").asText()).isEqualTo("red");
            assertThat(request(observer, "GET", "/" + index + "/_settings?flat_settings=true", null)).isEqualTo(metadata);
            assertThat(request(observer, "GET", "/" + index + "/_mapping", null)).isEqualTo(mapping);
            assertThat(status(business, "GET", "/")).isEqualTo(200);
            assertSafe(red, environment);
            assertThat(JSON.writeValueAsString(red)).doesNotContain(allocation);
        }
    }

    private static void createIndex(RestClient client, String index, int replicas, String allocation) throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", replicas);
        if (allocation != null) settings.put("index.routing.allocation.include._name", allocation);
        request(client, "PUT", "/" + index + "?wait_for_active_shards=0", Map.of("settings", settings));
    }

    private static void awaitHealth(RestClient client, String health) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(request(client, "GET", "/_cluster/health", null).path("status").asText()).isEqualTo(health));
    }

    private static JsonNode request(RestClient client, String method, String path, Object body) throws Exception {
        int query = path.indexOf('?');
        Request request = new Request(method, query < 0 ? path : path.substring(0, query));
        if (query >= 0) {
            for (String parameter : path.substring(query + 1).split("&")) {
                String[] entry = parameter.split("=", 2);
                request.addParameter(entry[0], entry.length == 2 ? entry[1] : "");
            }
        }
        if (body != null) request.setJsonEntity(JSON.writeValueAsString(body));
        var response = client.performRequest(request);
        try (var input = response.getEntity().getContent()) { return JSON.readTree(input); }
    }

    private static int status(RestClient client, String method, String path) throws Exception {
        try {
            var response = client.performRequest(new Request(method, path));
            if (response.getEntity() != null) try (var input = response.getEntity().getContent()) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
            return response.getStatusLine().getStatusCode();
        } catch (ResponseException failure) { return failure.getResponse().getStatusLine().getStatusCode(); }
    }

    private static boolean nativeTlsFailure(RestClient client) {
        try { client.performRequest(new Request("GET", "/")); return false; }
        catch (Exception failure) {
            if (failure instanceof ResponseException) return false;
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
                if (cause instanceof SSLException) return true;
            }
            return false;
        }
    }

    private static BusinessState state(RestClient client, String index) throws Exception {
        return new BusinessState(request(client, "GET", "/" + index + "/_settings?flat_settings=true", null),
                request(client, "GET", "/" + index + "/_mapping", null),
                request(client, "GET", "/" + index + "/_doc/fixture", null),
                request(client, "GET", "/" + index + "/_count", null).path("count").longValue());
    }

    private static ResolvedTarget resolve(RestClient source, List<String> indices) {
        var declaration = new MonitoringProperties.Target();
        declaration.setId("es-target");
        declaration.setType(MiddlewareType.ELASTICSEARCH);
        declaration.setEnabled(true);
        declaration.setConnectionSource("businessElasticsearch");
        declaration.getScope().setIndices(indices);
        var properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(List.of(declaration));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("businessElasticsearch", source);
        var resolved = new MonitoringConnectionResolver(properties, beans, List.of(new StandardConnectionInspector())).resolve();
        assertThat(resolved).hasSize(1);
        var target = resolved.get(0);
        assertThat(target.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
        assertThat(target.bindings().get(0).scope().indices()).containsExactlyElementsOf(indices);
        assertThat(target.bindings().get(0).connection().client()).isSameAs(source);
        assertThat(target.bindings().get(0).connection().settings().get("restClient")).isSameAs(source);
        return target;
    }

    private static void assertNativeOrdinary(CollectionResult result, Observation observation, String index) {
        assertThat(result.serviceProbe().availability()).isIn(ServiceAvailability.AVAILABLE, ServiceAvailability.DEGRADED);
        assertThat(metric(result, "cluster.health").value()).isEqualTo(observation.health.path("status").asText());
        assertThat(number(result, "cluster.nodes")).isEqualByComparingTo(observation.health.path("number_of_nodes").decimalValue());
        assertThat(number(result, "cluster.unassigned_shards")).isEqualByComparingTo(observation.health.path("unassigned_shards").decimalValue());
        assertThat(number(result, "probe.duration")).isNotNegative();
        JsonNode stats = observation.index(index);
        assertThat(indexNumber(result, "docs.count", index)).isEqualByComparingTo(stats.path("primaries").path("docs").path("count").decimalValue());
        assertThat(indexNumber(result, "query.total", index)).isEqualByComparingTo(stats.path("total").path("search").path("query_total").decimalValue());
        assertThat(indexNumber(result, "indexing.total", index)).isEqualByComparingTo(stats.path("primaries").path("indexing").path("index_total").decimalValue());
        assertThat(indexMetric(result, "coverage.complete", index).value()).isEqualTo(true);
        assertThat(observation.kinds).containsExactly(CollectionKind.ORDINARY);
        assertThat(observation.nodeStarts).hasSize(2);
        observation.threadPools.path("nodes").fields().forEachRemaining(node -> {
            for (String pool : List.of("search", "search_coordination", "write")) {
                MetricSample metric = scopedMetric(result, "thread_pool." + pool + ".rejected", ScopeKind.NODE, node.getKey());
                assertThat((BigDecimal) metric.value()).isEqualByComparingTo(node.getValue().path("thread_pool").path(pool).path("rejected").decimalValue());
                assertThat(metric.definition().scope().nodeId()).isEqualTo(node.getKey());
            }
        });
    }

    private static void assertNativeCapacity(CollectionResult result, Observation observation, String index) {
        JsonNode stats = observation.index(index);
        assertThat(indexNumber(result, "store.primary.bytes", index)).isEqualByComparingTo(stats.path("primaries").path("store").path("size_in_bytes").decimalValue());
        assertThat(indexNumber(result, "store.total.bytes", index)).isEqualByComparingTo(stats.path("total").path("store").path("size_in_bytes").decimalValue());
        assertThat(indexMetric(result, "store.primary.bytes", index).definition().unit()).isEqualTo(Unit.BYTES);
        assertThat(observation.kinds).containsExactly(CollectionKind.CAPACITY);
        assertThat(result.metrics()).noneSatisfy(sample -> assertThat(sample.definition().key()).contains(".rate"));
        assertThat(observation.health).isNull();
        assertThat(observation.threadPools).isNull();
    }

    private static void assertRate(CollectionResult first, CollectionResult second, Observation firstRead, Observation secondRead,
                                   String index, String operation, String aggregation, String group, String counter) {
        BigDecimal previous = firstRead.index(index).path(aggregation).path(group).path(counter).decimalValue();
        BigDecimal current = secondRead.index(index).path(aggregation).path(group).path(counter).decimalValue();
        long elapsed = Duration.between(indexMetric(first, operation + ".rate", index).sampledAt(),
                indexMetric(second, operation + ".rate", index).sampledAt()).toNanos();
        assertThat(elapsed).isPositive();
        BigDecimal expected = current.subtract(previous).divide(BigDecimal.valueOf(elapsed, 9), MathContext.DECIMAL64);
        assertThat(indexNumber(second, operation + ".rate", index)).isCloseTo(expected, within(new BigDecimal("0.000001")));
    }

    private static MetricSample metric(CollectionResult result, String key) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(PREFIX + key)).findFirst().orElseThrow();
    }
    private static MetricSample scopedMetric(CollectionResult result, String key, ScopeKind kind, String id) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(PREFIX + key)
                && sample.definition().scope().kind() == kind && sample.definition().scope().id().equals(id)).findFirst().orElseThrow();
    }
    private static MetricSample indexMetric(CollectionResult result, String key, String index) { return scopedMetric(result, "index." + key, ScopeKind.INDEX, index); }
    private static BigDecimal indexNumber(CollectionResult result, String key, String index) { return (BigDecimal) indexMetric(result, key, index).value(); }
    private static BigDecimal number(CollectionResult result, String key) { return (BigDecimal) metric(result, key).value(); }
    private static void assertSafe(CollectionResult result, MonitoringTestEnvironment environment) throws Exception {
        assertThat(JSON.writeValueAsString(result)).doesNotContain(environment.password(), environment.host() + ":" + environment.port(),
                "https://", "BEGIN PRIVATE KEY", "BEGIN CERTIFICATE", "SSLHandshakeException", "stackTrace");
    }
    private record BusinessState(JsonNode settings, JsonNode mapping, JsonNode sentinel, long documents) { }

    private static final class Observation {
        JsonNode health;
        JsonNode threadPools;
        final List<JsonNode> indexStats = new ArrayList<>();
        final List<List<String>> requestedIndices = new ArrayList<>();
        final List<CollectionKind> kinds = new ArrayList<>();
        final List<JsonNode> nodeStarts = new ArrayList<>();
        JsonNode index(String name) { return indexStats.stream().map(stats -> stats.path("indices").path(name)).filter(JsonNode::isObject).findFirst().orElseThrow(); }
    }

    private static final class RecordingConnections extends ElasticsearchMonitoringConnections {
        final List<Observation> observations = new ArrayList<>();
        int closed;
        @Override public Session open(CollectionRequest request) {
            Session delegate = super.open(request);
            Observation observation = new Observation();
            observations.add(observation);
            return new Session() {
                @Override public JsonNode health() { observation.health = delegate.health(); return observation.health; }
                @Override public JsonNode threadPools() { observation.threadPools = delegate.threadPools(); return observation.threadPools; }
                @Override public JsonNode indexStats(List<String> names, CollectionKind kind) {
                    observation.requestedIndices.add(List.copyOf(names));
                    observation.kinds.add(kind);
                    JsonNode value = delegate.indexStats(names, kind);
                    observation.indexStats.add(value);
                    return value;
                }
                @Override public JsonNode nodeStarts(List<String> ids) {
                    JsonNode value = delegate.nodeStarts(ids);
                    observation.nodeStarts.add(value);
                    return value;
                }
                @Override public void close() { try { delegate.close(); } finally { closed++; } }
            };
        }
    }

    private static final class Collector implements AutoCloseable {
        final Clock clock = Clock.systemUTC();
        final MonitoringProperties properties = new MonitoringProperties();
        final MonitoringCounterStore counters = new MonitoringCounterStore(1, 128);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(1, 128, 1);
        final RecordingConnections connections = new RecordingConnections();
        final ElasticsearchMonitoringAdapter adapter = new ElasticsearchMonitoringAdapter(properties, connections, clock);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
        long sequence;

        Observation last() { return connections.observations.get(connections.observations.size() - 1); }
        CollectionResult collect(ResolvedTarget target, CollectionKind kind) {
            var binding = target.bindings().get(0);
            var source = binding.connection();
            snapshots.activate(target.display().id(), 1);
            Instant scheduled = clock.instant();
            Duration timeout = properties.getCollectionTimeout();
            Duration interval = kind == CollectionKind.ORDINARY ? properties.getOrdinaryInterval() : properties.getCapacityInterval();
            var control = new CollectionControl(new Object(), () -> true, clock, System::nanoTime,
                    System.nanoTime() + timeout.toNanos(), cleanup, counters, target.display().id(), source.source(), kind, interval, source.client());
            var request = new CollectionRequest(target.display().id(), source.source(), source.source(), kind,
                    1, ++sequence, scheduled, scheduled.plus(timeout), binding.scope(), source.client(), source.settings(), control);
            try {
                CollectionResult result = adapter.collect(request);
                assertThat(control.complete(request, result, snapshots)).isEqualTo(ACCEPTED);
                await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
                assertThat(control.hasCleanupFailure()).isFalse();
                return result;
            } finally {
                control.cancel(MissingReason.FAILED);
                await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
            }
        }
        @Override public void close() throws InterruptedException {
            cleanup.shutdownNow();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
