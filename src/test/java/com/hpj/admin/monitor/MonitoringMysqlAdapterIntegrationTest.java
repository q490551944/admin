package com.hpj.admin.monitor;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringSnapshotStore.Acceptance.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Explicit MySQL selection must reach an owned real server; missing Docker is a failure, not a skip. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "mysql")
class MonitoringMysqlAdapterIntegrationTest {
    private static final List<String> WRITE_COUNTERS = List.of("Com_insert", "Com_insert_select", "Com_update",
            "Com_update_multi", "Com_delete", "Com_delete_multi", "Com_replace", "Com_create_table", "Com_drop_table");

    @ParameterizedTest(name = "{0}: real native observations without borrowing the exhausted pool")
    @ValueSource(strings = {"hikari", "druid"})
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realNativeMetricsPreserveBusinessPoolsAndData(String poolType) throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("mysql")) {
            environment.prepareData();
            try (PoolFixture pool = new PoolFixture(poolType, environment);
                 Connection business = pool.source.getConnection();
                 Connection observer = environment.jdbcConnection();
                 Collector collector = new Collector()) {
                // Caller-owned fixture preparation; the adapter itself only issues read commands.
                try (var statement = business.createStatement()) {
                    statement.execute("SET SESSION long_query_time=0.001");
                }
                List<String> businessData = rows(observer, environment.resourceName());
                Map<String, String> before = nativeStatus(observer);
                Set<Long> connections = clientIds(observer);
                Properties driverSettings = pool.driverSettings();
                String endpoint = pool.endpoint();
                pool.guard.monitoring = true;
                try {
                    ResolvedTarget target = resolve(pool, environment.resourceName());
                    var source = target.bindings().get(0).connection();
                    assertThat(source.client()).isSameAs(pool.source);
                    assertThat(source.settings().get("endpoint")).isEqualTo(environment.jdbcUrl());
                    assertThat(source.settings().get("username")).isEqualTo(environment.username());
                    assertThat(((Map<?, ?>) source.settings().get("properties")).get("useUnicode")).isEqualTo("true");
                    // Boolean comparison avoids printing a generated credential on assertion failure.
                    assertThat(environment.password().equals(source.settings().get("password"))).isTrue();
                    pool.assertExhaustedAndOpen();

                    CollectionResult first = collector.collect(target);
                    assertNativeGauges(first, collector.connections.observations.get(0));
                    assertThat(first.status()).isEqualTo(CollectionStatus.PARTIAL);
                    assertThat(metric(first, "mysql.questions.rate").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
                    assertThat(metric(first, "mysql.slow_queries.delta").missingReason()).isEqualTo(MissingReason.WAITING_SAMPLE);
                    assertThat(collector.counters.seriesCount(target.display().id())).isEqualTo(2);
                    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(clientIds(observer)).isEqualTo(connections));

                    // These deliberately slow SELECTs change native Questions/Slow_queries without changing table data.
                    try (var statement = business.createStatement()) {
                        for (int index = 0; index < 3; index++) {
                            try (var result = statement.executeQuery("SELECT SLEEP(0.02)")) {
                                assertThat(result.next()).isTrue();
                                assertThat(result.getInt(1)).isZero();
                            }
                        }
                    }
                    CollectionResult second = collector.collect(target);
                    assertThat(second.status()).isEqualTo(CollectionStatus.SUCCESS);
                    assertNativeGauges(second, collector.connections.observations.get(1));
                    NativeObservation initial = collector.connections.observations.get(0);
                    NativeObservation latest = collector.connections.observations.get(1);
                    BigDecimal questions = number(latest.status, "Questions").subtract(number(initial.status, "Questions"));
                    BigDecimal elapsed = seconds(Duration.between(metric(first, "mysql.questions.rate").sampledAt(),
                            metric(second, "mysql.questions.rate").sampledAt()));
                    assertThat(questions).isPositive();
                    assertThat(elapsed).isPositive();
                    assertThat(value(second, "mysql.questions.rate"))
                            .isEqualByComparingTo(questions.divide(elapsed, MathContext.DECIMAL128));
                    BigDecimal slowDelta = number(latest.status, "Slow_queries").subtract(number(initial.status, "Slow_queries"));
                    assertThat(slowDelta).isGreaterThanOrEqualTo(BigDecimal.valueOf(3));
                    assertThat(value(second, "mysql.slow_queries.delta")).isEqualByComparingTo(slowDelta);
                    assertThat(metric(second, "mysql.questions.rate").definition().unit()).isEqualTo(Unit.COUNT_PER_SECOND);

                    assertThat(collector.connections.observations).hasSize(2);
                    assertThat(collector.connections.closed).isEqualTo(2);
                    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(clientIds(observer)).isEqualTo(connections));
                    pool.assertExhaustedAndOpen();
                    assertThat(pool.endpoint()).isEqualTo(endpoint);
                    assertThat(pool.driverSettings().equals(driverSettings)).as("effective pool driver settings remain unchanged").isTrue();
                    assertThat(rows(observer, environment.resourceName())).isEqualTo(businessData);
                    Map<String, String> after = nativeStatus(observer);
                    for (String counter : WRITE_COUNTERS) {
                        assertThat(number(after, counter)).as(counter).isEqualByComparingTo(number(before, counter));
                    }
                    // Questions necessarily includes our observations and the collector's own reads.
                    assertThat(number(after, "Questions")).isGreaterThan(number(before, "Questions"));
                    assertThat(business.isClosed()).isFalse();
                    try (var statement = business.createStatement(); var result = statement.executeQuery("SELECT 1")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getInt(1)).isEqualTo(1);
                    }
                } finally {
                    pool.guard.monitoring = false;
                }
            }
            environment.verifyData();
        }
    }

    private static ResolvedTarget resolve(PoolFixture pool, String database) {
        MonitoringProperties.Target declaration = new MonitoringProperties.Target();
        declaration.setId("mysql-" + pool.type);
        declaration.setType(MiddlewareType.MYSQL);
        declaration.setEnabled(true);
        declaration.setConnectionSource("businessPool");
        declaration.getScope().setDatabases(List.of(database));
        MonitoringProperties properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(List.of(declaration));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("businessPool", pool.source);
        List<ResolvedTarget> targets = new MonitoringConnectionResolver(properties, beans,
                List.of(new StandardConnectionInspector())).resolve();
        assertThat(targets).hasSize(1);
        ResolvedTarget target = targets.get(0);
        assertThat(target.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
        return target;
    }

    private static void assertNativeGauges(CollectionResult result, NativeObservation observation) {
        assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        assertThat(value(result, "mysql.probe.duration")).isNotNegative();
        assertThat(metric(result, "mysql.probe.duration").definition().unit()).isEqualTo(Unit.MILLISECONDS);
        assertThat(value(result, "mysql.connections.current")).isEqualByComparingTo(number(observation.status, "Threads_connected"));
        assertThat(value(result, "mysql.connections.max")).isEqualByComparingTo(number(observation.variables, "max_connections"));
        assertThat(value(result, "mysql.threads.running")).isEqualByComparingTo(number(observation.status, "Threads_running"));
        assertThat(value(result, "mysql.slow_queries.total")).isEqualByComparingTo(number(observation.status, "Slow_queries"));
        assertThat(result.metrics()).hasSize(7).allSatisfy(metric -> {
            assertThat(metric.definition().scope().kind()).isEqualTo(ScopeKind.PROCESS);
            if (!metric.definition().key().equals("mysql.probe.duration")
                    && !metric.definition().key().equals("mysql.questions.rate")) {
                assertThat(metric.definition().unit()).isEqualTo(Unit.COUNT);
            }
        });
    }

    private static MetricSample metric(CollectionResult result, String key) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)).findFirst().orElseThrow();
    }
    private static BigDecimal value(CollectionResult result, String key) {
        MetricSample metric = metric(result, key);
        assertThat(metric.missingReason()).as(key).isNull();
        return (BigDecimal) metric.value();
    }
    private static BigDecimal number(Map<String, String> source, String key) {
        String value = source.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        return new BigDecimal(value);
    }
    private static BigDecimal seconds(Duration value) {
        return BigDecimal.valueOf(value.getSeconds()).add(BigDecimal.valueOf(value.getNano(), 9));
    }
    private static Map<String, String> nativeStatus(Connection connection) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SHOW GLOBAL STATUS")) {
            while (result.next()) values.put(result.getString(1), result.getString(2));
        }
        return values;
    }
    private static Set<Long> clientIds(Connection connection) throws SQLException {
        Set<Long> ids = new LinkedHashSet<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT ID FROM information_schema.PROCESSLIST WHERE USER='root'")) {
            while (result.next()) ids.add(result.getLong(1));
        }
        return ids;
    }
    private static List<String> rows(Connection connection, String database) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT id, payload FROM `" + database + "`.fixture ORDER BY id")) {
            while (result.next()) rows.add(result.getInt(1) + ":" + result.getString(2));
        }
        return rows;
    }

    /** Records real responses without replacing any protocol call, configuration, or driver connection. */
    private static final class RecordingConnections extends MysqlMonitoringConnections {
        final List<NativeObservation> observations = new ArrayList<>();
        int closed;
        @Override public Session open(CollectionRequest request) throws SQLException {
            Session actual = super.open(request);
            NativeObservation observation = new NativeObservation();
            observations.add(observation);
            return new Session() {
                @Override public void probe() throws SQLException { actual.probe(); }
                @Override public Map<String, String> globalStatus() throws SQLException {
                    observation.status = actual.globalStatus();
                    return observation.status;
                }
                @Override public Map<String, String> globalVariables() throws SQLException {
                    observation.variables = actual.globalVariables();
                    return observation.variables;
                }
                @Override public void close() throws SQLException { actual.close(); closed++; }
            };
        }
    }
    private static final class NativeObservation {
        Map<String, String> status = Map.of();
        Map<String, String> variables = Map.of();
    }

    private static final class Collector implements AutoCloseable {
        final Object lifecycle = new Object();
        final Clock clock = Clock.systemUTC();
        final MonitoringProperties properties = new MonitoringProperties();
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 32);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 32, 2);
        final RecordingConnections connections = new RecordingConnections();
        final MysqlMonitoringAdapter adapter = new MysqlMonitoringAdapter(properties, connections, clock);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        long sequence;

        CollectionResult collect(ResolvedTarget target) throws Exception {
            String id = target.display().id();
            var binding = target.bindings().get(0);
            var source = binding.connection();
            snapshots.activate(id, 1);
            Instant scheduled = clock.instant();
            Duration timeout = properties.getCollectionTimeout();
            CollectionControl control = new CollectionControl(lifecycle, () -> true, clock, System::nanoTime,
                    System.nanoTime() + timeout.toNanos(), cleanup, counters, id, source.source(),
                    CollectionKind.ORDINARY, properties.getOrdinaryInterval(), source.client());
            CollectionRequest request = new CollectionRequest(id, source.source(), source.source(), CollectionKind.ORDINARY,
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

    private static final class Guard {
        boolean monitoring;
        void check() { if (monitoring) throw new AssertionError("Monitoring must not borrow from or close the business pool"); }
    }
    private static final class GuardedHikari extends HikariDataSource {
        final Guard guard;
        GuardedHikari(Guard guard) { this.guard = guard; }
        @Override public Connection getConnection() throws SQLException { guard.check(); return super.getConnection(); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            guard.check(); return super.getConnection(user, password);
        }
        @Override public void close() { guard.check(); super.close(); }
    }
    private static final class GuardedDruid extends DruidDataSource {
        final Guard guard;
        GuardedDruid(Guard guard) { this.guard = guard; }
        @Override public DruidPooledConnection getConnection() throws SQLException { guard.check(); return super.getConnection(); }
        @Override public DruidPooledConnection getConnection(long timeout) throws SQLException {
            guard.check(); return super.getConnection(timeout);
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            guard.check(); return super.getConnection(user, password);
        }
        @Override public void close() { guard.check(); super.close(); }
    }
    private static final class PoolFixture implements AutoCloseable {
        final String type;
        final Guard guard = new Guard();
        final DataSource source;
        PoolFixture(String type, MonitoringTestEnvironment environment) throws SQLException {
            this.type = type;
            if (type.equals("hikari")) {
                GuardedHikari pool = new GuardedHikari(guard);
                pool.setJdbcUrl(environment.jdbcUrl());
                pool.setUsername(environment.username());
                pool.setPassword(environment.password());
                pool.setMaximumPoolSize(1);
                pool.setMinimumIdle(0);
                pool.setConnectionTimeout(1000);
                pool.setIdleTimeout(0);
                pool.setMaxLifetime(0);
                pool.setPoolName("monitor-test-business-hikari");
                pool.addDataSourceProperty("useUnicode", "true");
                source = pool;
            } else {
                GuardedDruid pool = new GuardedDruid(guard);
                pool.setUrl(environment.jdbcUrl());
                pool.setUsername(environment.username());
                pool.setPassword(environment.password());
                pool.setMaxActive(1);
                pool.setInitialSize(0);
                pool.setMinIdle(0);
                pool.setMaxWait(1000);
                pool.setTestWhileIdle(false);
                pool.setTestOnBorrow(false);
                pool.setTestOnReturn(false);
                pool.setConnectionProperties("useUnicode=true");
                source = pool;
            }
        }
        String endpoint() { return source instanceof HikariDataSource pool ? pool.getJdbcUrl() : ((DruidDataSource) source).getUrl(); }
        Properties driverSettings() {
            Properties properties = source instanceof HikariDataSource pool
                    ? pool.getDataSourceProperties() : ((DruidDataSource) source).getConnectProperties();
            return (Properties) properties.clone();
        }
        void assertExhaustedAndOpen() {
            if (source instanceof HikariDataSource pool) {
                assertThat(pool.isClosed()).isFalse();
                assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
                assertThat(pool.getHikariPoolMXBean().getIdleConnections()).isZero();
                assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
            } else {
                DruidDataSource pool = (DruidDataSource) source;
                assertThat(pool.isClosed()).isFalse();
                assertThat(pool.getMaxActive()).isEqualTo(1);
                assertThat(pool.getActiveCount()).isEqualTo(1);
                assertThat(pool.getPoolingCount()).isZero();
            }
        }
        @Override public void close() {
            guard.monitoring = false;
            if (source instanceof HikariDataSource pool) pool.close();
            else ((DruidDataSource) source).close();
        }
    }
}
