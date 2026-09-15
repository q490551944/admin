package com.hpj.admin.monitor.redis;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.DefaultJedisSocketFactory;
import redis.clients.jedis.HostAndPort;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Native Jedis command framing and lifecycle controls, with in-memory sockets instead of external Redis. */
class MonitoringRedisConnectionsTest {
    @Test
    void sendsOnlyAllowedReadsAndAuthSelectWithoutTouchingTheBusinessClient() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("username", "resolved-reader");
            fixture.settings.put("password", "resolved-secret");
            fixture.settings.put("database", 3);
            FakeSocket socket = new FakeSocket("+OK\r\n+OK\r\n+PONG\r\n" + bulk("# Clients\r\nconnected_clients:2\r\n"));
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertThat(session.ping()).isEqualTo("PONG");
                assertThat(session.info("clients")).isEqualTo("# Clients\r\nconnected_clients:2\r\n");
            }
            assertThat(socket.commands()).isEqualTo(resp("AUTH", "resolved-reader", "resolved-secret")
                    + resp("SELECT", "3") + resp("PING") + resp("INFO", "clients"));
            assertThat(socket.closed).isTrue();
            assertThat(socket.endpoint).isEqualTo(new InetSocketAddress(InetAddress.getLoopbackAddress(), 6381));
            assertThat(fixture.settings).containsEntry("password", "resolved-secret").containsEntry("database", 3);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
            verifyNoInteractions(fixture.business);
        }
    }

    @Test
    void passwordOnlyUsesOneArgumentAuthAndDatabaseZeroNeedsNoSelect() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("password", "secret");
            FakeSocket socket = new FakeSocket("+OK\r\n+PONG\r\n");
            try (var session = fixture.access(socket).open(fixture.request())) { session.ping(); }
            assertThat(socket.commands()).isEqualTo(resp("AUTH", "secret") + resp("PING"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "default", "keyspace", "cpu", "memory", "commandstats", "stats\r\nCONFIG GET *", "STATS", ""})
    @NullSource
    void rejectsUnapprovedInfoSectionsBeforeSendingAnyCommand(String section) throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket("");
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertFailure(() -> session.info(section), MissingReason.UNSUPPORTED, false);
                assertThat(socket.commands()).isEmpty();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cluster", "sentinel", "static-master-replica", "master-slave", "replicated", "socket", "unknown"})
    void doesNotGuessTopologyOrConnectToAnArbitraryMember(String topology) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("topology", topology);
            assertUnsupportedWithoutAcquisition(fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tls", "dynamicCredentials", "routing", "unknownClient", "missingClient", "badPort", "badDatabase", "badHost", "badCredential"})
    void unsupportedNativeConfigurationIsSanitizedBeforeDnsAndSocketAcquisition(String configuration) throws Exception {
        try (Fixture fixture = new Fixture()) {
            switch (configuration) {
                case "tls" -> fixture.settings.put("tls", true);
                case "dynamicCredentials" -> fixture.settings.put("dynamicCredentials", true);
                case "routing" -> fixture.settings.put("defaultRouting", false);
                case "unknownClient" -> fixture.settings.put("clientConfiguration", new Object());
                case "missingClient" -> fixture.settings.remove("clientConfiguration");
                case "badPort" -> fixture.settings.put("port", 70000);
                case "badDatabase" -> fixture.settings.put("database", -1);
                case "badHost" -> fixture.settings.put("host", "private@secret");
                case "badCredential" -> fixture.settings.put("password", new Object());
                default -> throw new AssertionError(configuration);
            }
            assertUnsupportedWithoutAcquisition(fixture);
        }
    }

    @Test
    void actualRunningJedisSocketTimeoutsOverrideLaterClientConfigurationWithoutMutatingEither() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var actual = DefaultJedisClientConfig.builder().connectionTimeoutMillis(173).socketTimeoutMillis(211).build();
            var socketFactory = new DefaultJedisSocketFactory(new HostAndPort("resolved.example", 6381), actual);
            fixture.settings.put("socketFactory", socketFactory);
            fixture.settings.put("clientConfiguration", DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(9000).socketTimeoutMillis(9000).build());
            FakeSocket socket = new FakeSocket("+PONG\r\n");
            try (var session = fixture.access(socket).open(fixture.request())) { session.ping(); }
            assertThat(socket.connectTimeout).isEqualTo(173);
            assertThat(socket.readTimeouts).allMatch(value -> value == 211);
            assertThat(socketFactory.getHostAndPort()).isEqualTo(new HostAndPort("resolved.example", 6381));
            assertThat(actual.getSocketTimeoutMillis()).isEqualTo(211);
        }
    }

    @Test
    void springJedisRetainsShorterNativeConnectAndReadTimeouts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("clientConfiguration", JedisClientConfiguration.builder()
                    .connectTimeout(Duration.ofMillis(91)).readTimeout(Duration.ofMillis(137)).build());
            FakeSocket socket = new FakeSocket("+PONG\r\n");
            try (var session = fixture.access(socket).open(fixture.request())) { session.ping(); }
            assertThat(socket.connectTimeout).isEqualTo(91);
            assertThat(socket.readTimeouts).allMatch(value -> value == 137);
        }
    }

    @Test
    void springLettucePreservesConnectAndShorterFixedCommandTimeout() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("clientConfiguration", LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofMillis(900))
                    .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                            .connectTimeout(Duration.ofMillis(133)).build())
                            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(277))).build()).build());
            FakeSocket socket = new FakeSocket("+PONG\r\n");
            try (var session = fixture.access(socket).open(fixture.request())) { session.ping(); }
            assertThat(socket.connectTimeout).isEqualTo(133);
            assertThat(socket.readTimeouts).allMatch(value -> value == 277);
        }
    }

    @Test
    void arbitraryLettuceTimeoutCallbackIsRejectedWithoutInvokingIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var source = mock(TimeoutOptions.TimeoutSource.class);
            fixture.settings.put("clientConfiguration", LettuceClientConfiguration.builder()
                    .clientOptions(ClientOptions.builder().timeoutOptions(TimeoutOptions.builder().timeoutSource(source).build()).build())
                    .build());
            assertUnsupportedWithoutAcquisition(fixture);
            verifyNoInteractions(source);
        }
    }

    @Test
    void redissonRetainsActualServerTimeoutWithoutCopyingOrChangingItsConfig() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Config nativeConfig = new Config();
            var server = nativeConfig.useSingleServer().setAddress("redis://resolved.example:6381")
                    .setConnectTimeout(187).setTimeout(293);
            fixture.settings.put("clientConfiguration", server);
            fixture.settings.put("configuration", nativeConfig);
            FakeSocket socket = new FakeSocket("+PONG\r\n");
            try (var session = fixture.access(socket).open(fixture.request())) { session.ping(); }
            assertThat(socket.connectTimeout).isEqualTo(187);
            assertThat(socket.readTimeouts).allMatch(value -> value == 293);
            assertThat(nativeConfig.getCodec()).isNull();
            assertThat(nativeConfig.useSingleServer()).isSameAs(server);
            assertThat(server.getTimeout()).isEqualTo(293);
        }
    }

    @Test
    void remainingBudgetIncludesDnsAndShrinksForEveryCommand() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("clientConfiguration", DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(9000).socketTimeoutMillis(9000).build());
            FakeSocket socket = new FakeSocket("+PONG\r\n" + bulk("# Stats\r\n"));
            var access = new RedisMonitoringConnections(() -> socket, host -> {
                fixture.ticker.set(TimeUnit.SECONDS.toNanos(2));
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            });
            try (var session = access.open(fixture.request())) {
                assertThat(socket.connectTimeout).isEqualTo(3000);
                session.ping();
                fixture.ticker.set(TimeUnit.MILLISECONDS.toNanos(4750));
                session.info("stats");
                assertThat(socket.readTimeouts).contains(3000, 250);
                fixture.ticker.set(TimeUnit.SECONDS.toNanos(5));
                assertFailure(session::ping, MissingReason.TIMEOUT, false);
            }
            assertThat(socket.commands()).isEqualTo(resp("PING") + resp("INFO", "stats"));
        }
    }

    @Test
    void canceledDnsKeepsReservationUntilItReturnsAndCannotCreateALateSocket() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger sockets = new AtomicInteger();
            var access = new RedisMonitoringConnections(() -> { sockets.incrementAndGet(); return new FakeSocket(""); }, host -> {
                fixture.control.cancel(MissingReason.TIMEOUT);
                fixture.drainCleanup();
                assertThat(fixture.control.isCleanupComplete()).isFalse();
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            });
            assertFailure(() -> access.open(fixture.request()), MissingReason.TIMEOUT, false);
            assertThat(sockets).hasValue(0);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void socketCreatedDuringCancellationIsClosedBeforeConnect() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket("");
            var access = new RedisMonitoringConnections(() -> {
                fixture.control.cancel(MissingReason.TIMEOUT);
                return socket;
            }, host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
            assertFailure(() -> access.open(fixture.request()), MissingReason.TIMEOUT, false);
            fixture.drainCleanup();
            assertThat(socket.closed).isTrue();
            assertThat(socket.connectTimeout).isZero();
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void socketIsRegisteredBeforeConnectAndCancellationClosesIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket("") {
                @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    fixture.control.cancel(MissingReason.TIMEOUT);
                    fixture.drainCleanup();
                    assertThat(closed).isTrue();
                    throw new IOException("private-endpoint/private-secret");
                }
            };
            assertFailure(() -> fixture.access(socket).open(fixture.request()), MissingReason.TIMEOUT, false);
            assertThat(socket.closed).isTrue();
        }
    }

    @Test
    void nativeConnectTimeoutKeepsTimeoutMeaningWithoutLeakingTheCause() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket("") {
                @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    throw new SocketTimeoutException("private-endpoint/private-secret");
                }
            };
            assertFailure(() -> fixture.access(socket).open(fixture.request()), MissingReason.TIMEOUT, true);
            assertThat(socket.closed).isTrue();
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOAUTH authentication required private-secret", "WRONGPASS private-secret", "NOPERM private-user cannot INFO"})
    void permissionFailuresAreSanitizedAndDoNotPreventALaterAllowedInfoSection(String error) throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket("+PONG\r\n-" + error + "\r\n" + bulk("role:master\r\n"));
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertThat(session.ping()).isEqualTo("PONG");
                assertFailure(() -> session.info("stats"), MissingReason.UNAUTHORIZED, false);
                assertThat(session.info("replication")).isEqualTo("role:master\r\n");
            }
            assertThat(socket.commands()).isEqualTo(resp("PING") + resp("INFO", "stats") + resp("INFO", "replication"));
        }
    }

    @Test
    void errorTextContainingPermissionWordDoesNotBecomeAPermissionFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket("-ERR unsupported NOPERM private-secret\r\n");
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertFailure(() -> session.info("stats"), MissingReason.FAILED, false);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"$2147483647\r\n", "*2147483647\r\n", "$262145\r\n", "$9999999999999999999999999\r\n",
            "$-2\r\n", "$1x\r\n", "%1\r\n", ":123\r\n", "$1\r\nx!!"})
    void invalidOrOversizedNativeReplyIsRejectedBeforeDecoderAllocationAndRetiresTheSocket(String reply) throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket(reply);
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertFailure(() -> session.info("stats"), MissingReason.INVALID_VALUE, false);
                assertThat(socket.closed).isTrue();
                assertFailure(() -> session.info("clients"), MissingReason.INVALID_VALUE, false);
                assertThat(socket.commands()).isEqualTo(resp("INFO", "stats"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"+", "-"})
    void boundsNativeSimpleAndErrorLinesBeforeTheirStringAllocation(String prefix) throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket(prefix + "x".repeat(RedisMonitoringConnections.MAX_STATUS_BYTES + 1) + "\r\n");
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertFailure(session::ping, MissingReason.INVALID_VALUE, false);
                assertThat(socket.closed).isTrue();
            }
        }
    }

    @Test
    void boundedBulkIsStillDecodedByTheNativeDriverAcrossMultipleReads() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String response = "x".repeat(RedisMonitoringConnections.MAX_REPLY_BYTES);
            FakeSocket socket = new FakeSocket(bulk(response));
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertThat(session.info("stats")).isEqualTo(response);
            }
        }
    }

    @Test
    void aFragmentedOversizedHeaderCannotBypassValidationBeforeNativeAllocation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] reply = "$2147483647\r\n".getBytes(StandardCharsets.UTF_8);
            FakeSocket socket = new FakeSocket("") {
                @Override public InputStream getInputStream() {
                    return new ByteArrayInputStream(reply) {
                        @Override public synchronized int read(byte[] bytes, int offset, int length) {
                            return super.read(bytes, offset, Math.min(1, length));
                        }
                    };
                }
            };
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertFailure(() -> session.info("stats"), MissingReason.INVALID_VALUE, false);
                assertThat(socket.closed).isTrue();
            }
        }
    }

    @Test
    void nativeReadTimeoutIsTerminalSoLaterSectionsCannotConsumeALateResponseOrReconnect() throws Exception {
        try (Fixture fixture = new Fixture()) {
            FakeSocket socket = new FakeSocket("") {
                @Override public InputStream getInputStream() {
                    return new InputStream() {
                        @Override public int read() throws IOException {
                            throw new SocketTimeoutException("private-endpoint/private-secret");
                        }
                    };
                }
            };
            try (var session = fixture.access(socket).open(fixture.request())) {
                assertFailure(() -> session.info("stats"), MissingReason.TIMEOUT, true);
                assertThat(socket.closed).isTrue();
                assertFailure(() -> session.info("clients"), MissingReason.TIMEOUT, true);
                assertThat(socket.commands()).isEqualTo(resp("INFO", "stats"));
            }
        }
    }

    @Test
    void infoRedirectIsUnsupportedAndNeverOpensAnotherConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger acquisitions = new AtomicInteger();
            FakeSocket socket = new FakeSocket("-MOVED 1234 10.1.2.3:6380\r\n");
            var access = new RedisMonitoringConnections(() -> { acquisitions.incrementAndGet(); return socket; },
                    host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
            try (var session = access.open(fixture.request())) {
                assertFailure(() -> session.info("stats"), MissingReason.UNSUPPORTED, false);
            }
            assertThat(acquisitions).hasValue(1);
        }
    }

    @Test
    void closedSessionCannotReconnectOrEmitCommands() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger acquisitions = new AtomicInteger();
            FakeSocket socket = new FakeSocket("");
            var access = new RedisMonitoringConnections(() -> { acquisitions.incrementAndGet(); return socket; },
                    host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
            var session = access.open(fixture.request());
            session.close();
            assertFailure(session::ping, MissingReason.FAILED, true);
            assertThat(acquisitions).hasValue(1);
            assertThat(socket.commands()).isEmpty();
        }
    }

    private static void assertUnsupportedWithoutAcquisition(Fixture fixture) {
        AtomicInteger acquisitions = new AtomicInteger();
        var access = new RedisMonitoringConnections(() -> { acquisitions.incrementAndGet(); return new FakeSocket(""); },
                host -> { acquisitions.incrementAndGet(); return new InetAddress[]{InetAddress.getLoopbackAddress()}; });
        assertFailure(() -> access.open(fixture.request()), MissingReason.UNSUPPORTED, false);
        assertThat(acquisitions).hasValue(0);
        verifyNoInteractions(fixture.business);
    }

    private static void assertFailure(Runnable action, MissingReason reason, boolean connectionFailure) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RedisMonitoringConnections.Failure.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.connectionFailure()).isEqualTo(connectionFailure);
            assertThat(failure).hasMessage("Redis monitoring operation unavailable").hasNoCause();
        });
    }

    private static String bulk(String text) { return "$" + text.getBytes(StandardCharsets.UTF_8).length + "\r\n" + text + "\r\n"; }

    private static String resp(String... arguments) {
        StringBuilder value = new StringBuilder("*").append(arguments.length).append("\r\n");
        for (String argument : arguments) value.append(bulk(argument));
        return value.toString();
    }

    private static class FakeSocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final List<Integer> readTimeouts = new ArrayList<>();
        protected boolean closed;
        private boolean connected;
        private int connectTimeout;
        private SocketAddress endpoint;
        private int soTimeout;

        private FakeSocket(String replies) { input = new ByteArrayInputStream(replies.getBytes(StandardCharsets.UTF_8)); }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            this.endpoint = endpoint;
            this.connectTimeout = timeout;
            connected = true;
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public boolean isBound() { return connected; }
        @Override public boolean isConnected() { return connected; }
        @Override public boolean isClosed() { return closed; }
        @Override public void setReuseAddress(boolean value) { }
        @Override public void setKeepAlive(boolean value) { }
        @Override public void setTcpNoDelay(boolean value) { }
        @Override public void setSoTimeout(int timeout) { soTimeout = timeout; readTimeouts.add(timeout); }
        @Override public int getSoTimeout() { return soTimeout; }
        @Override public void close() { closed = true; }
        private String commands() { return output.toString(StandardCharsets.UTF_8); }
    }

    private static final class Fixture implements AutoCloseable {
        private final Object business = mock(Object.class);
        private final AtomicLong ticker = new AtomicLong();
        private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        private final CollectionControl control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), ticker::get,
                TimeUnit.SECONDS.toNanos(5), cleanup, new MonitoringCounterStore(1, 8), "redis", "redisFactory",
                MetricContract.CollectionKind.ORDINARY, Duration.ofSeconds(15), business);
        private final Map<String, Object> settings = new LinkedHashMap<>(Map.of(
                "host", "resolved.example", "port", 6381, "database", 0, "topology", "standalone", "tls", false,
                "dynamicCredentials", false, "defaultRouting", true, "configuration", new RedisStandaloneConfiguration("resolved.example", 6381),
                "clientConfiguration", DefaultJedisClientConfig.builder().build()));

        private RedisMonitoringConnections access(FakeSocket socket) {
            return new RedisMonitoringConnections(() -> socket, host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
        }

        private CollectionRequest request() {
            Instant now = Instant.now();
            return new CollectionRequest("redis", "redisFactory", "redisFactory", MetricContract.CollectionKind.ORDINARY,
                    1, 1, now, now.plusSeconds(5), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    business, settings, control);
        }

        private void drainCleanup() throws IOException {
            try { cleanup.submit(() -> { }).get(3, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IOException("Monitoring test cleanup did not finish"); }
        }

        @Override public void close() throws InterruptedException {
            control.cancel(MissingReason.FAILED);
            cleanup.shutdown();
            if (!cleanup.awaitTermination(3, TimeUnit.SECONDS)) {
                cleanup.shutdownNow();
                throw new AssertionError("Monitoring test cleanup did not terminate");
            }
        }
    }
}
