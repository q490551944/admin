package com.hpj.admin.monitor.mongodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import org.bson.*;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
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

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual adapter and atomic snapshot/counter lifecycle; only same-connection native BSON replies are substituted. */
class MonitoringMongoAdapterTest {
    private static final Instant START = Instant.parse("2026-09-15T10:00:00Z");
    private static final ObjectId PROCESS_ID = new ObjectId("0123456789abcdef01234567");
    private static final String NODE_ID = "opaque-mongo-node-sentinel";
    private static final String SECRET = "mongodb://private-user:secret-sentinel@private-mongo/admin";
    private static final List<String> OPERATIONS = List.of("insert", "query", "update", "delete", "getmore", "command");

    @Test
    void firstSampleExposesExactlyEighteenProcessMetricsWithoutClaimingCpuOrClusterTotals() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.collect(0);
            assertThat(fixture.adapter.type()).isEqualTo(MiddlewareType.MONGODB);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(result.inventoryComplete()).isTrue();
            assertThat(result.metrics()).hasSize(18);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().kind()).containsOnly(ScopeKind.PROCESS);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().id()).containsOnly("mongo-target");
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().nodeId()).containsOnlyNulls();
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.serviceProbe().reason()).isNull();
            assertThat(number(result, "mongodb.probe.duration")).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(metric(result, "mongodb.probe.duration").definition().unit()).isEqualTo(Unit.MILLISECONDS);
            assertThat(number(result, "mongodb.connections.current")).isEqualByComparingTo("4");
            assertThat(number(result, "mongodb.connections.available")).isEqualByComparingTo("100");
            assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo("mongod");
            assertThat(metric(result, "mongodb.process.role").value()).isEqualTo("standalone");
            assertThat(metric(result, "mongodb.cpu.percent").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(metric(result, "mongodb.cpu.percent").value()).isNull();
            for (String operation : OPERATIONS) {
                assertThat(number(result, total(operation))).isEqualByComparingTo(fixture.counter(operation));
                assertThat(metric(result, rate(operation)).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
                assertThat(metric(result, rate(operation)).definition().unit()).isEqualTo(Unit.COUNT_PER_SECOND);
            }
            assertThat(metric(result, "mongodb.connections.current").validUntil()).isEqualTo(START.plusSeconds(45));
            assertThat(fixture.counters.seriesCount("mongo-target")).isEqualTo(6);
            var ordered = inOrder(fixture.session);
            ordered.verify(fixture.session).nodeIdentity();
            ordered.verify(fixture.session).ping();
            ordered.verify(fixture.session).hello();
            ordered.verify(fixture.session).serverStatus();
            ordered.verify(fixture.session).close();
            verifyNoMoreInteractions(fixture.session);
            assertSafe(result);
        }
    }

    @Test
    void allSixRatesUseActualFractionalSecondsAndTheNativeCounterClassification() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            for (int index = 0; index < OPERATIONS.size(); index++) fixture.addCounter(OPERATIONS.get(index), (index + 1) * 45);
            fixture.status.put("uptimeMillis", new BsonInt64(1_022_500));
            CollectionResult result = fixture.collect(22_500);
            for (int index = 0; index < OPERATIONS.size(); index++) {
                assertThat(number(result, rate(OPERATIONS.get(index)))).isEqualByComparingTo(Integer.toString((index + 1) * 2));
            }
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNSUPPORTED); // CPU is deliberately unavailable.
            assertThat(metric(result, rate("insert")).sampledAt()).isEqualTo(START.plusMillis(22_500));
        }
    }

    @Test
    void zeroCountersAndAvailableConnectionsRemainRealNumericZero() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.status.getDocument("connections").put("current", new BsonInt32(0));
            fixture.status.getDocument("connections").put("available", new BsonInt32(0));
            OPERATIONS.forEach(operation -> fixture.setCounter(operation, 0));
            fixture.collect(0);
            CollectionResult result = fixture.collect(15_000);
            assertThat(number(result, "mongodb.connections.current")).isEqualByComparingTo("0");
            assertThat(number(result, "mongodb.connections.available")).isEqualByComparingTo("0");
            for (String operation : OPERATIONS) assertThat(number(result, rate(operation))).isEqualByComparingTo("0");
        }
    }

    @Test
    void aSingleCounterResetInvalidatesOnlyItsOwnRateAndUsesTheResetBaselineNextTime() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.setCounter("insert", 0);
            fixture.addCounter("query", 30);
            CollectionResult reset = fixture.collect(15_000);
            assertThat(number(reset, total("insert"))).isEqualByComparingTo("0");
            assertThat(metric(reset, rate("insert")).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            assertThat(number(reset, rate("query"))).isEqualByComparingTo("2");
            fixture.setCounter("insert", 15);
            assertThat(number(fixture.collect(30_000), rate("insert"))).isEqualByComparingTo("1");
        }
    }

    @Test
    void lowerUptimeInvalidatesEvenCountersWhichAlreadyPassedThePreviousSample() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            OPERATIONS.forEach(operation -> fixture.addCounter(operation, 1000));
            fixture.status.put("uptimeMillis", new BsonInt64(5000));
            CollectionResult restarted = fixture.collect(15_000);
            for (String operation : OPERATIONS) assertThat(metric(restarted, rate(operation)).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            fixture.status.put("uptimeMillis", new BsonInt64(20_000));
            OPERATIONS.forEach(operation -> fixture.addCounter(operation, 30));
            CollectionResult next = fixture.collect(30_000);
            for (String operation : OPERATIONS) assertThat(number(next, rate(operation))).isEqualByComparingTo("2");
        }
    }

    @Test
    void changedProcessOrNodeIdentityResetsRatesWithoutPublishingEitherIdentity() throws Exception {
        for (boolean changeProcess : List.of(true, false)) {
            try (Fixture fixture = new Fixture()) {
                fixture.collect(0);
                if (changeProcess) fixture.hello.getDocument("topologyVersion").put("processId", new BsonObjectId(new ObjectId("fedcba9876543210fedcba98")));
                else fixture.nodeIdentity = "other-opaque-node";
                OPERATIONS.forEach(operation -> fixture.addCounter(operation, 1000));
                CollectionResult changed = fixture.collect(15_000);
                for (String operation : OPERATIONS) assertThat(metric(changed, rate(operation)).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
                assertSafe(changed);
                assertThat(json(changed)).doesNotContain("other-opaque-node", "fedcba9876543210fedcba98");
            }
        }
    }

    @Test
    void connectionIdsAndTopologyCountersMayChangeWithoutResettingTheSameProcessCounters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.hello.put("connectionId", new BsonInt64(1));
            fixture.collect(0);
            fixture.hello.put("connectionId", new BsonInt64(99));
            fixture.hello.getDocument("topologyVersion").put("counter", new BsonInt64(99));
            fixture.hello.put("electionId", new BsonObjectId(new ObjectId()));
            fixture.addCounter("query", 30);
            assertThat(number(fixture.collect(15_000), rate("query"))).isEqualByComparingTo("2");
        }
    }

    @Test
    void missingProcessTokenNodeIdentityOrUptimeOnlyDegradesRates() throws Exception {
        for (String missing : List.of("processId", "nodeIdentity", "uptimeMillis")) {
            try (Fixture fixture = new Fixture()) {
                if (missing.equals("processId")) fixture.hello.getDocument("topologyVersion").remove("processId");
                if (missing.equals("nodeIdentity")) fixture.nodeIdentity = null;
                if (missing.equals("uptimeMillis")) fixture.status.remove("uptimeMillis");
                CollectionResult result = fixture.collect(0);
                for (String operation : OPERATIONS) {
                    assertThat(metric(result, rate(operation)).missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
                    assertThat(number(result, total(operation))).isEqualByComparingTo(fixture.counter(operation));
                }
                assertThat(number(result, "mongodb.connections.current")).isEqualByComparingTo("4");
                assertThat(fixture.counters.seriesCount("mongo-target")).isZero();
            }
        }
    }

    @Test
    void mongosWithoutTopologyVersionKeepsRouterRoleProbeAndNativeTotalsButCannotInventRates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.hello.remove("topologyVersion");
            fixture.hello.put("msg", new BsonString("isdbgrid"));
            fixture.status.put("process", new BsonString("mongos"));
            CollectionResult result = fixture.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo("mongos");
            assertThat(metric(result, "mongodb.process.role").value()).isEqualTo("router");
            assertThat(number(result, "mongodb.connections.current")).isEqualByComparingTo("4");
            for (String operation : OPERATIONS) {
                assertThat(metric(result, rate(operation)).missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
                assertThat(number(result, total(operation))).isEqualByComparingTo(fixture.counter(operation));
            }
            assertThat(fixture.counters.seriesCount("mongo-target")).isZero();
        }
    }

    @Test
    void missingAndMalformedFieldsPreserveOtherMetricsAndDoNotAdvanceAGoodBaseline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.status.getDocument("connections").remove("available");
            fixture.status.getDocument("opcounters").put("insert", new BsonString(SECRET));
            fixture.addCounter("query", 30);
            CollectionResult result = fixture.collect(15_000);
            assertThat(metric(result, "mongodb.connections.available").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(metric(result, total("insert")).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, rate("insert")).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(number(result, rate("query"))).isEqualByComparingTo("2");
            fixture.setCounter("insert", 160);
            assertThat(number(fixture.collect(30_000), rate("insert"))).isEqualByComparingTo("2");
            assertSafe(result);
        }
    }

    @Test
    void integerDecodingPreservesInt64PrecisionAndRejectsUnsafeOrNonIntegerBsonValues() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.status.getDocument("opcounters").put("insert", new BsonInt64(Long.MAX_VALUE));
            fixture.status.getDocument("connections").put("available", new BsonDouble(42.0));
            CollectionResult result = fixture.collect(0);
            assertThat(number(result, total("insert"))).isEqualByComparingTo("9223372036854775807");
            assertThat(number(result, "mongodb.connections.available")).isEqualByComparingTo("42");
        }
        for (BsonValue invalid : List.of(new BsonInt32(-1), new BsonInt64(-1), new BsonDouble(-1), new BsonDouble(1.5),
                new BsonDouble(Double.NaN), new BsonDouble(Double.POSITIVE_INFINITY), new BsonDouble(9_007_199_254_740_992D),
                new BsonDecimal128(Decimal128.parse("1")), new BsonString("1"), BsonBoolean.TRUE, BsonNull.VALUE, new BsonDocument())) {
            try (Fixture fixture = new Fixture()) {
                fixture.status.getDocument("opcounters").put("insert", invalid);
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, total("insert")).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(metric(result, rate("insert")).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(number(result, total("query"))).isEqualByComparingTo("40");
            }
        }
    }

    @Test
    void ratesRemainExactWhenTheNativeInt64CounterIsAboveTheDoubleIntegerRange() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setCounter("query", 9_007_199_254_740_993L);
            fixture.collect(0);
            fixture.addCounter("query", 45);
            CollectionResult result = fixture.collect(22_500);
            assertThat(number(result, total("query"))).isEqualByComparingTo("9007199254741038");
            assertThat(number(result, rate("query"))).isEqualByComparingTo("2");
        }
    }

    @Test
    void nestedTypeErrorsAndMalformedProcessIdsRemainLocalAndPrivate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.status.put("connections", new BsonString(SECRET));
            fixture.hello.getDocument("topologyVersion").put("processId", new BsonString(SECRET));
            CollectionResult result = fixture.collect(0);
            assertThat(metric(result, "mongodb.connections.current").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, "mongodb.connections.available").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            for (String operation : OPERATIONS) assertThat(metric(result, rate(operation)).missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(number(result, total("query"))).isEqualByComparingTo("40");
            assertSafe(result);
        }
    }

    @Test
    void nativeRoleSignalsDistinguishReplicaRolesFromWritableStandaloneAndRouter() throws Exception {
        Map<String, BsonDocument> roles = Map.of(
                "standalone", new BsonDocument("isWritablePrimary", BsonBoolean.TRUE),
                "replica-primary", new BsonDocument("isWritablePrimary", BsonBoolean.TRUE).append("setName", new BsonString("private-rs")),
                "replica-secondary", new BsonDocument("isWritablePrimary", BsonBoolean.FALSE).append("secondary", BsonBoolean.TRUE).append("setName", new BsonString("private-rs")),
                "replica-arbiter", new BsonDocument("isWritablePrimary", BsonBoolean.FALSE).append("arbiterOnly", BsonBoolean.TRUE),
                "replica-other", new BsonDocument("isWritablePrimary", BsonBoolean.FALSE).append("setName", new BsonString("private-rs")),
                "router", new BsonDocument("isWritablePrimary", BsonBoolean.TRUE).append("msg", new BsonString("isdbgrid")));
        for (var entry : roles.entrySet()) {
            try (Fixture fixture = new Fixture()) {
                fixture.hello.remove("isWritablePrimary");
                fixture.hello.putAll(entry.getValue());
                fixture.status.put("process", new BsonString(entry.getKey().equals("router") ? "mongos" : "mongod"));
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, "mongodb.process.role").value()).isEqualTo(entry.getKey());
                assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo(entry.getKey().equals("router") ? "mongos" : "mongod");
                assertSafe(result);
                assertThat(json(result)).doesNotContain("private-rs");
            }
        }
    }

    @Test
    void contradictoryOrMalformedRoleEvidenceIsNotPublishedAsKnownHealthyState() throws Exception {
        List<BsonDocument> invalid = List.of(new BsonDocument("secondary", BsonBoolean.TRUE),
                new BsonDocument("isWritablePrimary", new BsonString(SECRET)), new BsonDocument("setName", new BsonString("")),
                new BsonDocument("msg", new BsonString(SECRET)), new BsonDocument("arbiterOnly", new BsonInt32(1)));
        for (BsonDocument document : invalid) {
            try (Fixture fixture = new Fixture()) {
                fixture.hello.putAll(document);
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, "mongodb.process.role").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo("mongod");
                assertSafe(result);
            }
        }
        for (String process : List.of("mongos", "C:\\private-installation\\mongos.exe")) {
            try (Fixture fixture = new Fixture()) {
                fixture.status.put("process", new BsonString(process)); // One connection cannot be standalone and router.
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, "mongodb.process.role").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(metric(result, "mongodb.process.kind").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(json(result)).doesNotContain("private-installation");
            }
        }
    }

    @Test
    void nativeExecutableBasenamesIdentifyWindowsAndUnixProcessesWithoutPublishingPaths() throws Exception {
        for (String kind : List.of("mongod", "mongos")) {
            for (String binary : List.of(kind + ".exe", "C:\\private-installation\\" + kind + ".exe",
                    "/private-installation/" + kind, "C:/private-installation/" + kind + ".exe")) {
                try (Fixture fixture = new Fixture()) {
                    fixture.status.put("process", new BsonString(binary));
                    when(fixture.session.hello()).thenThrow(new MongoMonitoringConnections.Failure(MissingReason.UNAUTHORIZED, false));
                    CollectionResult result = fixture.collect(0);
                    assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo(kind);
                    assertThat(metric(result, "mongodb.process.role").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
                    assertThat(json(result)).doesNotContain("private-installation", ".exe");
                    assertSafe(result);
                }
            }
        }
    }

    @Test
    void renamedExecutableRequiresIndependentHelloEvidenceAndNeverBecomesPublicText() throws Exception {
        for (boolean router : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                fixture.status.put("process", new BsonString(SECRET));
                if (router) fixture.hello.put("msg", new BsonString("isdbgrid"));
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo(router ? "mongos" : "mongod");
                assertThat(metric(result, "mongodb.process.role").value()).isEqualTo(router ? "router" : "standalone");
                assertSafe(result);
                fixture.hello.remove("msg");
                fixture.hello.remove("isWritablePrimary");
                assertThat(metric(fixture.collect(15_000), "mongodb.process.kind").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            }
        }
    }

    @Test
    void malformedOrUnboundedExecutableValuesDoNotOverrideIndependentRoleOrLeakText() throws Exception {
        for (BsonValue binary : List.of(new BsonInt32(1), new BsonString(""), new BsonString("mongod\n"),
                new BsonString("x".repeat(4097)))) {
            try (Fixture fixture = new Fixture()) {
                fixture.status.put("process", binary);
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, "mongodb.process.kind").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(metric(result, "mongodb.process.role").value()).isEqualTo("standalone");
                assertThat(number(result, "mongodb.connections.current")).isEqualByComparingTo("4");
                assertSafe(result);
            }
        }
    }

    @Test
    void missingRoleEvidenceAndUnknownProcessTextHaveExplicitReasonsWithoutLeakingText() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.hello.remove("isWritablePrimary");
            CollectionResult unknownRole = fixture.collect(0);
            assertThat(metric(unknownRole, "mongodb.process.role").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(metric(unknownRole, "mongodb.process.kind").value()).isEqualTo("mongod");
            fixture.status.put("process", new BsonString(SECRET));
            CollectionResult unknownKind = fixture.collect(15_000);
            assertThat(metric(unknownKind, "mongodb.process.kind").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertSafe(unknownKind);
        }
    }

    @Test
    void serverStatusPermissionDenialPreservesPingAndHelloRoleAndKind() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.serverStatus()).thenThrow(new MongoMonitoringConnections.Failure(MissingReason.UNAUTHORIZED, false));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo("mongod");
            assertThat(metric(result, "mongodb.process.role").value()).isEqualTo("standalone");
            assertThat(metric(result, "mongodb.connections.current").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(metric(result, "mongodb.connections.available").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            for (String operation : OPERATIONS) {
                assertThat(metric(result, total(operation)).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
                assertThat(metric(result, rate(operation)).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            }
            assertSafe(result);
        }
    }

    @Test
    void helloPermissionDenialDoesNotHideReadableServerStatusButPreventsUnprovenRateIdentity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.hello()).thenThrow(new MongoMonitoringConnections.Failure(MissingReason.UNAUTHORIZED, false));
            CollectionResult result = fixture.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, "mongodb.process.role").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo("mongod");
            assertThat(number(result, "mongodb.connections.current")).isEqualByComparingTo("4");
            assertThat(number(result, total("query"))).isEqualByComparingTo("40");
            assertThat(metric(result, rate("query")).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            verify(fixture.session).serverStatus();
        }
    }

    @Test
    void timeoutAfterPingCannotEraseItsSuccessfulServiceObservation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.serverStatus()).thenThrow(new MongoMonitoringConnections.Failure(MissingReason.TIMEOUT, true));
            CollectionResult result = fixture.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(metric(result, "mongodb.connections.current").missingReason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(metric(result, "mongodb.process.role").value()).isEqualTo("standalone");
        }
    }

    @Test
    void openingFailuresDistinguishAuthenticationConfigurationTransportAndTimeout() throws Exception {
        List<MissingReason> reasons = List.of(MissingReason.FAILED, MissingReason.UNAUTHORIZED, MissingReason.UNSUPPORTED, MissingReason.TIMEOUT);
        List<CollectionStatus> statuses = List.of(CollectionStatus.FAILED, CollectionStatus.UNAUTHORIZED, CollectionStatus.UNSUPPORTED, CollectionStatus.FAILED);
        for (int index = 0; index < reasons.size(); index++) {
            try (Fixture fixture = new Fixture()) {
                when(fixture.connections.open(any())).thenThrow(new MongoMonitoringConnections.Failure(reasons.get(index), index == 0));
                CollectionResult result = fixture.collect(0);
                assertThat(result.status()).isEqualTo(statuses.get(index));
                assertThat(result.reason()).isEqualTo(reasons.get(index));
                assertThat(result.serviceProbe().availability()).isEqualTo(index == 0 ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN);
                assertThat(metric(result, "mongodb.connections.current").missingReason()).isEqualTo(reasons.get(index));
                verifyNoInteractions(fixture.session);
                assertSafe(result);
            }
        }
    }

    @Test
    void missingOrMalformedNativeOkIsNotAValidProbeAndItsPayloadIsNotReflected() throws Exception {
        for (BsonDocument invalid : new BsonDocument[] {null, new BsonDocument(), new BsonDocument("ok", new BsonDouble(Double.NaN)),
                new BsonDocument("ok", new BsonInt32(0)).append("errmsg", new BsonString(SECRET))}) {
            try (Fixture fixture = new Fixture()) {
                when(fixture.session.ping()).thenReturn(invalid);
                CollectionResult result = fixture.collect(0);
                assertThat(result.reason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
                verify(fixture.session, never()).hello();
                verify(fixture.session, never()).serverStatus();
                verify(fixture.session).close();
                assertSafe(result);
            }
        }
    }

    @Test
    void nonDocumentNativeStatusAndUnknownBsonPayloadsNeverReachThePublicResult() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.status.put("unknown-secret-field", new BsonString(SECRET));
            fixture.hello.put("hosts", new BsonArray(List.of(new BsonString(SECRET))));
            assertSafe(fixture.collect(0));
            when(fixture.session.serverStatus()).thenReturn(null);
            CollectionResult invalid = fixture.collect(15_000);
            assertThat(metric(invalid, "mongodb.connections.current").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(invalid.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        }
    }

    @Test
    void sameTimestampKeepsThePreviousBaselineAndOverlongIntervalsStartFresh() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.addCounter("query", 30);
            assertThat(metric(fixture.collect(0), rate("query")).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            fixture.addCounter("query", 30);
            assertThat(number(fixture.collect(15_000), rate("query"))).isEqualByComparingTo("4");
            fixture.addCounter("query", 30);
            assertThat(metric(fixture.collect(60_001), rate("query")).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            fixture.addCounter("query", 30);
            assertThat(number(fixture.collect(75_001), rate("query"))).isEqualByComparingTo("2");
        }
    }

    @Test
    void unpublishedSamplesDoNotAdvanceAnyCounterBaseline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.adapter.collect(fixture.request(0, CollectionKind.ORDINARY));
            assertThat(fixture.counters.seriesCount("mongo-target")).isZero();
            fixture.addCounter("query", 30);
            assertThat(metric(fixture.collect(15_000), rate("query")).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            fixture.addCounter("query", 30);
            assertThat(number(fixture.collect(30_000), rate("query"))).isEqualByComparingTo("2");
        }
    }

    @Test
    void exhaustedPingDeadlineStopsAllFollowingCommandsAndCannotPublishCounters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.ping()).thenAnswer(call -> { fixture.ticker.set(Duration.ofSeconds(5).toNanos()); return ok(); });
            CollectionRequest request = fixture.request(0, CollectionKind.ORDINARY);
            CollectionResult result = fixture.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            verify(fixture.session, never()).hello();
            verify(fixture.session, never()).serverStatus();
            verify(fixture.session).close();
            assertThat(request.control().complete(request, result, fixture.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            assertThat(fixture.counters.seriesCount("mongo-target")).isZero();
        }
    }

    @Test
    void exhaustedHelloDeadlineRetainsPingButDoesNotRunServerStatus() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.hello()).thenAnswer(call -> { fixture.ticker.set(Duration.ofSeconds(5).toNanos()); return fixture.hello.clone(); });
            CollectionRequest request = fixture.request(0, CollectionKind.ORDINARY);
            CollectionResult result = fixture.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            verify(fixture.session, never()).serverStatus();
            assertThat(request.control().complete(request, result, fixture.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
        }
    }

    @Test
    void aCancelledLateStatusReplyCannotReplaceTheLastAcceptedCounterSample() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.addCounter("query", 90);
            CollectionRequest cancelled = fixture.request(15_000, CollectionKind.ORDINARY);
            when(fixture.session.serverStatus()).thenAnswer(call -> {
                fixture.clock.now = START.plusSeconds(21);
                cancelled.control().cancel(MissingReason.TIMEOUT);
                return fixture.status.clone();
            });
            CollectionResult late = fixture.adapter.collect(cancelled);
            assertThat(late.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(cancelled.control().complete(cancelled, late, fixture.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            when(fixture.session.serverStatus()).thenAnswer(call -> fixture.status.clone());
            fixture.addCounter("query", 30);
            assertThat(number(fixture.collect(30_000), rate("query"))).isEqualByComparingTo("4");
        }
    }

    @Test
    void databaseScopeNeverAddsDiscoveryOrExtraCommandsAndCapacityIsDeferred() throws Exception {
        for (List<String> databases : List.of(List.<String>of(), List.of("allowed-one", "allowed-two"))) {
            try (Fixture fixture = new Fixture()) {
                fixture.databases = databases;
                fixture.collect(0);
                verify(fixture.session).nodeIdentity();
                verify(fixture.session).ping();
                verify(fixture.session).hello();
                verify(fixture.session).serverStatus();
                verify(fixture.session).close();
                verifyNoMoreInteractions(fixture.session);
            }
        }
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

    private static BsonDocument ok() { return new BsonDocument("ok", new BsonDouble(1)); }
    private static String total(String operation) { return "mongodb.operations." + operation + ".total"; }
    private static String rate(String operation) { return "mongodb.operations." + operation + ".rate"; }
    private static MetricSample metric(CollectionResult result, String key) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)).findFirst().orElseThrow();
    }
    private static BigDecimal number(CollectionResult result, String key) { return (BigDecimal) metric(result, key).value(); }
    private static String json(CollectionResult result) throws Exception { return new ObjectMapper().findAndRegisterModules().writeValueAsString(result); }
    private static void assertSafe(CollectionResult result) throws Exception {
        assertThat(json(result)).doesNotContain(SECRET, NODE_ID, PROCESS_ID.toHexString(), "private-mongo", "secret-sentinel", "stackTrace");
    }

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
            @Override public String toString() { throw new AssertionError("Borrowed MongoClient must never be inspected or closed"); }
        };
        final MonitoringProperties properties = new MonitoringProperties();
        final MongoMonitoringConnections connections = mock(MongoMonitoringConnections.class);
        final MongoMonitoringConnections.Session session = mock(MongoMonitoringConnections.Session.class);
        final BsonDocument hello = ok().append("isWritablePrimary", BsonBoolean.TRUE)
                .append("topologyVersion", new BsonDocument("processId", new BsonObjectId(PROCESS_ID)).append("counter", new BsonInt64(0)));
        final BsonDocument status = ok().append("process", new BsonString("mongod")).append("pid", new BsonInt32(1000))
                .append("uptimeMillis", new BsonInt64(1_000_000))
                .append("connections", new BsonDocument("current", new BsonInt32(4)).append("available", new BsonInt32(100)))
                .append("opcounters", new BsonDocument("insert", new BsonInt64(100)).append("query", new BsonInt64(40))
                        .append("update", new BsonInt64(30)).append("delete", new BsonInt64(20))
                        .append("getmore", new BsonInt64(10)).append("command", new BsonInt64(200)));
        String nodeIdentity = NODE_ID;
        List<String> databases = List.of("allowed-db");
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 40);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 40, 4);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "mongo-adapter-unit-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        final List<CollectionControl> controls = new ArrayList<>();
        final MongoMonitoringAdapter adapter = new MongoMonitoringAdapter(properties, connections, clock);
        long sequence;

        Fixture() {
            when(connections.open(any())).thenReturn(session);
            when(session.nodeIdentity()).thenAnswer(call -> nodeIdentity);
            when(session.ping()).thenReturn(ok());
            when(session.hello()).thenAnswer(call -> hello.clone());
            when(session.serverStatus()).thenAnswer(call -> status.clone());
            snapshots.activate("mongo-target", 1);
        }
        BigDecimal counter(String operation) { return BigDecimal.valueOf(status.getDocument("opcounters").getInt64(operation).getValue()); }
        void setCounter(String operation, long value) { status.getDocument("opcounters").put(operation, new BsonInt64(value)); }
        void addCounter(String operation, long value) { setCounter(operation, counter(operation).longValueExact() + value); }

        CollectionRequest request(long millis, CollectionKind kind) {
            clock.now = START.plusMillis(millis);
            CollectionControl control = new CollectionControl(new Object(), () -> true, clock, ticker::get,
                    ticker.get() + Duration.ofSeconds(5).toNanos(), cleanup, counters, "mongo-target", "mongo-binding",
                    kind, Duration.ofSeconds(15), borrowed);
            controls.add(control);
            return new CollectionRequest("mongo-target", "mongoClient", "mongo-binding", kind, 1, sequence++, clock.now,
                    clock.now.plusSeconds(5), new MonitoringTarget.Scope(databases, List.of(), List.of(), List.of(), List.of()),
                    borrowed, Map.of("private-settings", SECRET), control);
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
