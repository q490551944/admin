package com.hpj.admin.monitor.scheduling;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringConnectionSource;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.connection.ConnectionIdentity;
import com.hpj.admin.monitor.connection.ResolvedConnection;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapterRegistry;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.hpj.admin.monitor.scheduling.MonitoringScheduler.ResolutionState.READY;
import static com.hpj.admin.monitor.scheduling.MonitoringScheduler.ResolutionState.RESOLVING;
import static com.hpj.admin.monitor.scheduling.MonitoringSchedulerFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MonitoringSchedulerReadStateTest {
    @Test
    void declarationsRemainVisibleAndUnknownUntilTheInitialResolutionFinishesEvenWithNoResolvedTargets() throws Exception {
        MonitoringProperties properties = properties();
        MonitoringProperties.Target enabled = declaration("cache", true);
        MonitoringProperties.Target disabled = declaration("disabled", false);
        properties.setTargets(List.of(enabled, disabled));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        try (MonitoringScheduler scheduler = scheduler(properties, () -> {
            resolutions.incrementAndGet();
            entered.countDown();
            awaitLatch(release);
            return List.of();
        })) {
            MonitoringScheduler.ReadState declared = scheduler.readState();
            assertThat(declared.generation()).isZero();
            assertThat(declared.resolutionState()).isEqualTo(RESOLVING);
            assertThat(declared.targets()).extracting(target -> target.display().id()).containsExactly("cache", "disabled");
            assertThat(declared.targets().get(0).display().status()).isNull();
            assertThat(declared.targets().get(0).resolutionReason()).isNull();
            assertThat(declared.targets().get(0).display().connectionSources()).containsExactly("cacheSource");
            assertThat(declared.targets().get(0).memberIds()).containsExactly("cache");
            assertThat(declared.targets().get(0).bindings()).isEmpty();
            assertThat(declared.targets().get(1).display().status()).isEqualTo(MonitoringTarget.ConfigurationStatus.DISABLED);
            assertThat(declared.targets().get(1).resolutionReason()).isEqualTo(ResolvedTarget.Reason.DISABLED);
            assertThat(declared.snapshots()).isEmpty();
            assertThat(resolutions).hasValue(0);

            // The HTTP read boundary must not retain mutable configuration collections.
            enabled.getScope().getDatabases().add("laterChange");
            assertThat(declared.targets().get(0).display().scope().databases()).containsExactly("0");
            scheduler.start();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            for (int read = 0; read < 100; read++) assertThat(scheduler.readState()).isEqualTo(declared);
            assertThat(resolutions).hasValue(1);
            release.countDown();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                MonitoringScheduler.ReadState ready = scheduler.readState();
                assertThat(ready.resolutionState()).isEqualTo(READY);
                assertThat(ready.generation()).isEqualTo(1);
                assertThat(ready.targets()).isEmpty();
                assertThat(ready.snapshots()).isEmpty();
            });
            assertThat(resolutions).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentReplacementsNeverCombineDifferentDirectoryAndSnapshotGenerations() throws Exception {
        var executor = Executors.newFixedThreadPool(5);
        BorrowedClient client = new BorrowedClient();
        CountDownLatch started = new CountDownLatch(5);
        AtomicInteger reads = new AtomicInteger();
        try (MonitoringScheduler scheduler = scheduler(properties(), List::of)) {
            scheduler.replaceTargets(List.of(target("cache1", client)));
            MonitoringScheduler.ReadState original = scheduler.readState();
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                started.countDown();
                awaitLatch(started);
                for (int generation = 2; generation <= 1000; generation++) {
                    scheduler.replaceTargets(List.of(target("cache" + generation, client)));
                    Thread.yield();
                }
            }));
            for (int reader = 0; reader < 4; reader++) {
                futures.add(executor.submit(() -> {
                    started.countDown();
                    awaitLatch(started);
                    for (int index = 0; index < 1000; index++) {
                        MonitoringScheduler.ReadState state = scheduler.readState();
                        assertThat(state.resolutionState()).isEqualTo(READY);
                        assertThat(state.targets()).hasSize(1);
                        assertThat(state.snapshots()).hasSize(1);
                        assertThat(state.targets().get(0).display().id()).isEqualTo("cache" + state.generation());
                        assertThat(state.snapshots().get(0).targetId()).isEqualTo(state.targets().get(0).display().id());
                        assertThat(state.snapshots().get(0).generation()).isEqualTo(state.generation());
                        reads.incrementAndGet();
                    }
                }));
            }
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            assertThat(reads).hasValue(4000);
            assertThat(original.generation()).isEqualTo(1);
            assertThat(original.targets().get(0).display().id()).isEqualTo("cache1");
            assertThat(original.snapshots().get(0).generation()).isEqualTo(1);
            assertThatThrownBy(() -> original.targets().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> original.snapshots().clear()).isInstanceOf(UnsupportedOperationException.class);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(client.closes).hasValue(0);
    }

    @Test
    void safeReadsPreserveEachBindingScopeWithoutTraversingClientsOrSecretSettings() throws Exception {
        SensitiveValue client = new SensitiveValue();
        SensitiveValue setting = new SensitiveValue();
        var connection = new ResolvedConnection(MiddlewareType.REDIS, "sharedRedis", client,
                ConnectionIdentity.redisStandalone(client, "private-host", 6379, 0, "private-user", "private-password"),
                Map.of("secret", setting));
        var bindings = List.of(new ResolvedTarget.SourceBinding("cache0", "sharedRedis", connection, scope("0")),
                new ResolvedTarget.SourceBinding("cache1", "sharedRedis", connection, scope("1")));
        ResolvedTarget merged = target("cache0", bindings);
        try (MonitoringScheduler scheduler = scheduler(properties(), List::of)) {
            scheduler.replaceTargets(List.of(merged));
            MonitoringScheduler.ReadState state = scheduler.readState();
            var target = state.targets().get(0);
            assertThat(target.memberIds()).containsExactly("cache0", "cache1");
            assertThat(target.bindings()).extracting(MonitoringScheduler.ReadBinding::bindingId).containsExactly("cache0", "cache1");
            assertThat(target.bindings()).extracting(MonitoringScheduler.ReadBinding::source)
                    .containsOnly(new MonitoringConnectionSource(MiddlewareType.REDIS, "sharedRedis"));
            assertThat(target.bindings().get(0).scope()).isEqualTo(scope("0"));
            assertThat(target.bindings().get(1).scope()).isEqualTo(scope("1"));
            assertSafeTree(state);

            // Safety comes from the object graph, rather than JsonIgnoreType on server-only objects.
            JsonMapper mapper = JsonMapper.builder().disable(MapperFeature.USE_ANNOTATIONS).build();
            String serialized = mapper.writeValueAsString(state);
            assertThat(serialized).contains("sharedRedis", "cache0", "cache1")
                    .doesNotContain("private-host", "private-user", "private-password", "secret", "client", "settings");
            assertThat(state.toString()).doesNotContain("private-host", "private-user", "private-password", "SensitiveValue");
            assertThat(scheduler.targets()).containsExactly(merged);
        }
        assertThat(client.touches).hasValue(0);
        assertThat(setting.touches).hasValue(0);
    }

    private static MonitoringScheduler scheduler(MonitoringProperties properties, Supplier<List<ResolvedTarget>> resolver) {
        return new MonitoringScheduler(properties, resolver, new MonitoringAdapterRegistry(List.of()),
                new MonitoringSnapshotStore(20, 100, 20), new MonitoringCounterStore(20, 100), false);
    }

    private static MonitoringProperties properties() {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static MonitoringProperties.Target declaration(String id, boolean enabled) {
        var target = new MonitoringProperties.Target();
        target.setId(id);
        target.setType(MiddlewareType.REDIS);
        target.setEnabled(enabled);
        target.setConnectionSource(id + "Source");
        target.getScope().getDatabases().add("0");
        return target;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test coordination interrupted", interrupted);
        }
    }

    private static void assertSafeTree(Object value) throws Exception {
        if (value == null) return;
        assertThat(value).isNotInstanceOfAny(ResolvedTarget.class, ResolvedTarget.SourceBinding.class,
                ResolvedConnection.class, ConnectionIdentity.class, CollectionRequest.class,
                CollectionControl.class, MonitoringProperties.class, SensitiveValue.class);
        if (value instanceof Iterable<?> entries) {
            for (Object entry : entries) assertSafeTree(entry);
        } else if (value instanceof Map<?, ?> entries) {
            for (var entry : entries.entrySet()) {
                assertSafeTree(entry.getKey());
                assertSafeTree(entry.getValue());
            }
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertSafeTree(component.getAccessor().invoke(value));
            }
        }
    }

    public static final class SensitiveValue {
        private final AtomicInteger touches = new AtomicInteger();
        public String getPassword() {
            touches.incrementAndGet();
            throw new AssertionError("Read boundary traversed sensitive properties");
        }
        @Override public String toString() {
            touches.incrementAndGet();
            throw new AssertionError("Read boundary formatted a sensitive value");
        }
    }
}
