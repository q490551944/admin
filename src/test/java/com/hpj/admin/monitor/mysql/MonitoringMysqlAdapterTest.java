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
import java.util.stream.IntStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The actual adapter and atomic snapshot/counter lifecycle, with only native SQL responses substituted. */
class MonitoringMysqlAdapterTest {
    private static final Instant START = Instant.parse("2026-09-14T03:00:00Z");
    private static final String UUID = "7f4bb01e-1111-2222-3333-7a607279caf0";
    private static final String SECRET = "jdbc:mysql://private-mysql/internal?password=secret-sentinel";
    private static final String RATE = "mysql.questions.rate";
    private static final String DELTA = "mysql.slow_queries.delta";
    private static final String TOTAL = "mysql.slow_queries.total";
    private static final String HIT = "mysql.innodb.buffer_pool.hit.percent";
    private static final String CACHE_DATA = "mysql.innodb.buffer_pool.data.bytes";
    private static final String CACHE_CONFIG = "mysql.innodb.buffer_pool.configured.bytes";
    private static final String DATA = "mysql.database.data.bytes";
    private static final String INDEX = "mysql.database.index.bytes";

    @Test
    void firstSampleExposesNativeProcessGaugesAndWaitsForDerivedCounters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.collect(0);
            assertThat(fixture.adapter.type()).isEqualTo(MiddlewareType.MYSQL);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(result.inventoryComplete()).isTrue();
            assertThat(result.metrics()).hasSize(12);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().kind()).containsOnly(ScopeKind.PROCESS, ScopeKind.CACHE);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().id()).containsOnly("database", "database.innodb-buffer-pool");
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
            assertThat(fixture.counters.seriesCount("database")).isEqualTo(3);
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
            fixture.status.put("Innodb_buffer_pool_reads", "12");
            fixture.status.put("Innodb_buffer_pool_read_requests", "120");
            CollectionResult result = fixture.collect(22_500);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNSUPPORTED);
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
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(metric(result, HIT).missingReason()).isEqualTo(MissingReason.NO_REQUESTS);
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
            assertThat(nativeOrdinary(result)).extracting(MetricSample::missingReason).containsOnly(MissingReason.FAILED);
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
                assertThat(nativeOrdinary(result)).extracting(MetricSample::missingReason).containsOnly(reasons.get(index));
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
            assertThat(nativeOrdinary(result)).extracting(MetricSample::missingReason).containsOnly(MissingReason.TIMEOUT);
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
    void capacityWithNoConfiguredDatabaseHasOnlyCoverageAndUnsupportedResourcesWithoutConnecting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.adapter.collect(fixture.request(0, CollectionKind.CAPACITY));
            assertThat(result.status()).isEqualTo(CollectionStatus.UNSUPPORTED);
            assertThat(result.reason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(result.metrics()).hasSize(9);
            assertThat(value(result, "mysql.databases.configured")).isEqualByComparingTo("0");
            assertThat(result.serviceProbe()).isNull();
            verifyNoInteractions(fixture.connections, fixture.session);
        }
    }

    @Test
    void bufferPoolUsesNativeBytesAndCacheScopeWithoutInventingProcessResources() throws Exception {
        try (Fixture f = new Fixture()) {
            f.status.put("Innodb_buffer_pool_pages_data", "999");
            f.variables.put("innodb_page_size", "4096");
            CollectionResult result = f.collect(0);
            assertThat(value(result, CACHE_DATA)).isEqualByComparingTo("16384");
            assertThat(value(result, CACHE_CONFIG)).isEqualByComparingTo("65536");
            assertThat(metric(result, CACHE_DATA).definition().scope()).isEqualTo(new Scope(ScopeKind.CACHE, "database.innodb-buffer-pool", null));
            assertThat(metric(result, CACHE_DATA).definition().source()).contains("Innodb_buffer_pool_bytes_data");
            assertThat(metric(result, HIT).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(result, "mysql.process.cpu.percent").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(metric(result, "mysql.process.memory.bytes").value()).isNull();
            assertThat(result.metrics()).noneMatch(sample -> sample.definition().scope().kind() == ScopeKind.DATABASE || sample.definition().scope().kind() == ScopeKind.FILESYSTEM);
            verify(f.session, never()).database(anyString(), anyInt());
            assertSafe(result);
        }
    }

    @Test
    void cacheConfigurationAndDataKeepTheirOwnNativeReadTimestamps() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.session.globalStatus()).thenAnswer(call -> { f.clock.now = START.plusMillis(10); return Map.copyOf(f.status); });
            when(f.session.globalVariables()).thenAnswer(call -> { f.clock.now = START.plusMillis(30); return Map.copyOf(f.variables); });
            CollectionResult result = f.collect(0);
            assertThat(metric(result, CACHE_DATA).sampledAt()).isEqualTo(START.plusMillis(10));
            assertThat(metric(result, CACHE_CONFIG).sampledAt()).isEqualTo(START.plusMillis(30));
            assertThat(metric(result, CACHE_CONFIG).validUntil()).isEqualTo(START.plusSeconds(45).plusMillis(30));
        }
    }

    @Test
    void cacheHitRatioUsesIntervalReadDeltasAndDistinguishesNoRequestsRealZeroAndInconsistency() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            f.readCounters("12", "120", "1015");
            assertThat(value(f.collect(15_000), HIT)).isEqualByComparingTo("90");
            f.readCounters("32", "140", "1030");
            assertThat(value(f.collect(30_000), HIT)).isEqualByComparingTo("0");
            f.readCounters("32", "140", "1045");
            assertThat(metric(f.collect(45_000), HIT).missingReason()).isEqualTo(MissingReason.NO_REQUESTS);
            f.readCounters("50", "150", "1060");
            assertThat(metric(f.collect(60_000), HIT).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            f.readCounters("50", "160", "1075");
            assertThat(value(f.collect(75_000), HIT)).isEqualByComparingTo("100");
        }
    }

    @Test
    void hitBaselineResetsOnEitherReadCounterUptimeOrServerIdentityChange() throws Exception {
        for (String changed : List.of("physical", "logical", "uptime", "uuid")) try (Fixture f = new Fixture()) {
            f.collect(0);
            f.readCounters("20", "200", "1015");
            if (changed.equals("physical")) f.status.put("Innodb_buffer_pool_reads", "1");
            if (changed.equals("logical")) f.status.put("Innodb_buffer_pool_read_requests", "1");
            if (changed.equals("uptime")) f.status.put("Uptime", "5");
            if (changed.equals("uuid")) f.variables.put("server_uuid", "d8cf101e-4444-5555-6666-7a607279caf0");
            assertThat(metric(f.collect(15_000), HIT).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            long physical = Long.parseLong(f.status.get("Innodb_buffer_pool_reads"));
            long logical = Long.parseLong(f.status.get("Innodb_buffer_pool_read_requests"));
            f.readCounters(Long.toString(physical + 1), Long.toString(logical + 10), "1030");
            assertThat(value(f.collect(30_000), HIT)).isEqualByComparingTo("90");
        }
    }

    @Test
    void cacheMissingAndInvalidFieldsNeverBorrowPageCountsOrAdvanceGoodHitBaseline() throws Exception {
        for (String invalid : List.of("-1", "1e6", "NaN", "18446744073709551616", "9".repeat(100))) try (Fixture f = new Fixture()) {
            f.collect(0);
            f.status.put("Innodb_buffer_pool_bytes_data", invalid);
            f.status.put("Innodb_buffer_pool_reads", invalid);
            CollectionResult bad = f.collect(15_000);
            assertThat(metric(bad, CACHE_DATA).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(bad, HIT).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(value(bad, CACHE_CONFIG)).isEqualByComparingTo("65536");
            f.readCounters("12", "120", "1030");
            assertThat(value(f.collect(30_000), HIT)).isEqualByComparingTo("90");
        }
        try (Fixture f = new Fixture()) {
            f.status.remove("Innodb_buffer_pool_bytes_data");
            f.status.put("Innodb_buffer_pool_pages_data", "999");
            f.variables.put("innodb_page_size", "16384");
            assertThat(metric(f.collect(0), CACHE_DATA).missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
        }
    }

    @Test
    void cacheStatusAndConfigurationPermissionsDegradeIndependently() throws Exception {
        for (boolean statusDenied : List.of(false, true)) try (Fixture f = new Fixture()) {
            if (statusDenied) when(f.session.globalStatus()).thenThrow(new SQLException(SECRET, "42000", 1227));
            else when(f.session.globalVariables()).thenThrow(new SQLException(SECRET, "42000", 1227));
            CollectionResult result = f.collect(0);
            assertThat(metric(result, HIT).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(metric(result, statusDenied ? CACHE_DATA : CACHE_CONFIG).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(metric(result, statusDenied ? CACHE_CONFIG : CACHE_DATA).value()).isNotNull();
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        }
    }

    @Test
    void hitTtlBoundaryAndCancelledSampleUseOnlyAcceptedPreviousObservation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.collect(0);
            f.readCounters("12", "120", "1045");
            assertThat(value(f.collect(45_000), HIT)).isEqualByComparingTo("90");
            f.readCounters("14", "140", "1090");
            assertThat(metric(f.collect(90_001), HIT).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
        }
        try (Fixture f = new Fixture()) {
            f.collect(0);
            f.readCounters("1000", "10000", "1015");
            CollectionRequest cancelled = f.request(15_000, CollectionKind.ORDINARY);
            CollectionResult discarded = f.adapter.collect(cancelled);
            cancelled.control().cancel(MissingReason.TIMEOUT);
            assertThat(cancelled.control().complete(cancelled, discarded, f.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            f.readCounters("12", "120", "1030");
            assertThat(value(f.collect(30_000), HIT)).isEqualByComparingTo("90");
        }
    }

    @Test
    void capacityMapsVisibleBaseTableMetadataOnlyAndKeepsItsOwnTtlWithoutProbe() throws Exception {
        try (Fixture f = new Fixture()) {
            f.databases = List.of("owned-db");
            f.capacity.put("owned-db", db("owned-db", List.of(row("owned-db", "one", "InnoDB", "100", "30"), row("owned-db", "two", "MEMORY", "40", "10"))));
            CollectionResult result = f.capacity(0);
            assertThat(dbValue(result, DATA, "owned-db")).isEqualByComparingTo("140");
            assertThat(dbValue(result, INDEX, "owned-db")).isEqualByComparingTo("40");
            assertThat(dbMetric(result, "mysql.database.tables.observed", "owned-db").value()).isEqualTo(BigDecimal.valueOf(2));
            assertThat(dbMetric(result, "mysql.database.coverage.complete", "owned-db").value()).isEqualTo(true);
            assertThat(dbMetric(result, DATA, "owned-db").definition().scope()).isEqualTo(new Scope(ScopeKind.DATABASE, "owned-db", null));
            assertThat(dbMetric(result, DATA, "owned-db").definition().calculation()).contains("当前凭据可见", "MEMORY", "统计缓存", "不是统计更新时间");
            assertThat(dbMetric(result, DATA, "owned-db").validUntil()).isEqualTo(START.plusSeconds(180));
            assertThat(result.metrics()).hasSize(13);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(result.serviceProbe()).isNull();
            assertThat(metric(result, "mysql.filesystem.total.bytes").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(metric(result, "mysql.filesystem.available.bytes").value()).isNull();
            assertThat(result.metrics()).noneMatch(sample -> sample.definition().key().contains("DATA_FREE") || sample.definition().key().contains("buffer_pool"));
            assertThat(f.counters.seriesCount("database")).isZero();
            verify(f.session).database("owned-db", 1000);
            verify(f.session, never()).probe();
            verify(f.session, never()).globalStatus();
            verify(f.session, never()).globalVariables();
            verify(f.session).close();
            assertSafe(result);
        }
    }

    @Test
    void invisibleDatabaseIsUnknownWhileVisibleEmptyMetadataIsAnExplicitVisibleScopeZero() throws Exception {
        try (Fixture f = new Fixture()) {
            f.databases = List.of("invisible", "empty");
            f.capacity.put("invisible", new MysqlMonitoringConnections.DatabaseCapacity(false, null, List.of(), List.of(), false));
            f.capacity.put("empty", db("empty", List.of()));
            CollectionResult result = f.capacity(0);
            assertThat(dbMetric(result, DATA, "invisible").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(dbMetric(result, "mysql.database.tables.observed", "invisible").value()).isNull();
            assertThat(dbValue(result, DATA, "empty")).isEqualByComparingTo("0");
            assertThat(dbValue(result, INDEX, "empty")).isEqualByComparingTo("0");
            assertThat(dbMetric(result, "mysql.database.tables.observed", "empty").definition().calculation()).contains("不证明整个数据库为空");
            assertThat(dbMetric(result, "mysql.database.coverage.complete", "empty").value()).isEqualTo(true);
        }
    }

    @Test
    void capacityNullOrInvalidSizeDegradesOnlyTheAffectedColumnAndNeverCoalescesToZero() throws Exception {
        for (String invalid : new String[] { null, "-1", "1e6", "18446744073709551616" }) try (Fixture f = new Fixture()) {
            f.databases = List.of("owned");
            f.capacity.put("owned", db("owned", List.of(row("owned", "one", "InnoDB", invalid, "30"), row("owned", "two", "InnoDB", "40", "10"))));
            CollectionResult result = f.capacity(0);
            assertThat(dbMetric(result, DATA, "owned").missingReason()).isEqualTo(invalid == null ? MissingReason.UNSUPPORTED : MissingReason.INVALID_VALUE);
            assertThat(dbValue(result, INDEX, "owned")).isEqualByComparingTo("40");
            assertThat(dbMetric(result, "mysql.database.coverage.complete", "owned").value()).isEqualTo(false);
        }
        try (Fixture f = new Fixture()) {
            f.databases = List.of("owned");
            f.capacity.put("owned", db("owned", List.of(row("owned", "one", "InnoDB", "20", null))));
            CollectionResult result = f.capacity(0);
            assertThat(dbValue(result, DATA, "owned")).isEqualByComparingTo("20");
            assertThat(dbMetric(result, INDEX, "owned").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
        }
    }

    @Test
    void truncatedOrDisappearingTablesCannotBecomeACompleteDatabaseAggregate() throws Exception {
        for (boolean truncated : List.of(false, true)) try (Fixture f = new Fixture()) {
            f.databases = List.of("owned");
            f.capacity.put("owned", new MysqlMonitoringConnections.DatabaseCapacity(true, "owned", List.of("one", "two"),
                    List.of(row("owned", "one", "InnoDB", "100", "30")), truncated));
            CollectionResult result = f.capacity(0);
            assertThat(dbMetric(result, DATA, "owned").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(dbMetric(result, INDEX, "owned").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(dbMetric(result, "mysql.database.tables.observed", "owned").value()).isEqualTo(BigDecimal.ONE);
            assertThat(dbMetric(result, "mysql.database.coverage.complete", "owned").value()).isEqualTo(false);
            assertThat(metric(result, "mysql.capacity.coverage.truncated").value()).isEqualTo(truncated);
        }
    }

    @Test
    void capacityUsesOneSharedTableBudgetAcrossDatabasesAndDoesNotReadOutsideConfiguredScope() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.getLimits().setTables(3);
            f.databases = List.of("first", "second", "third");
            f.capacity.put("first", db("first", List.of(row("first", "one", "InnoDB", "10", "2"), row("first", "two", "InnoDB", "20", "3"))));
            f.capacity.put("second", db("second", List.of(row("second", "one", "InnoDB", "50", "7"))));
            CollectionResult result = f.capacity(0);
            verify(f.session).database("first", 3);
            verify(f.session).database("second", 1);
            verify(f.session, never()).database(eq("third"), anyInt());
            verify(f.session, times(2)).database(anyString(), anyInt());
            assertThat(dbValue(result, DATA, "first")).isEqualByComparingTo("30");
            assertThat(dbValue(result, DATA, "second")).isEqualByComparingTo("50");
            assertThat(dbMetric(result, DATA, "third").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(value(result, "mysql.tables.selected")).isEqualByComparingTo("3");
            assertThat(metric(result, "mysql.capacity.coverage.truncated").value()).isEqualTo(true);
            assertThat(result.inventoryComplete()).isTrue();
        }
    }

    @Test
    void failedStatisticsConsumeSelectedNamesAndSchemaFailureHonorsNativeRemainingBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            f.databases = List.of("denied", "readable");
            f.properties.getLimits().setTables(3);
            f.capacity.put("denied", new MysqlMonitoringConnections.DatabaseCapacity(true, "denied", List.of("one", "two"), List.of(), false, MissingReason.UNAUTHORIZED));
            f.capacity.put("readable", db("readable", List.of(row("readable", "one", "InnoDB", "100", "40"))));
            CollectionResult result = f.capacity(0);
            assertThat(dbMetric(result, DATA, "denied").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(dbValue(result, DATA, "readable")).isEqualByComparingTo("100");
            verify(f.session).database("readable", 1);
            assertThat(result.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
        }
        try (Fixture f = new Fixture()) {
            f.databases = List.of("failed", "later");
            when(f.session.database(eq("failed"), anyInt())).thenThrow(new SQLException(SECRET, "22000"));
            when(f.session.remainingTableBudget()).thenReturn(0);
            CollectionResult result = f.capacity(0);
            assertThat(dbMetric(result, DATA, "failed").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            verify(f.session, never()).database(eq("later"), anyInt());
        }
    }

    @Test
    void authorizedSchemaFailureCanRemainLocalWhenNativeTableBudgetIsStillAvailable() throws Exception {
        try (Fixture f = new Fixture()) {
            f.databases = List.of("denied", "readable");
            when(f.session.database(eq("denied"), anyInt())).thenThrow(new SQLException(SECRET, "42000", 1044));
            CollectionResult result = f.capacity(0);
            assertThat(dbMetric(result, DATA, "denied").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(dbValue(result, DATA, "readable")).isEqualByComparingTo("0");
            verify(f.session).database("readable", 1000);
            assertSafe(result);
        }
    }

    @Test
    void scopeValidationDeduplicationAndHardLimitsBoundEveryCapacityRow() throws Exception {
        try (Fixture f = new Fixture()) {
            f.databases = List.of("owned", "owned", SECRET, "../outside");
            CollectionResult result = f.capacity(0);
            assertThat(value(result, "mysql.databases.configured")).isEqualByComparingTo("3");
            assertThat(value(result, "mysql.databases.requested")).isEqualByComparingTo("1");
            assertThat(dbMetric(result, DATA, "invalid-database#1").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            verify(f.session, times(1)).database(anyString(), anyInt());
            assertSafe(result);
        }
        try (Fixture f = new Fixture()) {
            f.properties.getLimits().setDatabases(100_000);
            f.properties.getLimits().setTables(100_000);
            f.databases = IntStream.range(0, 200).mapToObj(i -> "owned_" + i).toList();
            CollectionResult result = f.capacity(0);
            assertThat(result.metrics()).hasSize(409);
            assertThat(result.inventoryComplete()).isFalse();
            assertThat(value(result, "mysql.limits.databases")).isEqualByComparingTo("100");
            assertThat(value(result, "mysql.limits.tables")).isEqualByComparingTo("1000");
            verify(f.session, times(100)).database(anyString(), eq(1000));
            verify(f.session, never()).database(eq("owned_100"), anyInt());
        }
    }

    @Test
    void unexpectedSchemasTablesDuplicatesAndMalformedInventoryAreRejectedWithoutExposingValues() throws Exception {
        for (MysqlMonitoringConnections.DatabaseCapacity bad : List.of(
                new MysqlMonitoringConnections.DatabaseCapacity(true, "outside", List.of(), List.of(), false),
                new MysqlMonitoringConnections.DatabaseCapacity(true, "owned", List.of("one"), List.of(row("outside", "one", "InnoDB", "10", "2")), false),
                new MysqlMonitoringConnections.DatabaseCapacity(true, "owned", List.of("one"), List.of(row("owned", "outside", "InnoDB", "10", "2")), false),
                new MysqlMonitoringConnections.DatabaseCapacity(true, "owned", List.of("one", "one"), List.of(), false),
                new MysqlMonitoringConnections.DatabaseCapacity(true, "owned", List.of("one", "two"), List.of(row("owned", "one", "InnoDB", "10", "2"), row("owned", "one", "InnoDB", "10", "2")), false))) try (Fixture f = new Fixture()) {
            f.databases = List.of("owned");
            f.capacity.put("owned", bad);
            CollectionResult result = f.capacity(0);
            assertThat(dbMetric(result, DATA, "owned").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(dbMetric(result, INDEX, "owned").value()).isNull();
            assertThat(result.reason()).isEqualTo(MissingReason.INVALID_VALUE);
        }
    }

    @Test
    void caseOnlyCanonicalSchemaIsAcceptedButStatisticsRemainExactToItsNames() throws Exception {
        try (Fixture f = new Fixture()) {
            f.databases = List.of("OWNED");
            f.capacity.put("OWNED", db("owned", List.of(row("owned", "Table", "InnoDB", "10", "2"))));
            CollectionResult result = f.capacity(0);
            assertThat(dbValue(result, DATA, "OWNED")).isEqualByComparingTo("10");
            assertThat(dbMetric(result, DATA, "OWNED").definition().scope().id()).isEqualTo("OWNED");
        }
    }

    @Test
    void overBudgetNativeTableInventoryCannotAllocateExtraMetricsOrDispatchFurtherDatabases() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.getLimits().setTables(1);
            f.databases = List.of("oversized", "later");
            f.capacity.put("oversized", new MysqlMonitoringConnections.DatabaseCapacity(true, "oversized", List.of("one", "two"), List.of(), false));
            CollectionResult result = f.capacity(0);
            assertThat(dbMetric(result, DATA, "oversized").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            verify(f.session, never()).database(eq("later"), anyInt());
        }
    }

    @Test
    void capacityDeadlineStopsNextDatabaseAndDoesNotUpdateOrdinaryProbeOrCounters() throws Exception {
        try (Fixture f = new Fixture()) {
            CollectionResult ordinary = f.collect(0);
            f.databases = List.of("slow", "later");
            when(f.session.database(eq("slow"), anyInt())).thenAnswer(call -> { f.ticker.set(Duration.ofSeconds(5).toNanos()); return db("slow", List.of()); });
            CollectionRequest request = f.request(60_000, CollectionKind.CAPACITY);
            CollectionResult result = f.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.serviceProbe()).isNull();
            assertThat(dbMetric(result, DATA, "slow").value()).isNull();
            verify(f.session, never()).database(eq("later"), anyInt());
            assertThat(request.control().complete(request, result, f.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            assertThat(f.snapshots.snapshot("database").orElseThrow().serviceProbes().get("primary-db")).isEqualTo(ordinary.serviceProbe());
            assertThat(f.counters.seriesCount("database")).isEqualTo(3);
        }
    }

    @Test
    void capacityFailureRetainsItsPreviousSuccessAndRecoveryDoesNotRefreshOrdinarySamples() throws Exception {
        try (Fixture f = new Fixture()) {
            CollectionResult ordinary = f.collect(0);
            f.databases = List.of("owned");
            f.capacity.put("owned", db("owned", List.of(row("owned", "one", "InnoDB", "100", "20"))));
            f.capacity(0);
            f.capacity.put("owned", new MysqlMonitoringConnections.DatabaseCapacity(true, "owned", List.of("one"), List.of(), false, MissingReason.UNAUTHORIZED));
            f.capacity(60_000);
            var stored = f.snapshots.snapshot("database").orElseThrow().metrics().stream().filter(metric -> metric.latestAttempt().definition().key().equals(DATA)).findFirst().orElseThrow();
            assertThat(stored.lastSuccess().sampledAt()).isEqualTo(START);
            assertThat(stored.lastSuccess().validUntil()).isEqualTo(START.plusSeconds(180));
            assertThat(stored.latestAttempt().sampledAt()).isEqualTo(START.plusSeconds(60));
            f.capacity.put("owned", db("owned", List.of(row("owned", "one", "InnoDB", "150", "30"))));
            assertThat(dbValue(f.capacity(120_000), DATA, "owned")).isEqualByComparingTo("150");
            assertThat(f.snapshots.snapshot("database").orElseThrow().serviceProbes().get("primary-db")).isEqualTo(ordinary.serviceProbe());
            assertThat(f.counters.seriesCount("database")).isEqualTo(3);
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
    private static List<MetricSample> nativeOrdinary(CollectionResult result) { return result.metrics().stream().filter(sample -> !sample.definition().key().startsWith("mysql.process.")).toList(); }
    private static MetricSample dbMetric(CollectionResult result, String key, String database) { return result.metrics().stream().filter(sample -> sample.definition().key().equals(key) && sample.definition().scope().id().equals(database)).findFirst().orElseThrow(); }
    private static BigDecimal dbValue(CollectionResult result, String key, String database) { return (BigDecimal) dbMetric(result, key, database).value(); }
    private static MysqlMonitoringConnections.TableSize row(String schema, String table, String engine, String data, String index) { return new MysqlMonitoringConnections.TableSize(schema, table, engine, data, index); }
    private static MysqlMonitoringConnections.DatabaseCapacity db(String schema, List<MysqlMonitoringConnections.TableSize> rows) { return new MysqlMonitoringConnections.DatabaseCapacity(true, schema, rows.stream().map(MysqlMonitoringConnections.TableSize::table).toList(), rows, false); }

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
                "Questions", "100", "Slow_queries", "8", "Uptime", "1000", "Innodb_buffer_pool_bytes_data", "16384",
                "Innodb_buffer_pool_reads", "10", "Innodb_buffer_pool_read_requests", "100"));
        final Map<String, String> variables = new HashMap<>(Map.of("max_connections", "151", "server_uuid", UUID, "innodb_buffer_pool_size", "65536"));
        final Map<String, MysqlMonitoringConnections.DatabaseCapacity> capacity = new HashMap<>();
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 20);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 5000, 4);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "mysql-adapter-unit-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        final List<CollectionControl> controls = new ArrayList<>();
        final MysqlMonitoringAdapter adapter = new MysqlMonitoringAdapter(properties, connections, clock);
        List<String> databases = List.of();
        long sequence;

        Fixture() throws SQLException {
            when(connections.open(any())).thenReturn(session);
            when(session.globalStatus()).thenAnswer(call -> Map.copyOf(status));
            when(session.globalVariables()).thenAnswer(call -> Map.copyOf(variables));
            when(session.database(anyString(), anyInt())).thenAnswer(call -> {
                String name = call.getArgument(0);
                return capacity.getOrDefault(name, db(name, List.of()));
            });
            when(session.remainingTableBudget()).thenReturn(MysqlMonitoringConnections.MAX_TABLES);
            snapshots.activate("database", 1);
        }

        CollectionRequest request(long millis, CollectionKind kind) {
            clock.now = START.plusMillis(millis);
            CollectionControl control = new CollectionControl(new Object(), () -> true, clock, ticker::get,
                    ticker.get() + Duration.ofSeconds(5).toNanos(), cleanup, counters,
                    "database", "primary-db", kind, kind == CollectionKind.CAPACITY ? Duration.ofSeconds(60) : Duration.ofSeconds(15), borrowed);
            controls.add(control);
            return new CollectionRequest("database", "dataSource", "primary-db", kind, 1, sequence++,
                    clock.now, clock.now.plusSeconds(5), new MonitoringTarget.Scope(databases, List.of(), List.of(), List.of(), List.of()),
                    borrowed, Map.of("url", SECRET), control);
        }

        CollectionResult collect(long millis) {
            CollectionRequest request = request(millis, CollectionKind.ORDINARY);
            CollectionResult result = adapter.collect(request);
            assertThat(request.control().complete(request, result, snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.ACCEPTED);
            return result;
        }

        CollectionResult capacity(long millis) {
            CollectionRequest request = request(millis, CollectionKind.CAPACITY);
            CollectionResult result = adapter.collect(request);
            assertThat(request.control().complete(request, result, snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.ACCEPTED);
            return result;
        }
        void readCounters(String physical, String logical, String uptime) {
            status.put("Innodb_buffer_pool_reads", physical);
            status.put("Innodb_buffer_pool_read_requests", logical);
            status.put("Uptime", uptime);
        }

        @Override public void close() throws InterruptedException {
            controls.forEach(control -> control.cancel(MissingReason.FAILED));
            cleanup.shutdownNow();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
