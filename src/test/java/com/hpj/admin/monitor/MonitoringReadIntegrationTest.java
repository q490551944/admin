package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.hpj.admin.monitor.MonitoringReadFixtures.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {MonitoringSecurityTestApplication.class, MonitoringReadTestConfiguration.class}, properties = {
        "spring.config.location=classpath:/chat-test.yml", "chat.enabled=false", "monitor.enabled=true",
        "monitor.allowed-user-ids[0]=1",
        "spring.jackson.serialization.write-dates-as-timestamps=true",
        "spring.datasource.url=jdbc:h2:mem:monitor_reads;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class MonitoringReadIntegrationTest extends MonitoringSecurityTestSupport {
    @Autowired MonitoringReadFixtures fixtures;

    @BeforeEach
    void resetDirectory() { fixtures.replace(target("redis-main")); }

    @Test
    void everyReadUsesTheRealEmployeeSessionAndAllowlistAndUnknownDetailsRemain404() throws Exception {
        String[] paths = {"/catalog", "/snapshots", "/targets/redis-main", "/targets/private-unknown-target"};
        for (String path : paths) {
            mvc.perform(get(MONITOR + path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        }
        Login denied = login("bob");
        for (String path : paths) {
            mvc.perform(get(MONITOR + path).session(denied.session())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        Login allowed = login("alice");
        for (String path : List.of("/catalog", "/snapshots", "/targets/redis-main")) read(path, allowed);
        String unknown = mvc.perform(get(MONITOR + "/targets/private-unknown-target").session(allowed.session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TARGET_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(unknown).doesNotContain("private-unknown-target", "stackTrace", "exception");
        jdbc.update("UPDATE user SET status=FALSE WHERE id=1");
        mvc.perform(get(MONITOR + "/catalog").session(allowed.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void identityDatabaseOutageReturns503ForAllReadRoutesAndPreservesTheSessionForRecovery() throws Exception {
        Login allowed = login("alice");
        jdbc.execute("ALTER TABLE user RENAME TO monitor_read_employee_outage");
        try {
            for (String path : List.of("/catalog", "/snapshots", "/targets/redis-main")) {
                String body = mvc.perform(get(MONITOR + path).session(allowed.session()))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
                        .andReturn().getResponse().getContentAsString();
                assertThat(body).doesNotContain("SELECT", "Jdbc", "SQLException", "monitor_read_employee_outage", HASH);
                assertThat(allowed.session().getAttribute(CONTEXT)).isNotNull();
            }
        } finally {
            jdbc.execute("ALTER TABLE monitor_read_employee_outage RENAME TO user");
        }
        for (String path : List.of("/catalog", "/snapshots", "/targets/redis-main")) read(path, allowed);
    }

    @Test
    void catalogIncludesAllSixTypesAndRetainsMissingAndDisabledConfiguredEntries() throws Exception {
        fixtures.replace(target("redis-main"), unavailable("mysql-missing", MiddlewareType.MYSQL, true),
                unavailable("minio-disabled", MiddlewareType.MINIO, false));
        Login allowed = login("alice");
        JsonNode catalog = read("/catalog", allowed);
        assertThat(catalog.path("types")).hasSize(6);
        assertThat(catalog.path("resolutionState").asText()).isEqualTo("READY");
        assertThat(catalog.path("serverTime").asText()).isEqualTo(SAMPLE_TIME.plusSeconds(1).toString());
        assertThat(catalog.path("generation").asLong()).isPositive();
        JsonNode redis = find(catalog.path("types"), "type", "REDIS");
        assertThat(redis.path("configurationStatus").asText()).isEqualTo("CONFIGURED");
        assertThat(redis.path("configuredCount").asInt()).isEqualTo(1);
        JsonNode mysql = find(catalog.path("types"), "type", "MYSQL");
        assertThat(mysql.path("configurationStatus").asText()).isEqualTo("CONFIGURATION_MISSING");
        assertThat(mysql.path("missingCount").asInt()).isEqualTo(1);
        assertThat(mysql.at("/targets/0/resolutionReason").asText()).isEqualTo("SOURCE_MISSING");
        JsonNode minio = find(catalog.path("types"), "type", "MINIO");
        assertThat(minio.path("configurationStatus").asText()).isEqualTo("DISABLED");
        assertThat(minio.path("disabledCount").asInt()).isEqualTo(1);
        assertThat(find(catalog.path("types"), "type", "KAFKA").path("targets")).isEmpty();
        for (String id : List.of("mysql-missing", "minio-disabled")) {
            JsonNode detail = read("/targets/" + id, allowed);
            assertThat(detail.at("/target/target/id").asText()).isEqualTo(id);
            assertThat(detail.path("metrics")).isEmpty();
            assertThat(detail.at("/target/collections")).isEmpty();
            assertThat(detail.at("/target/serviceObservations")).isEmpty();
            assertThat(detail.path("capabilityState").asText()).isEqualTo("UNKNOWN");
            assertThat(detail.at("/target/schedulingState").asText())
                    .isEqualTo(id.equals("mysql-missing") ? "CONFIGURATION_MISSING" : "DISABLED");
        }
    }

    @Test
    void firstReadDoesNotInventAvailabilityOrZeroMetricsBeforeAnyCollection() throws Exception {
        Login allowed = login("alice");
        JsonNode overview = read("/snapshots", allowed).path("targets").get(0);
        assertThat(overview.path("schedulingState").asText()).isEqualTo("ACTIVE");
        assertThat(overview.path("metricCount").asInt()).isZero();
        assertThat(overview.path("missingMetricCount").asInt()).isZero();
        assertThat(overview.path("collections")).hasSize(2);
        for (JsonNode collection : overview.path("collections")) {
            assertThat(collection.path("status").asText()).isEqualTo("WAITING");
            assertThat(collection.path("reason").asText()).isEqualTo("WAITING_SAMPLE");
            assertThat(collection.path("lastAttempt").isNull()).isTrue();
        }
        assertThat(overview.at("/serviceObservations/0/availability").asText()).isEqualTo("UNKNOWN");
        assertThat(overview.at("/serviceObservations/0/reason").asText()).isEqualTo("WAITING_SAMPLE");
        assertThat(overview.at("/serviceObservations/0/lastObservation").isNull()).isTrue();
        JsonNode detail = read("/targets/redis-main", allowed);
        assertThat(detail.path("metrics")).isEmpty();
        assertThat(detail.path("capabilities")).isEmpty();
        assertThat(detail.path("capabilityState").asText()).isEqualTo("UNKNOWN");
        assertThat(detail.path("target")).isEqualTo(overview);
    }

    @Test
    void partialResultsRetainEachBindingScopeAndLastSuccessWithoutClaimingServiceAvailability() throws Exception {
        var target = target("combined", List.of(binding("binding-a", "sharedSource", "db-a"),
                binding("binding-b", "sharedSource", "db-b")));
        fixtures.replace(target);
        var keysA = definition("keys", "db-a");
        var memoryA = definition("memory", "db-a");
        var keysB = definition("keys", "db-b");
        fixtures.publish(target, 0, CollectionKind.ORDINARY, 1,
                new CollectionResult(CollectionStatus.SUCCESS, SAMPLE_TIME, SAMPLE_TIME,
                        List.of(MetricSample.success(keysA, 42, SAMPLE_TIME, Duration.ofSeconds(45))), null));
        var next = SAMPLE_TIME.plusMillis(500);
        fixtures.publish(target, 0, CollectionKind.ORDINARY, 2,
                new CollectionResult(CollectionStatus.PARTIAL, next, next,
                        List.of(MetricSample.missing(keysA, MissingReason.UNAUTHORIZED, next),
                                MetricSample.success(memoryA, 12, next, Duration.ofSeconds(45))),
                        null, false, MissingReason.UNAUTHORIZED));
        fixtures.publish(target, 1, CollectionKind.ORDINARY, 1,
                new CollectionResult(CollectionStatus.SUCCESS, SAMPLE_TIME, SAMPLE_TIME,
                        List.of(MetricSample.success(keysB, 99, SAMPLE_TIME, Duration.ofSeconds(45))),
                        new ServiceProbe(ServiceAvailability.AVAILABLE, null,
                                new Scope(ScopeKind.ENDPOINT, "sharedSource", null), SAMPLE_TIME, SAMPLE_TIME.plusSeconds(45))));
        Login allowed = login("alice");
        JsonNode detail = read("/targets/combined", allowed);
        assertThat(detail.path("metrics")).hasSize(3);
        assertThat(detail.path("capabilities")).hasSize(3);
        assertThat(detail.path("capabilityState").asText()).isEqualTo("OBSERVED");
        assertThat(detail.at("/target/metricCount").asInt()).isEqualTo(3);
        assertThat(detail.at("/target/missingMetricCount").asInt()).isEqualTo(1);
        JsonNode bindings = detail.at("/target/target/bindings");
        assertThat(find(bindings, "bindingId", "binding-a").at("/scope/databases").toString()).isEqualTo("[\"db-a\"]");
        assertThat(find(bindings, "bindingId", "binding-b").at("/scope/databases").toString()).isEqualTo("[\"db-b\"]");
        JsonNode missing = values(detail.path("metrics")).stream()
                .filter(metric -> metric.at("/latestAttempt/missingReason").asText().equals("UNAUTHORIZED")).findFirst().orElseThrow();
        assertThat(missing.path("bindingId").asText()).isEqualTo("binding-a");
        assertThat(missing.at("/latestAttempt/value").isNull()).isTrue();
        assertThat(missing.at("/latestAttempt/definition/scope/id").asText()).isEqualTo("db-a");
        assertThat(missing.at("/latestAttempt/sampledAt").asText()).isEqualTo(next.toString());
        assertThat(missing.at("/lastSuccess/value").asInt()).isEqualTo(42);
        assertThat(missing.at("/lastSuccess/lastSuccessAt").asText()).isEqualTo(SAMPLE_TIME.toString());
        assertThat(missing.at("/lastSuccess/validUntil").asText()).isEqualTo(SAMPLE_TIME.plusSeconds(45).toString());
        assertThat(values(detail.path("capabilities"))).anySatisfy(capability ->
                assertThat(capability.path("capability").asText()).isEqualTo("UNAUTHORIZED"));
        JsonNode services = detail.at("/target/serviceObservations");
        assertThat(find(services, "bindingId", "binding-a").path("availability").asText()).isEqualTo("UNKNOWN");
        assertThat(find(services, "bindingId", "binding-b").path("availability").asText()).isEqualTo("AVAILABLE");
        List<JsonNode> ordinary = values(detail.at("/target/collections")).stream()
                .filter(collection -> collection.path("kind").asText().equals("ORDINARY")).toList();
        assertThat(ordinary).anySatisfy(collection -> assertThat(collection.path("status").asText()).isEqualTo("PARTIAL"));
        assertThat(ordinary).anySatisfy(collection -> assertThat(collection.path("status").asText()).isEqualTo("SUCCESS"));
        assertThat(read("/snapshots", allowed).path("targets").get(0)).isEqualTo(detail.path("target"));
    }

    @Test
    void repeatedReadsDoNotResolveCollectOrChangePublishedSamples() throws Exception {
        var target = target("redis-main");
        fixtures.replace(target);
        fixtures.publish(target, 0, CollectionKind.ORDINARY, 1,
                new CollectionResult(CollectionStatus.SUCCESS, SAMPLE_TIME, SAMPLE_TIME,
                        List.of(MetricSample.success(definition("keys", "0"), 5, SAMPLE_TIME, Duration.ofSeconds(45))), null));
        Login allowed = login("alice");
        int resolutions = fixtures.resolutions.get();
        int collections = fixtures.collections.get();
        var snapshot = fixtures.store.snapshot("redis-main").orElseThrow();
        for (String path : List.of("/catalog", "/snapshots", "/targets/redis-main")) {
            JsonNode first = read(path, allowed);
            for (int repeat = 0; repeat < 5; repeat++) assertThat(read(path, allowed)).isEqualTo(first);
        }
        assertThat(fixtures.resolutions.get()).isEqualTo(resolutions);
        assertThat(fixtures.collections.get()).isEqualTo(collections).isZero();
        assertThat(fixtures.store.snapshot("redis-main").orElseThrow()).isEqualTo(snapshot);
    }

    @Test
    void replacingTheCatalogCannotAttachAnOldTargetsMetricsToItsSuccessor() throws Exception {
        var before = target("before");
        fixtures.replace(before);
        fixtures.publish(before, 0, CollectionKind.ORDINARY, 1,
                new CollectionResult(CollectionStatus.SUCCESS, SAMPLE_TIME, SAMPLE_TIME,
                        List.of(MetricSample.success(definition("old.metric", "0"), 73, SAMPLE_TIME, Duration.ofSeconds(45))), null));
        Login allowed = login("alice");
        long previous = read("/targets/before", allowed).path("generation").asLong();
        fixtures.replace(target("after"));
        JsonNode current = read("/targets/after", allowed);
        assertThat(current.path("generation").asLong()).isGreaterThan(previous);
        assertThat(current.path("metrics")).isEmpty();
        assertThat(read("/snapshots", allowed).toString()).doesNotContain("old.metric", "before");
        mvc.perform(get(MONITOR + "/targets/before").session(allowed.session())).andExpect(status().isNotFound());
    }

    private JsonNode read(String path, Login login) throws Exception {
        String body = mvc.perform(get(MONITOR + path).session(login.session())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PRIVATE_CLIENT, PRIVATE_SETTING, "private-monitor.example",
                "\"client\"", "\"settings\"", "\"connection\"", "\"password\"", "\"control\"", "\"identity\"", "stackTrace");
        return json.readTree(body);
    }

    private static JsonNode find(JsonNode array, String field, String expected) {
        return values(array).stream().filter(node -> node.path(field).asText().equals(expected)).findFirst().orElseThrow();
    }
    private static List<JsonNode> values(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();
        array.forEach(result::add);
        return result;
    }
}
