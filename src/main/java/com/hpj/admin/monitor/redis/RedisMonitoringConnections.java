package com.hpj.admin.monitor.redis;

import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import org.redisson.config.BaseConfig;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.DefaultJedisSocketFactory;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitoring-owned RESP2 connections. Business factories, pools and clients are never borrowed, changed or closed.
 * Only fixed PING/INFO reads plus AUTH/SELECT session setup are sent. The bare Jedis Connection(socketFactory)
 * constructor deliberately avoids automatic CLIENT SETNAME/SETINFO and native reconnect initialization.
 *
 * Only resolved single-host plain TCP with static credentials and standard routing is supported. TLS is declined
 * because Jedis, Lettuce and Redisson have different trust, hostname verification, provider and StartTLS policies.
 * Java DNS cannot be forcibly interrupted: its reservation and original worker remain occupied until it returns.
 * Each acquired socket is registered before connect; cancellation closes it during connect, writes and reads.
 */
public class RedisMonitoringConnections {
    static final Set<String> INFO_SECTIONS = Set.of("server", "clients", "stats", "persistence", "replication");
    static final int MAX_REPLY_BYTES = 262144;
    static final int MAX_STATUS_BYTES = 4096;
    private final SocketSupplier sockets;
    private final AddressResolver resolver;

    public RedisMonitoringConnections() { this(Socket::new, InetAddress::getAllByName); }

    RedisMonitoringConnections(SocketSupplier sockets, AddressResolver resolver) {
        this.sockets = sockets;
        this.resolver = resolver;
    }

    @FunctionalInterface interface SocketSupplier { Socket create() throws IOException; }
    @FunctionalInterface interface AddressResolver { InetAddress[] resolve(String host) throws IOException; }

    public interface Session extends AutoCloseable {
        String ping();
        String info(String section);
        @Override void close();
    }

    /** Contains only public classification, never native exception text, endpoint, credentials or causes. */
    public static final class Failure extends RuntimeException {
        private final MissingReason reason;
        private final boolean connectionFailure;

        public Failure(MissingReason reason, boolean connectionFailure) {
            super("Redis monitoring operation unavailable", null, false, false);
            this.reason = reason;
            this.connectionFailure = connectionFailure;
        }
        public MissingReason reason() { return reason; }
        public boolean connectionFailure() { return connectionFailure; }
    }

    public Session open(CollectionRequest request) {
        checkActive(request.control());
        Settings settings = settings(request);
        Attempt attempt = new Attempt(request.control());
        boolean opened = false;
        try {
            // No client config constructor: it would send CLIENT SETINFO even when no client name was set.
            Connection connection = new Connection(() -> socket(attempt, settings));
            connection.connect();
            NativeSession session = new NativeSession(attempt, connection, settings.readMillis());
            if (settings.password() != null) {
                if (settings.username() == null) session.setup(Command.AUTH, settings.password());
                else session.setup(Command.AUTH, settings.username(), settings.password());
            }
            if (settings.database() != 0) session.setup(Command.SELECT, Integer.toString(settings.database()));
            checkActive(request.control());
            opened = true;
            return session;
        } catch (RuntimeException failure) {
            throw sanitized(failure, request.control());
        } finally {
            if (!opened) attempt.close();
        }
    }

    private static Settings settings(CollectionRequest request) {
        try {
            Map<String, Object> values = request.settings();
            if (!"standalone".equals(values.get("topology")) || !Boolean.FALSE.equals(values.get("tls"))
                    || !Boolean.FALSE.equals(values.get("dynamicCredentials"))
                    || !Boolean.TRUE.equals(values.get("defaultRouting"))) throw unsupported();
            if (!(values.get("host") instanceof String host) || host.isBlank()
                    || host.chars().anyMatch(Character::isWhitespace) || host.chars().anyMatch(Character::isISOControl)
                    || host.contains("/") || host.contains("@") || host.contains("?") || host.contains("#")
                    || !(values.get("port") instanceof Integer port) || port < 1 || port > 65535
                    || !(values.get("database") instanceof Integer database) || database < 0) throw unsupported();
            String username = optionalString(values.get("username"));
            String password = optionalString(values.get("password"));
            Timeouts timeouts = timeouts(values);
            return new Settings(host, port, database, username, password, timeouts.connect(), timeouts.read());
        } catch (Failure unsupported) {
            throw unsupported;
        } catch (RuntimeException | ReflectiveOperationException invalidConfiguration) {
            throw unsupported();
        }
    }

    private static Timeouts timeouts(Map<String, Object> values) throws ReflectiveOperationException {
        Object nativeConfiguration = values.get("clientConfiguration");
        if (nativeConfiguration instanceof DefaultJedisClientConfig jedis) {
            // The running socket factory is the actual source of timeouts, just as it is for the resolved endpoint.
            if (values.get("socketFactory") != null) {
                Object factory = values.get("socketFactory");
                if (factory.getClass() != DefaultJedisSocketFactory.class
                        || !Boolean.FALSE.equals(field(DefaultJedisSocketFactory.class, "ssl", factory))
                        || field(DefaultJedisSocketFactory.class, "hostAndPortMapper", factory) != null) throw unsupported();
                return new Timeouts(millis((Integer) field(DefaultJedisSocketFactory.class, "connectionTimeout", factory)),
                        millis((Integer) field(DefaultJedisSocketFactory.class, "socketTimeout", factory)));
            }
            if (jedis.isSsl() || jedis.getHostAndPortMapper() != null) throw unsupported();
            return new Timeouts(millis(jedis.getConnectionTimeoutMillis()), millis(jedis.getSocketTimeoutMillis()));
        }
        if (nativeConfiguration instanceof JedisClientConfiguration jedis) {
            if (jedis.isUseSsl()) throw unsupported();
            return new Timeouts(millis(jedis.getConnectTimeout()), millis(jedis.getReadTimeout()));
        }
        if (nativeConfiguration instanceof LettuceClientConfiguration lettuce) {
            if (lettuce.isUseSsl() || lettuce.isStartTls() || lettuce.getRedisCredentialsProviderFactory().isPresent()) {
                throw unsupported();
            }
            ClientOptions options;
            Duration commandTimeout;
            if (values.get("nativeClient") != null) {
                Object nativeClient = values.get("nativeClient");
                if (nativeClient.getClass() != RedisClient.class || !(values.get("configuration") instanceof RedisURI uri)
                        || uri.isSsl() || uri.isStartTls()) throw unsupported();
                options = ((RedisClient) nativeClient).getOptions();
                commandTimeout = uri.getTimeout();
            } else {
                // Lettuce's documented native default, used only when its Optional configuration is absent.
                options = lettuce.getClientOptions().orElseGet(ClientOptions::create);
                commandTimeout = lettuce.getCommandTimeout();
            }
            if (options == null || options.getClass() != ClientOptions.class) throw unsupported();
            long read = millis(commandTimeout);
            TimeoutOptions timeout = options.getTimeoutOptions();
            if (timeout.isTimeoutCommands() && !timeout.isApplyConnectionTimeout()) {
                var source = timeout.getSource();
                // Only Lettuce's own immutable fixed source is safe to read; never invoke an arbitrary callback.
                if (source == null || !source.getClass().getName().equals("io.lettuce.core.TimeoutOptions$FixedTimeoutSource")) {
                    throw unsupported();
                }
                long fixed = source.getTimeout(null);
                if (fixed > 0) read = shorter(read, millis(Duration.ofNanos(source.getTimeUnit().toNanos(fixed))));
            }
            return new Timeouts(millis(options.getSocketOptions().getConnectTimeout()), read);
        }
        if (nativeConfiguration instanceof BaseConfig<?> redisson) {
            return new Timeouts(millis(redisson.getConnectTimeout()), millis(redisson.getTimeout()));
        }
        throw unsupported();
    }

    private static Object field(Class<?> owner, String name, Object instance) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw unsupported();
        return field.get(instance);
    }

    private static String optionalString(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw unsupported();
        return text;
    }

    private static long millis(long value) {
        if (value < 0) throw unsupported();
        return value;
    }

    private static long millis(Duration value) {
        if (value == null || value.isNegative()) throw unsupported();
        return value.isZero() ? 0 : Math.max(1, value.toMillis());
    }

    private static long shorter(long first, long second) {
        return first == 0 ? second : second == 0 ? first : Math.min(first, second);
    }

    private static int bound(long configured, CollectionControl control) {
        checkActive(control);
        long remaining = Math.max(1, control.remaining().toMillis());
        return (int) Math.min(Integer.MAX_VALUE, configured == 0 ? remaining : Math.min(remaining, configured));
    }

    private Socket socket(Attempt attempt, Settings settings) {
        if (!attempt.acquisition.compareAndSet(false, true)) throw new Failure(MissingReason.FAILED, true);
        try {
            checkActive(attempt.control);
            // Reservation stays unattached during DNS so timeout cannot pretend native acquisition has returned.
            InetAddress[] addresses = resolver.resolve(settings.host());
            checkActive(attempt.control);
            if (addresses == null || addresses.length == 0) throw new JedisConnectionException("Redis endpoint unavailable");
            IOException lastFailure = null;
            for (InetAddress address : addresses) {
                checkActive(attempt.control);
                Socket socket = sockets.create();
                attempt.accept(socket);
                try {
                    socket.setReuseAddress(true);
                    socket.setKeepAlive(true);
                    socket.setTcpNoDelay(true);
                    socket.connect(new InetSocketAddress(address, settings.port()), bound(settings.connectMillis(), attempt.control));
                    socket.setSoTimeout(bound(settings.readMillis(), attempt.control));
                    checkActive(attempt.control);
                    return new DeadlineSocket(socket, attempt, settings.readMillis());
                } catch (IOException failure) {
                    attempt.closeCurrent();
                    lastFailure = failure;
                }
            }
            throw new JedisConnectionException(lastFailure);
        } catch (IOException failure) {
            throw new JedisConnectionException(failure);
        }
    }

    private static Failure unsupported() { return new Failure(MissingReason.UNSUPPORTED, false); }

    private static void checkActive(CollectionControl control) {
        try { control.checkActive(); }
        catch (CollectionControl.InactiveCollectionException inactive) { throw new Failure(inactive.reason(), false); }
    }

    private static Failure sanitized(RuntimeException failure, CollectionControl control) {
        if (failure instanceof Failure safe) return safe;
        if (failure instanceof CollectionControl.InactiveCollectionException inactive) return new Failure(inactive.reason(), false);
        try { checkActive(control); }
        catch (Failure inactive) { return inactive; }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++) {
            if (cause instanceof SocketTimeoutException) return new Failure(MissingReason.TIMEOUT, true);
            Throwable next = cause.getCause();
            if (next == cause) break;
            cause = next;
        }
        if (failure instanceof JedisDataException data) {
            // Parse the exact Redis error token; never classify permission by a keyword elsewhere in its text.
            String message = data.getMessage();
            String code = message == null ? "" : message.split("[\\s]", 2)[0];
            if (Set.of("NOAUTH", "WRONGPASS", "NOPERM").contains(code)) return new Failure(MissingReason.UNAUTHORIZED, false);
            if (Set.of("MOVED", "ASK").contains(code)) return unsupported();
            return new Failure(MissingReason.FAILED, false);
        }
        return new Failure(MissingReason.FAILED, failure instanceof JedisConnectionException);
    }

    private record Settings(String host, int port, int database, String username, String password,
                            long connectMillis, long readMillis) { }
    private record Timeouts(long connect, long read) { }

    private static final class Attempt {
        private final CollectionControl control;
        private final CollectionControl.Registration registration;
        private final AtomicReference<Socket> socket = new AtomicReference<>();
        private final AtomicBoolean attached = new AtomicBoolean();
        private final AtomicBoolean acquisition = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean cleanupFailed;

        private Attempt(CollectionControl control) {
            this.control = control;
            try { registration = control.reserveOwnedCancellation(); }
            catch (CollectionControl.InactiveCollectionException inactive) { throw new Failure(inactive.reason(), false); }
        }

        private void accept(Socket acquired) {
            if (acquired == null) throw new Failure(MissingReason.FAILED, true);
            socket.set(acquired);
            if (attached.compareAndSet(false, true)) registration.attach(this, this::cancel);
            if (closed.get() || !control.isActive()) {
                closeCurrent();
                checkActive(control);
                throw new Failure(MissingReason.FAILED, true);
            }
        }

        private void closeCurrent() {
            Socket owned = socket.getAndSet(null);
            if (owned != null) {
                try { owned.close(); }
                catch (IOException failure) { cleanupFailed = true; }
            }
            if (cleanupFailed) throw new Failure(MissingReason.FAILED, false);
        }

        private void cancel() { closed.set(true); closeCurrent(); }
        private void close() { cancel(); registration.close(); }
    }

    /** Recomputes the remaining collection timeout for every underlying read, including fragmented replies. */
    private static final class DeadlineSocket extends Socket {
        private final Socket delegate;
        private final Attempt attempt;
        private final long timeout;

        private DeadlineSocket(Socket delegate, Attempt attempt, long timeout) {
            this.delegate = delegate;
            this.attempt = attempt;
            this.timeout = timeout;
        }

        @Override public InputStream getInputStream() throws IOException {
            return new FilterInputStream(delegate.getInputStream()) {
                private final ReplyGuard guard = new ReplyGuard();
                @Override public int read() throws IOException {
                    delegate.setSoTimeout(bound(timeout, attempt.control));
                    int result = super.read();
                    checkActive(attempt.control);
                    if (result != -1) guard.accept(result);
                    return result;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    delegate.setSoTimeout(bound(timeout, attempt.control));
                    int result = super.read(buffer, offset, length);
                    checkActive(attempt.control);
                    for (int i = 0; i < result; i++) guard.accept(buffer[offset + i] & 0xff);
                    return result;
                }
            };
        }
        @Override public OutputStream getOutputStream() throws IOException { return delegate.getOutputStream(); }
        @Override public int getSoTimeout() throws java.net.SocketException { return delegate.getSoTimeout(); }
        @Override public void setSoTimeout(int value) throws java.net.SocketException { delegate.setSoTimeout(value); }
        @Override public boolean isBound() { return delegate.isBound(); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public boolean isInputShutdown() { return delegate.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return delegate.isOutputShutdown(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    /**
     * Validates framing before bytes reach Jedis: its native decoder allocates a bulk/array from the declared length.
     * This guard does not decode Redis values. It bounds the only reply shapes our commands use and rejects all others.
     */
    private static final class ReplyGuard {
        private enum State { PREFIX, LINE, LINE_LF, LENGTH, LENGTH_LF, BULK, BULK_CR, BULK_LF }
        private State state = State.PREFIX;
        private int lineBytes;
        private int length;
        private int digits;
        private boolean negative;

        private void accept(int value) {
            switch (state) {
                case PREFIX -> {
                    if (value == '+' || value == '-') { lineBytes = 0; state = State.LINE; }
                    else if (value == '$') { length = digits = 0; negative = false; state = State.LENGTH; }
                    else throw invalidReply();
                }
                case LINE -> {
                    if (value == '\r') state = State.LINE_LF;
                    else if (++lineBytes > MAX_STATUS_BYTES || value == '\n') throw invalidReply();
                }
                case LINE_LF -> {
                    if (value != '\n') throw invalidReply();
                    state = State.PREFIX;
                }
                case LENGTH -> {
                    if (value == '-' && digits == 0 && !negative) negative = true;
                    else if (value == '\r') {
                        if (digits == 0 || negative && length != 1) throw invalidReply();
                        state = State.LENGTH_LF;
                    } else {
                        if (value < '0' || value > '9' || ++digits > 6) throw invalidReply();
                        length = length * 10 + value - '0';
                        if (length > MAX_REPLY_BYTES || negative && length > 1) throw invalidReply();
                    }
                }
                case LENGTH_LF -> {
                    if (value != '\n') throw invalidReply();
                    state = negative ? State.PREFIX : length == 0 ? State.BULK_CR : State.BULK;
                }
                case BULK -> { if (--length == 0) state = State.BULK_CR; }
                case BULK_CR -> {
                    if (value != '\r') throw invalidReply();
                    state = State.BULK_LF;
                }
                case BULK_LF -> {
                    if (value != '\n') throw invalidReply();
                    state = State.PREFIX;
                }
            }
        }

        private static Failure invalidReply() { return new Failure(MissingReason.INVALID_VALUE, false); }
    }

    private static final class NativeSession implements Session {
        private final Attempt attempt;
        private final Connection connection;
        private final long timeout;
        private Failure terminal;

        private NativeSession(Attempt attempt, Connection connection, long timeout) {
            this.attempt = attempt;
            this.connection = connection;
            this.timeout = timeout;
        }

        private String command(Command command, boolean bulk, String... arguments) {
            try {
                if (terminal != null) throw terminal;
                checkActive(attempt.control);
                if (attempt.closed.get()) throw new Failure(MissingReason.FAILED, true);
                connection.setSoTimeout(bound(timeout, attempt.control));
                connection.sendCommand(command, arguments);
                String response = bulk ? connection.getBulkReply() : connection.getStatusCodeReply();
                checkActive(attempt.control);
                if (response == null) throw new Failure(MissingReason.INVALID_VALUE, false);
                return response;
            } catch (RuntimeException failure) {
                Failure safe = sanitized(failure, attempt.control);
                // Redis command errors consume a complete response. I/O, malformed replies and deadlines do not:
                // retire the connection so a late response can never be mistaken for the next INFO section.
                if (!(failure instanceof JedisDataException) || safe.reason() == MissingReason.UNSUPPORTED) {
                    terminal = safe;
                    attempt.close();
                }
                throw safe;
            }
        }

        private void setup(Command command, String... arguments) {
            if (!"OK".equals(command(command, false, arguments))) throw new Failure(MissingReason.FAILED, false);
        }

        @Override public String ping() { return command(Command.PING, false); }

        @Override public String info(String section) {
            if (section == null || !INFO_SECTIONS.contains(section)) throw unsupported();
            return command(Command.INFO, true, section);
        }

        @Override public void close() {
            attempt.close();
            // The raw socket is already closed: native close cannot flush, reconnect, send QUIT or return a pool entry.
            connection.close();
        }
    }
}
