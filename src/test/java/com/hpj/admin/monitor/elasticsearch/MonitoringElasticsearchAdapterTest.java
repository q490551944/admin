package com.hpj.admin.monitor.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Native JSON contracts plus real atomic counter/snapshot acceptance; no network or container required. */
class MonitoringElasticsearchAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant START = Instant.parse("2026-09-15T12:00:00Z");
    private static final String A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String B = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final String UUID = "UUUUUUUUUUUUUUUUUUUUUU";
    private static final String SECRET = "https://private-user:secret-sentinel@private-es:9200";

    @Test
    void nativeColorsAreAllocationHealthWhileProbeAndScopedPrimaryReplicaMetricsStayDistinct() throws Exception {
        for (String color : List.of("green", "yellow", "red")) try (Fixture f = new Fixture()) {
            f.health.put("status", color);
            CollectionResult result = f.collect(0);
            assertThat(f.adapter.type()).isEqualTo(MiddlewareType.ELASTICSEARCH);
            assertThat(value(result, "elasticsearch.cluster.health")).isEqualTo(color);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(number(result, "elasticsearch.cluster.nodes")).isEqualByComparingTo("2");
            assertThat(number(result, "elasticsearch.index.docs.count")).isEqualByComparingTo("5");
            assertThat(number(result, "elasticsearch.index.query.total")).isEqualByComparingTo("30");
            assertThat(number(result, "elasticsearch.index.indexing.total")).isEqualByComparingTo("20");
            assertThat(metric(result, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(result, "elasticsearch.index.query.total").definition().scope()).isEqualTo(new Scope(ScopeKind.INDEX, "owned-index", null));
            assertThat(metric(result, "elasticsearch.thread_pool.search.rejected").definition().scope()).isEqualTo(new Scope(ScopeKind.NODE, A, A));
            assertThat(result.metrics()).noneMatch(sample -> sample.definition().key().contains("store") || sample.definition().key().contains("jvm") || sample.definition().key().contains("cpu"));
            assertThat(value(result, "elasticsearch.index.coverage.complete")).isEqualTo(true);
            assertThat(f.counters.seriesCount("es-target")).isEqualTo(2);
            var order = inOrder(f.session);
            order.verify(f.session).health();
            order.verify(f.session).threadPools();
            order.verify(f.session).nodeStarts(List.of(A, B));
            order.verify(f.session).indexStats(List.of("owned-index"), CollectionKind.ORDINARY);
            order.verify(f.session).nodeStarts(List.of(A, B));
            order.verify(f.session).close();
            verifyNoMoreInteractions(f.session);
            assertSafe(result);
        }
    }

    @Test
    void primaryIndexingAndAllCopyQueryRatesUseActualElapsedSecondsAndPreserveRealZero() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            f.counters(40, 65, 65); // query delta=75; primary indexing delta=45.
            CollectionResult next = f.collect(22_500);
            assertThat(number(next, "elasticsearch.index.query.rate")).isEqualByComparingTo("3.333333333333333333333333333333333");
            assertThat(number(next, "elasticsearch.index.indexing.rate")).isEqualByComparingTo("2");
            assertThat(next.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(value(next, "elasticsearch.coverage.complete")).isEqualTo(true);
            CollectionResult zero = f.collect(37_500);
            assertThat(number(zero, "elasticsearch.index.query.rate")).isEqualByComparingTo("0");
            assertThat(number(zero, "elasticsearch.thread_pool.write.rejected")).isEqualByComparingTo("0");
        }
    }

    @Test
    void processRestartEvenWithLargerCountersAndIndexRecreationResetOnlyAcceptedRateBaselines() throws Exception {
        for (boolean recreate : List.of(false, true)) try (Fixture f = new Fixture()) {
            f.collect(0);
            f.counters(1000, 2000, 3000);
            if (recreate) f.index().put("uuid", "VVVVVVVVVVVVVVVVVVVVVV");
            else f.starts.path("nodes").path(A).withObject("/jvm").put("start_time_in_millis", 987654321L);
            CollectionResult changed = f.collect(15_000);
            assertThat(metric(changed, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(changed, "elasticsearch.index.indexing.rate").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            f.counters(1015, 2015, 3030);
            assertThat(number(f.collect(30_000), "elasticsearch.index.query.rate")).isEqualByComparingTo("2");
            assertSafe(changed);
        }
    }

    @Test
    void restartBetweenBeforeAndAfterReadsCannotPairOldCountersWithNewProcessIdentity() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            AtomicLong calls = new AtomicLong();
            when(f.session.nodeStarts(anyList())).thenAnswer(call -> {
                ObjectNode copy = f.starts.deepCopy();
                if (calls.incrementAndGet() % 2 == 0) copy.path("nodes").path(A).withObject("/jvm").put("start_time_in_millis", 987654321L);
                return copy;
            });
            f.counters(1000, 2000, 3000);
            assertThat(metric(f.collect(15_000), "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            doAnswer(call -> f.starts.deepCopy()).when(f.session).nodeStarts(anyList());
            f.counters(25, 65, 50);
            assertThat(number(f.collect(30_000), "elasticsearch.index.query.rate")).isEqualByComparingTo("2");
        }
    }

    @Test
    void missingNodeStartPermissionOnlyDegradesRatesAndDoesNotAdvanceTheirBaseline() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            when(f.session.nodeStarts(anyList())).thenThrow(new ElasticsearchMonitoringConnections.Failure(MissingReason.UNAUTHORIZED, false));
            f.counters(20, 40, 40);
            CollectionResult denied = f.collect(15_000);
            assertThat(metric(denied, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(number(denied, "elasticsearch.index.query.total")).isEqualByComparingTo("60");
            assertThat(number(denied, "elasticsearch.index.docs.count")).isEqualByComparingTo("5");
            assertThat(value(denied, "elasticsearch.index.coverage.complete")).isEqualTo(true);
            assertThat(denied.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            doAnswer(call -> f.starts.deepCopy()).when(f.session).nodeStarts(anyList());
            f.counters(25, 65, 60);
            assertThat(number(f.collect(30_000), "elasticsearch.index.query.rate")).isEqualByComparingTo("2");
        }
    }

    @Test
    void partialShardResponsesKeepObservedTotalsWithExplicitCoverageButNeverAdvanceCompleteRates() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            f.stats.withObject("/_shards").put("total", 3).put("failed", 1);
            f.counters(500, 600, 700);
            CollectionResult partial = f.collect(15_000);
            assertThat(number(partial, "elasticsearch.index.query.total")).isEqualByComparingTo("1100");
            assertThat(value(partial, "elasticsearch.index.coverage.complete")).isEqualTo(false);
            assertThat(value(partial, "elasticsearch.coverage.complete")).isEqualTo(false);
            assertThat(metric(partial, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            f.stats.withObject("/_shards").put("total", 2).put("failed", 0);
            f.counters(25, 65, 60);
            assertThat(number(f.collect(30_000), "elasticsearch.index.query.rate")).isEqualByComparingTo("2");
        }
        try (Fixture f = new Fixture()) {
            // Unassigned replicas may contribute to total without becoming a failed response.
            f.stats.withObject("/_shards").put("total", 3);
            assertThat(value(f.collect(0), "elasticsearch.index.coverage.complete")).isEqualTo(false);
        }
    }

    @Test
    void partialNodesPreserveSuccessfulRowsAndAreNeverAnAuthoritativeInventory() throws Exception {
        try (Fixture f = new Fixture()) {
            f.pools.withObject("/_nodes").put("total", 3).put("failed", 1);
            CollectionResult partial = f.collect(0);
            assertThat(number(partial, "elasticsearch.nodes.observed")).isEqualByComparingTo("2");
            assertThat(number(partial, "elasticsearch.thread_pool.search.rejected")).isEqualByComparingTo("2");
            assertThat(partial.inventoryComplete()).isFalse();
            assertThat(value(partial, "elasticsearch.coverage.complete")).isEqualTo(false);
        }
    }

    @Test
    void nativeCounterRollbackWaitsButDoesNotLoseOtherOperationRate() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            f.counters(20, 40, 1);
            CollectionResult reset = f.collect(15_000);
            assertThat(number(reset, "elasticsearch.index.query.rate")).isEqualByComparingTo("2");
            assertThat(metric(reset, "elasticsearch.index.indexing.rate").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        }
    }

    @Test
    void anObservedShardMoveChangesTheEpochEvenWhenItsCountersAlreadyCaughtUp() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            String moved = "CCCCCCCCCCCCCCCCCCCCCC";
            ObjectNode poolNodes = (ObjectNode) f.pools.path("nodes");
            poolNodes.set(moved, poolNodes.remove(B));
            ObjectNode startNodes = (ObjectNode) f.starts.path("nodes");
            startNodes.set(moved, startNodes.remove(B));
            f.index().path("shards").path("0").get(1).withObject("/routing").put("node", moved);
            f.counters(1000, 2000, 3000);
            CollectionResult changed = f.collect(15_000);
            assertThat(metric(changed, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(changed, "elasticsearch.index.indexing.rate").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        }
    }

    @Test
    void partialNodeIdentityEvidenceCannotSeedRatesEvenWhenSomeStartFieldsArePresent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.starts.withObject("/_nodes").put("total", 3).put("failed", 1);
            CollectionResult result = f.collect(0);
            assertThat(metric(result, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(number(result, "elasticsearch.index.query.total")).isEqualByComparingTo("30");
            assertThat(f.counters.seriesCount("es-target")).isZero();
        }
    }

    @Test
    void aCalculatedButCancelledSampleCannotAdvanceTheNextAcceptedRate() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            f.counters(500, 600, 700);
            CollectionRequest cancelled = f.request(15_000, CollectionKind.ORDINARY);
            CollectionResult uncommitted = f.adapter.collect(cancelled);
            cancelled.control().cancel(MissingReason.TIMEOUT);
            assertThat(cancelled.control().complete(cancelled, uncommitted, f.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            f.counters(25, 65, 60);
            assertThat(number(f.collect(30_000), "elasticsearch.index.query.rate")).isEqualByComparingTo("2");
        }
    }

    @Test
    void capacityReadsOnlyStoreAndUsesItsOwnLongerTtl() throws Exception {
        try (Fixture f = new Fixture()) {
            f.index().remove("shards");
            f.index().remove("uuid");
            CollectionRequest request = f.request(0, CollectionKind.CAPACITY);
            CollectionResult result = f.adapter.collect(request);
            assertThat(number(result, "elasticsearch.index.store.primary.bytes")).isEqualByComparingTo("100");
            assertThat(number(result, "elasticsearch.index.store.total.bytes")).isEqualByComparingTo("250");
            assertThat(metric(result, "elasticsearch.index.store.total.bytes").validUntil()).isEqualTo(START.plusSeconds(180));
            assertThat(result.serviceProbe()).isNull();
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(result.metrics()).noneMatch(sample -> sample.definition().key().contains("query") || sample.definition().key().contains("thread_pool"));
            verify(f.session).indexStats(List.of("owned-index"), CollectionKind.CAPACITY);
            verify(f.session).close();
            verifyNoMoreInteractions(f.session);
            assertThat(f.counters.seriesCount("es-target")).isZero();
        }
    }

    @Test
    void configuredScopeIsExactEmptyIsNeverAllAndInvalidNamesCannotReflectCredentialUris() throws Exception {
        try (Fixture f = new Fixture()) {
            f.indices = List.of();
            CollectionResult result = f.collect(0);
            assertThat(number(result, "elasticsearch.indices.requested")).isEqualByComparingTo("0");
            verify(f.session, never()).indexStats(anyList(), any());
            verify(f.session, never()).nodeStarts(anyList());
        }
        try (Fixture f = new Fixture()) {
            f.indices = List.of(SECRET, "*", "_all", "remote:other", "invalid-index-0", "owned-index", "index[0]");
            CollectionResult result = f.collect(0);
            verify(f.session).indexStats(List.of("invalid-index-0", "owned-index"), CollectionKind.ORDINARY);
            assertThat(result.metrics()).anyMatch(sample -> sample.definition().scope().id().equals("invalid-index-0"));
            assertThat(result.metrics()).anyMatch(sample -> sample.definition().scope().id().equals("invalid-index#0"));
            assertThat(number(result, "elasticsearch.indices.requested")).isEqualByComparingTo("2");
            assertSafe(result);
        }
    }

    @Test
    void aliasesAndUnexpectedExpandedIndicesAreNeverDisplayedOrUsedForRates() throws Exception {
        try (Fixture f = new Fixture()) {
            f.indices = List.of("configured-alias");
            f.index().put("uuid", UUID);
            CollectionResult result = f.collect(0);
            assertThat(metric(result, "elasticsearch.index.docs.count").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(result.metrics()).noneMatch(sample -> sample.definition().scope().id().equals("owned-index"));
            assertThat(value(result, "elasticsearch.coverage.complete")).isEqualTo(false);
            assertThat(f.counters.seriesCount("es-target")).isZero();
        }
    }

    @Test
    void totalBudgetAndInternalShardBudgetAreExplicitAndBoundNodeStartRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.getLimits().setIndices(100_000);
            f.properties.getLimits().setNodes(100_000);
            f.indices = IntStream.range(0, 10_000).mapToObj(i -> "owned-" + i).toList();
            CollectionResult result = f.collect(0);
            assertThat(result.metrics().size()).isLessThanOrEqualTo(4500);
            assertThat(value(result, "elasticsearch.coverage.truncated")).isEqualTo(true);
            assertThat(result.inventoryComplete()).isFalse();
            assertThat(number(result, "elasticsearch.limits.metrics")).isEqualByComparingTo("4500");
            assertThat(number(result, "elasticsearch.limits.indices")).isEqualByComparingTo("128");
            assertThat(number(result, "elasticsearch.indices.requested")).isEqualByComparingTo("128");
            verify(f.session).indexStats(f.indices.subList(0, 128), CollectionKind.ORDINARY);
        }
        try (Fixture f = new Fixture()) {
            f.properties.getLimits().setNodes(1);
            CollectionResult result = f.collect(0);
            assertThat(value(result, "elasticsearch.coverage.truncated")).isEqualTo(true);
            assertThat(metric(result, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            verify(f.session, times(2)).nodeStarts(List.of(A));
            verify(f.session, never()).nodeStarts(List.of(A, B));
        }
        try (Fixture f = new Fixture()) {
            f.properties.getLimits().setPartitions(1);
            CollectionResult result = f.collect(0);
            assertThat(value(result, "elasticsearch.coverage.truncated")).isEqualTo(true);
            assertThat(metric(result, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
        }
    }

    @Test
    void permissionAndTlsFailureRemainDistinctAndDoNotLeakNativeDetails() throws Exception {
        for (MissingReason reason : List.of(MissingReason.UNAUTHORIZED, MissingReason.TLS_FAILED)) try (Fixture f = new Fixture()) {
            when(f.connections.open(any())).thenThrow(new ElasticsearchMonitoringConnections.Failure(reason, false));
            CollectionResult result = f.collect(0);
            assertThat(result.reason()).isEqualTo(reason);
            assertThat(result.status()).isEqualTo(reason == MissingReason.UNAUTHORIZED ? CollectionStatus.UNAUTHORIZED : CollectionStatus.FAILED);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(result.serviceProbe().reason()).isEqualTo(reason);
            assertSafe(result);
        }
        try (Fixture f = new Fixture()) {
            when(f.session.indexStats(anyList(), any())).thenThrow(new ElasticsearchMonitoringConnections.Failure(MissingReason.UNAUTHORIZED, false));
            CollectionResult result = f.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, "elasticsearch.index.docs.count").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(value(result, "elasticsearch.cluster.health")).isEqualTo("green");
        }
    }

    @Test
    void malformedNativeValuesDoNotBecomeZerosAndAggregateMismatchCannotSeedRates() throws Exception {
        try (Fixture f = new Fixture()) {
            f.index().withObject("/primaries/docs").put("count", SECRET);
            f.index().withObject("/total/search").put("query_total", 1234);
            CollectionResult result = f.collect(0);
            assertThat(metric(result, "elasticsearch.index.docs.count").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(number(result, "elasticsearch.index.query.total")).isEqualByComparingTo("1234");
            assertThat(metric(result, "elasticsearch.index.query.rate").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(f.counters.seriesCount("es-target")).isZero();
            assertSafe(result);
        }
    }

    @Test
    void elapsedSharedDeadlineStopsFurtherEndpointsAndCannotCommitCounters() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.session.nodeStarts(anyList())).thenAnswer(call -> { f.ticker.set(Duration.ofSeconds(5).toNanos()); return f.starts.deepCopy(); });
            CollectionRequest request = f.request(0, CollectionKind.ORDINARY);
            CollectionResult result = f.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            verify(f.session, never()).indexStats(anyList(), any());
            verify(f.session).close();
            assertThat(request.control().complete(request, result, f.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            assertThat(f.counters.seriesCount("es-target")).isZero();
        }
    }

    private static MetricSample metric(CollectionResult result, String key) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)).findFirst().orElseThrow();
    }
    private static Object value(CollectionResult result, String key) { return metric(result, key).value(); }
    private static BigDecimal number(CollectionResult result, String key) { return (BigDecimal) value(result, key); }
    private static void assertSafe(CollectionResult result) throws Exception {
        assertThat(JSON.writeValueAsString(result)).doesNotContain(SECRET, UUID, "987654321", "private-user", "secret-sentinel", "private-es");
    }

    private static final class MutableClock extends Clock {
        Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final AtomicLong ticker = new AtomicLong();
        final Object borrowed = new Object();
        final MonitoringProperties properties = new MonitoringProperties();
        final ElasticsearchMonitoringConnections connections = mock(ElasticsearchMonitoringConnections.class);
        final ElasticsearchMonitoringConnections.Session session = mock(ElasticsearchMonitoringConnections.Session.class);
        final ObjectNode health = JSON.createObjectNode().put("status", "green").put("number_of_nodes", 2).put("unassigned_shards", 0).put("timed_out", false);
        final ObjectNode stats = JSON.createObjectNode();
        final ObjectNode pools = JSON.createObjectNode();
        final ObjectNode starts = JSON.createObjectNode();
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 2000);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 5000, 4);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
        final List<CollectionControl> controls = new ArrayList<>();
        final ElasticsearchMonitoringAdapter adapter = new ElasticsearchMonitoringAdapter(properties, connections, clock);
        List<String> indices = List.of("owned-index");
        long sequence;

        Fixture() {
            counts(stats.putObject("_shards"));
            ObjectNode index = stats.putObject("indices").putObject("owned-index").put("uuid", UUID);
            index.withObject("/primaries/docs").put("count", 5);
            index.withObject("/total/docs").put("count", 10);
            index.withObject("/primaries/store").put("size_in_bytes", 100);
            index.withObject("/total/store").put("size_in_bytes", 250);
            var copies = index.putObject("shards").putArray("0");
            for (String id : List.of(A, B)) copies.addObject().putObject("routing").put("node", id).put("primary", id.equals(A)).put("state", "STARTED").putNull("relocating_node");
            counters(10, 20, 20);
            counts(pools.putObject("_nodes"));
            counts(starts.putObject("_nodes"));
            for (String id : List.of(A, B)) {
                ObjectNode node = pools.withObject("/nodes").putObject(id).putObject("thread_pool");
                node.putObject("search").put("rejected", 2);
                node.putObject("search_coordination").put("rejected", 1);
                node.putObject("write").put("rejected", 0);
                starts.withObject("/nodes").putObject(id).putObject("jvm").put("start_time_in_millis", 123456789L);
            }
            when(connections.open(any())).thenReturn(session);
            when(session.health()).thenAnswer(call -> health.deepCopy());
            when(session.threadPools()).thenAnswer(call -> pools.deepCopy());
            when(session.nodeStarts(anyList())).thenAnswer(call -> starts.deepCopy());
            when(session.indexStats(anyList(), any())).thenAnswer(call -> stats.deepCopy());
            snapshots.activate("es-target", 1);
        }
        private static void counts(ObjectNode node) { node.put("total", 2).put("successful", 2).put("failed", 0); }
        ObjectNode index() { return (ObjectNode) stats.path("indices").path("owned-index"); }
        void counters(long queryPrimary, long queryReplica, long indexingPrimary) {
            index().withObject("/total/search").put("query_total", queryPrimary + queryReplica);
            index().withObject("/primaries/search").put("query_total", queryPrimary);
            index().withObject("/primaries/indexing").put("index_total", indexingPrimary);
            index().withObject("/total/indexing").put("index_total", indexingPrimary * 2);
            index().path("shards").path("0").get(0).withObject("/search").put("query_total", queryPrimary);
            index().path("shards").path("0").get(1).withObject("/search").put("query_total", queryReplica);
            index().path("shards").path("0").forEach(node -> node.withObject("/indexing").put("index_total", indexingPrimary));
        }
        CollectionRequest request(long millis, CollectionKind kind) {
            clock.now = START.plusMillis(millis);
            CollectionControl control = new CollectionControl(new Object(), () -> true, clock, ticker::get,
                    ticker.get() + Duration.ofSeconds(5).toNanos(), cleanup, counters, "es-target", "es-binding", kind,
                    kind == CollectionKind.CAPACITY ? Duration.ofSeconds(60) : Duration.ofSeconds(15), borrowed);
            controls.add(control);
            return new CollectionRequest("es-target", "esClient", "es-binding", kind, 1, sequence++, clock.now, clock.now.plusSeconds(5),
                    new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), indices), borrowed, Map.of("private-settings", SECRET), control);
        }
        CollectionResult collect(long millis) {
            CollectionRequest request = request(millis, CollectionKind.ORDINARY);
            CollectionResult result = adapter.collect(request);
            assertThat(request.control().complete(request, result, snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.ACCEPTED);
            return result;
        }
        @Override public void close() throws InterruptedException {
            controls.forEach(control -> control.cancel(MissingReason.FAILED));
            cleanup.shutdownNow();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
