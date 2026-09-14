package com.hpj.admin.monitor;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.connection.ConnectionIdentity;
import com.hpj.admin.monitor.connection.ResolvedConnection;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import com.hpj.admin.monitor.metric.MonitoringAdapterRegistry;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real in-memory publication and scheduler, with counted stand-ins only for external middleware. */
final class MonitoringReadFixtures {
    static final Instant SAMPLE_TIME = Instant.parse("2026-09-14T01:00:00Z");
    static final String PRIVATE_SETTING = "read-api-password-sentinel";
    static final String PRIVATE_CLIENT = "redis://private-monitor.example:6379?password=" + PRIVATE_SETTING;
    final AtomicInteger resolutions = new AtomicInteger();
    final AtomicInteger collections = new AtomicInteger();
    final MonitoringSnapshotStore store = new MonitoringSnapshotStore(20, 5000, 20);
    final MonitoringScheduler scheduler;

    MonitoringReadFixtures(MonitoringProperties properties) {
        MonitoringAdapter adapter = new MonitoringAdapter() {
            @Override public MiddlewareType type() { return MiddlewareType.REDIS; }
            @Override public CollectionResult collect(CollectionRequest request) {
                collections.incrementAndGet();
                return new CollectionResult(CollectionStatus.SUCCESS, request.scheduledAt(),
                        request.scheduledAt(), List.of(), null);
            }
        };
        scheduler = new MonitoringScheduler(properties, () -> {
            resolutions.incrementAndGet();
            return List.of(target("bootstrap"));
        }, new MonitoringAdapterRegistry(List.of(adapter)), store, new MonitoringCounterStore(20, 5000), false);
        if (properties.isEnabled()) {
            scheduler.start();
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (scheduler.targets().stream().noneMatch(target -> target.display().id().equals("bootstrap"))) {
                if (System.nanoTime() >= until) throw new AssertionError("Monitoring test resolver did not finish");
                try { Thread.sleep(5); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    void replace(ResolvedTarget... targets) { scheduler.replaceTargets(List.of(targets)); }

    static MonitoringTarget.Scope scope(String database) {
        return new MonitoringTarget.Scope(List.of(database), List.of(), List.of(), List.of(), List.of());
    }

    static ResolvedTarget target(String id) {
        return target(id, List.of(binding(id, id + "Source", "0")));
    }

    static ResolvedTarget.SourceBinding binding(String id, String source, String database) {
        Object borrowedClient = new Object() {
            @Override public String toString() { return PRIVATE_CLIENT; }
        };
        return new ResolvedTarget.SourceBinding(id, source,
                new ResolvedConnection(MiddlewareType.REDIS, source, borrowedClient,
                        ConnectionIdentity.forOwner(borrowedClient),
                        Map.of("password", PRIVATE_SETTING, "endpoint", PRIVATE_CLIENT)), scope(database));
    }

    static ResolvedTarget target(String id, List<ResolvedTarget.SourceBinding> bindings) {
        var databases = bindings.stream().flatMap(binding -> binding.scope().databases().stream()).distinct().toList();
        var display = new MonitoringTarget(id, MiddlewareType.REDIS, "Display " + id,
                MonitoringTarget.ConfigurationStatus.CONFIGURED,
                bindings.stream().map(binding -> binding.connection().source()).distinct().toList(),
                new MonitoringTarget.Scope(databases, List.of(), List.of(), List.of(), List.of()));
        return new ResolvedTarget(display, bindings.stream().map(ResolvedTarget.SourceBinding::targetId).toList(),
                bindings, ResolvedTarget.Reason.CONFIGURED);
    }

    static ResolvedTarget unavailable(String id, MiddlewareType type, boolean enabled) {
        var display = new MonitoringTarget(id, type, "Display " + id,
                enabled ? MonitoringTarget.ConfigurationStatus.CONFIGURATION_MISSING : MonitoringTarget.ConfigurationStatus.DISABLED,
                List.of(id + "Source"), scope("allowed-db"));
        return new ResolvedTarget(display, List.of(id), List.of(),
                enabled ? ResolvedTarget.Reason.SOURCE_MISSING : ResolvedTarget.Reason.DISABLED);
    }

    static Definition definition(String key, String database) {
        return new Definition(key, "Metric " + key, Unit.COUNT,
                new Scope(ScopeKind.DATABASE, database, null), "INFO keyspace", "Native count");
    }

    void publish(ResolvedTarget target, int bindingIndex, CollectionKind kind, long sequence,
                 CollectionResult result) {
        var binding = target.bindings().get(bindingIndex);
        long generation = store.snapshot(target.display().id()).orElseThrow().generation();
        var request = new CollectionRequest(target.display().id(), binding.connection().source(), binding.targetId(),
                kind, generation, sequence, result.startedAt(), result.completedAt().plusSeconds(5),
                binding.scope(), binding.connection().client(), binding.connection().settings());
        assertThat(store.accept(request, result)).isEqualTo(MonitoringSnapshotStore.Acceptance.ACCEPTED);
    }
}
