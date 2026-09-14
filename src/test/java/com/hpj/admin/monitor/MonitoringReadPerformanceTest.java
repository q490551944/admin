package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hpj.admin.monitor.MonitoringReadFixtures.*;
import static com.hpj.admin.monitor.MonitoringSecurityTestSupport.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit HTTP performance gate: -Dmonitor.performance=true. No servlet/security/SQL mocks. */
@SpringBootTest(classes = {MonitoringSecurityTestApplication.class, MonitoringReadTestConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/chat-test.yml", "chat.enabled=false", "monitor.enabled=true",
                "monitor.allowed-user-ids[0]=1",
                "spring.datasource.url=jdbc:h2:mem:monitor_read_performance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
@EnabledIfSystemProperty(named = "monitor.performance", matches = "true")
class MonitoringReadPerformanceTest {
    static final int TARGETS = 20;
    static final int METRICS_PER_TARGET = 5000;
    static final int READERS = 5;
    static final int ROUNDS = 8;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MonitoringReadFixtures fixtures;

    @BeforeEach
    void seedEmployeeAndPublishedSnapshots() {
        jdbc.update("DELETE FROM user");
        jdbc.update("INSERT INTO user(id,username,password,status) VALUES(1,'alice',?,TRUE)", HASH);
        List<ResolvedTarget> targets = new ArrayList<>();
        for (int index = 0; index < TARGETS; index++) targets.add(target("target-" + index));
        fixtures.scheduler.replaceTargets(targets);
        List<MetricSample> metrics = new ArrayList<>();
        for (int index = 0; index < METRICS_PER_TARGET; index++) {
            metrics.add(MetricSample.success(definition("metric." + index, "0"), index,
                    SAMPLE_TIME, Duration.ofSeconds(45)));
        }
        for (ResolvedTarget target : targets) {
            fixtures.publish(target, 0, CollectionKind.ORDINARY, 1,
                    new CollectionResult(CollectionStatus.SUCCESS, SAMPLE_TIME, SAMPLE_TIME, metrics, null, true));
        }
        assertThat(fixtures.store.targetCount()).isEqualTo(TARGETS);
        assertThat(fixtures.store.snapshots()).allSatisfy(snapshot -> assertThat(snapshot.metrics()).hasSize(METRICS_PER_TARGET));
    }

    @Test
    void fiveAuthenticatedReadersStayWithinOneSecondAtTheConfiguredSnapshotLimit() throws Exception {
        List<Browser> browsers = new ArrayList<>();
        for (int reader = 0; reader < READERS; reader++) browsers.add(loginBrowser());
        // Warm each authenticated connection, both handlers, and JSON codecs before the measured interval.
        for (int reader = 0; reader < READERS; reader++) {
            measure(browsers.get(reader), "/snapshots", false);
            measure(browsers.get(reader), "/targets/target-" + reader, true);
        }
        int resolutionsBefore = fixtures.resolutions.get();
        int collectionsBefore = fixtures.collections.get();
        long generation = fixtures.store.snapshot("target-0").orElseThrow().generation();
        var workers = Executors.newFixedThreadPool(READERS);
        List<Reading> readings = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(READERS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Reading>>> tasks = new ArrayList<>();
            for (int reader = 0; reader < READERS; reader++) {
                Browser browser = browsers.get(reader);
                int readerIndex = reader;
                tasks.add(workers.submit(() -> {
                    List<Reading> samples = new ArrayList<>();
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Readers did not start together");
                    for (int round = 0; round < ROUNDS; round++) {
                        samples.add(measure(browser, "/snapshots", false));
                        samples.add(measure(browser, "/targets/target-" + ((readerIndex + round * READERS) % TARGETS), true));
                    }
                    return samples;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<List<Reading>> task : tasks) readings.addAll(task.get(90, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
        assertThat(fixtures.resolutions.get()).isEqualTo(resolutionsBefore);
        assertThat(fixtures.collections.get()).isEqualTo(collectionsBefore).isZero();
        assertThat(fixtures.store.snapshot("target-0").orElseThrow().generation()).isEqualTo(generation);

        var report = new LinkedHashMap<String, Object>();
        report.put("java", System.getProperty("java.version"));
        report.put("vm", System.getProperty("java.vm.name"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        report.put("database", jdbc.execute((java.sql.Connection connection) ->
                connection.getMetaData().getDatabaseProductName() + " " + connection.getMetaData().getDatabaseProductVersion()));
        report.put("targets", TARGETS);
        report.put("metricsPerTarget", METRICS_PER_TARGET);
        report.put("readers", READERS);
        report.put("warmupRequests", READERS * 2);
        report.put("measurement", "Five independently logged-in cookie sessions; real HTTP, CSRF/form authentication, per-read H2 identity validation, full response transfer and JSON decoding; login and warmup excluded.");
        var overview = readings.stream().filter(reading -> !reading.detail()).toList();
        var detail = readings.stream().filter(Reading::detail).toList();
        double overviewP95 = p95(overview);
        double detailP95 = p95(detail);
        report.put("overview", summary(overview, overviewP95));
        report.put("detail", summary(detail, detailP95));
        report.put("resolverCallsDuringMeasurement", fixtures.resolutions.get() - resolutionsBefore);
        report.put("adapterCallsDuringMeasurement", fixtures.collections.get() - collectionsBefore);
        String rendered = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        System.out.println("MONITOR_READ_HTTP_BASELINE " + rendered);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/monitor-api-performance.json"), rendered);
        assertThat(overviewP95).as("Authenticated overview HTTP P95 ms").isLessThanOrEqualTo(1000);
        assertThat(detailP95).as("Authenticated 5000-metric detail HTTP P95 ms").isLessThanOrEqualTo(1000);

        // A measured client really depends on persisted employee eligibility; its cookie is not a bypass.
        jdbc.update("UPDATE user SET status=FALSE WHERE id=1");
        assertThat(browsers.get(0).request("/snapshots", null, null).statusCode()).isEqualTo(401);
    }

    private Browser loginBrowser() throws Exception {
        Browser browser = new Browser();
        var initial = browser.request("/csrf-token", null, null);
        assertThat(initial.statusCode()).isEqualTo(200);
        String csrf = json.readTree(initial.body()).path("token").asText();
        assertThat(csrf).isNotBlank();
        var response = browser.request("/sessions", "username=alice&password=" + PASSWORD, csrf);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).path("id").asText()).isEqualTo("1");
        assertThat(browser.cookies.getCookieStore().getCookies()).isNotEmpty();
        return browser;
    }

    private Reading measure(Browser browser, String path, boolean detail) throws Exception {
        long started = System.nanoTime();
        var response = browser.request(path, null, null);
        JsonNode body = json.readTree(response.body());
        double milliseconds = (System.nanoTime() - started) / 1_000_000.0;
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(body.path("generation").asLong()).isPositive();
        if (detail) {
            assertThat(body.path("metrics").size()).isEqualTo(METRICS_PER_TARGET);
            assertThat(body.path("capabilities").size()).isEqualTo(METRICS_PER_TARGET);
            assertThat(body.at("/target/target/id").asText()).isEqualTo(path.substring("/targets/".length()));
        } else {
            assertThat(body.path("targets").size()).isEqualTo(TARGETS);
            for (JsonNode target : body.path("targets")) {
                assertThat(target.path("metricCount").asInt()).isEqualTo(METRICS_PER_TARGET);
                assertThat(target.has("metrics")).isFalse();
            }
        }
        return new Reading(detail, milliseconds, response.body().length);
    }

    private static double p95(List<Reading> values) {
        double[] sorted = values.stream().mapToDouble(Reading::milliseconds).sorted().toArray();
        return sorted[(int) Math.ceil(sorted.length * .95) - 1];
    }

    private static Object summary(List<Reading> readings, double p95) {
        var result = new LinkedHashMap<String, Object>();
        result.put("samples", readings.size());
        result.put("p95Ms", p95);
        result.put("maxMs", readings.stream().mapToDouble(Reading::milliseconds).max().orElseThrow());
        result.put("maxResponseBytes", readings.stream().mapToInt(Reading::bytes).max().orElseThrow());
        return result;
    }

    private record Reading(boolean detail, double milliseconds, int bytes) { }

    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();

        HttpResponse<byte[]> request(String path, String form, String csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + MONITOR + path))
                    .timeout(Duration.ofSeconds(15));
            if (form == null) request.GET();
            else request.header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }
}
