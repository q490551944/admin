package com.hpj.admin.monitor.mongodb;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.mongodb.*;
import com.mongodb.connection.*;
import com.mongodb.event.CommandListener;
import com.mongodb.internal.connection.CommandMessage;
import com.mongodb.internal.connection.InternalConnection;
import org.bson.*;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native configuration, command and cleanup contracts. Network deadline/TLS tests live separately. */
class MonitoringMongoConnectionsTest {
    @Test
    void preservesActualNativeSettingsAndOrdinarySingleUriWithoutRebuildingCredentialsOrTls() throws Exception {
        try (Fixture f = new Fixture()) {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, null, null);
            MongoCredential credential = MongoCredential.createScramSha256Credential("native-user", "auth-source", "private".toCharArray());
            f.settings = MongoClientSettings.builder(f.settings).credential(credential)
                    .applyToSslSettings(s -> s.enabled(true).context(tls).invalidHostNameAllowed(false))
                    .readPreference(ReadPreference.secondaryPreferred()).applicationName("actual-customizer-name")
                    .serverApi(ServerApi.builder().version(ServerApiVersion.V1).build())
                    .applyToSocketSettings(s -> s.connectTimeout(123, TimeUnit.MILLISECONDS).readTimeout(456, TimeUnit.MILLISECONDS)).build();
            assertThat(f.settings.getClusterSettings().getMode()).isEqualTo(ClusterConnectionMode.SINGLE);
            try (var session = f.access().open(f.request())) {
                assertThat(f.captured.get()).isSameAs(f.settings);
                assertThat(f.captured.get().getCredential()).isSameAs(credential);
                assertThat(f.captured.get().getSslSettings().getContext()).isSameAs(tls);
                assertThat(f.settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS)).isEqualTo(456);
            }
            verifyNoInteractions(f.business);
            verify(f.connection).close();
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mongodb://native-a:27017,native-b:27017", "mongodb://native-a:27017/?directConnection=false",
            "mongodb://native-a:27017/?replicaSet=replica", "mongodb://native-a:27017/?loadBalanced=true",
            "mongodb+srv://native.example.invalid/"})
    void declinesTopologyThatCannotProveOneOriginalTarget(String uri) throws Exception {
        try (Fixture f = new Fixture()) {
            f.settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri)).build();
            assertFailure(() -> f.access().open(f.request()), MissingReason.UNSUPPORTED, false);
            verifyNoInteractions(f.connection, f.business);
            assertThat(f.captured.get()).isNull();
        }
    }

    @Test
    void rejectsCustomRoutingCallbacksEncryptionTransportAndCompressionBeforeNativeCreation() throws Exception {
        List<Consumer<MongoClientSettings.Builder>> policies = List.of(
                b -> b.addCommandListener(mock(CommandListener.class)),
                b -> b.applyToClusterSettings(c -> c.serverSelector(description -> List.of())),
                b -> b.inetAddressResolver(host -> List.of()),
                b -> b.streamFactoryFactory((s, tls) -> address -> null),
                b -> b.compressorList(List.of(MongoCompressor.createZlibCompressor())),
                b -> b.applyToSocketSettings(s -> s.applyToProxySettings(p -> p.host("proxy-policy"))),
                b -> b.autoEncryptionSettings(AutoEncryptionSettings.builder()
                        .keyVaultNamespace("private.keys").kmsProviders(Map.of()).build()));
        for (Consumer<MongoClientSettings.Builder> policy : policies) {
            try (Fixture f = new Fixture()) {
                MongoClientSettings.Builder changed = MongoClientSettings.builder(f.settings);
                policy.accept(changed);
                f.settings = changed.build();
                assertFailure(() -> f.access().open(f.request()), MissingReason.UNSUPPORTED, false);
                verifyNoInteractions(f.connection, f.business);
            }
        }
    }

    @Test
    void preservesStaticSupportedAuthButRejectsDynamicOrUnknownMechanismProperties() throws Exception {
        List<MongoCredential> supported = List.of(
                MongoCredential.createCredential("u", "admin", new char[]{'p'}),
                MongoCredential.createScramSha1Credential("u", "admin", new char[]{'p'}),
                MongoCredential.createScramSha256Credential("u", "admin", new char[]{'p'}),
                MongoCredential.createPlainCredential("u", "$external", new char[]{'p'}),
                MongoCredential.createMongoX509Credential());
        for (MongoCredential credential : supported) {
            try (Fixture f = new Fixture()) {
                f.settings = MongoClientSettings.builder(f.settings).credential(credential).build();
                try (var ignored = f.access().open(f.request())) {
                    assertThat(f.captured.get().getCredential()).isSameAs(credential);
                }
            }
        }
        for (MongoCredential credential : List.of(MongoCredential.createGSSAPICredential("u"),
                MongoCredential.createAwsCredential(null, null), supported.get(0).withMechanismProperty("custom-policy", "hidden"))) {
            try (Fixture f = new Fixture()) {
                f.settings = MongoClientSettings.builder(f.settings).credential(credential).build();
                assertFailure(() -> f.access().open(f.request()), MissingReason.UNSUPPORTED, false);
                verifyNoInteractions(f.connection);
            }
        }
    }

    @Test
    void sendsOnlyFixedCommandsThroughSameConnectionAndNoSessionContext() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            assertThat(session.ping()).isEqualTo(f.ok);
            session.hello();
            session.serverStatus();
            assertThat(f.commands).containsExactly("ping", "hello", "serverStatus");
            assertThat(f.contexts).allSatisfy(context -> assertThat(context.getClass().getSimpleName()).isEqualTo("NoOpSessionContext"));
            verify(f.connection).open();
            verifyNoInteractions(f.business);
        }
    }

    @Test
    void permissionFailureDoesNotLoseOtherSameConnectionReads() throws Exception {
        try (Fixture f = new Fixture()) {
            f.failure = commandFailure(13);
            try (var session = f.access().open(f.request())) {
                assertFailure(session::serverStatus, MissingReason.UNAUTHORIZED, false);
                f.failure = null;
                assertThat(session.ping()).isEqualTo(f.ok);
                verify(f.connection, never()).close();
            }
            assertThat(f.commands).containsExactly("serverStatus", "ping");
        }
    }

    @Test
    void legacyHelloFallbackRequiresExactCommandNotFound() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.failure = commandFailure(59);
            f.failOnlyOnce = true;
            session.hello();
            assertThat(f.commands).containsExactly("hello", "isMaster");
            f.commands.clear();
            f.failure = commandFailure(13);
            assertFailure(session::hello, MissingReason.UNAUTHORIZED, false);
            assertThat(f.commands).containsExactly("hello");
        }
    }

    @Test
    void transportTimeoutIsTerminalAndDoesNotReadLateReplyOrReconnect() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.failure = new MongoSocketReadTimeoutException("private-native-message", new ServerAddress("private-host"),
                    new java.net.SocketTimeoutException("private-cause"));
            assertFailure(session::ping, MissingReason.TIMEOUT, true);
            f.failure = null;
            assertFailure(session::hello, MissingReason.TIMEOUT, true);
            assertThat(f.commands).containsExactly("ping");
            verify(f.connection).open();
        }
    }

    @Test
    void cancellationDuringNativeFactoryRetainsReservationUntilOriginalWorkerReturns() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            MongoMonitoringConnections access = new MongoMonitoringConnections((s, streams) -> {
                entered.countDown();
                try { released.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return f.connection;
            });
            CompletableFuture<MissingReason> worker = CompletableFuture.supplyAsync(() -> {
                try (var ignored = access.open(f.request())) { return null; }
                catch (MongoMonitoringConnections.Failure failure) { return failure.reason(); }
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                f.control.cancel(MissingReason.TIMEOUT);
                f.drain();
                assertThat(f.control.isCleanupComplete()).isFalse();
                assertThat(worker.isDone()).isFalse();
            } finally { released.countDown(); }
            assertThat(worker.get(2, TimeUnit.SECONDS)).isEqualTo(MissingReason.TIMEOUT);
            verify(f.connection, never()).open();
            verify(f.connection).close();
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void expiredOperationUsesCollectionReasonAndCloseFailureRetainsCleanupBarrier() throws Exception {
        try (Fixture f = new Fixture()) {
            var session = f.access().open(f.request());
            f.control.cancel(MissingReason.TIMEOUT);
            assertFailure(session::ping, MissingReason.TIMEOUT, false);
            session.close();
            f.drain();
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
        try (Fixture f = new Fixture()) {
            var session = f.access().open(f.request());
            doThrow(new IllegalStateException("private-cleanup-text")).when(f.connection).close();
            assertFailure(session::close, MissingReason.FAILED, true);
            assertThat(f.control.isCleanupComplete()).isFalse();
        }
    }

    @Test
    void rejectsReplicaSetConstraintMismatchWithoutBorrowingOrChangingSource() throws Exception {
        try (Fixture f = new Fixture()) {
            f.settings = MongoClientSettings.builder(f.settings).applyToClusterSettings(c -> c.requiredReplicaSetName("expected")).build();
            ServerDescription actual = ServerDescription.builder()
                    .address(new ServerAddress("native-a")).type(ServerType.REPLICA_SET_PRIMARY).setName("different")
                    .state(ServerConnectionState.CONNECTED).ok(true).minWireVersion(0).maxWireVersion(17).build();
            when(f.connection.getInitialServerDescription()).thenReturn(actual);
            assertFailure(() -> f.access().open(f.request()), MissingReason.UNSUPPORTED, false);
            verify(f.connection).close();
            verifyNoInteractions(f.business);
        }
    }

    @Test
    void retainsNativeWireVersionCompatibilityInsteadOfAcceptingAnythingThatAnswersPing() throws Exception {
        for (int[] wire : List.of(new int[]{0, 5}, new int[]{100, 100})) {
            try (Fixture f = new Fixture()) {
                ServerDescription actual = description(wire[0], wire[1]);
                when(f.connection.getInitialServerDescription()).thenReturn(actual);
                assertFailure(() -> f.access().open(f.request()), MissingReason.UNSUPPORTED, false);
                verify(f.connection).close();
            }
        }
        for (int maxWire : List.of(6, 17)) {
            try (Fixture f = new Fixture()) {
                ServerDescription actual = description(0, maxWire);
                when(f.connection.getInitialServerDescription()).thenReturn(actual);
                try (var session = f.access().open(f.request())) { assertThat(session.ping()).isEqualTo(f.ok); }
            }
        }
    }

    @Test
    void classifiesTlsHandshakeSocketTimeoutWrappedByNativeWriteException() throws Exception {
        try (Fixture f = new Fixture()) {
            doThrow(new MongoSocketWriteException("private-native-message", new ServerAddress("private-host"),
                    new java.net.SocketTimeoutException("private-cause"))).when(f.connection).open();
            assertFailure(() -> f.access().open(f.request()), MissingReason.TIMEOUT, true);
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void acceptsNativeReplyAndModernMessageWithNestedDocumentsArraysBinaryAndChecksum() {
        BsonDocument doc = new BsonDocument("ok", new BsonInt32(1))
                .append("nested", new BsonDocument("array", new BsonArray(List.of(new BsonDocument("value", new BsonInt32(3))))))
                .append("binary", new BsonBinary(new byte[]{1, 2, 3}));
        byte[] bson = bson(doc);
        for (int opcode : List.of(1, 2013)) {
            var guard = new MongoMonitoringConnections.ReplyGuard();
            byte[] body = body(bson, opcode, false);
            guard.accept(header(body.length, opcode), 16);
            guard.accept(body, body.length);
            byte[] checksummed = body(bson, 2013, true);
            guard.accept(header(checksummed.length, 2013), 16);
            guard.accept(checksummed, checksummed.length);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE, -1, 0, 20, 2097153})
    void rejectsOversizedOrMalformedNativeWireLengthBeforeAllocatingBody(int total) {
        var guard = new MongoMonitoringConnections.ReplyGuard();
        byte[] header = header(5, 2013);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(total);
        assertFailure(() -> guard.accept(header, 16), MissingReason.INVALID_VALUE, true);
    }

    @Test
    void rejectsCompressedWireAndHugeBinaryLengthWithoutPayloadAllocation() {
        var compressed = new MongoMonitoringConnections.ReplyGuard();
        assertFailure(() -> compressed.accept(header(12, 2012), 16), MissingReason.INVALID_VALUE, true);
        byte[] bson = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(13).put((byte) 5).put((byte) 'b').put((byte) 0).putInt(Integer.MAX_VALUE).put((byte) 0).put((byte) 0).array();
        var binary = new MongoMonitoringConnections.ReplyGuard();
        byte[] body = body(bson, 2013, false);
        binary.accept(header(body.length, 2013), 16);
        assertFailure(() -> binary.accept(body, body.length), MissingReason.INVALID_VALUE, true);
    }

    @Test
    void rejectsDeepBsonBeforeNativeDocumentCodecRecursion() {
        BsonDocument document = new BsonDocument("leaf", new BsonInt32(1));
        for (int i = 0; i < 40; i++) document = new BsonDocument("nested", document);
        byte[] body = body(bson(document), 2013, false);
        var guard = new MongoMonitoringConnections.ReplyGuard();
        guard.accept(header(body.length, 2013), 16);
        assertFailure(() -> guard.accept(body, body.length), MissingReason.INVALID_VALUE, true);
    }

    private static void assertFailure(Runnable action, MissingReason reason, boolean connection) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(MongoMonitoringConnections.Failure.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.connectionFailure()).isEqualTo(connection);
            assertThat(failure.getMessage()).isEqualTo("MongoDB monitoring operation unavailable");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getStackTrace()).isEmpty();
        });
    }
    private static MongoCommandException commandFailure(int code) {
        return new MongoCommandException(new BsonDocument("ok", new BsonInt32(0)).append("code", new BsonInt32(code))
                .append("errmsg", new BsonString("private-native-text")), new ServerAddress("private-host"));
    }
    private static ServerDescription description(int min, int max) {
        return ServerDescription.builder().address(new ServerAddress("native-a")).type(ServerType.STANDALONE)
                .state(ServerConnectionState.CONNECTED).ok(true).minWireVersion(min).maxWireVersion(max).build();
    }
    private static byte[] bson(BsonDocument document) {
        try (BasicOutputBuffer output = new BasicOutputBuffer(); BsonBinaryWriter writer = new BsonBinaryWriter(output)) {
            new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
            return output.toByteArray();
        }
    }
    private static byte[] body(byte[] bson, int opcode, boolean checksum) {
        ByteBuffer output = ByteBuffer.allocate(bson.length + (opcode == 1 ? 20 : 5) + (checksum ? 4 : 0))
                .order(ByteOrder.LITTLE_ENDIAN);
        if (opcode == 1) output.putInt(0).putLong(0).putInt(0).putInt(1);
        else output.putInt(checksum ? 1 : 0).put((byte) 0);
        output.put(bson);
        if (checksum) output.putInt(0);
        return output.array();
    }
    private static byte[] header(int body, int opcode) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(body + 16).putInt(1).putInt(1).putInt(opcode).array();
    }
    private static final class Fixture implements AutoCloseable {
        private final Object business = mock(Object.class);
        private final InternalConnection connection = mock(InternalConnection.class);
        private final AtomicLong ticker = new AtomicLong();
        private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        private final CollectionControl control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), ticker::get,
                TimeUnit.SECONDS.toNanos(5), cleanup, new MonitoringCounterStore(1, 8), "mongo", "mongoClient",
                MetricContract.CollectionKind.ORDINARY, Duration.ofSeconds(15), business);
        private MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString("mongodb://native-a:27017")).build();
        private final AtomicReference<MongoClientSettings> captured = new AtomicReference<>();
        private final List<String> commands = new ArrayList<>();
        private final List<Object> contexts = new ArrayList<>();
        private final BsonDocument ok = new BsonDocument("ok", new BsonInt32(1));
        private RuntimeException failure;
        private boolean failOnlyOnce;
        private Fixture() throws Exception {
            ConnectionDescription connectionDescription = new ConnectionDescription(new ServerId(new ClusterId(), new ServerAddress("native-a")));
            ServerDescription initialDescription = description(0, 17);
            when(connection.getDescription()).thenReturn(connectionDescription);
            when(connection.getInitialServerDescription()).thenReturn(initialDescription);
            when(connection.sendAndReceive(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                CommandMessage message = invocation.getArgument(0);
                Field field = CommandMessage.class.getDeclaredField("command");
                field.setAccessible(true);
                BsonDocument document = (BsonDocument) field.get(message);
                assertThat(document.size()).isEqualTo(1);
                commands.add(document.getFirstKey());
                contexts.add(invocation.getArgument(2));
                RuntimeException pending = failure;
                if (failOnlyOnce) failure = null;
                if (pending != null) throw pending;
                return ok;
            });
        }
        private MongoMonitoringConnections access() {
            return new MongoMonitoringConnections((effective, streams) -> { captured.set(effective); return connection; });
        }
        private CollectionRequest request() {
            Instant now = Instant.now();
            return new CollectionRequest("mongo", "mongoClient", "mongoClient", MetricContract.CollectionKind.ORDINARY,
                    1, 1, now, now.plusSeconds(5), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    business, Map.of("mongoSettings", settings), control);
        }
        private void drain() throws Exception { cleanup.submit(() -> {}).get(2, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            control.cancel(MissingReason.FAILED);
            cleanup.shutdown();
            if (!cleanup.awaitTermination(3, TimeUnit.SECONDS)) {
                cleanup.shutdownNow();
                throw new AssertionError("Mongo monitoring test cleanup did not stop");
            }
        }
    }
}
