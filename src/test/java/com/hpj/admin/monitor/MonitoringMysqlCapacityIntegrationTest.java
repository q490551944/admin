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
import com.hpj.admin.monitor.mysql.MysqlMonitoringAdapter;
import com.hpj.admin.monitor.mysql.MysqlMonitoringConnections;
import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
import static org.awaitility.Awaitility.await;

/** Fixed MySQL 8.0.36, real information_schema visibility, and independently checked read-only boundaries. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "mysql")
class MonitoringMysqlCapacityIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> WRITE_COUNTERS = List.of("Com_insert", "Com_insert_select", "Com_update", "Com_update_multi",
            "Com_delete", "Com_delete_multi", "Com_replace", "Com_replace_select", "Com_create_db", "Com_drop_db",
            "Com_create_table", "Com_alter_table", "Com_drop_table", "Com_truncate", "Com_analyze", "Com_optimize", "Com_repair",
            "Com_create_view", "Com_drop_view", "Com_grant", "Com_revoke");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeCapacityIncludesOnlyConfiguredBaseTablesAndProvesAuthorizedEmptyDatabase() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("mysql");
             Connection observer = environment.jdbcConnection();
             HikariDataSource source = source(environment, environment.username(), environment.password());
             Connection heldBusinessConnection = source.getConnection();
             Collector collector = new Collector(1000)) {
            Names names = prepare(environment, observer);
            Map<String, TableState> before = tableState(observer, List.of(names.data, names.excluded));
            Map<String, BigDecimal> writes = writeCounters(observer);
            Map<String, String> variables = statisticsConfiguration(observer);
            assertThat(source.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            ResolvedTarget target = resolve(source, List.of(names.data, names.empty));
            CollectionResult result = collector.collect(target, CollectionKind.CAPACITY);
            ReadObservation read = collector.last();
            assertThat(read.statusReads).isZero();
            assertThat(read.variableReads).isZero();
            assertThat(read.databases.keySet()).containsExactly(names.data, names.empty);
            assertThat(read.databases.get(names.data).selectedTables()).containsExactly("a_data", "b_indexed", "fixture");
            assertThat(read.databases.get(names.data).tables()).allSatisfy(table -> assertThat(table.engine()).isEqualTo("InnoDB"));
            assertThat(read.databases.get(names.data).tables()).noneSatisfy(table -> assertThat(table.table()).isEqualTo("visible_view"));
            assertNativeDatabase(result, read.databases.get(names.data), names.data);
            assertNativeDatabase(result, read.databases.get(names.empty), names.empty);
            assertCoverage(result, read, 2, 1000, false, true);
            assertThat(value(result, "database.data.bytes", names.empty)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(value(result, "database.index.bytes", names.empty)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.metrics()).noneSatisfy(sample -> assertThat(sample.definition().scope().id()).isEqualTo(names.excluded));
            assertThat(result.metrics()).noneSatisfy(sample -> assertThat(sample.definition().key()).startsWith("mysql.innodb."));
            assertThat(tableState(observer, List.of(names.data, names.excluded))).isEqualTo(before);
            assertWritesUnchanged(observer, writes);
            assertSafe(result, environment);

            // The ordinary and 60-second capacity inventories must coexist in the same accepted snapshot.
            CollectionResult ordinary = collector.collect(target, CollectionKind.ORDINARY);
            assertThat(collector.last().databases).isEmpty();
            assertThat(ordinary.metrics()).noneSatisfy(sample -> assertThat(sample.definition().key()).startsWith("mysql.database."));
            assertThat(collector.snapshots.snapshot("mysql-capacity").orElseThrow().metrics())
                    .anySatisfy(stored -> assertThat(stored.kind()).isEqualTo(CollectionKind.ORDINARY))
                    .anySatisfy(stored -> assertThat(stored.kind()).isEqualTo(CollectionKind.CAPACITY));
            assertThat(tableState(observer, List.of(names.data, names.excluded))).isEqualTo(before);
            assertWritesUnchanged(observer, writes);
            assertThat(source.isClosed()).isFalse();
            assertThat(heldBusinessConnection.isClosed()).isFalse();
            assertThat(source.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            assertThat(source.getHikariPoolMXBean().getTotalConnections()).isEqualTo(1);
            assertThat(statisticsConfiguration(observer)).isEqualTo(variables);
            environment.verifyData();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void singleTablePrivilegeShowsVisibleSubsetAndAnInvisibleSchemaNeverBecomesZero() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("mysql");
             Connection observer = environment.jdbcConnection();
             Collector collector = new Collector(1000)) {
            Names names = prepare(environment, observer);
            Credentials credentials = restrictedUser(observer, names);
            try (HikariDataSource source = source(environment, credentials.user, credentials.password);
                 Connection restricted = DriverManager.getConnection(environment.jdbcUrl(), credentials.user, credentials.password)) {
                assertThat(visibleTables(restricted, names.data)).containsExactly("a_data");
                assertThat(schemaVisible(restricted, names.empty)).isTrue();
                assertThat(visibleTables(restricted, names.empty)).isEmpty();
                assertThat(schemaVisible(restricted, names.excluded)).isFalse();
                Map<String, TableState> before = tableState(observer, List.of(names.data, names.excluded));
                Map<String, BigDecimal> writes = writeCounters(observer);
                CollectionResult result = collector.collect(resolve(source, List.of(names.data, names.empty, names.excluded)), CollectionKind.CAPACITY);
                ReadObservation read = collector.last();
                assertThat(read.databases.get(names.data).selectedTables()).containsExactly("a_data");
                assertThat(read.databases.get(names.data).tables()).hasSize(1);
                assertNativeDatabase(result, read.databases.get(names.data), names.data);
                assertNativeDatabase(result, read.databases.get(names.empty), names.empty);
                assertCoverage(result, read, 3, 1000, false, false);
                assertThat(read.databases.get(names.excluded).visible()).isFalse();
                for (String key : List.of("database.data.bytes", "database.index.bytes")) {
                    assertThat(metric(result, key, names.excluded).value()).isNull();
                    assertThat(metric(result, key, names.excluded).missingReason()).isNotNull();
                }
                assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
                // The visible subset must be described as such, never labelled the whole database's disk usage.
                for (String key : List.of("database.data.bytes", "database.index.bytes")) {
                    assertThat(metric(result, key, names.data).definition().calculation()).contains("可见");
                    assertThat(metric(result, key, names.data).definition().scope().kind()).isEqualTo(ScopeKind.DATABASE);
                }
                assertThat(tableState(observer, List.of(names.data, names.excluded))).isEqualTo(before);
                assertWritesUnchanged(observer, writes);
                assertSafe(result, environment);
                String serialized = JSON.writeValueAsString(result);
                assertThat(serialized.contains(credentials.user)).isFalse();
                assertThat(serialized.contains(credentials.password)).isFalse();
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void tableBudgetBoundsNativeSelectionAndDoesNotTurnUnvisitedDatabaseIntoAnEmptyOne() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("mysql");
             Connection observer = environment.jdbcConnection();
             HikariDataSource source = source(environment, environment.username(), environment.password());
             Collector collector = new Collector(1)) {
            Names names = prepare(environment, observer);
            Map<String, TableState> before = tableState(observer, List.of(names.data, names.excluded));
            Map<String, BigDecimal> writes = writeCounters(observer);
            CollectionResult result = collector.collect(resolve(source, List.of(names.data, names.excluded)), CollectionKind.CAPACITY);
            ReadObservation read = collector.last();
            assertThat(read.budgets).isNotEmpty().allSatisfy(budget -> assertThat(budget).isBetween(0, 1));
            assertThat(read.databases.get(names.data).selectedTables()).containsExactly("a_data");
            assertThat(read.databases.get(names.data).tables()).hasSize(1);
            assertThat(read.databases.get(names.data).truncated()).isTrue();
            assertThat(read.databases.values().stream().mapToInt(database -> database.tables().size()).sum()).isEqualTo(1);
            assertNativeDatabase(result, read.databases.get(names.data), names.data);
            assertCoverage(result, read, 2, 1, true, false);
            assertThat(metric(result, "database.coverage.complete", names.data).value()).isEqualTo(false);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(metric(result, "database.data.bytes", names.excluded).value()).isNull();
            assertThat(metric(result, "database.index.bytes", names.excluded).value()).isNull();
            assertThat(tableState(observer, List.of(names.data, names.excluded))).isEqualTo(before);
            assertWritesUnchanged(observer, writes);
            assertSafe(result, environment);
        }
    }

    private static Names prepare(MonitoringTestEnvironment environment, Connection observer) throws SQLException {
        environment.prepareData();
        Names names = new Names(environment.resourceName(), environment.resourceName() + "_empty", environment.resourceName() + "_excluded");
        try (var statement = observer.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + names.empty + "`");
            statement.executeUpdate("CREATE DATABASE `" + names.excluded + "`");
            for (String database : List.of(names.data, names.excluded)) {
                statement.executeUpdate("CREATE TABLE `" + database + "`.a_data (id INT PRIMARY KEY, payload VARCHAR(100)) ENGINE=InnoDB");
                statement.executeUpdate("INSERT INTO `" + database + "`.a_data VALUES (1,'owned-capacity-sentinel')");
                statement.executeUpdate("CREATE TABLE `" + database + "`.b_indexed (id INT PRIMARY KEY, payload VARCHAR(100), INDEX payload_index(payload)) ENGINE=InnoDB");
                statement.executeUpdate("INSERT INTO `" + database + "`.b_indexed VALUES (1,'owned-indexed-sentinel')");
                statement.executeUpdate("CREATE VIEW `" + database + "`.visible_view AS SELECT id,payload FROM `" + database + "`.a_data");
            }
        }
        return names;
    }

    private static Credentials restrictedUser(Connection observer, Names names) throws SQLException {
        Credentials credentials = new Credentials("mon" + UUID.randomUUID().toString().replace("-", "").substring(0, 17),
                UUID.randomUUID().toString().replace("-", ""));
        try (var statement = observer.createStatement()) {
            statement.executeUpdate("CREATE USER '" + credentials.user + "'@'%' IDENTIFIED BY '" + credentials.password + "'");
            statement.executeUpdate("GRANT SELECT ON `" + names.data + "`.a_data TO '" + credentials.user + "'@'%'");
            statement.executeUpdate("GRANT SELECT ON `" + names.empty + "`.* TO '" + credentials.user + "'@'%'");
        } catch (SQLException failure) {
            throw new SQLException("Owned MySQL capacity account setup failed", failure.getSQLState(), failure.getErrorCode());
        }
        return credentials;
    }

    private static HikariDataSource source(MonitoringTestEnvironment environment, String username, String password) {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(environment.jdbcUrl());
        source.setUsername(username);
        source.setPassword(password);
        source.setMaximumPoolSize(1);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(1000);
        return source;
    }

    private static ResolvedTarget resolve(HikariDataSource source, List<String> databases) {
        var declaration = new MonitoringProperties.Target();
        declaration.setId("mysql-capacity");
        declaration.setType(MiddlewareType.MYSQL);
        declaration.setEnabled(true);
        declaration.setConnectionSource("businessMysql");
        declaration.getScope().setDatabases(databases);
        var properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(List.of(declaration));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("businessMysql", source);
        var targets = new MonitoringConnectionResolver(properties, beans, List.of(new StandardConnectionInspector())).resolve();
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
        assertThat(targets.get(0).bindings().get(0).connection().client()).isSameAs(source);
        return targets.get(0);
    }

    private static void assertNativeDatabase(CollectionResult result, MysqlMonitoringConnections.DatabaseCapacity nativeResult, String database) {
        assertThat(nativeResult.visible()).isTrue();
        assertThat(nativeResult.canonicalName()).isEqualTo(database);
        BigDecimal data = nativeResult.tables().stream().map(table -> new BigDecimal(table.dataLength())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal indexes = nativeResult.tables().stream().map(table -> new BigDecimal(table.indexLength())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (nativeResult.truncated()) {
            assertThat(metric(result, "database.data.bytes", database).value()).isNull();
            assertThat(metric(result, "database.index.bytes", database).value()).isNull();
            assertThat(metric(result, "database.data.bytes", database).missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(metric(result, "database.index.bytes", database).missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
        } else {
            assertThat(value(result, "database.data.bytes", database)).isEqualByComparingTo(data);
            assertThat(value(result, "database.index.bytes", database)).isEqualByComparingTo(indexes);
        }
        assertThat(value(result, "database.tables.observed", database)).isEqualByComparingTo(BigDecimal.valueOf(nativeResult.tables().size()));
        assertThat(metric(result, "database.coverage.complete", database).value()).isEqualTo(!nativeResult.truncated());
        for (String key : List.of("database.data.bytes", "database.index.bytes")) {
            MetricSample sample = metric(result, key, database);
            assertThat(sample.definition().unit()).isEqualTo(Unit.BYTES);
            assertThat(sample.definition().scope().kind()).isEqualTo(ScopeKind.DATABASE);
            if (sample.value() != null) assertThat(Duration.between(sample.sampledAt(), sample.validUntil())).isEqualTo(Duration.ofSeconds(180));
        }
    }
    private static void assertCoverage(CollectionResult result, ReadObservation read, int databases, int tables,
                                       boolean truncated, boolean complete) {
        assertThat(value(result, "databases.configured", null)).isEqualByComparingTo(BigDecimal.valueOf(databases));
        assertThat(value(result, "databases.requested", null)).isEqualByComparingTo(BigDecimal.valueOf(databases));
        assertThat(value(result, "limits.databases", null)).isEqualByComparingTo(BigDecimal.valueOf(new MonitoringProperties().getLimits().getDatabases()));
        assertThat(value(result, "limits.tables", null)).isEqualByComparingTo(BigDecimal.valueOf(tables));
        assertThat(value(result, "tables.selected", null)).isEqualByComparingTo(BigDecimal.valueOf(
                read.databases.values().stream().mapToInt(database -> database.selectedTables().size()).sum()));
        assertThat(metric(result, "capacity.coverage.complete", null).value()).isEqualTo(complete);
        assertThat(metric(result, "capacity.coverage.truncated", null).value()).isEqualTo(truncated);
        for (String key : List.of("filesystem.total.bytes", "filesystem.available.bytes")) {
            MetricSample sample = metric(result, key, null);
            assertThat(sample.value()).isNull();
            assertThat(sample.missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(sample.definition().scope().kind()).isEqualTo(ScopeKind.FILESYSTEM);
        }
        assertThat(result.metrics()).hasSize(databases * 4 + 9);
    }
    private static MetricSample metric(CollectionResult result, String key, String database) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals("mysql." + key))
                .filter(sample -> database == null || sample.definition().scope().id().equals(database)).findFirst().orElseThrow();
    }
    private static BigDecimal value(CollectionResult result, String key, String database) {
        MetricSample sample = metric(result, key, database);
        assertThat(sample.missingReason()).as(key).isNull();
        return (BigDecimal) sample.value();
    }
    private static boolean schemaVisible(Connection observer, String database) throws SQLException {
        try (var statement = observer.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?")) {
            statement.setString(1, database);
            try (var result = statement.executeQuery()) { return result.next(); }
        }
    }
    private static List<String> visibleTables(Connection observer, String database) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (var statement = observer.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            statement.setString(1, database);
            try (var result = statement.executeQuery()) { while (result.next()) tables.add(result.getString(1)); }
        }
        return tables;
    }
    private static Map<String, TableState> tableState(Connection observer, List<String> databases) throws SQLException {
        Map<String, TableState> state = new LinkedHashMap<>();
        for (String database : databases) {
            for (String table : visibleTables(observer, database)) {
                String definition;
                List<String> rows = new ArrayList<>();
                try (var statement = observer.createStatement(); var result = statement.executeQuery("SHOW CREATE TABLE `" + database + "`.`" + table + "`")) {
                    result.next();
                    definition = result.getString(2);
                }
                try (var statement = observer.createStatement(); var result = statement.executeQuery("SELECT id,payload FROM `" + database + "`.`" + table + "` ORDER BY id")) {
                    while (result.next()) rows.add(result.getInt(1) + ":" + result.getString(2));
                }
                state.put(database + "." + table, new TableState(definition, rows));
            }
        }
        return state;
    }
    private static Map<String, BigDecimal> writeCounters(Connection observer) throws SQLException {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        try (var statement = observer.createStatement(); var result = statement.executeQuery("SHOW GLOBAL STATUS")) {
            while (result.next()) if (WRITE_COUNTERS.contains(result.getString(1))) values.put(result.getString(1), new BigDecimal(result.getString(2)));
        }
        assertThat(values.keySet()).containsAll(WRITE_COUNTERS);
        return values;
    }
    private static void assertWritesUnchanged(Connection observer, Map<String, BigDecimal> before) throws SQLException {
        assertThat(writeCounters(observer)).isEqualTo(before);
    }
    private static Map<String, String> statisticsConfiguration(Connection observer) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (var statement = observer.createStatement(); var result = statement.executeQuery(
                "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('innodb_buffer_pool_size','innodb_stats_on_metadata','information_schema_stats_expiry')")) {
            while (result.next()) values.put(result.getString(1), result.getString(2));
        }
        assertThat(values).hasSize(3);
        return values;
    }
    private static void assertSafe(CollectionResult result, MonitoringTestEnvironment environment) throws Exception {
        String text = JSON.writeValueAsString(result);
        assertThat(text.contains(environment.password())).isFalse();
        assertThat(text).doesNotContain(environment.jdbcUrl(), "jdbc:mysql:", "owned-capacity-sentinel");
    }

    private record Names(String data, String empty, String excluded) { }
    private record Credentials(String user, String password) {
        @Override public String toString() { return "OwnedMysqlCredentials[REDACTED]"; }
    }
    private record TableState(String definition, List<String> rows) { }
    private static final class ReadObservation {
        final Map<String, MysqlMonitoringConnections.DatabaseCapacity> databases = new LinkedHashMap<>();
        final List<Integer> budgets = new ArrayList<>();
        int statusReads;
        int variableReads;
    }
    private static final class RecordingConnections extends MysqlMonitoringConnections {
        final List<ReadObservation> observations = new ArrayList<>();
        @Override public Session open(CollectionRequest request) throws SQLException {
            Session delegate = super.open(request);
            ReadObservation observation = new ReadObservation();
            observations.add(observation);
            return new Session() {
                @Override public void probe() throws SQLException { delegate.probe(); }
                @Override public Map<String, String> globalStatus() throws SQLException {
                    observation.statusReads++;
                    return delegate.globalStatus();
                }
                @Override public Map<String, String> globalVariables() throws SQLException {
                    observation.variableReads++;
                    return delegate.globalVariables();
                }
                @Override public DatabaseCapacity database(String name, int remainingRows) throws SQLException {
                    observation.budgets.add(remainingRows);
                    DatabaseCapacity result = delegate.database(name, remainingRows);
                    observation.databases.put(name, result);
                    return result;
                }
                @Override public int remainingTableBudget() { return delegate.remainingTableBudget(); }
                @Override public void close() throws SQLException { delegate.close(); }
            };
        }
    }
    private static final class Collector implements AutoCloseable {
        final Clock clock = Clock.systemUTC();
        final MonitoringProperties properties = new MonitoringProperties();
        final MonitoringCounterStore counters = new MonitoringCounterStore(1, 64);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(1, 64, 1);
        final RecordingConnections connections = new RecordingConnections();
        final MysqlMonitoringAdapter adapter = new MysqlMonitoringAdapter(properties, connections, clock);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
        long sequence;
        Collector(int tables) { properties.getLimits().setTables(tables); }
        ReadObservation last() { return connections.observations.get(connections.observations.size() - 1); }
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
