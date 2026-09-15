package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.connection.StandardConnectionInspector;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.minio.MinioMonitoringAdapter;
import com.hpj.admin.monitor.minio.MinioMonitoringConnections;
import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringSnapshotStore.Acceptance.ACCEPTED;
import static com.hpj.admin.monitor.minio.MinioMonitoringConnections.HealthEndpoint;
import static com.hpj.admin.monitor.minio.MinioMonitoringConnections.Observation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real owned MinIO. Default CI uses the pinned Docker release. Explicit monitor.minio.test.binary
 * starts an isolated local process and records its version; it never attaches to an existing endpoint.
 * HTTP capability/failure variants use a bounded proxy; successful requests still reach real MinIO.
 */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "minio")
class MonitoringMinioAdapterIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String LIVE = "/minio/health/live";
    private static final String READ = "/minio/health/cluster/read";
    private static final String WRITE = "/minio/health/cluster";
    private static final String PAYLOAD = "owned-minio-monitor-sentinel";

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeHealthAndConfiguredBucketResultsMatchResponsesAndPreserveAllOwnedData() throws Exception {
        try (OwnedMinio environment = OwnedMinio.start();
             ReadOnlyProxy proxy = new ReadOnlyProxy(environment, List.of(environment.bucket), Map.of());
             Collector collector = new Collector()) {
            environment.prepare(environment.bucket);
            BusinessState before = environment.state();
            ChatProperties source = source(proxy.endpoint(), environment.bucket, environment.access, environment.secret);
            String original = JSON.writeValueAsString(source);
            CollectionResult result = collector.collect(resolve(source, List.of()));
            assertHealthy(result, collector.last());
            assertBucket(result, collector.last(), environment.bucket, 200, true, null);
            assertScopeCoverage(result, 1, true);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            proxy.assertReadOnly(List.of(environment.bucket));
            assertThat(JSON.writeValueAsString(source).equals(original)).as("business connection configuration unchanged").isTrue();
            assertThat(environment.state()).isEqualTo(before);
            assertThat(environment.client.bucketExists(BucketExistsArgs.builder().bucket(environment.bucket).build())).isTrue();
            assertSafe(result, environment);

            String absent = environment.bucket + "-absent";
            try (ReadOnlyProxy absentProxy = new ReadOnlyProxy(environment, List.of(absent), Map.of())) {
                CollectionResult missing = collector.collect(resolve(source(absentProxy.endpoint(), absent,
                        environment.access, environment.secret), List.of()));
                assertHealthy(missing, collector.last());
                assertBucket(missing, collector.last(), absent, 404, false, null);
                assertScopeCoverage(missing, 1, true);
                absentProxy.assertReadOnly(List.of(absent));
                assertThat(environment.state()).isEqualTo(before);
                assertSafe(missing, environment);
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeS3CredentialRejectionDoesNotEraseSuccessfulLiveOrReadWriteProbes() throws Exception {
        try (OwnedMinio environment = OwnedMinio.start();
             ReadOnlyProxy proxy = new ReadOnlyProxy(environment, List.of(environment.bucket), Map.of());
             Collector collector = new Collector()) {
            environment.prepare(environment.bucket);
            BusinessState before = environment.state();
            CollectionResult result = collector.collect(resolve(source(proxy.endpoint(), environment.bucket,
                    environment.access, "owned-invalid-secret"), List.of()));
            assertHealthy(result, collector.last());
            assertBucket(result, collector.last(), environment.bucket, 403, null, MissingReason.UNAUTHORIZED);
            assertScopeCoverage(result, 1, false);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            proxy.assertReadOnly(List.of(environment.bucket));
            assertThat(environment.state()).isEqualTo(before);
            assertSafe(result, environment);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void existingS3OnlyClientProvesBucketAccessWhileServiceHealthRemainsUnknown() throws Exception {
        try (OwnedMinio environment = OwnedMinio.start();
             ReadOnlyProxy proxy = new ReadOnlyProxy(environment, List.of(environment.bucket), Map.of());
             Collector collector = new Collector()) {
            environment.prepare(environment.bucket);
            BusinessState before = environment.state();
            MinioClient business = MinioClient.builder().endpoint(proxy.endpoint()).credentials(environment.access, environment.secret)
                    .httpClient(environment.http).build();
            CollectionResult result = collector.collect(resolve(business, List.of(environment.bucket)));
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(result.serviceProbe().reason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertThat(collector.last().health).isEmpty();
            assertThat(result.metrics()).filteredOn(sample -> sample.definition().key().startsWith("minio.health."))
                    .hasSize(9).allSatisfy(sample -> {
                        assertThat(sample.value()).isNull();
                        assertThat(sample.missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
                    });
            assertBucket(result, collector.last(), environment.bucket, 200, true, null);
            assertScopeCoverage(result, 1, false);
            proxy.assertReadOnly(List.of(environment.bucket));
            assertThat(proxy.requests).noneSatisfy(request -> assertThat(request.path()).startsWith("/minio/health/"));
            assertThat(environment.state()).isEqualTo(before);
            // The exact business SDK client and its externally owned HTTP resources remain usable.
            assertThat(business.bucketExists(BucketExistsArgs.builder().bucket(environment.bucket).build())).isTrue();
            assertThat(environment.http.dispatcher().executorService().isShutdown()).isFalse();
            assertSafe(result, environment);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void missingAndFailingHealthCapabilitiesKeepNativeBucketEvidenceWithoutInferringQuorums() throws Exception {
        try (OwnedMinio environment = OwnedMinio.start(); Collector collector = new Collector()) {
            environment.prepare(environment.bucket);
            BusinessState before = environment.state();
            List<Map<String, Integer>> variants = List.of(
                    Map.of(LIVE, 404, READ, 404, WRITE, 404),
                    Map.of(READ, 404, WRITE, 404),
                    Map.of(LIVE, 503),
                    Map.of(READ, 503, WRITE, 503));
            for (Map<String, Integer> variant : variants) {
                try (ReadOnlyProxy proxy = new ReadOnlyProxy(environment, List.of(environment.bucket), variant)) {
                    CollectionResult result = collector.collect(resolve(source(proxy.endpoint(), environment.bucket,
                            environment.access, environment.secret), List.of()));
                    ReadObservation observation = collector.last();
                    assertHealth(result, observation, HealthEndpoint.LIVE, "health.live", variant.getOrDefault(LIVE, 200));
                    assertHealth(result, observation, HealthEndpoint.READ_READY, "health.read.ready", variant.getOrDefault(READ, 200));
                    assertHealth(result, observation, HealthEndpoint.WRITE_READY, "health.write.ready", variant.getOrDefault(WRITE, 200));
                    assertBucket(result, observation, environment.bucket, 200, true, null);
                    assertScopeCoverage(result, 1, !variant.containsValue(404));
                    if (variant.getOrDefault(LIVE, 200) == 404) {
                        assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
                        assertThat(result.serviceProbe().reason()).isEqualTo(MissingReason.UNSUPPORTED);
                    } else if (variant.getOrDefault(LIVE, 200) == 503) {
                        assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.DEGRADED);
                    }
                    proxy.assertReadOnly(List.of(environment.bucket));
                    assertThat(environment.state()).isEqualTo(before);
                    assertSafe(result, environment);
                }
            }
        }
    }

    /** Docker CI additionally verifies a valid IAM identity with genuinely different bucket permissions. */
    @Test
    @DisabledIfSystemProperty(named = "monitor.minio.test.binary", matches = ".+", disabledReason = "Local binary mode covers native credential rejection; native IAM ACL setup is exercised by Docker CI")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeRestrictedAccountCanInspectAllowedBucketWhileDeniedBucketRemainsPartial() throws Exception {
        try (OwnedMinio environment = OwnedMinio.start(); Collector collector = new Collector()) {
            environment.prepare(environment.bucket);
            String denied = environment.bucket + "-denied";
            environment.prepare(denied);
            var credentials = environment.container.minioReadOnlyCredentials();
            BusinessState before = environment.state();
            try (ReadOnlyProxy proxy = new ReadOnlyProxy(environment, List.of(environment.bucket, denied), Map.of())) {
                CollectionResult result = collector.collect(resolve(source(proxy.endpoint(), environment.bucket,
                        credentials.accessKey(), credentials.secretKey()), List.of(environment.bucket, denied)));
                assertHealthy(result, collector.last());
                assertBucket(result, collector.last(), environment.bucket, 200, true, null);
                assertBucket(result, collector.last(), denied, 403, null, MissingReason.UNAUTHORIZED);
                assertScopeCoverage(result, 2, false);
                assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
                assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
                proxy.assertReadOnly(List.of(environment.bucket, denied));
                assertThat(environment.state()).isEqualTo(before);
                assertSafe(result, environment);
                assertThat(JSON.writeValueAsString(result).contains(credentials.accessKey())).isFalse();
                assertThat(JSON.writeValueAsString(result).contains(credentials.secretKey())).isFalse();
            }
        }
    }

    private static ChatProperties source(String endpoint, String bucket, String access, String secret) {
        ChatProperties source = new ChatProperties();
        source.setEnabled(true);
        source.getAttachment().setEndpoint(endpoint);
        source.getAttachment().setBucket(bucket);
        source.getAttachment().setAccessKey(access);
        source.getAttachment().setSecretKey(secret);
        return source;
    }

    private static ResolvedTarget resolve(Object source, List<String> buckets) {
        var declaration = new MonitoringProperties.Target();
        declaration.setId("minio-target");
        declaration.setType(MiddlewareType.MINIO);
        declaration.setEnabled(true);
        declaration.setConnectionSource("businessAttachments");
        declaration.getScope().setBuckets(buckets);
        var properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(List.of(declaration));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("businessAttachments", source);
        var targets = new MonitoringConnectionResolver(properties, beans, List.of(new StandardConnectionInspector())).resolve();
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
        assertThat(targets.get(0).bindings().get(0).connection().client()).isSameAs(source);
        return targets.get(0);
    }

    private static void assertHealthy(CollectionResult result, ReadObservation observation) {
        assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        assertThat(result.serviceProbe().scope().kind()).isEqualTo(ScopeKind.ENDPOINT);
        assertHealth(result, observation, HealthEndpoint.LIVE, "health.live", 200);
        assertHealth(result, observation, HealthEndpoint.READ_READY, "health.read.ready", 200);
        assertHealth(result, observation, HealthEndpoint.WRITE_READY, "health.write.ready", 200);
    }

    private static void assertHealth(CollectionResult result, ReadObservation read, HealthEndpoint endpoint,
                                     String key, int status) {
        Observation observation = read.health.get(endpoint);
        assertThat(observation).isNotNull();
        assertThat(observation.status()).isEqualTo(status);
        assertThat(number(metric(result, key + ".http.status", null))).isEqualByComparingTo(BigDecimal.valueOf(status));
        assertThat(number(metric(result, key + ".latency.ms", null))).isEqualByComparingTo(milliseconds(observation.elapsed()));
        MetricSample outcome = metric(result, key, null);
        assertThat(outcome.definition().unit()).isEqualTo(Unit.BOOLEAN);
        if (status == 404) {
            assertThat(outcome.value()).isNull();
            assertThat(outcome.missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
        } else {
            assertThat(outcome.value()).isEqualTo(status == 200);
            assertThat(outcome.missingReason()).isNull();
        }
    }

    private static void assertBucket(CollectionResult result, ReadObservation read, String bucket, int status,
                                     Boolean accessible, MissingReason reason) {
        Observation observation = read.buckets.get(bucket);
        assertThat(observation).isNotNull();
        assertThat(observation.status()).isEqualTo(status);
        assertThat(number(metric(result, "bucket.http.status", bucket))).isEqualByComparingTo(BigDecimal.valueOf(status));
        assertThat(number(metric(result, "bucket.latency.ms", bucket))).isEqualByComparingTo(milliseconds(observation.elapsed()));
        MetricSample outcome = metric(result, "bucket.accessible", bucket);
        assertThat(outcome.value()).isEqualTo(accessible);
        assertThat(outcome.missingReason()).isEqualTo(reason);
        assertThat(outcome.definition().scope().kind()).isEqualTo(ScopeKind.BUCKET);
    }

    private static void assertScopeCoverage(CollectionResult result, int count, boolean complete) {
        assertThat(number(metric(result, "buckets.configured", null))).isEqualByComparingTo(BigDecimal.valueOf(count));
        assertThat(number(metric(result, "buckets.requested", null))).isEqualByComparingTo(BigDecimal.valueOf(count));
        assertThat(number(metric(result, "buckets.limit", null))).isEqualByComparingTo(
                BigDecimal.valueOf(Math.min(100, new MonitoringProperties().getLimits().getBuckets())));
        assertThat(metric(result, "coverage.complete", null).value()).isEqualTo(complete);
        assertThat(metric(result, "coverage.truncated", null).value()).isEqualTo(false);
        // Every metric is checked: three health triplets, one triplet per configured bucket, five bounds.
        assertThat(result.metrics()).hasSize(14 + 3 * count);
    }

    private static MetricSample metric(CollectionResult result, String key, String bucket) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals("minio." + key))
                .filter(sample -> bucket == null || sample.definition().scope().id().equals(bucket)).findFirst().orElseThrow();
    }
    private static BigDecimal number(MetricSample metric) {
        assertThat(metric.missingReason()).isNull();
        return (BigDecimal) metric.value();
    }
    private static BigDecimal milliseconds(Duration elapsed) {
        return BigDecimal.valueOf(elapsed.getSeconds()).multiply(BigDecimal.valueOf(1000))
                .add(BigDecimal.valueOf(elapsed.getNano(), 6));
    }
    private static void assertSafe(CollectionResult result, OwnedMinio environment) throws Exception {
        String text = JSON.writeValueAsString(result);
        assertThat(text.contains(environment.access)).isFalse();
        assertThat(text.contains(environment.secret)).isFalse();
        assertThat(text).doesNotContain("Authorization", "AWS4-HMAC-SHA256", "owned-invalid-secret");
    }

    private static final class ReadObservation {
        final Map<HealthEndpoint, Observation> health = new EnumMap<>(HealthEndpoint.class);
        final Map<String, Observation> buckets = new LinkedHashMap<>();
    }

    private static final class RecordingConnections extends MinioMonitoringConnections {
        final List<ReadObservation> reads = new ArrayList<>();
        @Override public Session open(CollectionRequest request) {
            Session delegate = super.open(request);
            ReadObservation read = new ReadObservation();
            reads.add(read);
            return new Session() {
                @Override public Observation health(HealthEndpoint endpoint) {
                    Observation value = delegate.health(endpoint);
                    read.health.put(endpoint, value);
                    return value;
                }
                @Override public Observation bucket(String bucket) {
                    Observation value = delegate.bucket(bucket);
                    read.buckets.put(bucket, value);
                    return value;
                }
                @Override public void close() { delegate.close(); }
            };
        }
    }

    private static final class Collector implements AutoCloseable {
        final Clock clock = Clock.systemUTC();
        final MonitoringProperties properties = new MonitoringProperties();
        final MonitoringCounterStore counters = new MonitoringCounterStore(1, 64);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(1, 64, 1);
        final RecordingConnections connections = new RecordingConnections();
        final MinioMonitoringAdapter adapter = new MinioMonitoringAdapter(properties, connections, clock);
        final ThreadPoolExecutor cleanup = executor();
        long sequence;
        ReadObservation last() { return connections.reads.get(connections.reads.size() - 1); }
        CollectionResult collect(ResolvedTarget target) {
            var binding = target.bindings().get(0);
            var source = binding.connection();
            String id = target.display().id();
            snapshots.activate(id, 1);
            Instant scheduled = clock.instant();
            Duration timeout = properties.getCollectionTimeout();
            var control = new CollectionControl(new Object(), () -> true, clock, System::nanoTime,
                    System.nanoTime() + timeout.toNanos(), cleanup, counters, id, source.source(),
                    CollectionKind.ORDINARY, properties.getOrdinaryInterval(), source.client());
            var request = new CollectionRequest(id, source.source(), source.source(), CollectionKind.ORDINARY,
                    1, ++sequence, scheduled, scheduled.plus(timeout), binding.scope(), source.client(), source.settings(), control);
            try {
                CollectionResult result = adapter.collect(request);
                assertThat(control.complete(request, result, snapshots)).isEqualTo(ACCEPTED);
                await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
                assertThat(control.hasCleanupFailure()).isFalse();
                return result;
            } finally {
                control.cancel(MissingReason.FAILED);
                await().atMost(Duration.ofSeconds(3)).until(control::isCleanupComplete);
            }
        }
        @Override public void close() throws InterruptedException {
            cleanup.shutdownNow();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    private record WireRequest(String method, String path, String query) { }

    /** Denies any unexpected command before it can reach the owned service; no external URL is accepted. */
    private static final class ReadOnlyProxy implements AutoCloseable {
        final HttpServer server;
        final ThreadPoolExecutor executor = executor();
        final OkHttpClient http = httpClient();
        final List<WireRequest> requests = Collections.synchronizedList(new ArrayList<>());
        final Set<String> bucketPaths;
        final Map<String, Integer> overrides;
        final String upstream;
        ReadOnlyProxy(OwnedMinio owner, List<String> buckets, Map<String, Integer> overrides) throws IOException {
            this.upstream = owner.endpoint;
            this.bucketPaths = Set.copyOf(buckets.stream().map(bucket -> "/" + bucket).toList());
            this.overrides = Map.copyOf(overrides);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getRawPath();
                String query = exchange.getRequestURI().getRawQuery();
                String method = exchange.getRequestMethod();
                requests.add(new WireRequest(method, path, query));
                boolean health = Set.of(LIVE, READ, WRITE).contains(path) && "HEAD".equals(method) && query == null;
                boolean bucket = bucketPaths.contains(path) && ("HEAD".equals(method) && query == null
                        || "GET".equals(method) && ("location".equals(query) || "location=".equals(query)));
                if (!health && !bucket) { exchange.sendResponseHeaders(405, -1); return; }
                if (overrides.containsKey(path)) { exchange.sendResponseHeaders(overrides.get(path), -1); return; }
                var request = new Request.Builder().url(upstream + path + (query == null ? "" : "?" + query)).method(method, null);
                // Preserve the original signed Host header while connecting exclusively to the owned upstream.
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!name.equalsIgnoreCase("connection") && !name.equalsIgnoreCase("content-length")) {
                        for (String value : values) request.addHeader(name, value);
                    }
                });
                try (var response = http.newCall(request.build()).execute()) {
                    response.headers().toMultimap().forEach((name, values) -> {
                        if (!name.equalsIgnoreCase("transfer-encoding") && !name.equalsIgnoreCase("content-length")
                                && !name.equalsIgnoreCase("connection")) exchange.getResponseHeaders().put(name, values);
                    });
                    if (method.equals("HEAD")) exchange.sendResponseHeaders(response.code(), -1);
                    else {
                        byte[] body = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(16_385);
                        if (body.length > 16_384) { exchange.sendResponseHeaders(502, -1); return; }
                        exchange.sendResponseHeaders(response.code(), body.length);
                        exchange.getResponseBody().write(body);
                    }
                }
            }
        }
        void assertReadOnly(List<String> buckets) {
            assertThat(requests).isNotEmpty().allSatisfy(request -> {
                if (Set.of(LIVE, READ, WRITE).contains(request.path())) {
                    assertThat(request.method()).isEqualTo("HEAD");
                    assertThat(request.query()).isNull();
                } else {
                    assertThat(request.path()).isIn(buckets.stream().map(bucket -> "/" + bucket).toArray());
                    if (request.method().equals("GET")) assertThat(request.query()).isIn("location", "location=");
                    else {
                        assertThat(request.method()).isEqualTo("HEAD");
                        assertThat(request.query()).isNull();
                    }
                }
            });
            assertThat(requests.size()).isLessThanOrEqualTo(3 + 2 * buckets.size());
        }
        @Override public void close() throws InterruptedException {
            server.stop(0);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            closeHttp(http);
        }
    }

    private record ObjectState(String name, long size, String etag, String version, String modified) { }
    private record BusinessState(Map<String, List<ObjectState>> buckets, Map<String, String> sentinels) { }

    private static final class OwnedMinio implements AutoCloseable {
        final MonitoringTestEnvironment container;
        final Process process;
        final Path directory;
        final Thread logger;
        final String endpoint;
        final String access;
        final String secret;
        final String bucket;
        final OkHttpClient http = httpClient();
        final MinioClient client;

        static OwnedMinio start() throws Exception {
            String binary = System.getProperty("monitor.minio.test.binary", "");
            return binary.isBlank() ? new OwnedMinio(MonitoringTestEnvironment.start("minio")) : new OwnedMinio(Path.of(binary));
        }
        OwnedMinio(MonitoringTestEnvironment fixture) {
            container = fixture;
            process = null;
            directory = fixture.logDirectory();
            logger = null;
            endpoint = fixture.httpEndpoint();
            access = fixture.username();
            secret = fixture.password();
            bucket = fixture.resourceName();
            client = MinioClient.builder().endpoint(endpoint).credentials(access, secret).httpClient(http).build();
        }
        OwnedMinio(Path binary) throws Exception {
            if (!binary.isAbsolute() || !Files.isRegularFile(binary)) {
                throw new IllegalArgumentException("monitor.minio.test.binary must name an existing absolute executable path");
            }
            container = null;
            access = "mon" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
            secret = UUID.randomUUID().toString().replace("-", "");
            bucket = "mon-" + UUID.randomUUID().toString().replace("-", "");
            Path parent = Path.of("target", "monitor-environments").toAbsolutePath().normalize();
            Files.createDirectories(parent);
            directory = Files.createTempDirectory(parent, "minio-native-");
            Path objects = directory.resolve("objects");
            Files.createDirectories(objects);
            int port;
            int consolePort;
            try (ServerSocket api = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 ServerSocket console = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                port = api.getLocalPort();
                consolePort = console.getLocalPort();
            }
            endpoint = "http://127.0.0.1:" + port;
            // ProcessBuilder redirects all standard streams and does not create a Windows console window.
            var version = new ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true)
                    .redirectOutput(directory.resolve("version.txt").toFile()).start();
            if (!version.waitFor(5, TimeUnit.SECONDS)) {
                version.destroyForcibly();
                throw new IllegalStateException("Owned MinIO version command timed out");
            }
            if (version.exitValue() != 0) throw new IllegalStateException("Owned MinIO version command failed");
            var builder = new ProcessBuilder(binary.toString(), "server", objects.toString(), "--address", "127.0.0.1:" + port,
                    "--console-address", "127.0.0.1:" + consolePort).directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().keySet().removeIf(key -> key.startsWith("MINIO_"));
            builder.environment().put("MINIO_ROOT_USER", access);
            builder.environment().put("MINIO_ROOT_PASSWORD", secret);
            builder.environment().put("MINIO_BROWSER", "off");
            process = builder.start();
            process.getOutputStream().close();
            logger = new Thread(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int written = 0;
                    while ((line = lines.readLine()) != null) {
                        String safe = line.replace(access, "[REDACTED]").replace(secret, "[REDACTED]") + System.lineSeparator();
                        if ((written += safe.length()) <= 2_000_000) Files.writeString(directory.resolve("process.log"), safe,
                                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    }
                } catch (IOException ignored) { }
            }, "owned-minio-log");
            logger.setDaemon(true);
            logger.start();
            client = MinioClient.builder().endpoint(endpoint).credentials(access, secret).httpClient(http).build();
            try {
                await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250)).until(() -> {
                    if (!process.isAlive()) throw new IllegalStateException("Owned MinIO exited before readiness; see sanitized fixture logs");
                    try (var response = http.newCall(new Request.Builder().url(endpoint + LIVE).head().build()).execute()) {
                        return response.code() == 200;
                    } catch (IOException unavailable) { return false; }
                });
                JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("environment.json").toFile(),
                        Map.of("type", "minio", "mode", "owned-native-process", "port", port,
                                "version", Files.readString(directory.resolve("version.txt")), "status", "ready"));
            } catch (Exception | AssertionError failure) {
                close();
                throw failure;
            }
        }
        void prepare(String name) throws Exception {
            client.makeBucket(MakeBucketArgs.builder().bucket(name).build());
            byte[] bytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
            client.putObject(PutObjectArgs.builder().bucket(name).object("fixture")
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        }
        BusinessState state() throws Exception {
            Map<String, List<ObjectState>> buckets = new LinkedHashMap<>();
            Map<String, String> sentinels = new LinkedHashMap<>();
            // Independent test observer inventories only its fresh, owned environment, outside collection.
            for (var listed : client.listBuckets().stream().sorted(Comparator.comparing(io.minio.messages.Bucket::name)).toList()) {
                List<ObjectState> objects = new ArrayList<>();
                for (var item : client.listObjects(ListObjectsArgs.builder().bucket(listed.name()).recursive(true).build())) {
                    var value = item.get();
                    objects.add(new ObjectState(value.objectName(), value.size(), value.etag(), value.versionId(),
                            value.lastModified() == null ? null : value.lastModified().toString()));
                }
                buckets.put(listed.name(), List.copyOf(objects));
                try (var object = client.getObject(GetObjectArgs.builder().bucket(listed.name()).object("fixture").build())) {
                    sentinels.put(listed.name(), new String(object.readNBytes(1024), StandardCharsets.UTF_8));
                }
            }
            return new BusinessState(buckets, sentinels);
        }
        @Override public void close() throws Exception {
            closeHttp(http);
            if (container != null) { container.close(); return; }
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Owned MinIO process did not stop");
            }
            logger.join(3000);
            assertThat(process.isAlive()).isFalse();
            Path data = directory.resolve("objects").toAbsolutePath().normalize();
            Path allowed = Path.of("target", "monitor-environments").toAbsolutePath().normalize();
            if (!data.startsWith(allowed) || !data.getParent().equals(directory)) {
                throw new IllegalStateException("Unexpected owned MinIO cleanup target");
            }
            if (Files.exists(data)) {
                try (var paths = Files.walk(data)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
            Files.writeString(directory.resolve("closed.txt"), "owned process stopped; owned object directory removed\n");
        }
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
    }
    private static OkHttpClient httpClient() {
        return new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(2))
                .writeTimeout(Duration.ofSeconds(2)).callTimeout(Duration.ofSeconds(3))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }
    private static void closeHttp(OkHttpClient http) {
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }
}
