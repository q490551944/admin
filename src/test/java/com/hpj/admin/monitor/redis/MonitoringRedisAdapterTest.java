package com.hpj.admin.monitor.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
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
import java.util.stream.Collectors;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Exercises the actual adapter and accepted snapshot/counter lifecycle; only native replies are substituted. */
class MonitoringRedisAdapterTest {
    private static final Instant START = Instant.parse("2026-09-15T01:00:00Z");
    private static final String RUN_ID = "a0b1c2d3e4f5678901234567890123456789abcd";
    private static final String SECRET = "redis://private-redis:6379/0?password=secret-sentinel";
    private static final String RATE = "redis.commands.rate";
    private static final String HIT = "redis.keyspace.hit.percent";
    private static final String HITS = "redis.keyspace.hits.delta";
    private static final List<String> DERIVED = List.of(RATE, HITS, "redis.keyspace.misses.delta", HIT,
            "redis.keys.evicted.delta", "redis.keys.expired.delta");
    private static final List<String> SECTIONS = List.of("server", "clients", "stats", "persistence", "replication");

    @Test
    void firstSampleExposesFixedNativeInventoryAndWaitsOnlyForDerivedCounters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.collect(0);
            assertThat(fixture.adapter.type()).isEqualTo(MiddlewareType.REDIS);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(result.inventoryComplete()).isTrue();
            assertThat(result.metrics()).hasSize(21);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().kind()).containsOnly(ScopeKind.PROCESS);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().id()).containsOnly("cache");
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.serviceProbe().reason()).isNull();
            assertThat(value(result, "redis.probe.duration")).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(metric(result, "redis.probe.duration").definition().unit()).isEqualTo(Unit.MILLISECONDS);
            assertThat(value(result, "redis.clients.connected")).isEqualByComparingTo("4");
            assertThat(value(result, "redis.clients.blocked")).isEqualByComparingTo("2");
            assertThat(metric(result, "redis.role").value()).isEqualTo("master");
            assertThat(value(result, "redis.keyspace.hits.total")).isEqualByComparingTo("80");
            assertThat(value(result, "redis.keyspace.misses.total")).isEqualByComparingTo("20");
            assertThat(value(result, "redis.keys.evicted.total")).isEqualByComparingTo("2");
            assertThat(value(result, "redis.keys.expired.total")).isEqualByComparingTo("8");
            assertThat(metric(result, "redis.persistence.loading").value()).isEqualTo(false);
            assertThat(metric(result, "redis.rdb.bgsave.in_progress").value()).isEqualTo(false);
            assertThat(metric(result, "redis.rdb.last_bgsave.status").value()).isEqualTo("ok");
            assertThat(metric(result, "redis.aof.enabled").value()).isEqualTo(true);
            assertThat(metric(result, "redis.aof.rewrite.in_progress").value()).isEqualTo(false);
            assertThat(metric(result, "redis.aof.last_bgrewrite.status").value()).isEqualTo("err");
            assertThat(metric(result, "redis.aof.last_write.status").value()).isEqualTo("ok");
            assertThat(metric(result, RATE).definition().unit()).isEqualTo(Unit.COUNT_PER_SECOND);
            assertThat(metric(result, HIT).definition().unit()).isEqualTo(Unit.PERCENT);
            assertThat(metric(result, HITS).definition().unit()).isEqualTo(Unit.COUNT);
            DERIVED.forEach(key -> assertThat(metric(result, key).missingReason()).as(key).isEqualTo(MissingReason.WAITING_SAMPLE));
            assertThat(metric(result, "redis.clients.connected").validUntil()).isEqualTo(START.plusSeconds(45));
            assertThat(fixture.counters.seriesCount("cache")).isEqualTo(6);
            var ordered = inOrder(fixture.session);
            ordered.verify(fixture.session).ping();
            for (String section : SECTIONS) ordered.verify(fixture.session).info(section);
            ordered.verify(fixture.session).close();
            verifyNoMoreInteractions(fixture.session);
            assertSafe(result);
        }
    }

    @Test
    void commandRateUsesActualFractionalTimeAndHitRateUsesOnlyIntervalLookups() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.stats.putAll(Map.of("total_commands_processed", "145", "keyspace_hits", "83", "keyspace_misses", "21",
                    "evicted_keys", "4", "expired_keys", "11", "instantaneous_ops_per_sec", "999999"));
            CollectionResult result = fixture.collect(22_500);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(result.reason()).isNull();
            assertThat(value(result, RATE)).isEqualByComparingTo("2");
            assertThat(value(result, HITS)).isEqualByComparingTo("3");
            assertThat(value(result, "redis.keyspace.misses.delta")).isEqualByComparingTo("1");
            assertThat(value(result, HIT)).isEqualByComparingTo("75");
            assertThat(value(result, "redis.keys.evicted.delta")).isEqualByComparingTo("2");
            assertThat(value(result, "redis.keys.expired.delta")).isEqualByComparingTo("3");
            assertThat(value(result, "redis.keys.expired.total")).isEqualByComparingTo("11");
            assertThat(metric(result, RATE).sampledAt()).isEqualTo(START.plusMillis(22_500));
        }
    }

    @Test
    void noLookupRequestsHasExplicitReasonWhileUnchangedCountersKeepNumericZero() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            CollectionResult result = fixture.collect(15_000);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.NO_REQUESTS);
            assertThat(metric(result, HIT).missingReason()).isEqualTo(MissingReason.NO_REQUESTS);
            assertThat(metric(result, HIT).value()).isNull();
            for (String key : DERIVED) if (!key.equals(HIT)) assertThat(value(result, key)).as(key).isEqualByComparingTo("0");
            fixture.stats.put("keyspace_misses", "22");
            CollectionResult missed = fixture.collect(30_000);
            assertThat(value(missed, HIT)).isEqualByComparingTo("0");
            assertThat(missed.status()).isEqualTo(CollectionStatus.SUCCESS);
            fixture.stats.put("keyspace_hits", "84");
            CollectionResult hit = fixture.collect(45_000);
            assertThat(value(hit, HIT)).isEqualByComparingTo("100");
        }
    }

    @Test
    void resetToZeroReplacesBaselinesAndNextSampleUsesTheResetCounters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.stats.replaceAll((key, ignored) -> "0");
            CollectionResult reset = fixture.collect(15_000);
            DERIVED.forEach(key -> assertThat(metric(reset, key).missingReason()).as(key).isEqualTo(MissingReason.WAITING_SAMPLE));
            assertThat(value(reset, "redis.keyspace.hits.total")).isEqualByComparingTo("0");
            fixture.stats.put("total_commands_processed", "30");
            fixture.stats.put("keyspace_hits", "3");
            fixture.stats.put("keyspace_misses", "1");
            CollectionResult next = fixture.collect(30_000);
            assertThat(value(next, RATE)).isEqualByComparingTo("2");
            assertThat(value(next, HITS)).isEqualByComparingTo("3");
            assertThat(value(next, HIT)).isEqualByComparingTo("75");
            assertThat(value(next, "redis.keys.expired.delta")).isEqualByComparingTo("0");
        }
    }

    @Test
    void resettingOneLookupCounterCannotProduceANegativeOrFabricatedHitPercentage() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.stats.put("keyspace_misses", "0");
            fixture.stats.put("keyspace_hits", "90");
            CollectionResult result = fixture.collect(15_000);
            assertThat(metric(result, HIT).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(metric(result, "redis.keyspace.misses.delta").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(value(result, HITS)).isEqualByComparingTo("10");
        }
    }

    @Test
    void changedRunIdResetsEvenRisingCountersWithoutPublishingTheIdentity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.server.put("run_id", "f".repeat(40));
            fixture.stats.replaceAll((key, value) -> "1000");
            CollectionResult moved = fixture.collect(15_000);
            DERIVED.forEach(key -> assertThat(metric(moved, key).missingReason()).as(key).isEqualTo(MissingReason.WAITING_SAMPLE));
            assertThat(value(moved, "redis.keyspace.hits.total")).isEqualByComparingTo("1000");
            assertThat(json(moved)).doesNotContain(RUN_ID, fixture.server.get("run_id"), SECRET);
        }
    }

    @Test
    void oldGapsReplaceBaselinesAndSameTimestampCannotReplaceTheAcceptedCounter() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.stats.put("total_commands_processed", "130");
            CollectionResult sameTime = fixture.collect(0);
            assertThat(metric(sameTime, RATE).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            fixture.stats.put("total_commands_processed", "160");
            assertThat(value(fixture.collect(15_000), RATE)).isEqualByComparingTo("4");
            fixture.stats.put("total_commands_processed", "200");
            CollectionResult gap = fixture.collect(60_001);
            DERIVED.forEach(key -> assertThat(metric(gap, key).missingReason()).as(key).isEqualTo(MissingReason.WAITING_SAMPLE));
            fixture.stats.put("total_commands_processed", "230");
            assertThat(value(fixture.collect(75_001), RATE)).isEqualByComparingTo("2");
        }
    }

    @Test
    void absentRunIdLeavesEveryNativeGaugeAndTotalReadable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.server.remove("run_id");
            CollectionResult result = fixture.collect(0);
            DERIVED.forEach(key -> assertThat(metric(result, key).missingReason()).as(key).isEqualTo(MissingReason.UNSUPPORTED));
            assertThat(value(result, "redis.keyspace.hits.total")).isEqualByComparingTo("80");
            assertThat(value(result, "redis.clients.connected")).isEqualByComparingTo("4");
            assertThat(metric(result, "redis.role").value()).isEqualTo("master");
            assertThat(fixture.counters.seriesCount("cache")).isZero();
        }
    }

    @Test
    void missingAndInvalidIndividualFieldsPreserveOtherSeriesAndThePreviousGoodBaseline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.clients.remove("blocked_clients");
            fixture.stats.put("keyspace_hits", SECRET);
            fixture.stats.put("total_commands_processed", "130");
            fixture.persistence.remove("aof_last_write_status");
            CollectionResult result = fixture.collect(15_000);
            assertThat(metric(result, "redis.clients.blocked").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(metric(result, "redis.keyspace.hits.total").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, HITS).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, HIT).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, "redis.aof.last_write.status").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(value(result, RATE)).isEqualByComparingTo("2");
            fixture.stats.put("keyspace_hits", "86");
            fixture.stats.put("keyspace_misses", "22");
            CollectionResult next = fixture.collect(30_000);
            assertThat(value(next, HITS)).isEqualByComparingTo("6");
            assertThat(value(next, HIT)).isEqualByComparingTo("75");
            assertSafe(result);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "+1", "NaN", "Infinity", "1e3", "1.5", " 1", "1 ", "", "123456789012345678901"})
    void nativeCountersRejectInvalidNumbersWithoutInventingZeros(String bad) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.stats.put("keyspace_hits", bad);
            CollectionResult result = fixture.collect(0);
            assertThat(metric(result, "redis.keyspace.hits.total").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, HITS).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, HIT).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(value(result, "redis.keyspace.misses.total")).isEqualByComparingTo("20");
        }
    }

    @Test
    void onlyWhitelistedNativeTextAndFlagsCanReachThePublicSnapshot() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.replication.put("role", SECRET);
            fixture.persistence.put("loading", "2");
            fixture.persistence.put("rdb_last_bgsave_status", SECRET);
            fixture.persistence.put("aof_last_write_status", "OK");
            fixture.server.put("run_id", SECRET);
            fixture.stats.put("sensitive_unknown_field", SECRET);
            CollectionResult result = fixture.collect(0);
            for (String key : List.of("redis.role", "redis.persistence.loading", "redis.rdb.last_bgsave.status",
                    "redis.aof.last_write.status", RATE)) {
                assertThat(metric(result, key).missingReason()).as(key).isEqualTo(MissingReason.INVALID_VALUE);
            }
            assertSafe(result);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"master", "slave", "replica"})
    void documentedRoleTokensRemainSafeFixedText(String role) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.replication.put("role", role);
            fixture.persistence.put("loading", "1");
            fixture.persistence.put("aof_enabled", "0");
            CollectionResult result = fixture.collect(0);
            assertThat(metric(result, "redis.role").value()).isEqualTo(role);
            assertThat(metric(result, "redis.persistence.loading").value()).isEqualTo(true);
            assertThat(metric(result, "redis.aof.enabled").value()).isEqualTo(false);
            assertThat(metric(result, "redis.aof.last_write.status").value()).isEqualTo("ok");
        }
    }

    @Test
    void duplicateFieldsAreInvalidOnlyForThatFieldAndUnknownInfoTextIsDiscarded() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.info("clients")).thenReturn("# Clients\r\nconnected_clients:4\r\nconnected_clients:5\r\nblocked_clients:2\r\nprivate:" + SECRET + "\r\n");
            CollectionResult result = fixture.collect(0);
            assertThat(metric(result, "redis.clients.connected").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(value(result, "redis.clients.blocked")).isEqualByComparingTo("2");
            assertSafe(result);
        }
    }

    @Test
    void nullAndOversizedInfoRepliesAreBoundedSectionFailures() throws Exception {
        for (String response : new String[] {null, "x".repeat(262_145), "\nx".repeat(4097)}) {
            try (Fixture fixture = new Fixture()) {
                when(fixture.session.info("clients")).thenReturn(response);
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, "redis.clients.connected").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(metric(result, "redis.clients.blocked").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(value(result, "redis.keyspace.hits.total")).isEqualByComparingTo("80");
                assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            }
        }
    }

    @Test
    void partialInfoDenialPreservesSuccessfulProbeAndLaterReadableSections() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.info("stats")).thenThrow(new RedisMonitoringConnections.Failure(MissingReason.UNAUTHORIZED, false));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, RATE).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(value(result, "redis.clients.connected")).isEqualByComparingTo("4");
            assertThat(metric(result, "redis.role").value()).isEqualTo("master");
            assertThat(metric(result, "redis.rdb.last_bgsave.status").value()).isEqualTo("ok");
            for (String section : SECTIONS) verify(fixture.session).info(section);
            assertSafe(result);
        }
    }

    @Test
    void serverSectionDenialPreventsDerivedMetricsWithoutErasingNativeTotals() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.info("server")).thenThrow(new RedisMonitoringConnections.Failure(MissingReason.UNAUTHORIZED, false));
            CollectionResult result = fixture.collect(0);
            DERIVED.forEach(key -> assertThat(metric(result, key).missingReason()).as(key).isEqualTo(MissingReason.UNAUTHORIZED));
            assertThat(value(result, "redis.keyspace.hits.total")).isEqualByComparingTo("80");
            assertThat(value(result, "redis.keys.expired.total")).isEqualByComparingTo("8");
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        }
    }

    @Test
    void pingOnlyPermissionKeepsServiceAvailableWhenEveryInfoSectionIsDenied() throws Exception {
        try (Fixture fixture = new Fixture()) {
            doThrow(new RedisMonitoringConnections.Failure(MissingReason.UNAUTHORIZED, false)).when(fixture.session).info(anyString());
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.metrics().subList(1, 21)).extracting(MetricSample::missingReason).containsOnly(MissingReason.UNAUTHORIZED);
            assertThat(value(result, "redis.probe.duration")).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            for (String section : SECTIONS) verify(fixture.session).info(section);
        }
    }

    @Test
    void aSectionTimeoutDoesNotOverwriteEarlierSuccessfulServiceProbe() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.info("clients")).thenThrow(new RedisMonitoringConnections.Failure(MissingReason.TIMEOUT, true));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, "redis.clients.connected").missingReason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(value(result, "redis.keyspace.hits.total")).isEqualByComparingTo("80");
        }
    }

    @Test
    void failuresBeforePingDistinguishConnectionAuthenticationUnsupportedAndTimeout() throws Exception {
        List<MissingReason> reasons = List.of(MissingReason.FAILED, MissingReason.UNAUTHORIZED, MissingReason.UNSUPPORTED, MissingReason.TIMEOUT);
        List<CollectionStatus> statuses = List.of(CollectionStatus.FAILED, CollectionStatus.UNAUTHORIZED, CollectionStatus.UNSUPPORTED, CollectionStatus.FAILED);
        for (int index = 0; index < reasons.size(); index++) {
            try (Fixture fixture = new Fixture()) {
                when(fixture.connections.open(any())).thenThrow(new RedisMonitoringConnections.Failure(reasons.get(index), index == 0 || index == 3));
                CollectionResult result = fixture.collect(0);
                assertThat(result.status()).isEqualTo(statuses.get(index));
                assertThat(result.reason()).isEqualTo(reasons.get(index));
                assertThat(result.metrics()).extracting(MetricSample::missingReason).containsOnly(reasons.get(index));
                assertThat(result.serviceProbe().availability()).isEqualTo(index == 0 ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN);
                verifyNoInteractions(fixture.session);
                assertSafe(result);
            }
        }
    }

    @Test
    void unexpectedPingReplyIsNotAcceptedAsAvailabilityOrReflected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.ping()).thenReturn(SECRET);
            CollectionResult result = fixture.collect(0);
            assertThat(result.reason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            verify(fixture.session, never()).info(anyString());
            verify(fixture.session).close();
            assertSafe(result);
        }
    }

    @Test
    void exhaustedProbeDeadlineStopsInfoAndCannotPublishCounters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.ping()).thenAnswer(call -> { fixture.ticker.set(Duration.ofSeconds(5).toNanos()); return "PONG"; });
            CollectionRequest request = fixture.request(0, CollectionKind.ORDINARY);
            CollectionResult result = fixture.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.metrics()).extracting(MetricSample::missingReason).containsOnly(MissingReason.TIMEOUT);
            verify(fixture.session, never()).info(anyString());
            verify(fixture.session).close();
            assertThat(request.control().complete(request, result, fixture.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
        }
    }

    @Test
    void exhaustedInfoDeadlineStopsLaterSectionsAndRetainsAlreadyReadNativeValues() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.info("stats")).thenAnswer(call -> { fixture.ticker.set(Duration.ofSeconds(5).toNanos()); return Fixture.info(fixture.stats); });
            CollectionRequest request = fixture.request(0, CollectionKind.ORDINARY);
            CollectionResult result = fixture.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(value(result, "redis.clients.connected")).isEqualByComparingTo("4");
            assertThat(metric(result, RATE).missingReason()).isEqualTo(MissingReason.TIMEOUT);
            verify(fixture.session, never()).info("persistence");
            verify(fixture.session, never()).info("replication");
            assertThat(request.control().complete(request, result, fixture.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
        }
    }

    @Test
    void anUnpublishedAttemptCannotBecomeTheNextCounterBaseline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionRequest unpublished = fixture.request(0, CollectionKind.ORDINARY);
            fixture.adapter.collect(unpublished);
            assertThat(fixture.counters.seriesCount("cache")).isZero();
            fixture.stats.put("total_commands_processed", "130");
            assertThat(metric(fixture.collect(15_000), RATE).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            fixture.stats.put("total_commands_processed", "160");
            assertThat(value(fixture.collect(30_000), RATE)).isEqualByComparingTo("2");
        }
    }

    @Test
    void capacityHasNoInventoryOrProbeAndNeverOpensAConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.adapter.collect(fixture.request(0, CollectionKind.CAPACITY));
            assertThat(result.status()).isEqualTo(CollectionStatus.UNSUPPORTED);
            assertThat(result.reason()).isEqualTo(MissingReason.NOT_APPLICABLE);
            assertThat(result.inventoryComplete()).isTrue();
            assertThat(result.metrics()).isEmpty();
            assertThat(result.serviceProbe()).isNull();
            verifyNoInteractions(fixture.connections, fixture.session);
        }
    }

    private static void assertSafe(CollectionResult result) throws Exception {
        assertThat(json(result)).doesNotContain(SECRET, "private-redis", "secret-sentinel", "stackTrace", RUN_ID);
    }

    private static String json(CollectionResult result) throws Exception {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
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
            @Override public String toString() { throw new AssertionError("Borrowed Redis client must never be inspected or closed"); }
        };
        final MonitoringProperties properties = new MonitoringProperties();
        final RedisMonitoringConnections connections = mock(RedisMonitoringConnections.class);
        final RedisMonitoringConnections.Session session = mock(RedisMonitoringConnections.Session.class);
        final Map<String, String> server = new HashMap<>(Map.of("run_id", RUN_ID));
        final Map<String, String> clients = new HashMap<>(Map.of("connected_clients", "4", "blocked_clients", "2"));
        final Map<String, String> stats = new HashMap<>(Map.of("total_commands_processed", "100", "keyspace_hits", "80",
                "keyspace_misses", "20", "evicted_keys", "2", "expired_keys", "8"));
        final Map<String, String> persistence = new HashMap<>(Map.of("loading", "0", "rdb_bgsave_in_progress", "0",
                "rdb_last_bgsave_status", "ok", "aof_enabled", "1", "aof_rewrite_in_progress", "0",
                "aof_last_bgrewrite_status", "err", "aof_last_write_status", "ok"));
        final Map<String, String> replication = new HashMap<>(Map.of("role", "master"));
        final Map<String, Map<String, String>> sections = Map.of("server", server, "clients", clients, "stats", stats,
                "persistence", persistence, "replication", replication);
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 40);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 40, 4);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "redis-adapter-unit-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        final List<CollectionControl> controls = new ArrayList<>();
        final RedisMonitoringAdapter adapter = new RedisMonitoringAdapter(properties, connections, clock);
        long sequence;

        Fixture() {
            when(connections.open(any())).thenReturn(session);
            when(session.ping()).thenReturn("PONG");
            when(session.info(anyString())).thenAnswer(call -> info(sections.get(call.getArgument(0))));
            snapshots.activate("cache", 1);
        }

        static String info(Map<String, String> fields) {
            return "# Section\r\n" + fields.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue())
                    .collect(Collectors.joining("\r\n", "", "\r\n"));
        }

        CollectionRequest request(long millis, CollectionKind kind) {
            clock.now = START.plusMillis(millis);
            CollectionControl control = new CollectionControl(new Object(), () -> true, clock, ticker::get,
                    ticker.get() + Duration.ofSeconds(5).toNanos(), cleanup, counters,
                    "cache", "primary-cache", kind, Duration.ofSeconds(15), borrowed);
            controls.add(control);
            return new CollectionRequest("cache", "redisTemplate", "primary-cache", kind, 1, sequence++,
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
