package com.hpj.admin.monitor.mysql;

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
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The actual adapter and atomic snapshot/counter lifecycle, with only native SQL responses substituted. */
class MonitoringMysqlAdapterTest {
    private static final Instant START = Instant.parse("2026-09-14T03:00:00Z");
    private static final String UUID = "7f4bb01e-1111-2222-3333-7a607279caf0";
    private static final String SECRET = "jdbc:mysql://private-mysql/internal?password=secret-sentinel";
    private static final String RATE = "mysql.questions.rate";
    private static final String DELTA = "mysql.slow_queries.delta";
    private static final String TOTAL = "mysql.slow_queries.total";

    @Test
    void firstSampleExposesNativeProcessGaugesAndWaitsForDerivedCounters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.collect(0);
            assertThat(fixture.adapter.type()).isEqualTo(MiddlewareType.MYSQL);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(result.inventoryComplete()).isTrue();
            assertThat(result.metrics()).hasSize(7);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().kind()).containsOnly(ScopeKind.PROCESS);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().id()).containsOnly("database");
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.serviceProbe().reason()).isNull();
            assertThat(value(result, "mysql.probe.duration")).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(metric(result, "mysql.probe.duration").definition().unit()).isEqualTo(Unit.MILLISECONDS);
            assertThat(value(result, "mysql.connections.current")).isEqualByComparingTo("4");
            assertThat(value(result, "mysql.connections.max")).isEqualByComparingTo("151");
            assertThat(value(result, "mysql.threads.running")).isEqualByComparingTo("2");
            assertThat(value(result, TOTAL)).isEqualByComparingTo("8");
            assertThat(metric(result, TOTAL).definition().unit()).isEqualTo(Unit.COUNT);
            assertThat(metric(result, DELTA).definition().unit()).isEqualTo(Unit.COUNT);
            assertThat(metric(result, RATE).definition().unit()).isEqualTo(Unit.COUNT_PER_SECOND);
            assertThat(metric(result, RATE).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(result, DELTA).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(result, TOTAL).validUntil()).isEqualTo(START.plusSeconds(45));
            assertThat(fixture.counters.seriesCount("database")).isEqualTo(2);
            verify(fixture.session).probe();
            verify(fixture.session).close();
        }
    }

    @Test
    void ratesUseActualFractionalElapsedTimeAndSlowQueriesRemainADelta() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.status.put("Questions", "145");
            fixture.status.put("Slow_queries", "11");
            fixture.status.put("Uptime", "1022");
            CollectionResult result = fixture.collect(22_500);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(result.reason()).isNull();
            assertThat(value(result, RATE)).isEqualByComparingTo("2");
            assertThat(value(result, DELTA)).isEqualByComparingTo("3");
            assertThat(value(result, TOTAL)).isEqualByComparingTo("11");
            assertThat(metric(result, RATE).sampledAt()).isEqualTo(START.plusMillis(22_500));
        }
    }

    @Test
    void unchangedCountersProduceNumericZeroRatherThanMissingValues() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.status.put("Uptime", "1015");
            CollectionResult result = fixture.collect(15_000);
            assertThat(value(result, RATE)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(value(result, DELTA)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
        }
    }

    @Test
    void counterRegressionsReplaceOnlyTheNecessaryBaselines() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.status.put("Questions", "10");
            fixture.status.put("Slow_queries", "2");
            fixture.status.put("Uptime", "1015");
            CollectionResult reset = fixture.collect(15_000);
            assertThat(metric(reset, RATE).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(reset, DELTA).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(value(reset, TOTAL)).isEqualByComparingTo("2");
            fixture.status.put("Questions", "40");
            fixture.status.put("Slow_queries", "3");
            fixture.status.put("Uptime", "1030");
            CollectionResult next = fixture.collect(30_000);
            assertThat(value(next, RATE)).isEqualByComparingTo("2");
            assertThat(value(next, DELTA)).isEqualByComparingTo("1");
        }
    }

    @Test
    void lowerUptimeDetectsRestartEvenWhenQueryCountersAlreadyPassedThePreviousValues() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.status.put("Questions", "200");
            fixture.status.put("Slow_queries", "12");
            fixture.status.put("Uptime", "5");
            CollectionResult restarted = fixture.collect(15_000);
            assertThat(metric(restarted, RATE).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(restarted, DELTA).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            fixture.status.put("Questions", "230");
            fixture.status.put("Slow_queries", "12");
            fixture.status.put("Uptime", "20");
            CollectionResult next = fixture.collect(30_000);
            assertThat(value(next, RATE)).isEqualByComparingTo("2");
            assertThat(value(next, DELTA)).isEqualByComparingTo("0");
        }
    }

    @Test
    void aChangedServerUuidStartsNewBaselinesWithoutExposingServerIdentityOrSettings() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.variables.put("server_uuid", "d8cf101e-4444-5555-6666-7a607279caf0");
            fixture.status.put("Questions", "400");
            CollectionResult moved = fixture.collect(15_000);
            assertThat(metric(moved, RATE).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(moved, DELTA).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(moved);
            assertThat(json).doesNotContain(UUID, fixture.variables.get("server_uuid"), SECRET, "password", "private-mysql");
        }
    }

    @Test
    void missingAndMalformedNativeFieldsDegradeOnlyTheirOwnSeries() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.status.remove("Threads_running");
            fixture.status.put("Slow_queries", "not-a-number");
            fixture.status.put("Questions", "130");
            CollectionResult result = fixture.collect(15_000);
            assertThat(metric(result, "mysql.threads.running").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(metric(result, TOTAL).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, DELTA).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(value(result, RATE)).isEqualByComparingTo("2");
            assertThat(value(result, "mysql.connections.current")).isEqualByComparingTo("4");
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            // Missing values do not advance the previous good counter sample.
            fixture.status.put("Slow_queries", "10");
            CollectionResult recovered = fixture.collect(30_000);
            assertThat(value(recovered, DELTA)).isEqualByComparingTo("2");
        }
    }

    @Test
    void missingUptimeOrServerUuidLeavesNativeGaugesAndTotalsUsable() throws Exception {
        for (String missing : List.of("Uptime", "server_uuid")) {
            try (Fixture fixture = new Fixture()) {
                fixture.status.remove(missing);
                fixture.variables.remove(missing);
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, RATE).missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
                assertThat(metric(result, DELTA).missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
                assertThat(value(result, TOTAL)).isEqualByComparingTo("8");
                assertThat(value(result, "mysql.connections.max")).isEqualByComparingTo("151");
                assertThat(fixture.counters.seriesCount("database")).isZero();
            }
        }
    }

    @Test
    void statisticsPermissionDenialDoesNotTurnASuccessfulProbeIntoAConnectionFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.globalStatus()).thenThrow(new SQLException(SECRET, "42000", 1227));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, RATE).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(metric(result, "mysql.connections.current").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(value(result, "mysql.connections.max")).isEqualByComparingTo("151");
            verify(fixture.session).globalVariables();
            assertSafe(result);
        }
    }

    @Test
    void variablePermissionDenialPreservesReadableStatusAndSlowQueryTotal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.globalVariables()).thenThrow(new SQLException(SECRET, "28000"));
            CollectionResult result = fixture.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, "mysql.connections.max").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(metric(result, RATE).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(value(result, "mysql.connections.current")).isEqualByComparingTo("4");
            assertThat(value(result, TOTAL)).isEqualByComparingTo("8");
            assertSafe(result);
        }
    }

    @Test
    void databaseSelectionAndSyntaxErrorsAreFailuresRatherThanPermissionDenials() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.connections.open(any())).thenThrow(new SQLException(SECRET, "42000", 1049));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.FAILED);
            assertThat(result.reason()).isEqualTo(MissingReason.FAILED);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(result.metrics()).extracting(MetricSample::missingReason).containsOnly(MissingReason.FAILED);
            assertSafe(result);
        }
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.globalStatus()).thenThrow(new SQLException(SECRET, "42000", 1064));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.FAILED);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, RATE).missingReason()).isEqualTo(MissingReason.FAILED);
            assertThat(value(result, "mysql.connections.max")).isEqualByComparingTo("151");
            assertSafe(result);
        }
    }

    @Test
    void statisticsTimeoutRetainsProbeSuccessAndHasAFixedMissingReason() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.globalStatus()).thenThrow(new SQLTimeoutException(SECRET));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, TOTAL).missingReason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(value(result, "mysql.connections.max")).isEqualByComparingTo("151");
            assertSafe(result);
        }
    }

    @Test
    void failuresBeforeProbeDistinguishConnectionAuthenticationUnsupportedAndTimeout() throws Exception {
        List<SQLException> errors = List.of(new SQLException(SECRET, "08001"), new SQLException(SECRET, "28000"),
                new SQLFeatureNotSupportedException(SECRET), new SQLTimeoutException(SECRET));
        List<MissingReason> reasons = List.of(MissingReason.FAILED, MissingReason.UNAUTHORIZED, MissingReason.UNSUPPORTED, MissingReason.TIMEOUT);
        List<CollectionStatus> statuses = List.of(CollectionStatus.FAILED, CollectionStatus.UNAUTHORIZED,
                CollectionStatus.UNSUPPORTED, CollectionStatus.FAILED);
        for (int index = 0; index < errors.size(); index++) {
            try (Fixture fixture = new Fixture()) {
                when(fixture.connections.open(any())).thenThrow(errors.get(index));
                CollectionResult result = fixture.collect(0);
                assertThat(result.status()).isEqualTo(statuses.get(index));
                assertThat(result.reason()).isEqualTo(reasons.get(index));
                assertThat(result.metrics()).extracting(MetricSample::missingReason).containsOnly(reasons.get(index));
                assertThat(result.serviceProbe().availability()).isEqualTo(index == 0
                        ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN);
                verifyNoInteractions(fixture.session);
                assertSafe(result);
            }
        }
    }

    @Test
    void exhaustedDeadlineStopsFurtherSqlAndCannotPublishABaseline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            doAnswer(call -> { fixture.ticker.set(Duration.ofSeconds(5).toNanos()); return null; }).when(fixture.session).probe();
            CollectionRequest request = fixture.request(0, CollectionKind.ORDINARY);
            CollectionResult result = fixture.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.metrics()).extracting(MetricSample::missingReason).containsOnly(MissingReason.TIMEOUT);
            verify(fixture.session, never()).globalStatus();
            verify(fixture.session, never()).globalVariables();
            verify(fixture.session).close();
            assertThat(request.control().complete(request, result, fixture.snapshots))
                    .isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            assertThat(fixture.counters.seriesCount("database")).isZero();
        }
    }

    @Test
    void anUnpublishedAttemptDoesNotBecomeTheNextCounterBaseline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionRequest unpublished = fixture.request(0, CollectionKind.ORDINARY);
            fixture.adapter.collect(unpublished);
            assertThat(fixture.counters.seriesCount("database")).isZero();
            fixture.status.put("Questions", "130");
            CollectionResult firstAccepted = fixture.collect(15_000);
            assertThat(metric(firstAccepted, RATE).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            fixture.status.put("Questions", "160");
            CollectionResult next = fixture.collect(30_000);
            assertThat(value(next, RATE)).isEqualByComparingTo("2");
        }
    }

    @Test
    void capacityHasNoInventoryOrProbeAndNeverOpensAConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.adapter.collect(fixture.request(0, CollectionKind.CAPACITY));
            assertThat(result.status()).isEqualTo(CollectionStatus.UNSUPPORTED);
            assertThat(result.reason()).isEqualTo(MissingReason.NOT_APPLICABLE);
            assertThat(result.metrics()).isEmpty();
            assertThat(result.serviceProbe()).isNull();
            verifyNoInteractions(fixture.connections, fixture.session);
        }
    }

    private static void assertSafe(CollectionResult result) throws Exception {
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).doesNotContain(SECRET, "private-mysql", "secret-sentinel", "SQLException", "stackTrace", UUID);
    }

    private static MetricSample metric(CollectionResult result, String key) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)).findFirst().orElseThrow();
    }

    private static BigDecimal value(CollectionResult result, String key) { return (BigDecimal) metric(result, key).value(); }

    private static final class MutableClock extends Clock {
        private Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final AtomicLong ticker = new AtomicLong();
        final Object borrowed = new Object() {
            @Override public String toString() { throw new AssertionError("Borrowed datasource must never be inspected or closed"); }
        };
        final MonitoringProperties properties = new MonitoringProperties();
        final MysqlMonitoringConnections connections = mock(MysqlMonitoringConnections.class);
        final MysqlMonitoringConnections.Session session = mock(MysqlMonitoringConnections.Session.class);
        final Map<String, String> status = new HashMap<>(Map.of("Threads_connected", "4", "Threads_running", "2",
                "Questions", "100", "Slow_queries", "8", "Uptime", "1000"));
        final Map<String, String> variables = new HashMap<>(Map.of("max_connections", "151", "server_uuid", UUID));
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 20);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 20, 4);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "mysql-adapter-unit-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        final List<CollectionControl> controls = new ArrayList<>();
        final MysqlMonitoringAdapter adapter = new MysqlMonitoringAdapter(properties, connections, clock);
        long sequence;

        Fixture() throws SQLException {
            when(connections.open(any())).thenReturn(session);
            when(session.globalStatus()).thenAnswer(call -> Map.copyOf(status));
            when(session.globalVariables()).thenAnswer(call -> Map.copyOf(variables));
            snapshots.activate("database", 1);
        }

        CollectionRequest request(long millis, CollectionKind kind) {
            clock.now = START.plusMillis(millis);
            CollectionControl control = new CollectionControl(new Object(), () -> true, clock, ticker::get,
                    ticker.get() + Duration.ofSeconds(5).toNanos(), cleanup, counters,
                    "database", "primary-db", kind, Duration.ofSeconds(15), borrowed);
            controls.add(control);
            return new CollectionRequest("database", "dataSource", "primary-db", kind, 1, sequence++,
                    clock.now, clock.now.plusSeconds(5), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    borrowed, Map.of("url", SECRET), control);
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
