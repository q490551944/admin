package com.hpj.admin.monitor.mysql;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.mysql.cj.conf.HostInfo;
import com.mysql.cj.conf.PropertySet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real lifecycle controls and Connector/J URL parsing; protocol substitutes never open external services. */
class MonitoringMysqlConnectionsTest {
    @Test
    void resolvedCredentialsTlsAndHostOverridesSurviveWithoutTouchingTheBusinessDataSource() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var nativeOptions = new LinkedHashMap<String, String>();
            nativeOptions.put("user", "native-user");
            nativeOptions.put("password", "native-secret");
            nativeOptions.put("characterEncoding", "UTF-8");
            nativeOptions.put("trustCertificateKeyStoreUrl", "file:/owned/trust.jks");
            fixture.settings.put("properties", nativeOptions);
            fixture.settings.put("endpoint", "jdbc:mysql://address=(host=db.example)(port=3307)(user=host-user)"
                    + "(password=host-secret)(sslMode=VERIFY_IDENTITY)(socketTimeout=60000)/catalog"
                    + "?sslMode=REQUIRED&connectTimeout=90000");
            Connection connection = mock(Connection.class);
            AtomicReference<HostInfo> captured = new AtomicReference<>();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                captured.set(host);
                return connection;
            });
            try (var session = access.open(fixture.request())) {
                HostInfo host = captured.get();
                assertThat(host.getDatabaseUrl()).isEqualTo(fixture.settings.get("endpoint"));
                assertThat(host.getHost()).isEqualTo("db.example");
                assertThat(host.getPort()).isEqualTo(3307);
                assertThat(host.getUser()).isEqualTo("host-user");
                assertThat(host.getPassword()).isEqualTo("host-secret");
                assertThat(host.getDatabase()).isEqualTo("catalog");
                assertThat(host.getHostProperties()).containsEntry("sslMode", "VERIFY_IDENTITY")
                        .containsEntry("characterEncoding", "UTF-8")
                        .containsEntry("trustCertificateKeyStoreUrl", "file:/owned/trust.jks")
                        .containsEntry("autoReconnect", "false").containsEntry("enableQueryTimeouts", "false")
                        .containsEntry("socketFactory", MysqlMonitoringConnections.DeadlineSocketFactory.class.getName());
                assertThat(Integer.parseInt(host.getProperty("connectTimeout"))).isBetween(1, 5000);
                assertThat(Integer.parseInt(host.getProperty("socketTimeout"))).isBetween(1, 5000);
            }
            assertThat(nativeOptions).containsEntry("user", "native-user").containsEntry("password", "native-secret")
                    .doesNotContainKeys("socketFactory", "connectTimeout", "socketTimeout");
            verify(connection).abort(any());
            verify(connection, never()).close();
            verifyNoInteractions(fixture.business);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void topLevelResolvedCredentialsOverrideGlobalUrlPropertiesAndShorterNativeTimeoutIsPreserved() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("endpoint", "jdbc:mysql://localhost/db?user=url-user&password=url-secret&socketTimeout=120");
            AtomicReference<HostInfo> captured = new AtomicReference<>();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                captured.set(host); return mock(Connection.class);
            });
            try (var ignored = access.open(fixture.request())) {
                assertThat(captured.get().getHost()).isEqualTo("localhost");
                assertThat(captured.get().getUser()).isEqualTo("resolved-user");
                assertThat(captured.get().getPassword()).isEqualTo("resolved-secret");
                assertThat(captured.get().getProperty("socketTimeout")).isEqualTo("120");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://first:3306,second:3306/db",
            "jdbc:mysql:loadbalance://first/db",
            "jdbc:mysql:replication://first/db",
            "jdbc:mysql://host/db?dnsSrv=true",
            "jdbc:mysql://host/db?socketFactory=private.CustomFactory",
            "jdbc:mysql://host/db?socksProxyHost=proxy.example",
            "jdbc:mysql://host/db?propertiesTransform=private.CustomTransformer",
            "jdbc:mysql://host/db?useConfigs=maxPerformance",
            "jdbc:mysql://address=(host=host)(socketFactory=private.CustomFactory)/db"
    })
    void unsupportedRoutingOrCustomCodeIsRejectedBeforeAnyNativeAcquisition(String endpoint) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("endpoint", endpoint);
            AtomicInteger acquisitions = new AtomicInteger();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                acquisitions.incrementAndGet(); return mock(Connection.class);
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLFeatureNotSupportedException.class)
                    .hasMessageNotContaining("private.").hasMessageNotContaining("proxy.example");
            assertThat(acquisitions).hasValue(0);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
            verifyNoInteractions(fixture.business);
        }
    }

    @Test
    void opaqueCredentialCallbacksAreNotSilentlyReplacedWithMissingCredentials() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("passwordCallback", new Object());
            AtomicInteger acquisitions = new AtomicInteger();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                acquisitions.incrementAndGet(); return mock(Connection.class);
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThat(acquisitions).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"global:true", "global:YES", "host:true", "host:YES", "native:true", "native:YES"})
    void databaseAutoCreationIsRejectedBeforeAcquisitionAtEveryPropertyScope(String setting) throws Exception {
        try (Fixture fixture = new Fixture()) {
            String[] parts = setting.split(":");
            switch (parts[0]) {
                case "global" -> fixture.settings.put("endpoint", "jdbc:mysql://127.0.0.1/missing?createDatabaseIfNotExist=" + parts[1]);
                case "host" -> fixture.settings.put("endpoint", "jdbc:mysql://address=(host=127.0.0.1)(createDatabaseIfNotExist=" + parts[1] + ")/missing");
                case "native" -> fixture.settings.put("properties", Map.of("createDatabaseIfNotExist", parts[1]));
                default -> throw new AssertionError("Unknown test property scope");
            }
            AtomicInteger acquisitions = new AtomicInteger();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                acquisitions.incrementAndGet(); return mock(Connection.class);
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThat(acquisitions).hasValue(0);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
            verifyNoInteractions(fixture.business);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "global:sessionVariables", "native:sessionVariables",
            "global:queryInterceptors", "native:queryInterceptors",
            "global:connectionLifecycleInterceptors", "native:connectionLifecycleInterceptors",
            "global:exceptionInterceptors", "native:exceptionInterceptors"
    })
    void configuredSqlAndCallbacksAreRejectedWithoutInvokingTheConnector(String setting) throws Exception {
        try (Fixture fixture = new Fixture()) {
            String[] parts = setting.split(":");
            String value = parts[1].equals("sessionVariables") ? "@@GLOBAL.max_connections=100" : "private.Callback";
            if (parts[0].equals("global")) {
                fixture.settings.put("endpoint", "jdbc:mysql://127.0.0.1/db?" + parts[1] + "="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8));
            } else {
                fixture.settings.put("properties", Map.of(parts[1], value));
            }
            AtomicInteger acquisitions = new AtomicInteger();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                acquisitions.incrementAndGet(); return mock(Connection.class);
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLFeatureNotSupportedException.class)
                    .hasMessageNotContaining("max_connections").hasMessageNotContaining("private.Callback");
            assertThat(acquisitions).hasValue(0);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://host:invalid/db",
            "jdbc:mysql://host:70000/db",
            "jdbc:mysql://host/db?sslMode=INVALID_SECRET_VALUE",
            "jdbc:mysql://host/db?connectTimeout=not-a-number",
            "jdbc:mysql://host/db?characterEncoding=INVALID_SECRET_ENCODING"
    })
    void malformedNativeConfigurationReportsUnsupportedBeforeOpeningAnyConnection(String endpoint) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("endpoint", endpoint);
            AtomicInteger acquisitions = new AtomicInteger();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                acquisitions.incrementAndGet(); return mock(Connection.class);
            });
            try { access.open(fixture.request()); throw new AssertionError("Configuration failure expected"); }
            catch (SQLException invalid) {
                assertThat((Throwable) invalid).isInstanceOf(SQLFeatureNotSupportedException.class).hasNoCause()
                        .hasMessageNotContaining("SECRET").hasMessageNotContaining(endpoint);
                assertThat(invalid.getSQLState()).isEqualTo("0A000");
            }
            assertThat(acquisitions).hasValue(0);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void fixedReadOnlyQueriesPreserveMissingFieldsAndRefreshRemainingSocketBudget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = mock(Connection.class);
            Statement probe = mock(Statement.class), status = mock(Statement.class), variables = mock(Statement.class);
            ResultSet probeRows = mock(ResultSet.class), statusRows = mock(ResultSet.class), variableRows = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(probe, status, variables);
            when(probe.executeQuery(MysqlMonitoringConnections.PROBE)).thenReturn(probeRows);
            when(probeRows.next()).thenReturn(true, false);
            when(probeRows.getInt(1)).thenReturn(1);
            when(status.executeQuery(MysqlMonitoringConnections.STATUS)).thenReturn(statusRows);
            when(statusRows.next()).thenReturn(true, true, false);
            when(statusRows.getString(1)).thenReturn("threads_connected", "Slow_queries");
            when(statusRows.getString(2)).thenReturn("0", "123");
            when(variables.executeQuery(MysqlMonitoringConnections.VARIABLES)).thenReturn(variableRows);
            when(variableRows.next()).thenReturn(true, false);
            when(variableRows.getString(1)).thenReturn("MAX_CONNECTIONS");
            when(variableRows.getString(2)).thenReturn("151");
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> connection);
            try (var session = access.open(fixture.request())) {
                fixture.ticker.set(TimeUnit.SECONDS.toNanos(4));
                session.probe();
                assertThat(session.globalStatus()).containsExactlyInAnyOrderEntriesOf(Map.of("Threads_connected", "0", "Slow_queries", "123"));
                assertThat(session.globalVariables()).containsExactlyInAnyOrderEntriesOf(Map.of("max_connections", "151"));
            }
            verify(connection, times(3)).setNetworkTimeout(any(), eq(1000));
            for (Statement statement : List.of(probe, status, variables)) {
                verify(statement).close();
                verify(statement, never()).setQueryTimeout(anyInt());
                verify(statement, never()).cancel();
            }
            for (ResultSet rows : List.of(probeRows, statusRows, variableRows)) verify(rows).close();
            verifyNoInteractions(fixture.business);
        }
    }

    @Test
    void expiredRequestCannotStartAcquisition() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.ticker.set(TimeUnit.SECONDS.toNanos(5));
            AtomicInteger acquisitions = new AtomicInteger();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                acquisitions.incrementAndGet(); return mock(Connection.class);
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLTimeoutException.class);
            assertThat(acquisitions).hasValue(0);
        }
    }

    @Test
    void uncancellableAcquisitionRetainsReservationAndAbortsItsLateConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection late = mock(Connection.class);
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                fixture.control.cancel(MetricContract.MissingReason.TIMEOUT);
                assertThat(fixture.control.isCleanupComplete()).isFalse();
                return late;
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLTimeoutException.class);
            fixture.drainCleanup();
            verify(late).abort(any());
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void deadlineBeforeDnsReturnsPreventsEvenCreatingTheLateSocket() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                var sockets = new MysqlMonitoringConnections.DeadlineSocketFactory();
                fixture.ticker.set(TimeUnit.SECONDS.toNanos(5));
                sockets.createSocket(mock(PropertySet.class));
                throw new AssertionError("Expired resolution must not create a socket");
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLTimeoutException.class);
            fixture.drainCleanup();
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void cancellationClosesAnOwnedHandshakeSocketAndStillDisposesLateDriverResult() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection late = mock(Connection.class);
            AtomicReference<Socket> captured = new AtomicReference<>();
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                var sockets = new MysqlMonitoringConnections.DeadlineSocketFactory();
                captured.set(sockets.createSocket(mock(PropertySet.class)));
                fixture.control.cancel(MetricContract.MissingReason.TIMEOUT);
                try { fixture.drainCleanup(); }
                catch (Exception failure) { throw new SQLException("Test cleanup failed"); }
                assertThat(captured.get().isClosed()).isTrue();
                return late;
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLTimeoutException.class);
            verify(late).abort(any());
            assertThat(captured.get().isClosed()).isTrue();
            assertThat(fixture.control.isCleanupComplete()).isTrue();
            assertThatThrownBy(MysqlMonitoringConnections.DeadlineSocketFactory::new).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void socketTimeoutWrappedByCommunicationsFailureKeepsTimeoutMeaningWithoutLeakingDetails() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> {
                throw new SQLException("jdbc:mysql://sensitive-host?password=private", "08S01", 0,
                        new SocketTimeoutException("private-address timeout"));
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOf(SQLTimeoutException.class)
                    .hasMessage("MySQL monitoring deadline exceeded").hasNoCause();
        }
    }

    @Test
    void permissionErrorsKeepOnlySqlClassificationAndReleaseStatements() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(MysqlMonitoringConnections.STATUS))
                    .thenThrow(new SQLException("SELECT denied for private-user at private-host", "42000", 1142));
            MysqlMonitoringConnections access = new MysqlMonitoringConnections(host -> connection);
            try (var session = access.open(fixture.request())) {
                try { session.globalStatus(); throw new AssertionError("Permission failure expected"); }
                catch (SQLException denied) {
                    assertThat(denied.getSQLState()).isEqualTo("42000");
                    assertThat(denied.getErrorCode()).isEqualTo(1142);
                    assertThat((Throwable) denied).hasMessage("MySQL monitoring operation failed").hasNoCause();
                }
            }
            verify(statement).close();
            verify(connection).abort(any());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final DataSource business = mock(DataSource.class);
        private final AtomicLong ticker = new AtomicLong();
        private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        private final CollectionControl control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), ticker::get,
                TimeUnit.SECONDS.toNanos(5), cleanup, new MonitoringCounterStore(1, 8), "mysql", "dataSource",
                MetricContract.CollectionKind.ORDINARY, Duration.ofSeconds(15), business);
        private final Map<String, Object> settings = new LinkedHashMap<>(Map.of(
                "endpoint", "jdbc:mysql://127.0.0.1/test?sslMode=REQUIRED", "username", "resolved-user",
                "password", "resolved-secret", "properties", Map.of()));

        private CollectionRequest request() {
            Instant now = Instant.now();
            return new CollectionRequest("mysql", "dataSource", "dataSource", MetricContract.CollectionKind.ORDINARY,
                    1, 1, now, now.plusSeconds(5), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    business, settings, control);
        }

        private void drainCleanup() throws Exception { cleanup.submit(() -> { }).get(3, TimeUnit.SECONDS); }

        @Override public void close() throws InterruptedException {
            control.cancel(MetricContract.MissingReason.FAILED);
            cleanup.shutdown();
            if (!cleanup.awaitTermination(3, TimeUnit.SECONDS)) {
                cleanup.shutdownNow();
                throw new AssertionError("Monitoring test cleanup did not terminate");
            }
        }
    }
}
