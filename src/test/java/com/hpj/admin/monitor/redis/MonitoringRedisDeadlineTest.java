package com.hpj.admin.monitor.redis;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

/** Real native RESP reads against a caller-owned socket that accepts PING but never responds. */
@Timeout(10)
class MonitoringRedisDeadlineTest {
    @Test
    void nativeReadTimeoutClosesTheActualSocket() throws Exception {
        try (StalledPeer peer = new StalledPeer(Duration.ofMillis(250))) {
            long started = System.nanoTime();
            RedisMonitoringConnections.Failure failure = peer.ping();
            assertThat(failure.reason()).isEqualTo(TIMEOUT);
            assertThat(failure).hasNoCause();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            await().atMost(Duration.ofSeconds(2)).until(peer.control::isCleanupComplete);
        }
    }

    @Test
    void activeCancellationEndsPendingNativeReadAndRetiresOwnedResources() throws Exception {
        try (StalledPeer peer = new StalledPeer(Duration.ofSeconds(5))) {
            CompletableFuture<RedisMonitoringConnections.Failure> result =
                    CompletableFuture.supplyAsync(peer::ping, peer.workers);
            assertThat(peer.received.await(2, TimeUnit.SECONDS)).isTrue();
            peer.control.cancel(TIMEOUT);
            assertThat(result.get(2, TimeUnit.SECONDS).reason()).isEqualTo(TIMEOUT);
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            await().atMost(Duration.ofSeconds(2)).until(peer.control::isCleanupComplete);
            assertThat(peer.control.hasCleanupFailure()).isFalse();
        }
    }

    @Test
    void collectionDeadlineShortensALongerNativeTimeout() throws Exception {
        try (StalledPeer peer = new StalledPeer(Duration.ofMillis(250), Duration.ofSeconds(5))) {
            long started = System.nanoTime();
            assertThat(peer.ping().reason()).isEqualTo(TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            await().atMost(Duration.ofSeconds(2)).until(peer.control::isCleanupComplete);
        }
    }

    private static final class StalledPeer implements AutoCloseable {
        final ServerSocket listener;
        final AtomicReference<Socket> socket = new AtomicReference<>();
        final CountDownLatch received = new CountDownLatch(1);
        final CompletableFuture<Integer> eof = new CompletableFuture<>();
        final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "redis-owned-stalled-peer");
            thread.setDaemon(true);
            return thread;
        });
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "redis-deadline-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        final CollectionControl control;
        final CollectionRequest request;

        StalledPeer(Duration nativeReadTimeout) throws Exception {
            this(Duration.ofSeconds(5), nativeReadTimeout);
        }

        StalledPeer(Duration budget, Duration nativeReadTimeout) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Object borrowed = new Object();
            Instant scheduled = Instant.now();
            control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), System::nanoTime,
                    System.nanoTime() + budget.toNanos(), cleanup, new MonitoringCounterStore(1, 8),
                    "cache", "cache", ORDINARY, Duration.ofSeconds(15), borrowed);
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", listener.getLocalPort());
            request = new CollectionRequest("cache", "redisFactory", "cache", ORDINARY, 1, 1,
                    scheduled, scheduled.plus(budget), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    borrowed, Map.of("host", "127.0.0.1", "port", listener.getLocalPort(), "database", 0,
                    "topology", "standalone", "tls", false, "dynamicCredentials", false, "defaultRouting", true,
                    "configuration", configuration, "clientConfiguration", LettuceClientConfiguration.builder()
                            .commandTimeout(nativeReadTimeout).build()), control);
            workers.submit(() -> {
                try (Socket owned = listener.accept()) {
                    socket.set(owned);
                    owned.setSoTimeout(4000);
                    byte[] command = owned.getInputStream().readNBytes(14);
                    assertThat(new String(command, StandardCharsets.US_ASCII)).isEqualTo("*1\r\n$4\r\nPING\r\n");
                    received.countDown();
                    eof.complete(owned.getInputStream().read());
                } catch (Throwable failure) {
                    received.countDown();
                    eof.completeExceptionally(failure);
                }
            });
        }

        RedisMonitoringConnections.Failure ping() {
            try (var session = new RedisMonitoringConnections().open(request)) {
                session.ping();
                throw new AssertionError("A peer without replies cannot answer PING");
            } catch (RedisMonitoringConnections.Failure failure) {
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
    }
}
