package com.hpj.admin.monitor;

import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real periodic runtime/browser entry point. Test controls and network faults never ship in the main jar. */
public final class MonitoringRuntimeE2eApplication {
    public static void main(String[] args) throws Exception {
        MonitoringE2eApplication.requireControlToken();
        if (args.length != 1) throw new IllegalArgumentException("Owned readiness file required");
        Path ready = Path.of(args[0]).toAbsolutePath().normalize();
        if (!ready.startsWith(Path.of("target").toAbsolutePath().normalize())) throw new IllegalArgumentException("Readiness must stay in target");
        Files.createDirectories(ready.getParent());
        var fixture = MonitoringRuntimeFixture.startBrowser();
        var shutdown = new Thread(() -> { try { fixture.close(); } catch (Exception ignored) { } }, "owned-runtime-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        boolean verified = false;
        try {
            // Wait for actual 15s/60s periodic collection. No submitNow or fabricated snapshot is used.
            var store = fixture.context.getBean(MonitoringSnapshotStore.class);
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
                for (String id : List.of("mysql-00", "redis-00")) {
                    var snapshot = store.snapshot(id).orElseThrow();
                    assertThat(snapshot.attempts()).hasSize(2);
                    assertThat(snapshot.attempts()).allSatisfy(attempt -> assertThat(attempt.status()).isIn(CollectionStatus.SUCCESS, CollectionStatus.PARTIAL));
                }
            });
            var observer = fixture.login();
            for (String type : List.of("mysql", "redis")) MonitoringRuntimePipelineIntegrationTest.verifyKinds(observer.get("/targets/" + type + "-00"), type);
            long mysqlNative;
            try (var connection = fixture.source("mysql-00").environment().jdbcConnection(); var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (var result = statement.executeQuery("SELECT @@innodb_buffer_pool_size")) { result.next(); mysqlNative = result.getLong(1); }
            }
            assertThat(MonitoringRuntimePipelineIntegrationTest.metric(observer.get("/targets/mysql-00"), "mysql.innodb.buffer_pool.configured.bytes").path("value").asLong()).isEqualTo(mysqlNative);
            try (var redis = fixture.source("redis-00").environment().redisConnection()) {
                assertThat(redis.sync().info("memory")).contains("maxmemory:0\r\n");
            }
            assertThat(MonitoringRuntimePipelineIntegrationTest.metric(observer.get("/targets/redis-00"), "redis.memory.maxmemory.bytes").path("value").asLong()).isZero();
            Path pending = ready.resolveSibling("ready.pending");
            var properties = fixture.context.getBean(MonitoringProperties.class);
            Files.writeString(pending, fixture.json.writeValueAsString(Map.of("baseURL", fixture.baseURL,
                    "chatEnabled", false, "monitorEnabled", true, "periodic", true, "types", List.of("MYSQL", "REDIS"),
                    "ordinaryIntervalMillis", properties.getOrdinaryInterval().toMillis(), "capacityIntervalMillis", properties.getCapacityInterval().toMillis(),
                    "mysqlBufferPoolNativeBytes", mysqlNative, "redisNativeMaxmemoryBytes", 0)));
            Files.move(pending, ready, StandardCopyOption.REPLACE_EXISTING);
            fixture.browserFinish.await();
            Thread.sleep(250); // Let the finish HTTP response flush before closing its own servlet container.
            fixture.redisProxy.reconnect();
            for (var source : fixture.sources) source.environment().verifyData();
            verified = true;
        } finally {
            try {
                fixture.close();
                Files.writeString(ready.resolveSibling("cleanup.json"), "{\"ownedSources\":2,\"sentinelsUnchanged\":" + verified + ",\"closed\":true}");
            } finally { Runtime.getRuntime().removeShutdownHook(shutdown); }
        }
    }

    @RestController
    static final class Controls {
        private final MonitoringRuntimeFixture fixture;
        private final byte[] token = MonitoringE2eApplication.requireControlToken();
        private final List<StatefulRedisConnection<String, String>> load = new ArrayList<>();
        Controls(MonitoringRuntimeFixture fixture) { this.fixture = fixture; }
        private void authorize(HttpServletRequest request) {
            String supplied = request.getHeader("X-Monitor-Test-Token");
            if (!"127.0.0.1".equals(request.getRemoteAddr()) || supplied == null || supplied.length() > 256
                    || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        @PostMapping("/__monitor_test/runtime")
        synchronized ResponseEntity<Map<String, Object>> control(HttpServletRequest request, @RequestBody Change change) throws Exception {
            authorize(request);
            if (change.loadConnections() != null && !List.of(0, 8).contains(change.loadConnections())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            if (change.connected() != null) {
                if (change.connected()) fixture.redisProxy.reconnect(); else fixture.redisProxy.disconnect();
            }
            if (change.loadConnections() != null) {
                closeLoad();
                for (int i = 0; i < change.loadConnections(); i++) load.add(fixture.source("redis-00").environment().redisConnection());
            }
            return state();
        }
        @GetMapping("/__monitor_test/runtime")
        synchronized ResponseEntity<Map<String, Object>> observe(HttpServletRequest request) { authorize(request); return state(); }
        private ResponseEntity<Map<String, Object>> state() {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("connected", fixture.redisProxy.connected(),
                    "ownedLoadConnections", load.size(), "collectorCalls", fixture.ledger.calls.get()));
        }
        @PostMapping("/__monitor_test/finish")
        ResponseEntity<Void> finish(HttpServletRequest request) { authorize(request); fixture.browserFinish.countDown(); return ResponseEntity.noContent().build(); }
        @PreDestroy synchronized void closeLoad() { for (var connection : load) connection.close(); load.clear(); }
    }
    record Change(Boolean connected, Integer loadConnections) { }
}
