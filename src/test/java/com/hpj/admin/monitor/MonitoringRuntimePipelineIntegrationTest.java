package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import org.elasticsearch.client.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs native adapters only through the real resolver/scheduler/store and authenticated HTTP routes. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "mysql|redis|kafka|mongodb|elasticsearch|minio")
class MonitoringRuntimePipelineIntegrationTest {
    @Test @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void ownedNativeSourcePassesOrdinaryCapacityAndEveryStoredSampleThroughAuthenticatedApi() throws Exception {
        String type = System.getProperty("monitor.test.type");
        try (var fixture = MonitoringRuntimeFixture.start(List.of(type), 1, false)) {
            fixture.collect();
            var browser = fixture.login();
            assertThat(browser.get("/catalog").path("types")).hasSize(6);
            JsonNode detail = browser.get("/targets/" + type + "-00");
            assertThat(detail.at("/target/target/type").asText()).isEqualTo(type.toUpperCase(Locale.ROOT));
            assertThat(detail.at("/target/schedulingState").asText()).isEqualTo("ACTIVE");
            assertThat(detail.at("/target/collections")).hasSize(2);
            verifyKinds(detail, type);
            var snapshot = fixture.context.getBean(MonitoringSnapshotStore.class).snapshot(type + "-00").orElseThrow();
            assertThat(detail.path("metrics")).hasSize(snapshot.metrics().size()).isNotEmpty();
            for (var stored : snapshot.metrics()) {
                JsonNode expected = fixture.json.readTree(fixture.json.writeValueAsString(stored.latestAttempt()));
                boolean found = false;
                for (JsonNode metric : detail.path("metrics")) if (metric.path("source").asText().equals(stored.source())
                        && metric.path("bindingId").asText().equals(stored.bindingId()) && metric.path("kind").asText().equals(stored.kind().name())
                        && metric.path("latestAttempt").equals(expected)) { found = true; break; }
                assertThat(found).as("Every native scheduled sample survives HTTP projection: %s", stored.latestAttempt().definition().key()).isTrue();
            }
            verifyNativeReference(fixture, detail);
            int calls = fixture.ledger.calls.get();
            assertThat(calls).isEqualTo(2);
            for (int index = 0; index < 5; index++) {
                var reader = fixture.login(); reader.get("/catalog"); reader.get("/snapshots"); reader.get("/targets/" + type + "-00");
            }
            assertThat(fixture.ledger.calls.get()).isEqualTo(calls);
            fixture.sources.get(0).environment().verifyData();
            var report = new LinkedHashMap<String, Object>();
            report.put("type", type); report.put("image", fixture.sources.get(0).environment().image());
            report.put("route", "native source -> resolver -> bounded scheduler -> shared store -> authenticated HTTP");
            report.put("samplesCompared", snapshot.metrics().size()); report.put("ordinaryAndCapacity", true);
            report.put("independentReaders", 5); report.put("collectionCallsBeforeAndAfterReads", calls); report.put("ownedSentinelUnchanged", true);
            Files.writeString(Path.of("target", "monitor-runtime-" + type + ".json"), fixture.json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
    static JsonNode metric(JsonNode detail, String key) {
        for (JsonNode metric : detail.path("metrics")) if (metric.at("/latestAttempt/definition/key").asText().equals(key)) {
            JsonNode sample = metric.path("latestAttempt");
            assertThat(sample.hasNonNull("value")).as("Native reference must have a real value: %s", key).isTrue();
            assertThat(sample.hasNonNull("missingReason")).as("Native reference must be successfully collected: %s", key).isFalse();
            return sample;
        }
        throw new AssertionError("Native metric missing: " + key);
    }
    static void verifyKinds(JsonNode detail, String type) {
        Set<String> kinds = new HashSet<>();
        for (JsonNode collection : detail.at("/target/collections")) {
            kinds.add(collection.path("kind").asText());
            assertThat(collection.path("status").asText()).as("Both kinds must collect native evidence").isIn("SUCCESS", "PARTIAL");
            assertThat(collection.path("reason").asText()).isNotIn("FAILED", "TIMEOUT", "TLS_FAILED", "UNAUTHORIZED", "INVALID_VALUE", "BUSY");
        }
        assertThat(kinds).containsExactlyInAnyOrder("ORDINARY", "CAPACITY");
        String ordinary = switch (type) {
            case "mysql" -> "mysql.connections.current"; case "redis" -> "redis.memory.maxmemory.bytes";
            case "kafka" -> "kafka.topic.partitions"; case "mongodb" -> "mongodb.storage.engine";
            case "elasticsearch" -> "elasticsearch.index.docs.count"; case "minio" -> "minio.native.process.resident.bytes";
            default -> throw new AssertionError("Unknown owned fixture type");
        };
        String capacity = switch (type) {
            case "mysql" -> "mysql.database.data.bytes"; case "redis" -> "redis.aof.current.bytes";
            case "kafka" -> "kafka.replica.log.bytes"; case "mongodb" -> "mongodb.database.dataSize.bytes";
            case "elasticsearch" -> "elasticsearch.index.store.primary.bytes"; case "minio" -> "minio.native.cluster.raw.total.bytes";
            default -> throw new AssertionError("Unknown owned fixture type");
        };
        for (var requirement : Map.of("ORDINARY", ordinary, "CAPACITY", capacity).entrySet()) {
            boolean found = false;
            for (JsonNode observation : detail.path("metrics")) if (observation.path("kind").asText().equals(requirement.getKey())
                    && observation.at("/latestAttempt/definition/key").asText().equals(requirement.getValue())) {
                metric(detail, requirement.getValue()); found = true; break;
            }
            assertThat(found).as("Supported native %s sample is required: %s", requirement.getKey(), requirement.getValue()).isTrue();
        }
    }
    private static void verifyNativeReference(MonitoringRuntimeFixture fixture, JsonNode detail) throws Exception {
        var environment = fixture.sources.get(0).environment();
        switch (environment.type()) {
            case "mysql" -> {
                try (var connection = environment.jdbcConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT @@innodb_buffer_pool_size")) {
                    assertThat(result.next()).isTrue(); assertThat(metric(detail, "mysql.innodb.buffer_pool.configured.bytes").path("value").decimalValue()).isEqualByComparingTo(result.getBigDecimal(1));
                }
            }
            case "redis" -> {
                try (var connection = environment.redisConnection()) {
                    String nativeValue = connection.sync().info("memory").lines().filter(line -> line.startsWith("maxmemory:")).findFirst().orElseThrow().substring("maxmemory:".length()).trim();
                    assertThat(metric(detail, "redis.memory.maxmemory.bytes").path("value").decimalValue()).isEqualByComparingTo(nativeValue);
                }
            }
            case "kafka" -> {
                try (var admin = environment.kafkaAdmin()) {
                    int count = admin.describeTopics(List.of(environment.resourceName())).allTopicNames().get(5, TimeUnit.SECONDS).get(environment.resourceName()).partitions().size();
                    assertThat(metric(detail, "kafka.topic.partitions").path("value").asInt()).isEqualTo(count);
                }
            }
            case "mongodb" -> {
                try (var client = environment.mongoClient()) {
                    var status = client.getDatabase("admin").runCommand(new org.bson.Document("serverStatus", 1));
                    assertThat(metric(detail, "mongodb.storage.engine").path("value").asText()).isEqualTo(status.get("storageEngine", org.bson.Document.class).getString("name"));
                }
            }
            case "elasticsearch" -> {
                try (var client = environment.elasticsearchClient()) {
                    var response = client.performRequest(new Request("GET", "/" + environment.resourceName() + "/_stats/docs"));
                    JsonNode nativeBody = fixture.json.readTree(response.getEntity().getContent());
                    assertThat(metric(detail, "elasticsearch.index.docs.count").path("value").asLong()).isEqualTo(nativeBody.at("/indices/" + environment.resourceName() + "/total/docs/count").asLong());
                }
            }
            case "minio" -> {
                String bearer = environment.minioMetricsBearer();
                var response = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build().send(HttpRequest.newBuilder(URI.create(environment.httpEndpoint() + "/minio/v2/metrics/node")).timeout(java.time.Duration.ofSeconds(5)).header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                String line = response.body().lines().filter(value -> value.startsWith("minio_process_start_time_seconds ")).findFirst().orElseThrow();
                assertThat(metric(detail, "minio.native.process.start.seconds").path("value").decimalValue()).isEqualByComparingTo(new BigDecimal(line.split("\\s+")[1]));
            }
            default -> throw new AssertionError("Unknown owned fixture type");
        }
    }
}
