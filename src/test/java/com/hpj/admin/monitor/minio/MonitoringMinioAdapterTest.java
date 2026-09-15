package com.hpj.admin.monitor.minio;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.minio.MinioMonitoringConnections.HealthEndpoint.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Exact native HTTP contracts, bounded request scope and accepted current snapshots; no object writes. */
class MonitoringMinioAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant START = Instant.parse("2026-09-15T12:00:00Z");
    private static final String SECRET = "https://private-user:secret-sentinel@private-minio:9000";

    @Test
    void eachNativeHeadOwnsItsResultScopeLatencyAndTimestamp() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.session.health(LIVE)).thenAnswer(call -> { f.clock.now = START.plusMillis(10); return observed(200, 10); });
            when(f.session.health(READ_READY)).thenAnswer(call -> { f.clock.now = START.plusMillis(30); return observed(200, 20); });
            when(f.session.health(WRITE_READY)).thenAnswer(call -> { f.clock.now = START.plusMillis(60); return observed(200, 30); });
            when(f.session.bucket("owned-bucket")).thenAnswer(call -> { f.clock.now = START.plusMillis(100); return observed(200, 40); });
            CollectionResult result = f.collect(0);
            assertThat(f.adapter.type()).isEqualTo(MiddlewareType.MINIO);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(result.inventoryComplete()).isTrue();
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.serviceProbe().scope()).isEqualTo(new Scope(ScopeKind.ENDPOINT, "minio-target", null));
            assertThat(result.serviceProbe().sampledAt()).isEqualTo(START.plusMillis(10));
            assertThat(result.serviceProbe().validUntil()).isEqualTo(START.plusSeconds(45).plusMillis(10));
            for (String key : List.of("minio.health.live", "minio.health.read.ready", "minio.health.write.ready", "minio.bucket.accessible")) {
                assertThat(value(result, key)).isEqualTo(true);
                assertThat(metric(result, key).lastSuccessAt()).isEqualTo(metric(result, key).sampledAt());
                assertThat(metric(result, key).validUntil()).isEqualTo(metric(result, key).sampledAt().plusSeconds(45));
            }
            assertThat(metric(result, "minio.health.read.ready").definition().scope().kind()).isEqualTo(ScopeKind.CLUSTER);
            assertThat(metric(result, "minio.health.write.ready").definition().source()).isEqualTo("HEAD /minio/health/cluster");
            assertThat(metric(result, "minio.bucket.accessible").definition().scope()).isEqualTo(new Scope(ScopeKind.BUCKET, "owned-bucket", null));
            assertThat(number(result, "minio.health.live.latency.ms")).isEqualByComparingTo("10");
            assertThat(number(result, "minio.health.read.ready.latency.ms")).isEqualByComparingTo("20");
            assertThat(number(result, "minio.health.write.ready.latency.ms")).isEqualByComparingTo("30");
            assertThat(number(result, "minio.bucket.latency.ms")).isEqualByComparingTo("40");
            assertThat(metric(result, "minio.bucket.accessible").sampledAt()).isEqualTo(START.plusMillis(100));
            assertThat(result.metrics()).hasSize(17);
            assertThat(value(result, "minio.coverage.complete")).isEqualTo(true);
            var order = inOrder(f.session);
            order.verify(f.session).health(LIVE);
            order.verify(f.session).health(READ_READY);
            order.verify(f.session).health(WRITE_READY);
            order.verify(f.session).bucket("owned-bucket");
            order.verify(f.session).close();
            verifyNoMoreInteractions(f.session);
            assertSafe(result);
        }
    }

    @Test
    void liveAndBucketSuccessNeverFillInMissingClusterReadiness() throws Exception {
        for (int code : List.of(401, 403, 404, 405, 501)) try (Fixture f = new Fixture()) {
            when(f.session.health(READ_READY)).thenReturn(observed(code, 2));
            when(f.session.health(WRITE_READY)).thenReturn(observed(code, 3));
            CollectionResult result = f.collect(0);
            MissingReason reason = code == 401 || code == 403 ? MissingReason.UNAUTHORIZED : MissingReason.UNSUPPORTED;
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(reason);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(value(result, "minio.health.live")).isEqualTo(true);
            assertThat(value(result, "minio.bucket.accessible")).isEqualTo(true);
            assertThat(metric(result, "minio.health.read.ready").missingReason()).isEqualTo(reason);
            assertThat(metric(result, "minio.health.write.ready").missingReason()).isEqualTo(reason);
            assertThat(number(result, "minio.health.write.ready.http.status")).isEqualByComparingTo(Integer.toString(code));
            assertThat(number(result, "minio.health.write.ready.latency.ms")).isEqualByComparingTo("3");
            assertThat(value(result, "minio.coverage.complete")).isEqualTo(false);
        }
    }

    @Test
    void onlyMatchingReadinessEndpointCanProveTheCorrespondingReadOrWriteResult() throws Exception {
        for (boolean readReady : List.of(false, true)) try (Fixture f = new Fixture()) {
            when(f.session.health(READ_READY)).thenReturn(observed(readReady ? 200 : 503, 2));
            when(f.session.health(WRITE_READY)).thenReturn(observed(readReady ? 503 : 200, 3));
            CollectionResult result = f.collect(0);
            assertThat(value(result, "minio.health.read.ready")).isEqualTo(readReady);
            assertThat(value(result, "minio.health.write.ready")).isEqualTo(!readReady);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.serviceProbe().scope().kind()).isEqualTo(ScopeKind.ENDPOINT);
        }
    }

    @Test
    void explicitLiveFailureIsDegradedEvenWhenConfiguredBucketRemainsAccessible() throws Exception {
        for (int code : List.of(429, 503)) try (Fixture f = new Fixture()) {
            when(f.session.health(LIVE)).thenReturn(observed(code, 7));
            CollectionResult result = f.collect(0);
            assertThat(value(result, "minio.health.live")).isEqualTo(false);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.DEGRADED);
            assertThat(result.serviceProbe().reason()).isEqualTo(code == 429 ? MissingReason.BUSY : MissingReason.FAILED);
            assertThat(value(result, "minio.bucket.accessible")).isEqualTo(true);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(number(result, "minio.health.live.http.status")).isEqualByComparingTo(Integer.toString(code));
        }
    }

    @Test
    void s3OnlyAccessKeepsServiceUnknownAndSuccessfulBucketVisible() throws Exception {
        for (MissingReason reason : List.of(MissingReason.UNSUPPORTED, MissingReason.UNAUTHORIZED)) try (Fixture f = new Fixture()) {
            when(f.session.health(any())).thenThrow(new MinioMonitoringConnections.Failure(reason, false));
            CollectionResult result = f.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(result.serviceProbe().reason()).isEqualTo(reason);
            assertThat(value(result, "minio.bucket.accessible")).isEqualTo(true);
            for (String key : List.of("minio.health.live", "minio.health.read.ready", "minio.health.write.ready")) {
                assertThat(metric(result, key).missingReason()).isEqualTo(reason);
                assertThat(metric(result, key + ".latency.ms").value()).isNull();
            }
            assertSafe(result);
        }
    }

    @Test
    void unavailableHealthInterfaceDoesNotBecomeAServiceOutageFromHttpOrBucketResponses() throws Exception {
        for (int code : List.of(301, 302, 401, 403, 404, 405, 500, 501, 502)) try (Fixture f = new Fixture()) {
            when(f.session.health(any())).thenReturn(observed(code, 1));
            CollectionResult result = f.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(value(result, "minio.health.live")).isNull();
            assertThat(number(result, "minio.health.live.http.status")).isEqualByComparingTo(Integer.toString(code));
            assertThat(value(result, "minio.bucket.accessible")).isEqualTo(true);
        }
    }

    @Test
    void bucketMissingAndDeniedAreDistinctWithIndependentRowsAndMeasuredResponseTimes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.buckets = List.of("owned-bucket", "missing-bucket", "denied-bucket");
            when(f.session.bucket("missing-bucket")).thenReturn(observed(404, 8));
            when(f.session.bucket("denied-bucket")).thenReturn(observed(403, 9));
            CollectionResult result = f.collect(0);
            assertThat(bucketMetric(result, "minio.bucket.accessible", "owned-bucket").value()).isEqualTo(true);
            assertThat(bucketMetric(result, "minio.bucket.accessible", "missing-bucket").value()).isEqualTo(false);
            assertThat(bucketMetric(result, "minio.bucket.accessible", "denied-bucket").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(bucketMetric(result, "minio.bucket.accessible", "denied-bucket").lastSuccessAt()).isNull();
            assertThat(bucketMetric(result, "minio.bucket.http.status", "denied-bucket").value()).isEqualTo(BigDecimal.valueOf(403));
            assertThat((BigDecimal) bucketMetric(result, "minio.bucket.latency.ms", "denied-bucket").value()).isEqualByComparingTo("9");
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(result.inventoryComplete()).isTrue();
        }
    }

    @Test
    void absentBucketIsAnObservedFalseAndDoesNotNeedExtraRequestsToExplainIt() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.session.bucket(anyString())).thenReturn(observed(404, 1));
            CollectionResult result = f.collect(0);
            assertThat(value(result, "minio.bucket.accessible")).isEqualTo(false);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(metric(result, "minio.bucket.accessible").definition().calculation()).contains("当前凭据", "不能证明全局不存在");
            verify(f.session).bucket("owned-bucket");
        }
    }

    @Test
    void aBucketNetworkFailureDoesNotReplaceLiveServiceEvidenceOrOtherBuckets() throws Exception {
        try (Fixture f = new Fixture()) {
            f.buckets = List.of("failed-bucket", "owned-bucket");
            when(f.session.bucket("failed-bucket")).thenThrow(new MinioMonitoringConnections.Failure(MissingReason.FAILED, true));
            CollectionResult result = f.collect(0);
            assertThat(bucketMetric(result, "minio.bucket.accessible", "failed-bucket").missingReason()).isEqualTo(MissingReason.FAILED);
            assertThat(bucketMetric(result, "minio.bucket.latency.ms", "failed-bucket").value()).isNull();
            assertThat(bucketMetric(result, "minio.bucket.accessible", "owned-bucket").value()).isEqualTo(true);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
        }
    }

    @Test
    void openFailureProducesEveryAuthorizedPlaceholderAndRetainsSafeFailureClassification() throws Exception {
        for (MissingReason reason : List.of(MissingReason.UNAUTHORIZED, MissingReason.UNSUPPORTED, MissingReason.TLS_FAILED, MissingReason.TIMEOUT, MissingReason.FAILED)) try (Fixture f = new Fixture()) {
            when(f.connections.open(any())).thenThrow(new MinioMonitoringConnections.Failure(reason, reason == MissingReason.FAILED));
            CollectionResult result = f.collect(0);
            assertThat(result.reason()).isEqualTo(reason);
            assertThat(result.serviceProbe().availability()).isEqualTo(reason == MissingReason.FAILED ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN);
            assertThat(result.metrics().stream().filter(sample -> sample.definition().scope().kind() != ScopeKind.CONFIGURED_SCOPE))
                    .allMatch(sample -> sample.value() == null && sample.missingReason() == reason);
            assertThat(result.metrics()).hasSize(17);
            assertThat(result.inventoryComplete()).isTrue();
            verifyNoInteractions(f.session);
            assertSafe(result);
        }
    }

    @Test
    void aLiveConnectionFailureCannotBeOverwrittenByOtherSuccessfulNativeReads() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.session.health(LIVE)).thenThrow(new MinioMonitoringConnections.Failure(MissingReason.FAILED, true));
            CollectionResult result = f.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.CONNECTION_FAILED);
            assertThat(value(result, "minio.health.read.ready")).isEqualTo(true);
            assertThat(value(result, "minio.bucket.accessible")).isEqualTo(true);
        }
    }

    @Test
    void scopeIsExplicitOrFallsBackToOnlyTheExistingAttachmentBucketWithoutEnumeration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.settings.put("bucket", "attachment-bucket");
            f.collect(0);
            verify(f.session).bucket("owned-bucket");
            verify(f.session, never()).bucket("attachment-bucket");
        }
        try (Fixture f = new Fixture()) {
            f.buckets = List.of();
            f.settings.put("bucket", "attachment-bucket");
            CollectionResult result = f.collect(0);
            assertThat(metric(result, "minio.bucket.accessible").definition().scope().id()).isEqualTo("attachment-bucket");
            assertThat(number(result, "minio.buckets.configured")).isEqualByComparingTo("1");
            verify(f.session).bucket("attachment-bucket");
        }
        try (Fixture f = new Fixture()) {
            f.buckets = List.of();
            CollectionResult result = f.collect(0);
            assertThat(number(result, "minio.buckets.configured")).isEqualByComparingTo("0");
            assertThat(result.metrics()).noneMatch(sample -> sample.definition().scope().kind() == ScopeKind.BUCKET);
            verify(f.session, never()).bucket(anyString());
        }
    }

    @Test
    void duplicateNamesAreReadOnceAndInvalidOrSecretNamesNeverBecomeRequestsOrPublicScopeIds() throws Exception {
        try (Fixture f = new Fixture()) {
            f.buckets = List.of("owned-bucket", "owned-bucket", SECRET, "../outside", "BadName", "a", "a..b", "127.0.0.1");
            CollectionResult result = f.collect(0);
            assertThat(number(result, "minio.buckets.configured")).isEqualByComparingTo("7");
            assertThat(number(result, "minio.buckets.requested")).isEqualByComparingTo("1");
            assertThat(result.reason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(bucketMetric(result, "minio.bucket.accessible", "invalid-bucket#1").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            verify(f.session).bucket("owned-bucket");
            verify(f.session, times(1)).bucket(anyString());
            assertSafe(result);
        }
    }

    @Test
    void scopeLimitsAreBoundedEvenWhenConfigurationAllowsMoreAndTruncationIsExplicit() throws Exception {
        for (int limit : List.of(2, 100_000)) try (Fixture f = new Fixture()) {
            f.properties.getLimits().setBuckets(limit);
            f.buckets = IntStream.range(0, 200).mapToObj(i -> "owned-bucket-" + i).toList();
            CollectionResult result = f.collect(0);
            int effective = Math.min(limit, 100);
            assertThat(number(result, "minio.buckets.limit")).isEqualByComparingTo(Integer.toString(effective));
            assertThat(number(result, "minio.buckets.requested")).isEqualByComparingTo(Integer.toString(effective));
            assertThat(value(result, "minio.coverage.truncated")).isEqualTo(true);
            assertThat(value(result, "minio.coverage.complete")).isEqualTo(false);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.inventoryComplete()).isFalse();
            assertThat(result.metrics()).hasSize(14 + effective * 3);
            verify(f.session, times(effective)).bucket(anyString());
            verify(f.session, never()).bucket("owned-bucket-" + effective);
        }
    }

    @Test
    void malformedNativeObservationDoesNotProduceZeroOrDiscardIndependentGoodReads() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.session.health(LIVE)).thenReturn(null);
            CollectionResult result = f.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(metric(result, "minio.health.live").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, "minio.health.live.latency.ms").value()).isNull();
            assertThat(value(result, "minio.bucket.accessible")).isEqualTo(true);
        }
    }

    @Test
    void returnedFractionalAndZeroLatenciesArePreservedWithoutInventingResourceMetrics() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.session.health(LIVE)).thenReturn(new MinioMonitoringConnections.Observation(200, Duration.ofNanos(125_500)));
            when(f.session.bucket(anyString())).thenReturn(observed(200, 0));
            CollectionResult result = f.collect(0);
            assertThat(number(result, "minio.health.live.latency.ms")).isEqualByComparingTo("0.1255");
            assertThat(number(result, "minio.bucket.latency.ms")).isEqualByComparingTo("0");
            assertThat(result.metrics()).noneMatch(sample -> sample.definition().key().matches(".*(cpu|memory|bytes|disk|object|history|rate).*"));
            assertThat(f.counters.seriesCount("minio-target")).isZero();
        }
    }

    @Test
    void cancellationAfterLiveStopsFurtherReadsAndCannotPublishTheLateValue() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.session.health(LIVE)).thenAnswer(call -> { f.ticker.set(Duration.ofSeconds(5).toNanos()); return observed(200, 5_000); });
            CollectionRequest request = f.request(0, CollectionKind.ORDINARY);
            CollectionResult result = f.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(metric(result, "minio.health.live").value()).isNull();
            assertThat(metric(result, "minio.bucket.accessible").missingReason()).isEqualTo(MissingReason.TIMEOUT);
            verify(f.session, never()).health(READ_READY);
            verify(f.session, never()).health(WRITE_READY);
            verify(f.session, never()).bucket(anyString());
            verify(f.session).close();
            assertThat(request.control().complete(request, result, f.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
        }
    }

    @Test
    void cancellationAfterAnAcceptedNativeReadKeepsItsTimestampAndStopsSubsequentBuckets() throws Exception {
        try (Fixture f = new Fixture()) {
            f.buckets = List.of("slow-bucket", "owned-bucket");
            when(f.session.health(LIVE)).thenAnswer(call -> { f.clock.now = START.plusMillis(5); return observed(200, 5); });
            when(f.session.bucket("slow-bucket")).thenAnswer(call -> { f.ticker.set(Duration.ofSeconds(5).toNanos()); return observed(200, 5_000); });
            CollectionResult result = f.adapter.collect(f.request(0, CollectionKind.ORDINARY));
            assertThat(result.serviceProbe().sampledAt()).isEqualTo(START.plusMillis(5));
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(bucketMetric(result, "minio.bucket.accessible", "slow-bucket").value()).isNull();
            verify(f.session, never()).bucket("owned-bucket");
        }
    }

    @Test
    void failedBucketAttemptPreservesItsLastSuccessWithoutRefreshingItsSampleTime() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            when(f.session.bucket(anyString())).thenReturn(observed(403, 2));
            CollectionResult denied = f.collect(15_000);
            assertThat(metric(denied, "minio.bucket.accessible").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            var stored = f.snapshots.snapshot("minio-target").orElseThrow().metrics().stream()
                    .filter(metric -> metric.latestAttempt().definition().key().equals("minio.bucket.accessible")).findFirst().orElseThrow();
            assertThat(stored.lastSuccess().sampledAt()).isEqualTo(START);
            assertThat(stored.latestAttempt().sampledAt()).isEqualTo(START.plusSeconds(15));
            when(f.session.bucket(anyString())).thenReturn(observed(200, 2));
            CollectionResult recovered = f.collect(30_000);
            assertThat(metric(recovered, "minio.bucket.accessible").sampledAt()).isEqualTo(START.plusSeconds(30));
            assertThat(recovered.status()).isEqualTo(CollectionStatus.SUCCESS);
        }
    }

    @Test
    void capacityDoesNotOpenAConnectionOrCreateAnyProbeOrResourceSeries() throws Exception {
        try (Fixture f = new Fixture()) {
            CollectionResult result = f.adapter.collect(f.request(0, CollectionKind.CAPACITY));
            assertThat(result.status()).isEqualTo(CollectionStatus.UNSUPPORTED);
            assertThat(result.reason()).isEqualTo(MissingReason.NOT_APPLICABLE);
            assertThat(result.metrics()).isEmpty();
            assertThat(result.serviceProbe()).isNull();
            assertThat(result.inventoryComplete()).isFalse();
            verifyNoInteractions(f.connections, f.session);
        }
    }

    private static MinioMonitoringConnections.Observation observed(int status, long millis) { return new MinioMonitoringConnections.Observation(status, Duration.ofMillis(millis)); }
    private static MetricSample metric(CollectionResult result, String key) { return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)).findFirst().orElseThrow(); }
    private static MetricSample bucketMetric(CollectionResult result, String key, String bucket) { return result.metrics().stream().filter(sample -> sample.definition().key().equals(key) && sample.definition().scope().id().equals(bucket)).findFirst().orElseThrow(); }
    private static Object value(CollectionResult result, String key) { return metric(result, key).value(); }
    private static BigDecimal number(CollectionResult result, String key) { return (BigDecimal) value(result, key); }
    private static void assertSafe(CollectionResult result) throws Exception { assertThat(JSON.writeValueAsString(result)).doesNotContain(SECRET, "private-user", "secret-sentinel", "private-minio", "stackTrace"); }

    private static final class MutableClock extends Clock {
        Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final AtomicLong ticker = new AtomicLong();
        final Object borrowed = new Object() { @Override public String toString() { throw new AssertionError("Borrowed client must not be inspected"); } };
        final MonitoringProperties properties = new MonitoringProperties();
        final MinioMonitoringConnections connections = mock(MinioMonitoringConnections.class);
        final MinioMonitoringConnections.Session session = mock(MinioMonitoringConnections.Session.class);
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 10);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 1000, 4);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
        final List<CollectionControl> controls = new ArrayList<>();
        final MinioMonitoringAdapter adapter = new MinioMonitoringAdapter(properties, connections, clock);
        final Map<String, Object> settings = new LinkedHashMap<>(Map.of("endpoint", SECRET));
        List<String> buckets = List.of("owned-bucket");
        long sequence;

        Fixture() {
            when(connections.open(any())).thenReturn(session);
            when(session.health(any())).thenReturn(observed(200, 1));
            when(session.bucket(anyString())).thenReturn(observed(200, 1));
            snapshots.activate("minio-target", 1);
        }
        CollectionRequest request(long millis, CollectionKind kind) {
            clock.now = START.plusMillis(millis);
            CollectionControl control = new CollectionControl(new Object(), () -> true, clock, ticker::get,
                    ticker.get() + Duration.ofSeconds(5).toNanos(), cleanup, counters, "minio-target", "minio-binding", kind,
                    kind == CollectionKind.CAPACITY ? Duration.ofSeconds(60) : Duration.ofSeconds(15), borrowed);
            controls.add(control);
            return new CollectionRequest("minio-target", "minioClient", "minio-binding", kind, 1, sequence++, clock.now, clock.now.plusSeconds(5),
                    new MonitoringTarget.Scope(List.of(), List.of(), List.of(), buckets, List.of()), borrowed, settings, control);
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
