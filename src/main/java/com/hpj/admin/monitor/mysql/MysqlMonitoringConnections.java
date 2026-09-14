package com.hpj.admin.monitor.mysql;

import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.mysql.cj.conf.ConnectionUrl;
import com.mysql.cj.conf.ConnectionUrlParser;
import com.mysql.cj.conf.HostInfo;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.jdbc.ConnectionImpl;
import com.mysql.cj.jdbc.JdbcPropertySetImpl;
import com.mysql.cj.protocol.StandardSocketFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens one monitoring-owned physical connection from the resolved native configuration. The borrowed
 * business DataSource is never used or changed. Only single-host TCP connections with the native socket
 * factory are supported; TLS and host-specific credentials retain Connector/J's normal precedence.
 *
 * The scheduler's deadline covers acquisition and every query. Standard Java DNS resolution cannot be
 * forcibly interrupted: its existing collection worker/reservation remain occupied until it returns.
 * A late resolver result cannot start a socket after cancellation. No resolver, query-timeout, or retry
 * threads are created by this layer. Closing the owned socket cancels connect, handshake and query I/O.
 */
public class MysqlMonitoringConnections {
    static final String PROBE = "SELECT 1";
    static final List<String> STATUS_NAMES = List.of("Threads_connected", "Threads_running", "Questions", "Slow_queries", "Uptime");
    static final List<String> VARIABLE_NAMES = List.of("max_connections", "server_uuid");
    static final String STATUS = "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Questions','Slow_queries','Uptime')";
    static final String VARIABLES = "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('max_connections','server_uuid')";
    private static final ThreadLocal<Attempt> CONNECTING = new ThreadLocal<>();
    private final Connector connector;

    public MysqlMonitoringConnections() { this(ConnectionImpl::getInstance); }

    MysqlMonitoringConnections(Connector connector) { this.connector = connector; }

    @FunctionalInterface
    interface Connector { Connection connect(HostInfo host) throws SQLException; }

    public interface Session extends AutoCloseable {
        void probe() throws SQLException;
        Map<String, String> globalStatus() throws SQLException;
        Map<String, String> globalVariables() throws SQLException;
        @Override void close() throws SQLException;
    }

    public Session open(CollectionRequest request) throws SQLException {
        checkActive(request.control());
        Attempt attempt = new Attempt(request.control());
        boolean opened = false;
        try {
            HostInfo host = host(request, attempt);
            CONNECTING.set(attempt);
            Connection connection = connector.connect(host);
            if (connection == null) throw new SQLException("MySQL monitoring connection unavailable", "08001");
            attempt.accept(connection);
            checkActive(request.control());
            opened = true;
            return new NativeSession(attempt, connection);
        } catch (SQLException failure) {
            throw sanitized(failure, request.control());
        } catch (CollectionControl.InactiveCollectionException inactive) {
            throw inactive(inactive);
        } catch (RuntimeException failure) {
            if (nativeTimeout(failure) || timedOut(request.control()) || failure instanceof CollectionDeadlineException) {
                throw new SQLTimeoutException("MySQL monitoring deadline exceeded", "HYT00");
            }
            throw new SQLException("MySQL monitoring connection unavailable", "08001");
        } finally {
            CONNECTING.remove();
            if (!opened) attempt.close();
        }
    }

    private static HostInfo host(CollectionRequest request, Attempt attempt) throws SQLException {
        try {
            return parsedHost(request, attempt);
        } catch (CollectionControl.InactiveCollectionException inactive) {
            throw inactive(inactive);
        } catch (RuntimeException invalidConfiguration) {
            // Parser/property failures are absent configuration capability, never evidence of a down server.
            throw unsupported();
        }
    }

    private static HostInfo parsedHost(CollectionRequest request, Attempt attempt) throws SQLException {
        Map<String, Object> settings = request.settings();
        if (!(settings.get("endpoint") instanceof String endpoint) || !endpoint.startsWith("jdbc:mysql://")
                || settings.get("usernameCallback") != null || settings.get("passwordCallback") != null) {
            throw unsupported();
        }
        Properties properties = new Properties();
        Object nativeProperties = settings.get("properties");
        if (nativeProperties != null) {
            if (!(nativeProperties instanceof Map<?, ?> values)) throw unsupported();
            for (var value : values.entrySet()) {
                if (!(value.getKey() instanceof String key) || !(value.getValue() instanceof String text)) throw unsupported();
                properties.setProperty(key, text);
            }
        }
        if (settings.get("username") instanceof String username) properties.setProperty("user", username);
        if (settings.get("password") instanceof String password) properties.setProperty("password", password);
        // Parse only syntax before rejecting configuration that could execute a custom transformer,
        // perform SRV discovery, or change connection routing during Connector/J URL construction.
        var parser = ConnectionUrlParser.parseConnectionString(endpoint);
        if (!"jdbc:mysql:".equals(parser.getScheme()) || parser.getHosts().size() != 1) throw unsupported();
        checkSupported(parser.getProperties());
        checkSupported(parser.getHosts().get(0).getHostProperties());
        checkSupported(properties);
        ConnectionUrl parsed = ConnectionUrl.getConnectionUrlInstance(endpoint, properties);
        if (parsed.getType() != ConnectionUrl.Type.SINGLE_CONNECTION) throw unsupported();
        HostInfo original = parsed.getMainHost();
        if (original.getPort() < 1 || original.getPort() > 65535) throw unsupported();
        Map<String, String> options = new LinkedHashMap<>(original.getHostProperties());
        checkSupported(options);
        options.put("connectTimeout", Integer.toString(boundTimeout(options.get("connectTimeout"), attempt.control)));
        options.put("socketTimeout", Integer.toString(boundTimeout(options.get("socketTimeout"), attempt.control)));
        options.put("socketFactory", DeadlineSocketFactory.class.getName());
        // Never permit hidden reconnect work or Connector/J's extra KILL QUERY cancellation connection.
        options.put("autoReconnect", "false");
        options.put("autoReconnectForPools", "false");
        options.put("reconnectAtTxEnd", "false");
        options.put("enableQueryTimeouts", "false");
        attempt.socketTimeout = Integer.parseInt(options.get("socketTimeout"));
        HostInfo bounded = new HostInfo(parsed, original.getHost(), original.getPort(), original.getUser(), original.getPassword(), options);
        // Validate native boolean/enum/numeric/encoding properties before the connector can create a socket.
        new JdbcPropertySetImpl().initializeProperties(bounded.exposeAsProperties());
        return bounded;
    }

    private static void checkSupported(Map<?, ?> options) throws SQLFeatureNotSupportedException {
        for (var entry : options.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (value.isEmpty()) continue;
            if ((key.equalsIgnoreCase("socketFactory") && !value.equals(StandardSocketFactory.class.getName()))
                    || key.equalsIgnoreCase("socksProxyHost") || key.equalsIgnoreCase("propertiesTransform")
                    || key.equalsIgnoreCase("useConfigs")
                    // These options execute configured SQL or arbitrary callback code during login/queries.
                    // Preserve their meaning by declining collection rather than deleting them from a clone.
                    || key.equalsIgnoreCase("sessionVariables") || key.equalsIgnoreCase("queryInterceptors")
                    || key.equalsIgnoreCase("connectionLifecycleInterceptors") || key.equalsIgnoreCase("exceptionInterceptors")
                    // Native changeDatabase() may issue CREATE DATABASE when this flag is enabled.
                    // Connector/J also accepts YES/NO booleans, so recognize both false spellings.
                    || (key.equalsIgnoreCase("createDatabaseIfNotExist")
                    && !value.equalsIgnoreCase("false") && !value.equalsIgnoreCase("no"))
                    || (key.equalsIgnoreCase("dnsSrv") && !value.equalsIgnoreCase("false"))
                    || (key.equalsIgnoreCase("protocol") && !value.equalsIgnoreCase("tcp"))) throw unsupported();
        }
    }

    private static SQLFeatureNotSupportedException unsupported() {
        return new SQLFeatureNotSupportedException("MySQL monitoring requires a supported single-host native TCP configuration", "0A000");
    }

    private static int boundTimeout(String configured, CollectionControl control) throws SQLException {
        int remaining = remainingMillis(control);
        if (configured == null || configured.isEmpty()) return remaining;
        try {
            int value = Integer.parseInt(configured);
            if (value < 0) throw unsupported();
            return value == 0 ? remaining : Math.min(value, remaining);
        } catch (NumberFormatException invalid) {
            throw unsupported();
        }
    }

    private static int remainingMillis(CollectionControl control) throws SQLException {
        checkActive(control);
        Duration remaining = control.remaining();
        if (remaining.isZero() || remaining.isNegative()) throw new SQLTimeoutException("MySQL monitoring deadline exceeded", "HYT00");
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, remaining.toMillis()));
    }

    private static void checkActive(CollectionControl control) throws SQLException {
        try { control.checkActive(); }
        catch (CollectionControl.InactiveCollectionException inactive) { throw inactive(inactive); }
    }

    private static SQLException inactive(CollectionControl.InactiveCollectionException inactive) {
        return inactive.reason() == MissingReason.TIMEOUT
                ? new SQLTimeoutException("MySQL monitoring deadline exceeded", "HYT00")
                : new SQLException("MySQL monitoring collection is inactive", "HY008");
    }

    private static SQLException sanitized(SQLException failure, CollectionControl control) {
        if (failure instanceof SQLFeatureNotSupportedException) return unsupported();
        if (failure instanceof SQLTimeoutException || "HYT00".equals(failure.getSQLState())
                || "HYT01".equals(failure.getSQLState()) || nativeTimeout(failure) || timedOut(control)) {
            return new SQLTimeoutException("MySQL monitoring deadline exceeded", "HYT00");
        }
        return new SQLException("MySQL monitoring operation failed", failure.getSQLState(), failure.getErrorCode());
    }

    private static boolean nativeTimeout(Throwable failure) {
        // Native CommunicationsException retains SocketTimeoutException in its cause chain.
        // Bound traversal also handles malformed/custom cyclic exception chains without matching text.
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
            if (failure instanceof SocketTimeoutException || failure instanceof SQLTimeoutException) return true;
        }
        return false;
    }

    private static boolean timedOut(CollectionControl control) {
        try { control.checkActive(); return false; }
        catch (CollectionControl.InactiveCollectionException inactive) { return inactive.reason() == MissingReason.TIMEOUT; }
    }

    /** Instantiated by Connector/J on the same collection worker; delegates TLS and DNS routing unchanged. */
    public static final class DeadlineSocketFactory extends StandardSocketFactory {
        private final Attempt attempt;

        public DeadlineSocketFactory() {
            attempt = CONNECTING.get();
            if (attempt == null) throw new IllegalStateException("Monitoring socket factory requires an active acquisition");
        }

        @Override protected Socket createSocket(PropertySet properties) {
            attempt.control.checkActive();
            Socket socket = super.createSocket(properties);
            attempt.accept(socket);
            attempt.control.checkActive();
            return socket;
        }

        @Override protected int getRealTimeout(int expectedTimeout) {
            try { return boundTimeout(Integer.toString(super.getRealTimeout(expectedTimeout)), attempt.control); }
            catch (SQLException expired) { throw new CollectionDeadlineException(); }
        }

        @Override public void afterHandshake() throws IOException {
            super.afterHandshake();
            try { rawSocket.setSoTimeout(boundTimeout(Integer.toString(attempt.socketTimeout), attempt.control)); }
            catch (SQLException expired) { throw new SocketException("MySQL monitoring deadline exceeded"); }
        }
    }

    private static final class CollectionDeadlineException extends IllegalStateException {
        private CollectionDeadlineException() { super("MySQL monitoring deadline exceeded"); }
    }

    private static final class Attempt {
        private final CollectionControl control;
        private final CollectionControl.Registration registration;
        private final AtomicReference<Socket> socket = new AtomicReference<>();
        private final AtomicReference<Connection> connection = new AtomicReference<>();
        private final AtomicBoolean attached = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile SQLException cleanupFailure;
        private int socketTimeout;

        private Attempt(CollectionControl control) throws SQLException {
            this.control = control;
            try { registration = control.reserveOwnedCancellation(); }
            catch (CollectionControl.InactiveCollectionException inactive) { throw inactive(inactive); }
        }

        private void attach() {
            if (attached.compareAndSet(false, true)) registration.attach(this, this::cancel);
        }

        private void accept(Socket acquired) {
            Socket previous = socket.getAndSet(acquired);
            closeSocket(previous);
            attach();
            if (closed.get() || !control.isActive()) cancel();
        }

        private void accept(Connection acquired) throws SQLException {
            connection.set(acquired);
            attach();
            if (closed.get() || !control.isActive()) {
                cancel();
                checkActive(control);
                throw new SQLException("MySQL monitoring connection is closed", "08003");
            }
        }

        private void closeSocket(Socket owned) {
            if (owned == null) return;
            try { owned.close(); }
            catch (IOException failure) { cleanupFailure = new SQLException("MySQL monitoring socket cleanup failed", "08006"); }
        }

        private void cancel() {
            closed.set(true);
            closeSocket(socket.getAndSet(null));
            Connection owned = connection.getAndSet(null);
            if (owned != null) {
                try { owned.abort(Runnable::run); }
                catch (SQLException failure) { cleanupFailure = new SQLException("MySQL monitoring connection cleanup failed", "08006"); }
            }
            if (cleanupFailure != null) throw new IllegalStateException("MySQL monitoring resource cleanup failed");
        }

        private void close() throws SQLException {
            closed.set(true);
            // Abort the owned physical connection even on success: closing must not send another
            // protocol command or wait for a peer after the collection's remaining budget is spent.
            closeSocket(socket.getAndSet(null));
            Connection owned = connection.getAndSet(null);
            if (owned != null) {
                try { owned.abort(Runnable::run); }
                catch (SQLException failure) { cleanupFailure = new SQLException("MySQL monitoring connection cleanup failed", "08006"); }
            }
            if (cleanupFailure != null) throw cleanupFailure;
            registration.close();
        }
    }

    private static final class NativeSession implements Session {
        private final Attempt attempt;
        private final Connection connection;
        private NativeSession(Attempt attempt, Connection connection) { this.attempt = attempt; this.connection = connection; }

        private void prepare() throws SQLException {
            checkActive(attempt.control);
            if (attempt.closed.get()) throw new SQLException("MySQL monitoring connection is closed", "08003");
            connection.setNetworkTimeout(Runnable::run, boundTimeout(Integer.toString(attempt.socketTimeout), attempt.control));
        }

        @Override public void probe() throws SQLException {
            try {
                prepare();
                try (var statement = connection.createStatement(); var result = statement.executeQuery(PROBE)) {
                    if (!result.next() || result.getInt(1) != 1) throw new SQLException("Invalid MySQL monitoring probe", "HY000");
                }
                checkActive(attempt.control);
            } catch (SQLException failure) { throw sanitized(failure, attempt.control); }
        }

        @Override public Map<String, String> globalStatus() throws SQLException { return read(STATUS, STATUS_NAMES); }
        @Override public Map<String, String> globalVariables() throws SQLException { return read(VARIABLES, VARIABLE_NAMES); }

        private Map<String, String> read(String sql, List<String> names) throws SQLException {
            try {
                prepare();
                Map<String, String> values = new LinkedHashMap<>();
                try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
                    int count = 0;
                    while (result.next()) {
                        if (++count > names.size()) throw new SQLException("Unexpected MySQL monitoring result size", "HY000");
                        String key = result.getString(1);
                        String name = names.stream().filter(candidate -> candidate.equalsIgnoreCase(key)).findFirst().orElse(null);
                        if (name == null || values.containsKey(name)) throw new SQLException("Unexpected MySQL monitoring result", "HY000");
                        values.put(name, result.getString(2));
                    }
                }
                checkActive(attempt.control);
                return Collections.unmodifiableMap(values);
            } catch (SQLException failure) { throw sanitized(failure, attempt.control); }
        }

        @Override public void close() throws SQLException { attempt.close(); }
    }
}
