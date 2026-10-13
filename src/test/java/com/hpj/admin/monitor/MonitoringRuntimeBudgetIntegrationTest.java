package com.hpj.admin.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static com.hpj.admin.monitor.metric.MetricContract.CollectionKind.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Twenty distinct owned Redis endpoints, real ordinary/capacity work, and five authenticated readers. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "redis")
class MonitoringRuntimeBudgetIntegrationTest {
    @Test @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void twentyTargetsKeepEightPlusTwoWorkersAndTimeoutDoesNotStarveCapacityOrAmplifyPageReads() throws Exception {
        try (var fixture = MonitoringRuntimeFixture.start(List.of("redis"), 20, false)) {
            fixture.collect();
            var readers = new ArrayList<MonitoringRuntimeFixture.Browser>();
            for (int index = 0; index < 5; index++) readers.add(fixture.login());
            assertThat(readers.get(0).get("/snapshots").path("targets")).hasSize(20);
            for (var source : fixture.sources) {
                var detail = readers.get(0).get("/targets/" + source.targetId());
                assertThat(MonitoringRuntimePipelineIntegrationTest.metric(detail, "redis.memory.maxmemory.bytes").path("value").asLong()).isZero();
            }
            fixture.pauseRedis("redis-00");
            try {
                long started = System.nanoTime(); fixture.collect();
                assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(7000);
                var broken = readers.get(0).get("/targets/redis-00");
                // A paused server accepts TCP but never replies to AUTH, so no PING outcome proves it down.
                assertThat(broken.at("/target/serviceAvailability").asText()).isEqualTo("UNKNOWN");
                assertThat(broken.at("/target/collections").toString()).contains("TIMEOUT");
                Instant timeoutCompleted = null;
                for (var collection : broken.at("/target/collections")) if (collection.path("kind").asText().equals("ORDINARY")) timeoutCompleted = Instant.parse(collection.at("/lastAttempt/completedAt").asText());
                assertThat(timeoutCompleted).isNotNull();
                for (int index = 1; index < 20; index++) {
                    var healthy = readers.get(0).get("/targets/redis-" + String.format(Locale.ROOT, "%02d", index));
                    assertThat(healthy.at("/target/serviceAvailability").asText()).isEqualTo("AVAILABLE");
                    assertThat(healthy.at("/target/collections")).hasSize(2);
                    MonitoringRuntimePipelineIntegrationTest.verifyKinds(healthy, "redis");
                    for (var collection : healthy.at("/target/collections")) if (collection.path("kind").asText().equals("CAPACITY")) assertThat(Instant.parse(collection.at("/lastAttempt/completedAt").asText())).isBefore(timeoutCompleted);
                }
                var diagnostics = fixture.scheduler().diagnostics();
                assertThat(diagnostics.workerThreads()).isLessThanOrEqualTo(10);
                assertThat(diagnostics.cleanupThreads()).isLessThanOrEqualTo(10);
                assertThat(fixture.ledger.peak.get(ORDINARY).get()).isLessThanOrEqualTo(8);
                assertThat(fixture.ledger.peak.get(CAPACITY).get()).isLessThanOrEqualTo(2);
                int before = fixture.ledger.calls.get();
                var executor = Executors.newFixedThreadPool(5); var start = new CountDownLatch(1);
                var latencies = new ArrayList<Double>();
                try {
                    List<Future<List<Double>>> tasks = new ArrayList<>();
                    for (int reader = 0; reader < 5; reader++) {
                        var browser = readers.get(reader); final int offset = reader;
                        tasks.add(executor.submit(() -> {
                            start.await(); var values = new ArrayList<Double>();
                            for (int round = 0; round < 20; round++) {
                                long time = System.nanoTime(); browser.get("/snapshots");
                                browser.get("/targets/redis-" + String.format(Locale.ROOT, "%02d", (offset + round) % 20));
                                values.add((System.nanoTime() - time) / 1_000_000.0);
                            }
                            return values;
                        }));
                    }
                    start.countDown(); for (var task : tasks) latencies.addAll(task.get(45, TimeUnit.SECONDS));
                } finally { executor.shutdownNow(); }
                assertThat(fixture.ledger.calls.get()).isEqualTo(before);
                Collections.sort(latencies); double p95 = latencies.get((int) Math.ceil(latencies.size() * .95) - 1);
                assertThat(p95).as("Snapshot + detail HTTP pair P95 ms").isLessThanOrEqualTo(1000);
                var report = new LinkedHashMap<String, Object>(); report.put("targets", 20); report.put("ownedRealRedisInstances", 20); report.put("readers", 5);
                report.put("ordinaryPeak", fixture.ledger.peak.get(ORDINARY).get()); report.put("capacityPeak", fixture.ledger.peak.get(CAPACITY).get());
                report.put("workerThreads", diagnostics.workerThreads()); report.put("cleanupThreads", diagnostics.cleanupThreads());
                report.put("collectionCallsUnchangedByReads", before); report.put("snapshotAndDetailPairP95Millis", p95); report.put("httpPairs", latencies.size());
                Files.writeString(Path.of("target", "monitor-runtime-budget.json"), fixture.json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            } finally { fixture.resumeRedis("redis-00"); }
            fixture.collect();
            assertThat(readers.get(0).get("/targets/redis-00").at("/target/serviceAvailability").asText()).isEqualTo("AVAILABLE");
            for (var source : fixture.sources) source.environment().verifyData();
        }
    }
}
