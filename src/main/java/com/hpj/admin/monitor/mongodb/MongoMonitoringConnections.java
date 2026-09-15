package com.hpj.admin.monitor.mongodb;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.mongodb.*;
import com.mongodb.connection.*;
import com.mongodb.event.CommandListener;
import com.mongodb.internal.build.MongoDriverVersion;
import com.mongodb.internal.connection.CommandHelper;
import com.mongodb.internal.connection.InternalConnection;
import com.mongodb.internal.connection.MongoCredentialWithCache;
import com.mongodb.internal.connection.SocketStream;
import com.mongodb.spi.dns.InetAddressResolver;
import org.bson.*;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * One monitoring-owned MongoDB 4.11.1 physical connection, without a client, pool, SDAM threads or sessions.
 * The source must already describe one direct native target. Native handshake/authentication and command
 * encoding are retained; cancellation owns only the sockets created here. DNS cannot be forcibly interrupted,
 * so a separate acquisition/worker reservation remains until the original worker finishes cleanup.
 */
public class MongoMonitoringConnections {
    static final int MAX_REPLY_BYTES = 2 * 1024 * 1024;
    static final int MAX_BSON_DEPTH = 32;
    private final NativeFactory factory;

    public MongoMonitoringConnections() { this(MongoMonitoringConnections::nativeConnection); }
    MongoMonitoringConnections(NativeFactory factory) { this.factory = factory; }

    @FunctionalInterface interface NativeFactory {
        InternalConnection create(MongoClientSettings settings, StreamFactory streams);
    }

    @JsonIgnoreType
    public interface Session extends AutoCloseable {
        BsonDocument ping();
        BsonDocument hello();
        BsonDocument serverStatus();
        /** Stable physical endpoint hash, used only for internal counter identity. */
        String nodeIdentity();
        @Override void close();
    }

    @JsonIgnoreType
    public static final class Failure extends RuntimeException {
        private final MissingReason reason;
        private final boolean connectionFailure;
        public Failure(MissingReason reason, boolean connectionFailure) {
            super("MongoDB monitoring operation unavailable", null, false, false);
            this.reason = reason;
            this.connectionFailure = connectionFailure;
        }
        public MissingReason reason() { return reason; }
        public boolean connectionFailure() { return connectionFailure; }
    }

    public Session open(CollectionRequest request) {
        checkActive(request.control());
        MongoClientSettings settings = settings(request);
        Attempt attempt = new Attempt(request.control());
        InternalConnection connection = null;
        boolean success = false;
        try {
            connection = factory.create(settings, address -> new OwnedStream(attempt, settings, address));
            if (connection == null) throw new Failure(MissingReason.FAILED, true);
            checkActive(request.control());
            connection.open();
            checkActive(request.control());
            // A full native client enforces this in BaseCluster selection. The owned direct connection
            // must retain the same driver compatibility fence despite not constructing a cluster.
            ServerDescription actual = connection.getInitialServerDescription();
            if (actual == null || !actual.isOk() || !actual.isCompatibleWithDriver()) throw unsupported();
            ClusterSettings cluster = settings.getClusterSettings();
            if (cluster.getRequiredReplicaSetName() != null || cluster.getRequiredClusterType() != ClusterType.UNKNOWN) {
                if (cluster.getRequiredReplicaSetName() != null
                        && !cluster.getRequiredReplicaSetName().equals(actual.getSetName())
                        || cluster.getRequiredClusterType() != ClusterType.UNKNOWN
                        && cluster.getRequiredClusterType() != actual.getType().getClusterType()) throw unsupported();
            }
            success = true;
            return new NativeSession(attempt, connection, settings);
        } catch (RuntimeException failure) {
            throw sanitized(failure, request.control());
        } finally {
            if (!success) attempt.finish(connection);
        }
    }

    static MongoClientSettings settings(CollectionRequest request) {
        try {
            if (!"4.11.1".equals(MongoDriverVersion.class.getField("VERSION").get(null))
                    || !(request.settings().get("mongoSettings") instanceof MongoClientSettings settings)) throw unsupported();
            ClusterSettings cluster = settings.getClusterSettings();
            if (cluster.getMode() != ClusterConnectionMode.SINGLE || cluster.getHosts().size() != 1
                    || cluster.getSrvHost() != null || cluster.getServerSelector() != null
                    || settings.getStreamFactoryFactory() != null || settings.getTransportSettings() != null
                    || settings.getDnsClient() != null || settings.getInetAddressResolver() != null
                    || settings.getAutoEncryptionSettings() != null || settings.getContextProvider() != null
                    || !settings.getCommandListeners().isEmpty() || !settings.getCompressorList().isEmpty()
                    || settings.getSocketSettings().getProxySettings().isProxyEnabled()) throw unsupported();
            ServerAddress address = cluster.getHosts().get(0);
            if (address.getClass() != ServerAddress.class || address.getHost().isBlank()
                    || address.getPort() < 1 || address.getPort() > 65535) throw unsupported();
            MongoCredential credential = settings.getCredential();
            if (credential != null) {
                AuthenticationMechanism mechanism = credential.getAuthenticationMechanism();
                if (mechanism != null && !Set.of(AuthenticationMechanism.SCRAM_SHA_1, AuthenticationMechanism.SCRAM_SHA_256,
                        AuthenticationMechanism.PLAIN, AuthenticationMechanism.MONGODB_X509).contains(mechanism)) throw unsupported();
                // Built-in supported mechanisms do not consume mechanism properties. Decline unknown callbacks
                // instead of invoking or silently omitting a credential policy hidden in the immutable credential.
                var properties = MongoCredential.class.getDeclaredField("mechanismProperties");
                if (!properties.trySetAccessible() || !((java.util.Map<?, ?>) properties.get(credential)).isEmpty()) throw unsupported();
            }
            return settings;
        } catch (Failure safe) {
            throw safe;
        } catch (ReflectiveOperationException | RuntimeException invalid) {
            throw unsupported();
        }
    }

    private static InternalConnection nativeConnection(MongoClientSettings settings, StreamFactory streams) {
        try {
            Class<?> factoryType = Class.forName("com.mongodb.internal.connection.InternalStreamConnectionFactory");
            Constructor<?> constructor = factoryType.getDeclaredConstructor(ClusterConnectionMode.class, boolean.class,
                    StreamFactory.class, MongoCredentialWithCache.class, String.class, MongoDriverInformation.class,
                    List.class, LoggerSettings.class, CommandListener.class, ServerApi.class, InetAddressResolver.class);
            if (!constructor.trySetAccessible()) throw unsupported();
            // In 4.11.1 this boolean only suppresses command events/debug payload logging. It does not
            // create a monitor or change handshake/authentication. No source callback is invoked.
            Object nativeFactory = constructor.newInstance(settings.getClusterSettings().getMode(), true, streams,
                    settings.getCredential() == null ? null : new MongoCredentialWithCache(settings.getCredential()),
                    settings.getApplicationName(), null, settings.getCompressorList(), settings.getLoggerSettings(),
                    null, settings.getServerApi(), null);
            Class<?> interfaceType = Class.forName("com.mongodb.internal.connection.InternalConnectionFactory");
            Method create = interfaceType.getDeclaredMethod("create", ServerId.class);
            if (!create.trySetAccessible()) throw unsupported();
            // Native default method supplies generation 0. There is no business pool generation to borrow.
            return (InternalConnection) create.invoke(nativeFactory,
                    new ServerId(new ClusterId(), settings.getClusterSettings().getHosts().get(0)));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw unsupported();
        }
    }

    private static final class NativeSession implements Session {
        private final Attempt attempt;
        private final InternalConnection connection;
        private final MongoClientSettings settings;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Failure terminal;

        private NativeSession(Attempt attempt, InternalConnection connection, MongoClientSettings settings) {
            this.attempt = attempt;
            this.connection = connection;
            this.settings = settings;
        }
        @Override public BsonDocument ping() { return command("ping"); }
        @Override public BsonDocument hello() { return command("hello"); }
        @Override public BsonDocument serverStatus() { return command("serverStatus"); }
        @Override public String nodeIdentity() {
            if (attempt.identity == null) throw unsupported();
            return attempt.identity;
        }
        private BsonDocument command(String name) {
            if (terminal != null) throw terminal;
            if (closed.get()) throw new Failure(MissingReason.FAILED, true);
            try {
                checkActive(attempt.control);
                BsonDocument result;
                try { result = execute(name); }
                catch (MongoCommandException missing) {
                    // 4.11's native initial handshake already supports legacy isMaster. A command-not-found
                    // response alone allows this same read-only fallback; never retry an authorization error.
                    if (!"hello".equals(name) || missing.getErrorCode() != 59) throw missing;
                    result = execute("isMaster");
                }
                checkActive(attempt.control);
                if (result == null) throw new Failure(MissingReason.INVALID_VALUE, true);
                return result;
            } catch (RuntimeException failure) {
                Failure safe = sanitized(failure, attempt.control);
                if (safe.connectionFailure() || !attempt.control.isActive()) {
                    terminal = safe;
                    attempt.cancel();
                    try { connection.close(); }
                    catch (RuntimeException cleanupFailure) { throw sanitized(cleanupFailure, attempt.control); }
                }
                throw safe;
            }
        }
        private BsonDocument execute(String name) {
            // No implicit session, database discovery, retry-read loop or topology selection. The same
            // physical connection handles all three fixed commands, including a direct secondary/mongos.
            return CommandHelper.executeCommand("admin", new BsonDocument(name, new BsonInt32(1)), null,
                    settings.getClusterSettings().getMode(), settings.getServerApi(), connection);
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) attempt.finish(connection); }
    }

    private static final class Attempt {
        private final CollectionControl control;
        private final CollectionControl.Registration worker;
        private final CollectionControl.Registration cancellation;
        private final List<Socket> sockets = new ArrayList<>();
        private boolean cancelled;
        private volatile boolean closeFailed;
        private volatile String identity;

        private Attempt(CollectionControl control) {
            this.control = control;
            worker = control.reserveOwnedCancellation();
            CollectionControl.Registration reserved = null;
            try {
                reserved = control.reserveOwnedCancellation();
                reserved.attach(this, this::cancel);
            } catch (RuntimeException failure) {
                worker.close();
                if (reserved != null) reserved.close();
                throw sanitized(failure, control);
            }
            cancellation = reserved;
        }
        private synchronized void accept(Socket socket) {
            sockets.add(socket);
            if (cancelled || !control.isActive()) {
                closeSocket(socket);
                checkActive(control);
                throw new Failure(MissingReason.FAILED, true);
            }
        }
        private void closeSocket(Socket socket) {
            try { socket.close(); }
            catch (IOException | RuntimeException failure) { closeFailed = true; }
        }
        private synchronized void cancel() {
            cancelled = true;
            for (Socket socket : sockets) closeSocket(socket);
            if (closeFailed) throw new Failure(MissingReason.FAILED, false);
        }
        private void finish(InternalConnection connection) {
            boolean complete = false;
            try {
                cancel();
                if (connection != null) connection.close();
                complete = !closeFailed;
            } catch (RuntimeException cleanupFailure) {
                throw sanitized(cleanupFailure, control);
            } finally {
                // A failed native/socket close retains the reservation, just as the collection contract requires.
                if (complete) {
                    cancellation.close();
                    worker.close();
                }
            }
        }
    }

    /** Native socket setup/TLS plus bounded stream I/O; MongoDB wire encoding and BSON decoding remain native. */
    static final class OwnedStream implements Stream {
        private final Attempt attempt;
        private final MongoClientSettings settings;
        private final ServerAddress address;
        private final ReplyGuard guard = new ReplyGuard();
        private volatile Socket socket;
        private volatile SocketStream delegate;
        private volatile boolean closed;

        private OwnedStream(Attempt attempt, MongoClientSettings settings, ServerAddress address) {
            this.attempt = attempt;
            this.settings = settings;
            this.address = address;
        }
        @Override public void open() throws IOException {
            checkActive(attempt.control);
            // InternalStreamConnection passes its native ServerAddressWithResolver here, preserving the
            // driver's effective SPI default resolver. The worker reservation remains held if DNS stalls.
            List<InetSocketAddress> addresses = address.getSocketAddresses();
            checkActive(attempt.control);
            if (addresses.isEmpty() || addresses.size() > 64) throw unsupported();
            SocketFactory original;
            try {
                original = settings.getSslSettings().isEnabled()
                        ? (settings.getSslSettings().getContext() == null ? SSLContext.getDefault()
                            : settings.getSslSettings().getContext()).getSocketFactory()
                        : SocketFactory.getDefault();
            } catch (java.security.NoSuchAlgorithmException unavailable) { throw unsupported(); }
            for (int i = 0; i < addresses.size(); i++) {
                checkActive(attempt.control);
                InetSocketAddress endpoint = addresses.get(i);
                SocketSettings bounded = SocketSettings.builder(settings.getSocketSettings())
                        .connectTimeout(bound(settings.getSocketSettings().getConnectTimeout(MILLISECONDS), attempt.control), MILLISECONDS)
                        .readTimeout(bound(settings.getSocketSettings().getReadTimeout(MILLISECONDS), attempt.control), MILLISECONDS).build();
                ServerAddress resolved = new ServerAddress(address.getHost(), address.getPort()) {
                    @Override public List<InetSocketAddress> getSocketAddresses() { return List.of(endpoint); }
                };
                delegate = new SocketStream(resolved, bounded, settings.getSslSettings(),
                        registeredFactory(original), this);
                try {
                    delegate.open();
                    checkActive(attempt.control);
                    attempt.identity = identity(socket);
                    return;
                } catch (MongoSocketOpenException failed) {
                    closeCurrent();
                    // Match SocketStream's native address fallback: only a connect timeout retries another address.
                    if (!(failed.getCause() instanceof SocketTimeoutException) || i + 1 == addresses.size()) throw failed;
                }
            }
            throw new Failure(MissingReason.FAILED, true);
        }
        private SocketFactory registeredFactory(SocketFactory original) {
            return new SocketFactory() {
                @Override public Socket createSocket() throws IOException {
                    checkActive(attempt.control);
                    Socket owned = original.createSocket();
                    socket = owned;
                    attempt.accept(owned); // Physical SSLSocket is registered before native connect/handshake.
                    return owned;
                }
                @Override public Socket createSocket(String h, int p) { throw unsupported(); }
                @Override public Socket createSocket(String h, int p, InetAddress l, int lp) { throw unsupported(); }
                @Override public Socket createSocket(InetAddress h, int p) { throw unsupported(); }
                @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { throw unsupported(); }
            };
        }
        @Override public ByteBuf getBuffer(int size) {
            if (size < 0 || size > MAX_REPLY_BYTES) throw new Failure(MissingReason.INVALID_VALUE, true);
            // Avoid PowerOfTwoBufferPool.DEFAULT: its shared cache starts a global pruning thread.
            return new ByteBufNIO(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN));
        }
        @Override public ByteBuf read(int numBytes) throws IOException {
            if (numBytes < 0 || numBytes > MAX_REPLY_BYTES) throw new Failure(MissingReason.INVALID_VALUE, true);
            ByteBuf buffer = getBuffer(numBytes);
            try {
                InputStream input = socket.getInputStream();
                int offset = 0;
                while (offset < numBytes) {
                    socket.setSoTimeout(bound(settings.getSocketSettings().getReadTimeout(MILLISECONDS), attempt.control));
                    int read = input.read(buffer.array(), offset, numBytes - offset);
                    if (read < 0) throw new IOException("Monitoring stream ended");
                    offset += read;
                }
                checkActive(attempt.control);
                guard.accept(buffer.array(), numBytes);
                return buffer;
            } catch (RuntimeException | IOException failure) {
                buffer.release();
                throw failure;
            }
        }
        @Override public boolean supportsAdditionalTimeout() { return true; }
        @Override public ByteBuf read(int numBytes, int additionalTimeout) throws IOException {
            // These non-awaitable commands never need SDAM's extra heartbeat timeout.
            if (additionalTimeout != 0) throw unsupported();
            return read(numBytes);
        }
        @Override public void write(List<ByteBuf> buffers) throws IOException {
            checkActive(attempt.control);
            // JSSE may start its handshake on the first write. Preserve the current budget there as well.
            socket.setSoTimeout(bound(settings.getSocketSettings().getReadTimeout(MILLISECONDS), attempt.control));
            delegate.write(buffers);
            checkActive(attempt.control);
        }
        private void closeCurrent() { if (socket != null) attempt.closeSocket(socket); }
        @Override public void close() { closed = true; closeCurrent(); }
        @Override public boolean isClosed() { return closed; }
        @Override public ServerAddress getAddress() { return address; }
        @Override public void openAsync(AsyncCompletionHandler<Void> handler) { throw unsupported(); }
        @Override public void writeAsync(List<ByteBuf> b, AsyncCompletionHandler<Void> h) { throw unsupported(); }
        @Override public void readAsync(int n, AsyncCompletionHandler<ByteBuf> h) { throw unsupported(); }
    }

    /** Checks lengths before the native decoder allocates, then uses its own BSON reader to validate structure. */
    static final class ReplyGuard {
        private int expectedBody;
        private int opcode;
        void accept(byte[] bytes, int length) {
            try {
                ByteBuffer input = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
                if (expectedBody == 0) {
                    if (length != 16) throw invalid();
                    int total = input.getInt();
                    opcode = input.getInt(12);
                    if (total < 21 || total > MAX_REPLY_BYTES || (opcode != 1 && opcode != 2013)) throw invalid();
                    expectedBody = total - 16;
                    return;
                }
                if (length != expectedBody) throw invalid();
                expectedBody = 0;
                int bsonOffset;
                int end = length;
                if (opcode == 1) {
                    if (length < 25 || input.getInt(16) != 1) throw invalid();
                    bsonOffset = 20;
                } else {
                    int flags = input.getInt();
                    if ((flags & ~1) != 0 || input.get() != 0) throw invalid();
                    bsonOffset = 5;
                    if ((flags & 1) != 0) end -= 4;
                }
                if (end - bsonOffset < 5 || input.getInt(bsonOffset) != end - bsonOffset) throw invalid();
                input.position(bsonOffset).limit(end);
                try (BsonBinaryReader reader = new BsonBinaryReader(input.slice().order(ByteOrder.LITTLE_ENDIAN))) {
                    document(reader, 0, false);
                }
            } catch (Failure safe) { throw safe; }
            catch (RuntimeException malformed) { throw invalid(); }
        }
        private static void document(BsonBinaryReader reader, int depth, boolean array) {
            if (depth > MAX_BSON_DEPTH) throw invalid();
            if (array) reader.readStartArray(); else reader.readStartDocument();
            BsonType type;
            while ((type = reader.readBsonType()) != BsonType.END_OF_DOCUMENT) {
                if (!array) reader.readName();
                switch (type) {
                    case DOCUMENT -> document(reader, depth + 1, false);
                    case ARRAY -> document(reader, depth + 1, true);
                    case JAVASCRIPT_WITH_SCOPE -> {
                        reader.readJavaScriptWithScope();
                        document(reader, depth + 1, false);
                    }
                    case BINARY -> {
                        int size = reader.peekBinarySize();
                        if (size < 0 || size > MAX_REPLY_BYTES) throw invalid();
                        reader.skipValue();
                    }
                    default -> reader.skipValue();
                }
            }
            if (array) reader.readEndArray(); else reader.readEndDocument();
        }
        private static Failure invalid() { return new Failure(MissingReason.INVALID_VALUE, true); }
    }

    private static String identity(Socket socket) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (socket.getInetAddress().getHostAddress() + ":" + socket.getPort()).getBytes(StandardCharsets.UTF_8));
            return "node-" + HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw unsupported(); }
    }
    private static int bound(long configured, CollectionControl control) {
        checkActive(control);
        long remaining = Math.max(1, control.remaining().toMillis());
        return (int) Math.min(Integer.MAX_VALUE, configured == 0 ? remaining : Math.min(configured, remaining));
    }
    private static void checkActive(CollectionControl control) {
        try { control.checkActive(); }
        catch (CollectionControl.InactiveCollectionException inactive) { throw new Failure(inactive.reason(), false); }
    }
    private static Failure unsupported() { return new Failure(MissingReason.UNSUPPORTED, false); }
    private static Failure sanitized(RuntimeException failure, CollectionControl control) {
        if (failure instanceof Failure safe) return safe;
        try { checkActive(control); } catch (Failure inactive) { return inactive; }
        if (failure instanceof MongoSecurityException) return new Failure(MissingReason.UNAUTHORIZED, true);
        if (failure instanceof MongoTimeoutException || failure instanceof MongoExecutionTimeoutException
                || failure instanceof MongoSocketReadTimeoutException
                || failure instanceof MongoSocketException socket && socket.getCause() instanceof SocketTimeoutException) {
            return new Failure(MissingReason.TIMEOUT, true);
        }
        if (failure instanceof MongoCommandException command) {
            return new Failure(switch (command.getErrorCode()) {
                case 13, 18 -> MissingReason.UNAUTHORIZED;
                case 59, 115, 323 -> MissingReason.UNSUPPORTED;
                case 50 -> MissingReason.TIMEOUT;
                default -> MissingReason.FAILED;
            }, false);
        }
        return new Failure(failure instanceof BsonSerializationException ? MissingReason.INVALID_VALUE : MissingReason.FAILED, true);
    }
}
