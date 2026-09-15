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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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

    @Test
    void bufferPoolShowWhitelistUsesNativeBytesAndOnlyRequiredCounters() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = mock(Connection.class);
            Statement status = mock(Statement.class), variables = mock(Statement.class);
            when(connection.createStatement()).thenReturn(status, variables);
            ResultSet statusRows = rows(new String[]{"Innodb_buffer_pool_bytes_data", "65536"},
                    new String[]{"Innodb_buffer_pool_reads", "4"}, new String[]{"Innodb_buffer_pool_read_requests", "400"});
            ResultSet variableRows = rows(new String[]{"innodb_buffer_pool_size", "134217728"});
            when(status.executeQuery(anyString())).thenReturn(statusRows);
            when(variables.executeQuery(anyString())).thenReturn(variableRows);
            try (var session = new MysqlMonitoringConnections(host -> connection).open(fixture.request())) {
                assertThat(session.globalStatus()).containsEntry("Innodb_buffer_pool_bytes_data", "65536")
                        .containsEntry("Innodb_buffer_pool_reads", "4").containsEntry("Innodb_buffer_pool_read_requests", "400");
                assertThat(session.globalVariables()).containsEntry("innodb_buffer_pool_size", "134217728");
            }
            verify(status).executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Questions','Slow_queries','Uptime',"
                    + "'Innodb_buffer_pool_bytes_data','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests')");
            verify(variables).executeQuery("SHOW GLOBAL VARIABLES WHERE Variable_name IN ('max_connections','server_uuid','innodb_buffer_pool_size')");
            verify(statusRows).close();
            verify(variableRows).close();
        }
    }

    @Test
    void capacityBindsDatabaseAndTableNamesAndReadsDynamicStatsOnlyAfterBoundedSelection() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            String database = "quoted' OR 1=1 --";
            String table = "odd'name";
            f.databases = List.of(database);
            CapacityScript script = new CapacityScript()
                    .add(new String[]{database})
                    .add(new String[]{database, table}, new String[]{database, "next-table"}, new String[]{database, "sentinel-table"})
                    .add(new String[]{database, table, "InnoDB", "16384", "0"}, new String[]{database, "next-table", "InnoDB", null, "8192"});
            try (var session = script.access().open(f.request())) {
                var result = session.database(database, 2);
                assertThat(result.visible()).isTrue();
                assertThat(result.canonicalName()).isEqualTo(database);
                assertThat(result.selectedTables()).containsExactly(table, "next-table");
                assertThat(result.tables()).hasSize(2);
                assertThat(result.tables().get(1).dataLength()).isNull();
                assertThat(result.truncated()).isTrue();
                assertThat(result.reason()).isNull();
                assertThat(session.remainingTableBudget()).isZero();
            }
            assertThat(script.queries).hasSize(3);
            assertThat(script.queries.get(0).sql).isEqualTo(MysqlMonitoringConnections.DATABASE);
            assertThat(script.queries.get(0).parameters).containsExactlyEntriesOf(Map.of(1, database));
            assertThat(script.queries.get(1).sql).isEqualTo(MysqlMonitoringConnections.TABLE_NAMES)
                    .doesNotContain("DATA_LENGTH", "INDEX_LENGTH", "SUM(");
            assertThat(script.queries.get(1).parameters).containsExactlyInAnyOrderEntriesOf(Map.of(1, database, 2, database, 3, 3));
            assertThat(script.queries.get(2).sql).isEqualTo(MysqlMonitoringConnections.TABLE_SIZES_PREFIX
                    + "CAST(? AS BINARY),CAST(? AS BINARY)) LIMIT ?");
            assertThat(script.queries.get(2).parameters).containsExactlyInAnyOrderEntriesOf(Map.of(1, database, 2, database, 3, table, 4, "next-table", 5, 3));
            assertThat(script.queries).allSatisfy(query -> assertThat(query.sql).doesNotContain(database, table, "sentinel-table"));
            script.verifyClosed();
            verifyNoInteractions(f.business);
        }
    }

    @Test
    void globalTableBudgetCannotBeMultipliedAcrossDatabasesOrIncreasedByLaterCaller() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("first", "second", "third");
            CapacityScript script = new CapacityScript()
                    .add(new String[]{"first"}).add(new String[]{"first", "one"})
                    .add(new String[]{"first", "one", "InnoDB", "1", "2"})
                    .add(new String[]{"second"}).add(new String[]{"second", "two"}, new String[]{"second", "three"})
                    .add(new String[]{"second", "two", "InnoDB", "3", "4"});
            try (var session = script.access().open(f.request())) {
                assertThat(session.database("first", 2).truncated()).isFalse();
                assertThat(session.remainingTableBudget()).isEqualTo(1);
                var second = session.database("second", MysqlMonitoringConnections.MAX_TABLES);
                assertThat(second.selectedTables()).containsExactly("two");
                assertThat(second.truncated()).isTrue();
                assertThat(session.remainingTableBudget()).isZero();
                assertThatThrownBy(() -> session.database("third", 1000)).isInstanceOf(SQLFeatureNotSupportedException.class);
            }
            assertThat(script.queries).hasSize(6);
            assertThat(script.queries.get(4).parameters.get(3)).isEqualTo(2);
            assertThat(script.queries.get(5).parameters.values()).doesNotContain("three");
            script.verifyClosed();
        }
    }

    @Test
    void invisibleDatabaseAndVisibleZeroTableScopeAreDistinctWithoutZeroByteFabrication() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("invisible", "visible");
            CapacityScript script = new CapacityScript().add().add(new String[]{"visible"}).add();
            try (var session = script.access().open(f.request())) {
                var invisible = session.database("invisible", 10);
                assertThat(invisible.visible()).isFalse();
                assertThat(invisible.canonicalName()).isNull();
                assertThat(invisible.selectedTables()).isEmpty();
                var empty = session.database("visible", 10);
                assertThat(empty.visible()).isTrue();
                assertThat(empty.canonicalName()).isEqualTo("visible");
                assertThat(empty.selectedTables()).isEmpty();
                assertThat(empty.tables()).isEmpty();
                assertThat(empty.reason()).isNull();
                assertThat(session.remainingTableBudget()).isEqualTo(10);
            }
            assertThat(script.queries).hasSize(3);
            assertThat(script.queries).noneSatisfy(query -> assertThat(query.sql).contains("DATA_LENGTH"));
            script.verifyClosed();
        }
    }

    @Test
    void scopeKindDuplicateDatabaseAndCallerBoundsAreCheckedBeforeQuery() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("allowed");
            CapacityScript script = new CapacityScript().add();
            try (var session = script.access().open(f.request())) {
                assertThatThrownBy(() -> session.database("outside", 1)).isInstanceOf(SQLFeatureNotSupportedException.class);
                assertThatThrownBy(() -> session.database("allowed", 0)).isInstanceOf(SQLFeatureNotSupportedException.class);
                assertThatThrownBy(() -> session.database("allowed", 1001)).isInstanceOf(SQLFeatureNotSupportedException.class);
                assertThat(script.queries).isEmpty();
                session.database("allowed", 1);
                assertThatThrownBy(() -> session.database("allowed", 1)).isInstanceOf(SQLFeatureNotSupportedException.class);
                assertThat(script.queries).hasSize(1);
            }
        }
        try (Fixture f = new Fixture()) {
            f.databases = List.of("allowed");
            CapacityScript script = new CapacityScript();
            try (var session = script.access().open(f.request())) {
                assertThatThrownBy(() -> session.database("allowed", 1)).isInstanceOf(SQLFeatureNotSupportedException.class);
                assertThat(script.queries).isEmpty();
            }
        }
    }

    @Test
    void canonicalCaseFromServerIsPinnedAndCannotBeCountedTwiceThroughScopeAliases() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("DATABASE", "database");
            CapacityScript script = new CapacityScript().add(new String[]{"database"}).add(new String[]{"database", "Case"})
                    .add(new String[]{"database", "Case", "InnoDB", "1", "2"}).add(new String[]{"database"});
            try (var session = script.access().open(f.request())) {
                var result = session.database("DATABASE", 10);
                assertThat(result.canonicalName()).isEqualTo("database");
                assertThat(script.queries.get(1).parameters.get(1)).isEqualTo("database");
                assertThat(script.queries.get(2).sql).contains("CAST(TABLE_SCHEMA AS BINARY)", "CAST(TABLE_NAME AS BINARY)");
                assertThatThrownBy(() -> session.database("database", 9)).isInstanceOf(SQLFeatureNotSupportedException.class);
            }
            assertThat(script.queries).hasSize(4);
        }
    }

    @Test
    void deniedStatisticsKeepSelectedNamesAndTheirBudgetWhileOtherDatabaseRemainsReadable() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("first", "second");
            CapacityScript script = new CapacityScript().add(new String[]{"first"}).add(new String[]{"first", "selected"})
                    .fail(new SQLException("denied private-user private-host", "42000", 1142))
                    .add(new String[]{"second"}).add(new String[]{"second", "allowed"})
                    .add(new String[]{"second", "allowed", "InnoDB", "1", "2"});
            try (var session = script.access().open(f.request())) {
                var denied = session.database("first", 2);
                assertThat(denied.selectedTables()).containsExactly("selected");
                assertThat(denied.reason()).isEqualTo(MetricContract.MissingReason.UNAUTHORIZED);
                assertThat(denied.tables()).isEmpty();
                assertThat(session.remainingTableBudget()).isEqualTo(1);
                var available = session.database("second", 1);
                assertThat(available.tables()).hasSize(1);
                assertThat(available.reason()).isNull();
            }
            script.verifyClosed();
        }
    }

    @Test
    void deniedNameLookupConsumesNoSelectedRowsAndCanContinueWithAnotherDatabase() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("first", "second");
            CapacityScript script = new CapacityScript().add(new String[]{"first"})
                    .fail(new SQLException("private permission text", "42000", 1142))
                    .add(new String[]{"second"}).add();
            try (var session = script.access().open(f.request())) {
                assertThat(session.database("first", 2).reason()).isEqualTo(MetricContract.MissingReason.UNAUTHORIZED);
                assertThat(session.remainingTableBudget()).isEqualTo(2);
                assertThat(session.database("second", 2).reason()).isNull();
            }
            script.verifyClosed();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-schema", "duplicate", "unselected", "oversized-number"})
    void malformedStatisticsCannotEscapeSelectedSchemaOrNameList(String error) throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("database");
            CapacityScript script = new CapacityScript().add(new String[]{"database"}).add(new String[]{"database", "selected"});
            switch (error) {
                case "wrong-schema" -> script.add(new String[]{"other", "selected", "InnoDB", "1", "2"});
                case "duplicate" -> script.add(new String[]{"database", "selected", "InnoDB", "1", "2"}, new String[]{"database", "selected", "InnoDB", "1", "2"});
                case "unselected" -> script.add(new String[]{"database", "outside", "InnoDB", "1", "2"});
                case "oversized-number" -> script.add(new String[]{"database", "selected", "InnoDB", "1".repeat(21), "2"});
                default -> throw new AssertionError("Unknown test");
            }
            try (var session = script.access().open(f.request())) {
                var result = session.database("database", 2);
                assertThat(result.reason()).isEqualTo(MetricContract.MissingReason.INVALID_VALUE);
                assertThat(result.tables()).isEmpty();
                assertThat(result.selectedTables()).containsExactly("selected");
            }
            script.verifyClosed();
        }
    }

    @Test
    void droppedTableBetweenPhasesRemainsASelectedMissingObservation() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("database");
            CapacityScript script = new CapacityScript().add(new String[]{"database"}).add(new String[]{"database", "dropped"}).add();
            try (var session = script.access().open(f.request())) {
                var result = session.database("database", 2);
                assertThat(result.selectedTables()).containsExactly("dropped");
                assertThat(result.tables()).isEmpty();
                assertThat(session.remainingTableBudget()).isEqualTo(1);
            }
        }
    }

    @Test
    void phaseDeadlineStopsDynamicStatisticsAndRetainsBudgetWithoutQueryTimeoutThreads() throws Exception {
        try (Fixture f = new Fixture(MetricContract.CollectionKind.CAPACITY)) {
            f.databases = List.of("database");
            CapacityScript script = new CapacityScript().add(new String[]{"database"}).add(new String[]{"database", "selected"});
            script.onExecute = sql -> { if (sql.equals(MysqlMonitoringConnections.TABLE_NAMES)) f.ticker.set(TimeUnit.SECONDS.toNanos(5)); };
            try (var session = script.access().open(f.request())) {
                var result = session.database("database", 2);
                assertThat(result.reason()).isEqualTo(MetricContract.MissingReason.TIMEOUT);
                assertThat(script.queries).hasSize(2);
            }
            script.verifyClosed();
        }
    }

    private static ResultSet rows(String[]... records) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(result.next()).thenAnswer(invocation -> index.incrementAndGet() < records.length);
        when(result.getString(anyInt())).thenAnswer(invocation -> records[index.get()][invocation.<Integer>getArgument(0) - 1]);
        return result;
    }

    private static final class CapacityScript {
        private final Connection connection = mock(Connection.class);
        private final List<Object> results = new ArrayList<>();
        private final List<Query> queries = new ArrayList<>();
        private Consumer<String> onExecute = sql -> {};
        private CapacityScript() throws SQLException {
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                Query query = new Query(sql);
                int sequence = queries.size();
                queries.add(query);
                if (sequence >= results.size()) throw new AssertionError("Unexpected SQL query");
                Object scripted = results.get(sequence);
                doAnswer(call -> { query.parameters.put(call.getArgument(0), call.getArgument(1)); return null; }).when(query.statement).setString(anyInt(), anyString());
                doAnswer(call -> { query.parameters.put(call.getArgument(0), call.getArgument(1)); return null; }).when(query.statement).setInt(anyInt(), anyInt());
                when(query.statement.executeQuery()).thenAnswer(call -> {
                    onExecute.accept(sql);
                    if (scripted instanceof SQLException failure) throw failure;
                    query.rows = rows((String[][]) scripted);
                    return query.rows;
                });
                return query.statement;
            });
        }
        private CapacityScript add(String[]... rows) { results.add(rows); return this; }
        private CapacityScript fail(SQLException failure) { results.add(failure); return this; }
        private MysqlMonitoringConnections access() { return new MysqlMonitoringConnections(host -> connection); }
        private void verifyClosed() throws SQLException {
            for (Query query : queries) {
                verify(query.statement).close();
                verify(query.statement, never()).setMaxRows(anyInt());
                verify(query.statement, never()).setQueryTimeout(anyInt());
                verify(query.statement, never()).cancel();
                if (query.rows != null) verify(query.rows).close();
            }
            verify(connection).abort(any());
        }
    }
    private static final class Query {
        private final String sql;
        private final PreparedStatement statement = mock(PreparedStatement.class);
        private final Map<Integer, Object> parameters = new LinkedHashMap<>();
        private ResultSet rows;
        private Query(String sql) { this.sql = sql; }
    }

    private static final class Fixture implements AutoCloseable {
        private final DataSource business = mock(DataSource.class);
        private final AtomicLong ticker = new AtomicLong();
        private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        private final CollectionControl control;
        private final MetricContract.CollectionKind kind;
        private List<String> databases = List.of();
        private final Map<String, Object> settings = new LinkedHashMap<>(Map.of(
                "endpoint", "jdbc:mysql://127.0.0.1/test?sslMode=REQUIRED", "username", "resolved-user",
                "password", "resolved-secret", "properties", Map.of()));

        private Fixture() { this(MetricContract.CollectionKind.ORDINARY); }
        private Fixture(MetricContract.CollectionKind kind) {
            this.kind = kind;
            control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), ticker::get,
                    TimeUnit.SECONDS.toNanos(5), cleanup, new MonitoringCounterStore(1, 8), "mysql", "dataSource",
                    kind, Duration.ofSeconds(kind == MetricContract.CollectionKind.CAPACITY ? 60 : 15), business);
        }

        private CollectionRequest request() {
            Instant now = Instant.now();
            return new CollectionRequest("mysql", "dataSource", "dataSource", kind,
                    1, 1, now, now.plusSeconds(5), new MonitoringTarget.Scope(databases, List.of(), List.of(), List.of(), List.of()),
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
