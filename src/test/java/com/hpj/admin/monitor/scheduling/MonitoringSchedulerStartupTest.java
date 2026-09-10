package com.hpj.admin.monitor.scheduling;

import com.hpj.admin.common.config.monitor.MonitoringConfiguration;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.metric.MonitoringAdapterRegistry;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.hpj.admin.monitor.metric.MetricContract.CollectionKind;
import static com.hpj.admin.monitor.metric.MetricContract.CollectionKind.ORDINARY;
import static com.hpj.admin.monitor.scheduling.MonitoringSchedulerFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MonitoringSchedulerStartupTest {
    @Test
    void periodicCollectionStaggersTargetsInSeparateOrdinaryAndCapacityWindows() {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setOrdinaryInterval(Duration.ofMillis(800));
        properties.setCapacityInterval(Duration.ofMillis(1600));
        properties.setCollectionTimeout(Duration.ofMillis(100));
        List<ResolvedTarget> targets = IntStream.range(0, 4)
                .mapToObj(index -> target("cache" + index, new BorrowedClient())).toList();
        Map<String, Instant> firstOrdinary = new ConcurrentHashMap<>();
        Map<String, Instant> firstCapacity = new ConcurrentHashMap<>();
        AtomicInteger resolutions = new AtomicInteger();
        ScriptedAdapter adapter = new ScriptedAdapter(request -> {
            Map<String, Instant> first = request.kind() == ORDINARY ? firstOrdinary : firstCapacity;
            first.putIfAbsent(request.targetId(), request.scheduledAt());
            return success(request);
        });
        try (MonitoringScheduler scheduler = new MonitoringScheduler(properties, () -> {
            resolutions.incrementAndGet();
            return targets;
        }, new MonitoringAdapterRegistry(List.of(adapter)), new MonitoringSnapshotStore(20, 100, 20),
                new MonitoringCounterStore(20, 100))) {
            scheduler.start();
            scheduler.start();
            await().pollInterval(Duration.ofMillis(5)).atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
                assertThat(firstOrdinary).hasSize(4);
                assertThat(firstCapacity).hasSize(4);
            });
            assertThat(resolutions).hasValue(1);
            for (CollectionKind kind : CollectionKind.values()) {
                Map<String, Instant> first = kind == ORDINARY ? firstOrdinary : firstCapacity;
                Duration period = kind == ORDINARY ? properties.getOrdinaryInterval() : properties.getCapacityInterval();
                Instant earliest = first.values().stream().min(Instant::compareTo).orElseThrow();
                Instant latest = first.values().stream().max(Instant::compareTo).orElseThrow();
                assertThat(Duration.between(earliest, latest)).isGreaterThanOrEqualTo(period.dividedBy(2));
            }
        }
    }

    @Test
    void aLateStartupResolutionCannotOverwriteAnExplicitReplacement() throws Exception {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setEnabled(true);
        BorrowedClient oldClient = new BorrowedClient();
        BorrowedClient newClient = new BorrowedClient();
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        ScriptedAdapter adapter = new ScriptedAdapter(MonitoringSchedulerFixtures::success);
        MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(20, 100, 20);
        try (MonitoringScheduler scheduler = new MonitoringScheduler(properties, () -> {
            resolverEntered.countDown();
            try {
                if (!releaseResolver.await(3, TimeUnit.SECONDS)) throw new AssertionError("Startup resolver was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Startup resolver interrupted");
            }
            return List.of(target("cache", oldClient));
        }, new MonitoringAdapterRegistry(List.of(adapter)), snapshots, new MonitoringCounterStore(20, 100), false)) {
            scheduler.start();
            assertThat(resolverEntered.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.replaceTargets(List.of(target("cache", newClient)));
            long generation = snapshots.snapshot("cache").orElseThrow().generation();
            releaseResolver.countDown();
            // The resolver runs on the real ordinary pool, so idle proves its attempted publication has completed.
            await().untilAsserted(() -> assertThat(scheduler.diagnostics().ordinaryActive()).isZero());
            assertThat(scheduler.targets().get(0).bindings().get(0).connection().client()).isSameAs(newClient);
            assertThat(snapshots.snapshot("cache").orElseThrow().generation()).isEqualTo(generation);
            assertThat(scheduler.submitNow("cache", ORDINARY)).isTrue();
            await().untilAsserted(() -> assertThat(snapshots.snapshot("cache").orElseThrow().attempts()).hasSize(1));
            assertThat(adapter.requests).hasSize(1);
            assertThat(adapter.requests.get(0).client()).isSameAs(newClient);
        } finally {
            releaseResolver.countDown();
        }
        assertThat(oldClient.closes).hasValue(0);
        assertThat(newClient.closes).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applicationReadinessStartsOnlyEnabledMonitoringWithoutInitializingOptionalClients(boolean enabled) {
        AtomicReference<MonitoringScheduler> captured = new AtomicReference<>();
        new ApplicationContextRunner().withUserConfiguration(MonitoringConfiguration.class, LazyClient.class)
                .withPropertyValues("monitor.enabled=" + enabled,
                        "monitor.targets[0].id=optional", "monitor.targets[0].type=redis",
                        "monitor.targets[0].enabled=true", "monitor.targets[0].connection-source=redisFactory")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MonitoringScheduler.class);
                    MonitoringScheduler scheduler = context.getBean(MonitoringScheduler.class);
                    captured.set(scheduler);
                    assertThat(scheduler.isRunning()).isFalse();
                    assertThat(scheduler.diagnostics().workerThreads()).isZero();
                    context.publishEvent(new ApplicationReadyEvent(new SpringApplication(MonitoringConfiguration.class),
                            new String[0], context.getSourceApplicationContext(), Duration.ZERO));
                    if (enabled) {
                        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                            assertThat(scheduler.isRunning()).isTrue();
                            assertThat(scheduler.targets()).hasSize(1);
                            assertThat(scheduler.targets().get(0).reason()).isEqualTo(ResolvedTarget.Reason.SOURCE_MISSING);
                        });
                    } else {
                        assertThat(scheduler.isRunning()).isFalse();
                        assertThat(scheduler.targets()).isEmpty();
                        assertThat(scheduler.diagnostics().workerThreads()).isZero();
                        assertThat(scheduler.diagnostics().cleanupThreads()).isZero();
                    }
                    assertThat(context.getBeanFactory().containsSingleton("redisFactory")).isFalse();
                });
        assertThat(captured.get().isRunning()).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyClient {
        @Bean @Lazy
        RedisConnectionFactory redisFactory() {
            throw new AssertionError("Monitoring readiness must not initialize a lazy business connection");
        }
    }
}
