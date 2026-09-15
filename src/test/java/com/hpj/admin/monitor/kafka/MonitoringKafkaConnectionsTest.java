package com.hpj.admin.monitor.kafka;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native config parsing, independent metadata futures and real lifecycle controls; no remote broker is needed. */
class MonitoringKafkaConnectionsTest {
    @Test
    void preservesNativeTlsDnsAndCredentialsAndOnlyBoundsOwnedSettings() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("security.protocol", "SSL");
            fixture.settings.put("client.dns.lookup", "resolve_canonical_bootstrap_servers_only");
            fixture.settings.put("ssl.keystore.type", "PKCS12");
            fixture.settings.put("ssl.keystore.location", "private-keystore.p12");
            fixture.settings.put("ssl.keystore.password", new Password("private-secret"));
            fixture.settings.put("ssl.truststore.location", "private-truststore.p12");
            fixture.settings.put("ssl.endpoint.identification.algorithm", "HTTPS");
            fixture.settings.put("ssl.engine.factory.class", "org.apache.kafka.common.security.ssl.DefaultSslEngineFactory");
            fixture.settings.put("client.id", "private-business-client");
            fixture.settings.put("group.id", "private-group");
            fixture.settings.put("key.deserializer", "private.UnusedDeserializer");
            fixture.ticker.set(TimeUnit.SECONDS.toNanos(2));
            AtomicReference<Map<String, Object>> copied = new AtomicReference<>();
            var access = new KafkaMonitoringConnections(properties -> { copied.set(properties); return fixture.admin; });
            try (var ignored = access.open(fixture.request())) {
                assertThat(copied.get()).containsEntry("security.protocol", "SSL")
                        .containsEntry("client.dns.lookup", "resolve_canonical_bootstrap_servers_only")
                        .containsEntry("ssl.keystore.type", "PKCS12")
                        .containsEntry("ssl.keystore.location", "private-keystore.p12")
                        .containsEntry("ssl.truststore.location", "private-truststore.p12")
                        .containsEntry("ssl.endpoint.identification.algorithm", "HTTPS");
                assertThat(((Password) copied.get().get("ssl.keystore.password")).value()).isEqualTo("private-secret");
                assertThat(copied.get().get("client.id")).asString().startsWith("admin-monitor-kafka-");
                assertThat(copied.get()).containsEntry("request.timeout.ms", 3000).containsEntry("default.api.timeout.ms", 3000)
                        .containsEntry("socket.connection.setup.timeout.ms", 3000)
                        .doesNotContainKeys("group.id", "key.deserializer");
            }
            assertThat(fixture.settings).containsEntry("client.id", "private-business-client").containsEntry("group.id", "private-group")
                    .doesNotContainKey("request.timeout.ms");
            verify(fixture.admin).close(Duration.ZERO);
            verifyNoInteractions(fixture.business);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"})
    void preservesExplicitBuiltinSaslWithoutInvokingAGlobalJaasProvider(String mechanism) throws Exception {
        try (Fixture fixture = new Fixture()) {
            String module = mechanism.equals("PLAIN") ? "plain.PlainLoginModule" : "scram.ScramLoginModule";
            Password jaas = new Password("org.apache.kafka.common.security." + module
                    + " required username=\"actual-reader\" password=\"actual-secret\";");
            fixture.settings.put("security.protocol", "SASL_SSL");
            fixture.settings.put("sasl.mechanism", mechanism);
            fixture.settings.put("sasl.jaas.config", jaas);
            var access = new KafkaMonitoringConnections(properties -> {
                assertThat(properties.get("sasl.jaas.config")).isSameAs(jaas);
                assertThat(properties.get("security.protocol")).isEqualTo("SASL_SSL");
                assertThat(properties.get("sasl.mechanism")).isEqualTo(mechanism);
                return fixture.admin;
            });
            try (var ignored = access.open(fixture.request())) { }
            assertThat(fixture.settings.get("sasl.jaas.config")).isSameAs(jaas);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"config.providers", "config.providers.private.class", "security.providers", "metric.reporters",
            "ssl.engine.factory.class", "sasl.login.class", "sasl.login.callback.handler.class", "sasl.client.callback.handler.class"})
    void customCodeIsRejectedBeforeNativeFactoryOrClassLoading(String property) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put(property, "private.CredentialCallback");
            assertUnsupportedBeforeCreation(fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GSSAPI", "OAUTHBEARER", "UNKNOWN"})
    void refreshOrUnknownSaslMechanismIsExplicitlyUnsupported(String mechanism) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("security.protocol", "SASL_PLAINTEXT");
            fixture.settings.put("sasl.mechanism", mechanism);
            fixture.settings.put("sasl.jaas.config", "private.LoginModule required username=\"secret\" password=\"secret\";");
            assertUnsupportedBeforeCreation(fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "custom", "multiple", "missing-password", "wrong-module"})
    void staticSaslRequiresOneExplicitCorrectBuiltinModuleWithActualCredentials(String configuration) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("security.protocol", "SASL_PLAINTEXT");
            fixture.settings.put("sasl.mechanism", "PLAIN");
            String plain = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"reader\" password=\"secret\";";
            switch (configuration) {
                case "custom" -> fixture.settings.put("sasl.jaas.config", "private.LoginModule required username=\"reader\" password=\"secret\";");
                case "multiple" -> fixture.settings.put("sasl.jaas.config", plain + plain);
                case "missing-password" -> fixture.settings.put("sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"reader\";");
                case "wrong-module" -> fixture.settings.put("sasl.jaas.config", plain.replace("plain.PlainLoginModule", "scram.ScramLoginModule"));
                case "missing" -> { }
                default -> throw new AssertionError(configuration);
            }
            assertUnsupportedBeforeCreation(fixture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"private:secret@host:9092", "host:70000", "host", "http://host:9092", "host:bad", "host:9092?password=secret"})
    void malformedOrCredentialBearingBootstrapIsRejectedBeforeNativeLoggingOrDns(String endpoint) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("bootstrap.servers", endpoint);
            assertUnsupportedBeforeCreation(fixture);
        }
    }

    @Test
    void shorterNativeTimeoutsIncludingZeroAreNotExpanded() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.settings.put("request.timeout.ms", 53);
            fixture.settings.put("default.api.timeout.ms", 127);
            fixture.settings.put("socket.connection.setup.timeout.ms", 0);
            fixture.settings.put("socket.connection.setup.timeout.max.ms", 31);
            var access = new KafkaMonitoringConnections(properties -> {
                assertThat(properties).containsEntry("request.timeout.ms", 53).containsEntry("default.api.timeout.ms", 127)
                        .containsEntry("socket.connection.setup.timeout.ms", 0).containsEntry("socket.connection.setup.timeout.max.ms", 31);
                return fixture.admin;
            });
            try (var ignored = access.open(fixture.request())) { }
        }
    }

    @Test
    void clusterNodesRemainAvailableWhenClusterIdentityIsDenied() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var cluster = cluster(KafkaFuture.completedFuture(List.of(new Node(1, "native-host", 9092))),
                    failed(new ClusterAuthorizationException("private-user denied")));
            when(fixture.admin.describeCluster(any())).thenReturn(cluster);
            try (var session = fixture.access().open(fixture.request())) {
                var result = session.describeCluster();
                assertThat(result.nodes()).extracting(Node::id).containsExactly(1);
                assertThat(result.nodesFailure()).isNull();
                assertFailure(result.clusterIdFailure(), MissingReason.UNAUTHORIZED, false);
                assertThat(result.clusterId()).isNull();
            }
            verify(fixture.admin).describeCluster(argThat(options -> options.timeoutMs() == 5000 && !options.includeAuthorizedOperations()));
            verify(fixture.admin).close(Duration.ZERO);
            verifyNoMoreInteractions(fixture.admin);
        }
    }

    @Test
    void partialTopicAclFailurePreservesAllowedSuccessAndOmitsUnexpectedResults() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TopicDescription good = topic("ready");
            DescribeTopicsResult response = topics(Map.of("ready", KafkaFuture.completedFuture(good),
                    "denied", failed(new TopicAuthorizationException(Set.of("private-topic"))),
                    "outside", KafkaFuture.completedFuture(topic("outside"))));
            when(fixture.admin.describeTopics(anyCollection(), any())).thenReturn(response);
            try (var session = fixture.access().open(fixture.request())) {
                var result = session.describeTopics(List.of("ready", "denied"));
                assertThat(result.keySet()).containsExactly("ready", "denied");
                assertThat(result.get("ready").topic()).isSameAs(good);
                assertThat(result.get("ready").failure()).isNull();
                assertFailure(result.get("denied").failure(), MissingReason.UNAUTHORIZED, false);
                assertThat(result.get("denied").topic()).isNull();
            }
            verify(fixture.admin).describeTopics(eq(List.of("ready", "denied")), argThat(options -> !options.includeAuthorizedOperations()));
        }
    }

    @Test
    void pendingFirstTopicCannotHideAReadyTopicBeforeOrDuringItsWait() throws Exception {
        try (Fixture fixture = new Fixture()) {
            @SuppressWarnings("unchecked") KafkaFuture<TopicDescription> pending = mock(KafkaFuture.class);
            var becomesReady = new KafkaFutureImpl<TopicDescription>();
            when(pending.isDone()).thenReturn(false);
            when(pending.get(anyLong(), any())).thenAnswer(invocation -> {
                becomesReady.complete(topic("later"));
                fixture.ticker.set(TimeUnit.SECONDS.toNanos(5));
                throw new TimeoutException("private-native-timeout");
            });
            var response = topics(Map.of("pending", pending, "ready", KafkaFuture.completedFuture(topic("ready")), "later", becomesReady));
            when(fixture.admin.describeTopics(anyCollection(), any())).thenReturn(response);
            try (var session = fixture.access().open(fixture.request())) {
                var result = session.describeTopics(List.of("pending", "ready", "later"));
                assertFailure(result.get("pending").failure(), MissingReason.TIMEOUT, false);
                assertThat(result.get("ready").topic().name()).isEqualTo("ready");
                assertThat(result.get("later").topic().name()).isEqualTo("later");
            }
        }
    }

    @Test
    void eachNativeRequestUsesCurrentRemainingBudget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var clusterResponse = cluster(KafkaFuture.completedFuture(List.of()), KafkaFuture.completedFuture("id"));
            var topicResponse = topics(Map.of("ready", KafkaFuture.completedFuture(topic("ready"))));
            when(fixture.admin.describeCluster(any())).thenReturn(clusterResponse);
            when(fixture.admin.describeTopics(anyCollection(), any())).thenReturn(topicResponse);
            try (var session = fixture.access().open(fixture.request())) {
                fixture.ticker.set(TimeUnit.SECONDS.toNanos(3));
                session.describeCluster();
                fixture.ticker.set(TimeUnit.MILLISECONDS.toNanos(4750));
                session.describeTopics(List.of("ready"));
            }
            verify(fixture.admin).describeCluster(argThat(options -> options.timeoutMs() == 2000));
            verify(fixture.admin).describeTopics(anyCollection(), argThat(options -> options.timeoutMs() == 250));
        }
    }

    @Test
    void missingOrMismatchedTopicReplyIsInvalidAndScopeEscapeNeverReachesAdmin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var response = topics(Map.of("ready", KafkaFuture.completedFuture(topic("outside"))));
            when(fixture.admin.describeTopics(anyCollection(), any())).thenReturn(response);
            try (var session = fixture.access().open(fixture.request())) {
                var result = session.describeTopics(List.of("ready", "denied"));
                assertFailure(result.get("ready").failure(), MissingReason.INVALID_VALUE, false);
                assertFailure(result.get("denied").failure(), MissingReason.INVALID_VALUE, false);
                assertThat(session.describeTopics(List.of())).isEmpty();
                assertThatThrownBy(() -> session.describeTopics(List.of("outside")))
                        .isInstanceOfSatisfying(KafkaMonitoringConnections.Failure.class,
                                failure -> assertFailure(failure, MissingReason.UNSUPPORTED, false));
            }
            verify(fixture.admin, times(1)).describeTopics(anyCollection(), any());
        }
    }

    @Test
    void failedConstructionIsSanitizedAndReleasesReservationsWithoutTouchingBusinessClient() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var access = new KafkaMonitoringConnections(properties -> { throw new AuthenticationException("private-password"); });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOfSatisfying(KafkaMonitoringConnections.Failure.class,
                    failure -> assertFailure(failure, MissingReason.UNAUTHORIZED, false));
            assertThat(fixture.control.isCleanupComplete()).isTrue();
            verifyNoInteractions(fixture.business);
        }
    }

    @Test
    void cancellationDuringAcquisitionKeepsReservationAndClosesALateAdmin() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var access = new KafkaMonitoringConnections(properties -> {
                fixture.control.cancel(MissingReason.TIMEOUT);
                fixture.drainCleanup();
                assertThat(fixture.control.isCleanupComplete()).isFalse();
                return fixture.admin;
            });
            assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOfSatisfying(KafkaMonitoringConnections.Failure.class,
                    failure -> assertFailure(failure, MissingReason.TIMEOUT, false));
            fixture.drainCleanup();
            verify(fixture.admin).close(Duration.ZERO);
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void cancellationCloseRequestDoesNotReleaseTheOriginalWorkerExitBarrier() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean restored = new AtomicBoolean();
            AtomicInteger joins = new AtomicInteger();
            doAnswer(invocation -> {
                joins.incrementAndGet();
                joined.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return null;
            }).when(fixture.admin).close(Duration.ZERO);
            var session = fixture.access().open(fixture.request());
            CompletableFuture<Void> done = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try { session.close(); restored.set(Thread.currentThread().isInterrupted()); done.complete(null); }
                catch (Throwable failure) { done.completeExceptionally(failure); }
            }, "owned-kafka-close-test");
            worker.start();
            try {
                assertThat(joined.await(2, TimeUnit.SECONDS)).isTrue();
                fixture.control.cancel(MissingReason.TIMEOUT);
                fixture.drainCleanup();
                verify(fixture.admin).close(Duration.ofMillis(1));
                assertThat(fixture.control.isCleanupComplete()).isFalse();
                assertThat(done).isNotDone();
                worker.interrupt();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> joins.get() >= 2);
                assertThat(fixture.control.isCleanupComplete()).isFalse();
                assertThat(done).isNotDone();
            } finally {
                release.countDown();
                done.get(3, TimeUnit.SECONDS);
                worker.join(3000);
            }
            assertThat(restored).isTrue();
            assertThat(fixture.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void failedNativeCloseCannotPretendTheExitBarrierWasReleased() throws Exception {
        try (Fixture fixture = new Fixture()) {
            doThrow(new IllegalStateException("private-native-cleanup")).when(fixture.admin).close(Duration.ZERO);
            var session = fixture.access().open(fixture.request());
            assertThatThrownBy(session::close).isInstanceOfSatisfying(KafkaMonitoringConnections.Failure.class,
                    failure -> assertFailure(failure, MissingReason.FAILED, false));
            assertThat(fixture.control.isCleanupComplete()).isFalse();
        }
    }

    @Test
    void nativeVersionAndTransportFailureClassificationRemainSeparateFromPermissions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var response = cluster(failed(new DisconnectException("private-node")),
                    failed(new UnsupportedVersionException("private-native-version")));
            when(fixture.admin.describeCluster(any())).thenReturn(response);
            try (var session = fixture.access().open(fixture.request())) {
                var result = session.describeCluster();
                assertFailure(result.nodesFailure(), MissingReason.FAILED, true);
                assertFailure(result.clusterIdFailure(), MissingReason.UNSUPPORTED, false);
            }
        }
    }

    @Test
    void configuredTopicThatDoesNotCurrentlyExistIsNotApplicable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var response = topics(Map.of("ready", failed(new UnknownTopicOrPartitionException("private-native-topic"))));
            when(fixture.admin.describeTopics(anyCollection(), any())).thenReturn(response);
            try (var session = fixture.access().open(fixture.request())) {
                assertFailure(session.describeTopics(List.of("ready")).get("ready").failure(), MissingReason.NOT_APPLICABLE, false);
            }
        }
    }

    @Test
    void interruptionFromDeadlineCancellationRetainsItsTimeoutReason() throws Exception {
        try (Fixture fixture = new Fixture()) {
            @SuppressWarnings("unchecked") KafkaFuture<TopicDescription> pending = mock(KafkaFuture.class);
            when(pending.isDone()).thenReturn(false);
            when(pending.get(anyLong(), any())).thenAnswer(invocation -> {
                fixture.control.cancel(MissingReason.TIMEOUT);
                throw new InterruptedException("owned request cancelled");
            });
            var response = topics(Map.of("pending", pending));
            when(fixture.admin.describeTopics(anyCollection(), any())).thenReturn(response);
            try (var session = fixture.access().open(fixture.request())) {
                assertFailure(session.describeTopics(List.of("pending")).get("pending").failure(), MissingReason.TIMEOUT, false);
            } finally { Thread.interrupted(); }
        }
    }

    private static void assertUnsupportedBeforeCreation(Fixture fixture) {
        AtomicInteger calls = new AtomicInteger();
        var access = new KafkaMonitoringConnections(properties -> { calls.incrementAndGet(); return fixture.admin; });
        assertThatThrownBy(() -> access.open(fixture.request())).isInstanceOfSatisfying(KafkaMonitoringConnections.Failure.class,
                failure -> assertFailure(failure, MissingReason.UNSUPPORTED, false));
        assertThat(calls).hasValue(0);
        verifyNoInteractions(fixture.admin, fixture.business);
    }

    private static void assertFailure(KafkaMonitoringConnections.Failure failure, MissingReason reason, boolean connectionFailure) {
        assertThat(failure).isNotNull().hasMessage("Kafka monitoring operation unavailable").hasNoCause();
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.connectionFailure()).isEqualTo(connectionFailure);
    }

    private static TopicDescription topic(String name) { return new TopicDescription(name, false, List.of()); }

    private static <T> KafkaFuture<T> failed(Throwable failure) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static DescribeClusterResult cluster(KafkaFuture<Collection<Node>> nodes, KafkaFuture<String> id) {
        var result = mock(DescribeClusterResult.class);
        when(result.nodes()).thenReturn(nodes);
        when(result.clusterId()).thenReturn(id);
        return result;
    }

    private static DescribeTopicsResult topics(Map<String, KafkaFuture<TopicDescription>> futures) {
        var result = mock(DescribeTopicsResult.class);
        when(result.topicNameValues()).thenReturn(futures);
        return result;
    }

    private static final class Fixture implements AutoCloseable {
        private final Object business = mock(Object.class);
        private final Admin admin = mock(Admin.class);
        private final AtomicLong ticker = new AtomicLong();
        private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        private final CollectionControl control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), ticker::get,
                TimeUnit.SECONDS.toNanos(5), cleanup, new MonitoringCounterStore(1, 8), "kafka", "kafkaFactory",
                MetricContract.CollectionKind.ORDINARY, Duration.ofSeconds(15), business);
        private final Map<String, Object> settings = new LinkedHashMap<>(Map.of("bootstrap.servers", "native-host:9092"));

        private KafkaMonitoringConnections access() { return new KafkaMonitoringConnections(properties -> admin); }

        private CollectionRequest request() {
            Instant now = Instant.now();
            return new CollectionRequest("kafka", "kafkaFactory", "kafkaFactory", MetricContract.CollectionKind.ORDINARY,
                    1, 1, now, now.plusSeconds(5), new MonitoringTarget.Scope(List.of(), List.of("ready", "denied", "pending", "later"), List.of(), List.of(), List.of()),
                    business, settings, control);
        }

        private void drainCleanup() {
            try { cleanup.submit(() -> { }).get(3, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError("Monitoring test cleanup did not finish", failure); }
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
