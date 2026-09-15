package com.hpj.admin.monitor.mongodb;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.hpj.admin.monitor.metric.MetricContract.CollectionKind.ORDINARY;
import static com.hpj.admin.monitor.metric.MetricContract.MissingReason.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real MongoDB 4.11.1 wire/TLS handshakes against an owned peer that never sends a response. */
@Timeout(12)
class MonitoringMongoNativeDeadlineTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void originalShorterReadTimeoutEndsTheNativeHandshakeAndClosesItsSocket(boolean tls) throws Exception {
        try (StalledPeer peer = new StalledPeer(tls, 250)) {
            long started = System.nanoTime();
            MongoMonitoringConnections.Failure failure = peer.open();
            assertThat(failure.reason()).isEqualTo(TIMEOUT);
            assertThat(failure).hasNoCause();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(peer.received.getCount()).isZero();
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            await().atMost(Duration.ofSeconds(2)).until(peer.control::isCleanupComplete);
            assertThat(peer.control.hasCleanupFailure()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationClosesTheSocketDuringNativeOrTlsHandshake(boolean tls) throws Exception {
        try (StalledPeer peer = new StalledPeer(tls, 5000)) {
            CompletableFuture<MongoMonitoringConnections.Failure> opened =
                    CompletableFuture.supplyAsync(peer::open, peer.workers);
            assertThat(peer.received.await(3, TimeUnit.SECONDS)).isTrue();
            peer.control.cancel(TIMEOUT);
            assertThat(opened.get(2, TimeUnit.SECONDS).reason()).isEqualTo(TIMEOUT);
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            await().atMost(Duration.ofSeconds(2)).until(peer.control::isCleanupComplete);
            assertThat(peer.control.hasCleanupFailure()).isFalse();
        }
    }

    private static final class StalledPeer implements AutoCloseable {
        final ServerSocket listener;
        final AtomicReference<Socket> socket = new AtomicReference<>();
        final CountDownLatch received = new CountDownLatch(1);
        final CompletableFuture<Integer> eof = new CompletableFuture<>();
        final ExecutorService workers = Executors.newFixedThreadPool(2, task -> daemon(task, "mongo-stalled-peer"));
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), task -> daemon(task, "mongo-native-cleanup"),
                new ThreadPoolExecutor.AbortPolicy());
        final CollectionControl control;
        final CollectionRequest request;

        StalledPeer(boolean tls, int readTimeoutMillis) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Object borrowed = new Object();
            Instant at = Instant.now();
            Duration budget = Duration.ofSeconds(5);
            control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), System::nanoTime,
                    System.nanoTime() + budget.toNanos(), cleanup, new MonitoringCounterStore(1, 8),
                    "mongo", "mongo", ORDINARY, Duration.ofSeconds(15), borrowed);
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString("mongodb://127.0.0.1:" + listener.getLocalPort()))
                    .applyToSocketSettings(value -> value.connectTimeout(2000, TimeUnit.MILLISECONDS)
                            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS))
                    .applyToSslSettings(value -> value.enabled(tls))
                    .build();
            request = new CollectionRequest("mongo", "mongoClient", "mongo", ORDINARY, 1, 1,
                    at, at.plus(budget), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    borrowed, Map.of("mongoSettings", settings), control);
            workers.submit(() -> {
                try (Socket owned = listener.accept()) {
                    socket.set(owned);
                    owned.setSoTimeout(8000);
                    byte[] buffer = new byte[4096];
                    int count;
                    int total = 0;
                    while ((count = owned.getInputStream().read(buffer)) != -1) {
                        received.countDown();
                        total += count;
                        if (total > 1_048_576) throw new AssertionError("Unexpected unbounded handshake traffic");
                    }
                    eof.complete(-1);
                } catch (Throwable failure) {
                    eof.completeExceptionally(failure);
                }
            });
        }

        MongoMonitoringConnections.Failure open() {
            try (var ignored = new MongoMonitoringConnections().open(request)) {
                throw new AssertionError("A peer without a native greeting cannot establish a MongoDB connection");
            } catch (MongoMonitoringConnections.Failure failure) {
                return failure;
            }
        }

        @Override public void close() throws Exception {
            control.cancel(TIMEOUT);
            listener.close();
            Socket owned = socket.get();
            if (owned != null) owned.close();
            workers.shutdownNow();
            cleanup.shutdownNow();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }

        private static Thread daemon(Runnable runnable, String name) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
