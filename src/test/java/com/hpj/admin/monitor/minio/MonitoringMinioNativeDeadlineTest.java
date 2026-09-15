package com.hpj.admin.monitor.minio;

import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
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

/** Real black-hole HTTP/TLS sockets prove that a native call ends before its collection slot is released. */
class MonitoringMinioNativeDeadlineTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void collectionDeadlineClosesTheActualHttpOrTlsSocket(boolean tls) throws Exception {
        try (Peer peer = new Peer(tls, Duration.ofMillis(650))) {
            var collected = CompletableFuture.supplyAsync(peer::failure, peer.workers);
            assertThat(peer.received.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(collected.get(3, TimeUnit.SECONDS).reason()).isEqualTo(TIMEOUT);
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            peer.assertClean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitCancellationClosesTheActualHttpOrTlsSocket(boolean tls) throws Exception {
        try (Peer peer = new Peer(tls, Duration.ofSeconds(5))) {
            var collected = CompletableFuture.supplyAsync(peer::failure, peer.workers);
            assertThat(peer.received.await(3, TimeUnit.SECONDS)).isTrue();
            peer.control.cancel(TIMEOUT);
            assertThat(collected.get(3, TimeUnit.SECONDS).reason()).isEqualTo(TIMEOUT);
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            peer.assertClean();
        }
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        final AtomicReference<Socket> socket = new AtomicReference<>();
        final CountDownLatch received = new CountDownLatch(1);
        final CompletableFuture<Integer> eof = new CompletableFuture<>();
        final ExecutorService workers = Executors.newFixedThreadPool(2, task -> daemon(task, "minio-native-peer"));
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), task -> daemon(task, "minio-native-cleanup"),
                new ThreadPoolExecutor.AbortPolicy());
        final CollectionControl control;
        final CollectionRequest request;

        Peer(boolean tls, Duration budget) throws Exception {
            ChatProperties source = new ChatProperties();
            String endpoint = (tls ? "https" : "http") + "://127.0.0.1:" + listener.getLocalPort();
            source.getAttachment().setEndpoint(endpoint);
            source.getAttachment().setAccessKey("owned-access");
            source.getAttachment().setSecretKey("owned-secret");
            source.getAttachment().setBucket("owned-bucket");
            Instant at = Instant.now();
            control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), System::nanoTime,
                    System.nanoTime() + budget.toNanos(), cleanup, new MonitoringCounterStore(1, 8),
                    "minio", "minio", ORDINARY, Duration.ofSeconds(15), source);
            request = new CollectionRequest("minio", "chatProperties", "minio", ORDINARY, 1, 1,
                    at, at.plus(budget), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    source, Map.of("endpoint", endpoint, "accessKey", "owned-access", "secretKey", "owned-secret",
                    "bucket", "owned-bucket"), control);
            workers.submit(() -> {
                try (Socket owned = listener.accept()) {
                    socket.set(owned);
                    owned.setSoTimeout(8000);
                    byte[] buffer = new byte[4096];
                    int total = 0;
                    int count;
                    while ((count = owned.getInputStream().read(buffer)) != -1) {
                        received.countDown();
                        total += count;
                        if (total > 1_048_576) throw new AssertionError("Unbounded handshake traffic");
                    }
                    eof.complete(-1);
                } catch (Exception error) { eof.completeExceptionally(error); }
            });
        }

        MinioMonitoringConnections.Failure failure() {
            try (var session = new MinioMonitoringConnections().open(request)) {
                session.health(MinioMonitoringConnections.HealthEndpoint.LIVE);
                throw new AssertionError("Expected native collection failure");
            } catch (MinioMonitoringConnections.Failure failure) { return failure; }
        }

        void assertClean() {
            await().atMost(Duration.ofSeconds(2)).until(control::isCleanupComplete);
            assertThat(control.hasCleanupFailure()).isFalse();
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

        private static Thread daemon(Runnable task, String name) {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
