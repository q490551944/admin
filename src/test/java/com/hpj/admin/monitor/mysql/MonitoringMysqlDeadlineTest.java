package com.hpj.admin.monitor.mysql;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
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

/** Real Connector/J socket acquisition against an owned peer that never sends a MySQL greeting. */
@Timeout(10)
class MonitoringMysqlDeadlineTest {
    @Test
    void nativeHandshakeReadTimeoutClosesTheActualSocket() throws Exception {
        try (StalledPeer peer = new StalledPeer(Duration.ofSeconds(5), 250)) {
            long start = System.nanoTime();
            SQLException failure = peer.open();
            assertThat((Throwable) failure).isInstanceOf(SQLTimeoutException.class).hasMessageNotContaining("loopback-test");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            await().atMost(Duration.ofSeconds(2)).until(peer.control::isCleanupComplete);
        }
    }

    @Test
    void explicitCancellationEndsAHandshakeBeforeItsNativeTimeoutAndRetiresOwnedResources() throws Exception {
        try (StalledPeer peer = new StalledPeer(Duration.ofSeconds(5), 5000)) {
            CompletableFuture<SQLException> opened = CompletableFuture.supplyAsync(peer::open, peer.workers);
            assertThat(peer.accepted.await(2, TimeUnit.SECONDS)).isTrue();
            peer.control.cancel(TIMEOUT);
            assertThat((Throwable) opened.get(2, TimeUnit.SECONDS)).isInstanceOf(SQLTimeoutException.class);
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            await().atMost(Duration.ofSeconds(2)).until(peer.control::isCleanupComplete);
            assertThat(peer.control.hasCleanupFailure()).isFalse();
        }
    }

    private static final class StalledPeer implements AutoCloseable {
        final ServerSocket listener;
        final AtomicReference<Socket> socket = new AtomicReference<>();
        final CountDownLatch accepted = new CountDownLatch(1);
        final CompletableFuture<Integer> eof = new CompletableFuture<>();
        final ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "mysql-owned-stalled-peer");
            thread.setDaemon(true);
            return thread;
        });
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "mysql-deadline-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        final CollectionControl control;
        final CollectionRequest request;

        StalledPeer(Duration budget, int readTimeout) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Object borrowed = new Object();
            Instant scheduled = Instant.now();
            control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), System::nanoTime,
                    System.nanoTime() + budget.toNanos(), cleanup, new MonitoringCounterStore(1, 8),
                    "database", "database", ORDINARY, Duration.ofSeconds(15), borrowed);
            request = new CollectionRequest("database", "pool", "database", ORDINARY, 1, 1,
                    scheduled, scheduled.plus(budget), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    borrowed, Map.of("endpoint", "jdbc:mysql://127.0.0.1:" + listener.getLocalPort() + "/?useSSL=false",
                    "username", "loopback-test", "password", "unused-test-password",
                    "properties", Map.of("socketTimeout", Integer.toString(readTimeout))), control);
            workers.submit(() -> {
                try (Socket owned = listener.accept()) {
                    socket.set(owned);
                    owned.setSoTimeout(4000);
                    accepted.countDown();
                    eof.complete(owned.getInputStream().read());
                } catch (Exception failure) {
                    eof.completeExceptionally(failure);
                }
            });
        }

        SQLException open() {
            try (var ignored = new MysqlMonitoringConnections().open(request)) {
                throw new AssertionError("A peer without a MySQL greeting cannot authenticate");
            } catch (SQLException failure) {
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
