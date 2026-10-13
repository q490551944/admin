package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.connection.StandardConnectionInspector;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.mongodb.MongoMonitoringAdapter;
import com.hpj.admin.monitor.mongodb.MongoMonitoringConnections;
import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.internal.MongoClientImpl;
import com.mongodb.connection.ClusterConnectionMode;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * Opt-in owned MongoDB 6.0.11 tests: real standalone metadata, SCRAM authentication and native authorization.
 * Replica/mongos roles, malformed responses, restart/reset and unavailable sources use separate contract tests;
 * this disposable standalone fixture does not claim to exercise those real deployment topologies.
 */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "mongodb")
class MonitoringMongoAdapterIntegrationTest {
    private static final List<String> OPERATIONS = List.of("insert", "query", "update", "delete", "getmore", "command");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeSingleNodeMetricsAndActualIntervalRatesPreserveBusinessDataAndClient() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("mongodb");
             MongoClient observer = environment.mongoClient();
             MongoClient business = source(environment, environment.username(), environment.password());
             Collector collector = new Collector()) {
            environment.prepareData();
            assertPing(business);
            BusinessState before = state(observer, environment.resourceName());
            Map<String, Long> writesBefore = writeCounters(observer);
            MongoClientSettings settings = ((MongoClientImpl) business).getSettings();
            ResolvedTarget target = resolve(business, environment.resourceName());

            CollectionResult first = collector.collect(target);
            Observation firstNative = collector.connections.observations.get(0);
            assertNative(first, firstNative);
            assertThat(first.metrics()).hasSize(23);
            assertThat(first.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(metric(first, "mongodb.cpu.percent").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            for (String operation : OPERATIONS) {
                assertThat(metric(first, rateKey(operation)).missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
            }

            // Read an owned sentinel through the already-live business client; the monitoring collector never writes.
            for (int read = 0; read < 3; read++) {
                assertThat(business.getDatabase(environment.resourceName()).getCollection("fixture")
                        .find(new Document("_id", "fixture")).first()).isNotNull();
            }
            CollectionResult second = collector.collect(target);
            Observation secondNative = collector.connections.observations.get(1);
            assertNative(second, secondNative);
            assertThat(secondNative.nodeIdentity).isEqualTo(firstNative.nodeIdentity).isNotBlank();
            assertThat(secondNative.hello.getDocument("topologyVersion").getObjectId("processId"))
                    .isEqualTo(firstNative.hello.getDocument("topologyVersion").getObjectId("processId"));
            for (String operation : OPERATIONS) {
                MetricSample previous = metric(first, rateKey(operation));
                MetricSample current = metric(second, rateKey(operation));
                long delta = counter(secondNative.status, operation) - counter(firstNative.status, operation);
                long elapsedNanos = Duration.between(previous.sampledAt(), current.sampledAt()).toNanos();
                assertThat(delta).isNotNegative();
                assertThat(elapsedNanos).isPositive();
                BigDecimal expected = BigDecimal.valueOf(delta).divide(BigDecimal.valueOf(elapsedNanos, 9), MathContext.DECIMAL64);
                assertThat((BigDecimal) current.value()).isCloseTo(expected, within(new BigDecimal("0.000001")));
                assertThat(current.definition().unit()).isEqualTo(Unit.COUNT_PER_SECOND);
            }
            assertThat(counter(secondNative.status, "query")).isGreaterThan(counter(firstNative.status, "query"));
            assertThat(writeCounters(observer)).isEqualTo(writesBefore);
            assertThat(state(observer, environment.resourceName())).isEqualTo(before);
            environment.verifyData();
            assertThat(((MongoClientImpl) business).getSettings()).isSameAs(settings);
            assertPing(business);
            assertThat(collector.connections.closed).isEqualTo(2);
            assertSafe(first, environment);
            assertSafe(second, environment);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realReadOnlyUserCanPingAndReadRoleWhileServerStatusMetricsDegradeIndividually() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("mongodb");
             MongoClient observer = environment.mongoClient();
             Collector collector = new Collector()) {
            environment.prepareData();
            String username = "reader_" + UUID.randomUUID().toString().replace("-", "");
            String password = UUID.randomUUID().toString();
            observer.getDatabase("admin").runCommand(new Document("createUser", username).append("pwd", password)
                    .append("roles", List.of(new Document("role", "read").append("db", environment.resourceName()))));
            try (MongoClient business = source(environment, username, password)) {
                assertPing(business);
                BusinessState before = state(observer, environment.resourceName());
                Map<String, Long> writesBefore = writeCounters(observer);
                CollectionResult result = collector.collect(resolve(business, environment.resourceName()));
                Observation nativeRead = collector.connections.observations.get(0);
                assertThat(nativeRead.commands).containsExactly("ping", "hello", "serverStatus");
                assertThat(nativeRead.ping.getNumber("ok").doubleValue()).isEqualTo(1);
                assertThat(nativeRead.hello.getBoolean("isWritablePrimary").getValue()).isTrue();
                assertThat(nativeRead.status).isNull();
                assertThat(nativeRead.statusFailure).isEqualTo(MissingReason.UNAUTHORIZED);
                assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
                assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
                assertThat(metric(result, "mongodb.probe.duration").value()).isInstanceOf(BigDecimal.class);
                assertThat(metric(result, "mongodb.process.role").value()).isEqualTo("standalone");
                assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo("mongod");
                assertThat(metric(result, "mongodb.cpu.percent").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
                for (String key : List.of("mongodb.connections.current", "mongodb.connections.available")) {
                    assertThat(metric(result, key).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
                }
                for (String operation : OPERATIONS) {
                    assertThat(metric(result, totalKey(operation)).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
                    assertThat(metric(result, rateKey(operation)).missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
                }
                assertThat(writeCounters(observer)).isEqualTo(writesBefore);
                assertThat(state(observer, environment.resourceName())).isEqualTo(before);
                assertThat(business.getDatabase(environment.resourceName()).getCollection("fixture")
                        .find(new Document("_id", "fixture")).first()).isNotNull();
                assertPing(business);
                assertThat(collector.connections.closed).isEqualTo(1);
                assertSafe(result, environment);
                assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(result)).doesNotContain(username, password);
            } finally {
                observer.getDatabase("admin").runCommand(new Document("dropUser", username));
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeCapacityQueriesOnlyTwoConfiguredDatabasesAndReportsOneSharedFilesystemWithoutWrites() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("mongodb");
             MongoClient observer = environment.mongoClient();
             MongoClient business = source(environment, environment.username(), environment.password());
             Collector collector = new Collector()) {
            environment.prepareData();
            String second = environment.resourceName() + "_second";
            String excluded = environment.resourceName() + "_excluded";
            for (String database : List.of(second, excluded)) observer.getDatabase(database).getCollection("fixture")
                    .insertOne(new Document("_id", "capacity-owned").append("sentinel", "unchanged"));
            List<String> configured = List.of(environment.resourceName(), second);
            Map<String, BusinessState> before = new LinkedHashMap<>();
            for (String database : List.of(environment.resourceName(), second, excluded)) before.put(database, state(observer, database));
            Map<String, Long> writes = writeCounters(observer);
            var settings = ((MongoClientImpl) business).getSettings();
            ResolvedTarget target = resolve(business, configured);
            CollectionResult ordinary = collector.collect(target);
            CollectionResult capacity = collector.collect(target, CollectionKind.CAPACITY);
            Observation nativeRead = collector.connections.observations.get(1);
            assertThat(nativeRead.commands).containsExactly("hello", "dbStats:" + configured.get(0), "dbStats:" + configured.get(1));
            assertThat(nativeRead.stats).containsOnlyKeys(configured.get(0), configured.get(1));
            assertThat(capacity.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(capacity.serviceProbe()).isNull();
            for (String database : configured) for (String field : List.of("dataSize", "storageSize", "indexSize")) {
                MetricSample sample = capacity.metrics().stream().filter(value -> value.definition().scope().id().equals(database)
                        && value.definition().key().equals("mongodb.database." + field + ".bytes")).findFirst().orElseThrow();
                assertThat((BigDecimal) sample.value()).isEqualByComparingTo(BigDecimal.valueOf(nativeRead.stats.get(database).getNumber(field).longValue()));
                assertThat(Duration.between(sample.sampledAt(), sample.validUntil())).isEqualTo(Duration.ofSeconds(180));
                assertThat(sample.definition().scope().nodeId()).startsWith("mongo-node-");
            }
            var fs = nativeRead.stats.get(configured.get(0));
            assertThat((BigDecimal) metric(capacity, "mongodb.filesystem.total.bytes").value())
                    .isEqualByComparingTo(BigDecimal.valueOf(fs.getNumber("fsTotalSize").longValue()));
            assertThat((BigDecimal) metric(capacity, "mongodb.filesystem.used.bytes").value())
                    .isEqualByComparingTo(BigDecimal.valueOf(fs.getNumber("fsUsedSize").longValue()));
            assertThat(capacity.metrics()).filteredOn(sample -> sample.definition().scope().kind() == ScopeKind.FILESYSTEM).hasSize(3);
            assertThat(collector.snapshots.snapshot("mongo-target").orElseThrow().metrics())
                    .filteredOn(stored -> stored.kind() == CollectionKind.ORDINARY && stored.latestAttempt().definition().key().equals("mongodb.memory.resident.bytes"))
                    .singleElement().satisfies(stored -> assertThat(stored.lastSuccess().sampledAt()).isEqualTo(metric(ordinary, "mongodb.memory.resident.bytes").sampledAt()));
            assertThat(writeCounters(observer)).isEqualTo(writes);
            for (var entry : before.entrySet()) assertThat(state(observer, entry.getKey())).isEqualTo(entry.getValue());
            assertThat(((MongoClientImpl) business).getSettings()).isSameAs(settings);
            assertThat(collector.connections.closed).isEqualTo(2);
            environment.verifyData(); assertPing(business); assertSafe(capacity, environment);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeDatabaseReadPermissionKeepsAllowedDbStatsWhileOtherDatabaseIsDenied() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("mongodb");
             MongoClient observer = environment.mongoClient(); Collector collector = new Collector()) {
            environment.prepareData();
            String denied = environment.resourceName() + "_denied";
            observer.getDatabase(denied).getCollection("fixture").insertOne(new Document("_id", "owned"));
            String username = "stats_" + UUID.randomUUID().toString().replace("-", ""), password = UUID.randomUUID().toString();
            observer.getDatabase("admin").runCommand(new Document("createUser", username).append("pwd", password)
                    .append("roles", List.of(new Document("role", "read").append("db", environment.resourceName()))));
            try (MongoClient business = source(environment, username, password)) {
                BusinessState beforeAllowed = state(observer, environment.resourceName()), beforeDenied = state(observer, denied);
                Map<String, Long> writes = writeCounters(observer);
                CollectionResult result = collector.collect(resolve(business, List.of(environment.resourceName(), denied)), CollectionKind.CAPACITY);
                Observation nativeRead = collector.connections.observations.get(0);
                assertThat(nativeRead.stats).containsOnlyKeys(environment.resourceName());
                assertThat(nativeRead.statsFailures).containsEntry(denied, MissingReason.UNAUTHORIZED);
                assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
                assertThat(result.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
                assertThat(result.serviceProbe()).isNull();
                assertThat(result.metrics()).filteredOn(sample -> sample.definition().scope().id().equals(denied))
                        .hasSize(3).allSatisfy(sample -> assertThat(sample.missingReason()).isEqualTo(MissingReason.UNAUTHORIZED));
                assertThat(writeCounters(observer)).isEqualTo(writes);
                assertThat(state(observer, environment.resourceName())).isEqualTo(beforeAllowed);
                assertThat(state(observer, denied)).isEqualTo(beforeDenied);
                assertThat(collector.connections.closed).isEqualTo(1);
                assertSafe(result, environment);
            } finally { observer.getDatabase("admin").runCommand(new Document("dropUser", username)); }
        }
    }

    private static MongoClient source(MonitoringTestEnvironment environment, String username, String password) {
        return MongoClients.create(MongoClientSettings.builder()
                .credential(MongoCredential.createCredential(username, "admin", password.toCharArray()))
                .applyToClusterSettings(settings -> settings.mode(ClusterConnectionMode.SINGLE)
                        .hosts(List.of(new ServerAddress(environment.host(), environment.port())))
                        .serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(settings -> settings.connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS))
                .build());
    }

    private static ResolvedTarget resolve(MongoClient source, String database) {
        return resolve(source, List.of(database));
    }
    private static ResolvedTarget resolve(MongoClient source, List<String> databases) {
        var declaration = new MonitoringProperties.Target();
        declaration.setId("mongo-target");
        declaration.setType(MiddlewareType.MONGODB);
        declaration.setEnabled(true);
        declaration.setConnectionSource("businessMongo");
        declaration.getScope().setDatabases(databases);
        var properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(List.of(declaration));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("businessMongo", source);
        var resolved = new MonitoringConnectionResolver(properties, beans, List.of(new StandardConnectionInspector())).resolve();
        assertThat(resolved).hasSize(1);
        ResolvedTarget target = resolved.get(0);
        assertThat(target.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
        assertThat(target.bindings().get(0).scope().databases()).containsExactlyElementsOf(databases);
        var connection = target.bindings().get(0).connection();
        assertThat(connection.client()).isSameAs(source);
        var settings = (MongoClientSettings) connection.settings().get("mongoSettings");
        assertThat(settings).isSameAs(((MongoClientImpl) source).getSettings());
        assertThat(settings.getClusterSettings().getMode()).isEqualTo(ClusterConnectionMode.SINGLE);
        assertThat(settings.getClusterSettings().getHosts()).hasSize(1);
        assertThat(settings.getClusterSettings().getSrvHost()).isNull();
        return target;
    }

    private static void assertNative(CollectionResult result, Observation nativeRead) {
        assertThat(nativeRead.commands).containsExactly("ping", "hello", "serverStatus");
        assertThat(nativeRead.ping.getNumber("ok").doubleValue()).isEqualTo(1);
        assertThat(nativeRead.hello.getBoolean("isWritablePrimary").getValue()).isTrue();
        assertThat(nativeRead.hello.containsKey("setName")).isFalse();
        assertThat(nativeRead.statusFailure).isNull();
        assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        assertThat((BigDecimal) metric(result, "mongodb.probe.duration").value()).isNotNegative();
        assertThat(metric(result, "mongodb.probe.duration").definition().unit()).isEqualTo(Unit.MILLISECONDS);
        assertThat(metric(result, "mongodb.process.kind").value()).isEqualTo(nativeRead.status.getString("process").getValue()).isEqualTo("mongod");
        assertThat(metric(result, "mongodb.process.role").value()).isEqualTo("standalone");
        for (String connection : List.of("current", "available")) {
            assertThat((BigDecimal) metric(result, "mongodb.connections." + connection).value())
                    .isEqualByComparingTo(BigDecimal.valueOf(nativeRead.status.getDocument("connections").getNumber(connection).longValue()));
        }
        for (String operation : OPERATIONS) {
            assertThat((BigDecimal) metric(result, totalKey(operation)).value())
                    .isEqualByComparingTo(BigDecimal.valueOf(counter(nativeRead.status, operation)));
        }
        assertThat(result.metrics().subList(0, 18)).allSatisfy(sample -> {
            assertThat(sample.definition().scope().kind()).isEqualTo(ScopeKind.PROCESS);
            assertThat(sample.definition().scope().id()).isEqualTo("mongo-target");
        });
        assertThat((BigDecimal) metric(result, "mongodb.memory.resident.bytes").value())
                .isEqualByComparingTo(BigDecimal.valueOf(nativeRead.status.getDocument("mem").getNumber("resident").longValue()).multiply(BigDecimal.valueOf(1_048_576)));
        var cache = nativeRead.status.getDocument("wiredTiger").getDocument("cache");
        assertThat((BigDecimal) metric(result, "mongodb.wiredtiger.cache.used.bytes").value())
                .isEqualByComparingTo(BigDecimal.valueOf(cache.getNumber("bytes currently in the cache").longValue()));
        assertThat((BigDecimal) metric(result, "mongodb.wiredtiger.cache.maximum.bytes").value())
                .isEqualByComparingTo(BigDecimal.valueOf(cache.getNumber("maximum bytes configured").longValue()));
        assertThat(metric(result, "mongodb.memory.resident.bytes").definition().scope().nodeId())
                .startsWith("mongo-node-").isEqualTo(metric(result, "mongodb.wiredtiger.cache.used.bytes").definition().scope().nodeId());
    }

    private static void assertPing(MongoClient client) {
        assertThat(((Number) client.getDatabase("admin").runCommand(new Document("ping", 1)).get("ok")).doubleValue()).isEqualTo(1);
    }

    private static BusinessState state(MongoClient client, String database) {
        var db = client.getDatabase(database);
        List<String> names = db.listCollectionNames().into(new ArrayList<>()).stream().sorted().toList();
        Map<String, List<Document>> documents = new TreeMap<>();
        for (String name : names) documents.put(name, db.getCollection(name).find().sort(new Document("_id", 1)).into(new ArrayList<>()));
        return new BusinessState(names, documents);
    }

    private static Map<String, Long> writeCounters(MongoClient observer) {
        Document counters = observer.getDatabase("admin").runCommand(new Document("serverStatus", 1)).get("opcounters", Document.class);
        Map<String, Long> result = new LinkedHashMap<>();
        for (String key : List.of("insert", "update", "delete")) result.put(key, ((Number) counters.get(key)).longValue());
        return result;
    }

    private static long counter(BsonDocument status, String operation) { return status.getDocument("opcounters").getNumber(operation).longValue(); }
    private static String rateKey(String operation) { return "mongodb.operations." + operation + ".rate"; }
    private static String totalKey(String operation) { return "mongodb.operations." + operation + ".total"; }
    private static MetricSample metric(CollectionResult result, String key) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)).findFirst().orElseThrow();
    }
    private static void assertSafe(CollectionResult result, MonitoringTestEnvironment environment) throws Exception {
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(result))
                .doesNotContain(environment.password(), environment.host() + ":" + environment.port(), "mongodb://", "stackTrace");
    }
    private record BusinessState(List<String> collections, Map<String, List<Document>> documents) { }

    private static final class Observation {
        final List<String> commands = new ArrayList<>();
        BsonDocument ping;
        BsonDocument hello;
        BsonDocument status;
        String nodeIdentity;
        MissingReason statusFailure;
        final Map<String, BsonDocument> stats = new LinkedHashMap<>();
        final Map<String, MissingReason> statsFailures = new LinkedHashMap<>();
    }

    private static final class RecordingConnections extends MongoMonitoringConnections {
        final List<Observation> observations = new ArrayList<>();
        int closed;
        @Override public Session open(CollectionRequest request) {
            Session delegate = super.open(request);
            Observation observation = new Observation();
            observations.add(observation);
            return new Session() {
                @Override public BsonDocument ping() {
                    observation.commands.add("ping");
                    observation.ping = delegate.ping();
                    return observation.ping;
                }
                @Override public BsonDocument hello() {
                    observation.commands.add("hello");
                    observation.hello = delegate.hello();
                    return observation.hello;
                }
                @Override public BsonDocument serverStatus() {
                    observation.commands.add("serverStatus");
                    try { observation.status = delegate.serverStatus(); return observation.status; }
                    catch (Failure failure) { observation.statusFailure = failure.reason(); throw failure; }
                }
                @Override public String nodeIdentity() { observation.nodeIdentity = delegate.nodeIdentity(); return observation.nodeIdentity; }
                @Override public BsonDocument dbStats(String database) {
                    observation.commands.add("dbStats:" + database);
                    try { BsonDocument stats = delegate.dbStats(database); observation.stats.put(database, stats); return stats; }
                    catch (Failure failure) { observation.statsFailures.put(database, failure.reason()); throw failure; }
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
        final MongoMonitoringAdapter adapter = new MongoMonitoringAdapter(properties, connections, clock);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        long sequence;

        CollectionResult collect(ResolvedTarget target) {
            return collect(target, CollectionKind.ORDINARY);
        }
        CollectionResult collect(ResolvedTarget target, CollectionKind kind) {
            var binding = target.bindings().get(0);
            var source = binding.connection();
            snapshots.activate(target.display().id(), 1);
            Instant scheduled = clock.instant();
            Duration timeout = properties.getCollectionTimeout();
            var control = new CollectionControl(new Object(), () -> true, clock, System::nanoTime,
                    System.nanoTime() + timeout.toNanos(), cleanup, counters, target.display().id(), source.source(),
                    kind, kind == CollectionKind.CAPACITY ? properties.getCapacityInterval() : properties.getOrdinaryInterval(), source.client());
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
