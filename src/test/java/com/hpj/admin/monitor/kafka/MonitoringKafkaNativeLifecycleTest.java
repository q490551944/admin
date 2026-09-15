package com.hpj.admin.monitor.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.MetricsReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.hpj.admin.monitor.metric.MetricContract.CollectionKind.ORDINARY;
import static com.hpj.admin.monitor.metric.MetricContract.MissingReason.TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real Kafka 3.6.1 client lifecycle, bound to an owned loopback endpoint; no broker or external service needed. */
@Timeout(15)
public class MonitoringKafkaNativeLifecycleTest {
    private static final AtomicReference<Gate> REPORTER_GATE = new AtomicReference<>();

    @Test
    void cancellationAndRepeatedWorkerInterruptsKeepTheSlotUntilTheNativeThreadExits() throws Exception {
        Gate gate = new Gate();
        assertThat(REPORTER_GATE.compareAndSet(null, gate)).isTrue();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closeSession = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "kafka-original-collection-worker");
            thread.setDaemon(true);
            worker.set(thread);
            return thread;
        });
        try (Fixture fixture = new Fixture(Map.of())) {
            KafkaMonitoringConnections connections = new KafkaMonitoringConnections(properties -> {
                Map<String, Object> nativeProperties = new LinkedHashMap<>(properties);
                // Test-only injection after production validates the plain source; production rejects reporters.
                nativeProperties.put(AdminClientConfig.METRIC_REPORTER_CLASSES_CONFIG, BlockingReporter.class.getName());
                nativeProperties.put("auto.include.jmx.reporter", false);
                return Admin.create(nativeProperties);
            });
            CompletableFuture<Void> result = CompletableFuture.runAsync(() -> {
                try (var session = connections.open(fixture.request)) {
                    opened.countDown();
                    try { closeSession.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    closing.countDown();
                } finally {
                    restoredInterrupt.set(Thread.currentThread().isInterrupted());
                }
            }, executor);
            try {
                assertThat(opened.await(3, TimeUnit.SECONDS)).isTrue();
                fixture.control.cancel(TIMEOUT);
                assertThat(gate.entered.await(3, TimeUnit.SECONDS)).isTrue();
                closeSession.countDown();
                assertThat(closing.await(2, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(2)).until(() -> worker.get().getState() == Thread.State.WAITING);
                worker.get().interrupt();
                await().atMost(Duration.ofSeconds(2)).until(() -> worker.get().getState() == Thread.State.WAITING
                        && !worker.get().isInterrupted());
                worker.get().interrupt();
                assertThat(result.isDone()).isFalse();
                assertThat(fixture.control.isCleanupComplete()).isFalse();
                assertThat(gate.nativeThread.get().isAlive()).isTrue();
                gate.release.countDown();
                result.get(3, TimeUnit.SECONDS);
                assertThat(restoredInterrupt).isTrue();
                assertThat(gate.nativeThread.get().isAlive()).isFalse();
                await().atMost(Duration.ofSeconds(2)).until(fixture.control::isCleanupComplete);
                assertThat(fixture.control.hasCleanupFailure()).isFalse();
            } finally {
                closeSession.countDown();
                gate.release.countDown();
                result.get(3, TimeUnit.SECONDS);
            }
        } finally {
            gate.release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            REPORTER_GATE.compareAndSet(gate, null);
        }
    }

    @Test
    void nativeConfigurationLoggingMasksTheExplicitJaasPassword() throws Exception {
        String secret = "owned-kafka-test-secret-marker";
        Logger logger = (Logger) LoggerFactory.getLogger(AdminClientConfig.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try (Fixture fixture = new Fixture(Map.of(
                "security.protocol", "SASL_PLAINTEXT", "sasl.mechanism", "PLAIN",
                "sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required "
                        + "username=\"owned-test\" password=\"" + secret + "\";"))) {
            try (var session = new KafkaMonitoringConnections().open(fixture.request)) {
                String configurationLogs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .collect(Collectors.joining("\n"));
                assertThat(configurationLogs).contains("sasl.jaas.config = [hidden]").doesNotContain(secret);
            }
            await().atMost(Duration.ofSeconds(2)).until(fixture.control::isCleanupComplete);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    public static final class BlockingReporter implements MetricsReporter {
        @Override public void configure(Map<String, ?> configuration) { }
        @Override public void init(List<KafkaMetric> metrics) { }
        @Override public void metricChange(KafkaMetric metric) { }
        @Override public void metricRemoval(KafkaMetric metric) { }
        @Override public void close() {
            Gate gate = REPORTER_GATE.get();
            gate.nativeThread.set(Thread.currentThread());
            gate.entered.countDown();
            boolean interrupted = false;
            for (;;) {
                try { gate.release.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> nativeThread = new AtomicReference<>();
    }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket listener;
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), task -> {
                    Thread thread = new Thread(task, "kafka-owned-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        final CollectionControl control;
        final CollectionRequest request;

        Fixture(Map<String, Object> overrides) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Object borrowed = new Object();
            Instant at = Instant.now();
            Duration budget = Duration.ofSeconds(10);
            control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), System::nanoTime,
                    System.nanoTime() + budget.toNanos(), cleanup, new MonitoringCounterStore(1, 8),
                    "kafka", "kafka", ORDINARY, Duration.ofSeconds(15), borrowed);
            Map<String, Object> properties = new LinkedHashMap<>(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + listener.getLocalPort(),
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000,
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000));
            properties.putAll(overrides);
            request = new CollectionRequest("kafka", "kafkaAdmin", "kafka", ORDINARY, 1, 1,
                    at, at.plus(budget), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    borrowed, properties, control);
        }

        @Override public void close() throws Exception {
            control.cancel(TIMEOUT);
            listener.close();
            cleanup.shutdownNow();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
