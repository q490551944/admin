package com.hpj.admin.monitor.minio;

import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.sun.net.httpserver.HttpServer;
import io.minio.MinioClient;
import io.minio.credentials.StaticProvider;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.minio.MinioMonitoringConnections.HealthEndpoint.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Fixed native requests, credential boundaries, protocol region discovery and cancellation cleanup. */
class MonitoringMinioConnectionsTest {
    private static final String REGION = "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">eu-west-1</LocationConstraint>";

    @Test
    void healthAndScopedBucketUseOnlyFixedRequestsAndCredentialsStayOffHealth() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            assertThat(session.health(LIVE).status()).isEqualTo(200);
            session.health(READ_READY);
            session.health(WRITE_READY);
            assertThat(session.bucket("configured-bucket").status()).isEqualTo(200);
            assertThat(f.sent).extracting(Request::method).containsExactly("HEAD", "HEAD", "HEAD", "GET", "HEAD");
            assertThat(f.sent).extracting(request -> request.url().encodedPath()).containsExactly(
                    "/minio/health/live", "/minio/health/cluster/read", "/minio/health/cluster", "/configured-bucket", "/configured-bucket");
            assertThat(f.sent.subList(0, 3)).allSatisfy(request -> {
                assertThat(request.header("Authorization")).isNull();
                assertThat(request.header("x-amz-security-token")).isNull();
                assertThat(request.url().query()).isNull();
            });
            assertThat(f.sent.get(3).url().queryParameterNames()).containsExactly("location");
            assertThat(f.sent.get(3).header("Authorization")).contains("/us-east-1/s3/aws4_request");
            assertThat(f.sent.get(4).header("Authorization")).contains("/eu-west-1/s3/aws4_request");
            assertThat(f.sent.get(4).url().query()).isNull();
            assertThat(f.sent).allSatisfy(request -> {
                assertThat(request.body()).isNull();
                assertThat(request.url().host()).isEqualTo("127.0.0.1");
                assertThat(request.url().port()).isEqualTo(39000);
            });
            assertThat(f.closedBodies.get()).isEqualTo(5);
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void regionLookupIsCachedOnlyWithinOwnedSessionAndEmptyRegionMeansSdkDefault() throws Exception {
        try (Fixture f = new Fixture()) {
            f.location = "<LocationConstraint/>";
            try (var session = f.access().open(f.request())) {
                session.bucket("configured-bucket");
                session.bucket("configured-bucket");
                assertThat(f.sent).extracting(Request::method).containsExactly("GET", "HEAD", "HEAD");
                assertThat(f.sent.get(2).header("Authorization")).contains("/us-east-1/s3/aws4_request");
            }
            try (var session = f.access().open(f.request())) { session.bucket("configured-bucket"); }
            assertThat(f.sent).extracting(Request::method).containsExactly("GET", "HEAD", "HEAD", "GET", "HEAD");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 307, 401, 403, 404, 429, 500, 503})
    void nativeHttpStatusIsPreservedWithoutRetryAndRegionFailureStopsHead(int status) throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.status = status;
            assertThat(session.health(LIVE).status()).isEqualTo(status);
            assertThat(session.bucket("configured-bucket").status()).isEqualTo(status);
            assertThat(f.sent).extracting(Request::method).containsExactly("HEAD", "GET");
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void emptyScopeOnlyAllowsConfiguredAttachmentBucketAndExplicitScopeOverridesIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.buckets = List.of();
            try (var session = f.access().open(f.request())) {
                session.bucket("configured-bucket");
                assertFailure(() -> session.bucket("outside-bucket"), MissingReason.UNSUPPORTED, false);
            }
            f.buckets = List.of("explicit-bucket");
            try (var session = f.access().open(f.request())) {
                assertFailure(() -> session.bucket("configured-bucket"), MissingReason.UNSUPPORTED, false);
                session.bucket("explicit-bucket");
            }
            assertThat(f.sent).extracting(request -> request.url().encodedPath())
                    .containsExactly("/configured-bucket", "/configured-bucket", "/explicit-bucket", "/explicit-bucket");
        }
    }

    @Test
    void authorizedScopeUsesFirstHundredDistinctBucketsLikeAdapter() throws Exception {
        try (Fixture f = new Fixture()) {
            var declared = new ArrayList<String>(Collections.nCopies(100, "repeated-bucket"));
            declared.add("later-bucket");
            for (int i = 0; i < 99; i++) declared.add("extra-bucket-" + i);
            f.buckets = declared;
            try (var session = f.access().open(f.request())) {
                session.bucket("later-bucket");
                assertFailure(() -> session.bucket("extra-bucket-98"), MissingReason.UNSUPPORTED, false);
            }
            assertThat(f.sent).extracting(request -> request.url().encodedPath()).containsExactly("/later-bucket", "/later-bucket");
        }
    }

    @Test
    void schemeLessEndpointUsesSameHttpsDefaultAsExistingMinioSdk() throws Exception {
        try (Fixture f = new Fixture()) {
            f.settings.put("endpoint", "storage.example.invalid");
            try (var session = f.access().open(f.request())) { session.health(LIVE); }
            assertThat(f.sent.get(0).url()).isEqualTo(io.minio.http.HttpUtils.getBaseUrl("storage.example.invalid")
                    .newBuilder().encodedPath("/minio/health/live").build());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "abc?location", "UPPER", "a", "a..b", "a.-b", "127.0.0.1", "a/b", "a%2fb"})
    void invalidBucketNeverDispatches(String bucket) throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            assertFailure(() -> session.bucket(bucket), MissingReason.INVALID_VALUE, false);
            assertThat(f.sent).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://private@127.0.0.1/", "http://127.0.0.1/prefix", "http://127.0.0.1/?token=private", "http://127.0.0.1/#private", "file:///private", "http://127.0.0.1/\\outside"})
    void unprovableEndpointIsRejectedWithoutLeakingConfiguration(String endpoint) throws Exception {
        try (Fixture f = new Fixture()) {
            f.settings.put("endpoint", endpoint);
            assertFailure(() -> f.access().open(f.request()), MissingReason.UNSUPPORTED, false);
            assertThat(f.sent).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://s3.us-east-1.amazonaws.com", "https://oss-cn-hangzhou.aliyuncs.com"})
    void attachmentEndpointWithSdkInferredVirtualRoutingIsDeclinedBeforeDispatch(String endpoint) throws Exception {
        try (Fixture f = new Fixture()) {
            f.settings.put("endpoint", endpoint);
            assertFailure(() -> f.access().open(f.request()), MissingReason.UNSUPPORTED, false);
            assertThat(f.sent).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<invalid/>", "<LocationConstraint><region>x</region></LocationConstraint>",
            "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///private'>]><LocationConstraint>&x;</LocationConstraint>",
            "<LocationConstraint>region/other</LocationConstraint>", "<LocationConstraint xmlns='urn:other'>region</LocationConstraint>"})
    void unsafeOrInvalidRegionXmlNeverReachesHead(String body) throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.location = body;
            assertFailure(() -> session.bucket("configured-bucket"), MissingReason.INVALID_VALUE, false);
            assertThat(f.sent).extracting(Request::method).containsExactly("GET");
            assertThat(f.closedBodies.get()).isEqualTo(1);
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void oversizedRegionResponseIsBoundedAndClosedWithoutParsingOrHead() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.location = " ".repeat(MinioMonitoringConnections.MAX_LOCATION_BYTES + 1);
            assertFailure(() -> session.bucket("configured-bucket"), MissingReason.INVALID_VALUE, false);
            assertThat(f.sent).hasSize(1);
            assertThat(f.closedBodies.get()).isEqualTo(1);
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void exactNativeS3ClientPreservesRegionTokenTlsAndTimeoutPolicyAndDoesNotAuthorizeHealth() throws Exception {
        OkHttpClient original = new OkHttpClient.Builder().connectTimeout(125, TimeUnit.MILLISECONDS)
                .readTimeout(350, TimeUnit.MILLISECONDS).writeTimeout(450, TimeUnit.MILLISECONDS)
                .callTimeout(650, TimeUnit.MILLISECONDS).build();
        MinioClient nativeClient = MinioClient.builder().endpoint("https://native.example.invalid")
                .region("native-region").credentialsProvider(new StaticProvider("fixture-access", "fixture-secret", "fixture-token"))
                .httpClient(original).build();
        try (Fixture f = new Fixture(nativeClient)) {
            var inspected = MinioMonitoringConnections.inspect("s3", nativeClient).orElseThrow();
            assertThat(inspected.client()).isSameAs(nativeClient);
            assertThat(inspected.toString()).doesNotContain("fixture-", "native.example");
            try (var session = f.access().open(f.request())) {
                assertFailure(() -> session.health(LIVE), MissingReason.UNSUPPORTED, false);
                assertThat(session.bucket("configured-bucket").status()).isEqualTo(200);
                assertThat(f.sent).extracting(Request::method).containsExactly("HEAD");
                assertThat(f.sent.get(0).header("Authorization")).contains("/native-region/s3/aws4_request");
                assertThat(f.sent.get(0).header("x-amz-security-token")).isEqualTo("fixture-token");
                OkHttpClient owned = f.configurations.get(0);
                assertThat(owned.sslSocketFactory()).isSameAs(original.sslSocketFactory());
                assertThat(owned.hostnameVerifier()).isSameAs(original.hostnameVerifier());
                assertThat(owned.certificatePinner()).isEqualTo(original.certificatePinner());
                assertThat(owned.dns()).isSameAs(original.dns());
                assertThat(owned.connectTimeoutMillis()).isEqualTo(125);
                assertThat(owned.readTimeoutMillis()).isEqualTo(350);
                assertThat(owned.writeTimeoutMillis()).isEqualTo(450);
                assertThat(owned.callTimeoutMillis()).isEqualTo(650);
                assertThat(owned.dispatcher()).isNotSameAs(original.dispatcher());
                assertThat(owned.connectionPool()).isNotSameAs(original.connectionPool());
                assertThat(owned.followRedirects()).isFalse();
                assertThat(owned.followSslRedirects()).isFalse();
                assertThat(owned.retryOnConnectionFailure()).isFalse();
            }
            assertThat(original.followRedirects()).isTrue();
            assertThat(original.retryOnConnectionFailure()).isTrue();
            assertThat(original.dispatcher().executorService().isShutdown()).isFalse();
        } finally { original.dispatcher().executorService().shutdown(); original.connectionPool().evictAll(); }
    }

    @Test
    void unknownNativeRegionUsesScopedLookupWithoutChangingBusinessRegionCache() throws Exception {
        OkHttpClient original = new OkHttpClient();
        MinioClient nativeClient = MinioClient.builder().endpoint("http://127.0.0.1:39000")
                .credentials("fixture-access", "fixture-secret").httpClient(original).build();
        try {
            for (int run = 0; run < 2; run++) try (Fixture f = new Fixture(nativeClient); var session = f.access().open(f.request())) {
                session.bucket("configured-bucket");
                assertThat(f.sent).extracting(Request::method).containsExactly("GET", "HEAD");
            }
        } finally { original.dispatcher().executorService().shutdown(); original.connectionPool().evictAll(); }
    }

    @Test
    void dynamicCredentialsOrCustomRoutingAreDeclinedWithoutFetchingOrExecuting() {
        io.minio.credentials.Provider provider = mock(io.minio.credentials.Provider.class);
        MinioClient dynamic = MinioClient.builder().endpoint("http://127.0.0.1:39000").credentialsProvider(provider).build();
        assertThat(MinioMonitoringConnections.inspect("s3", dynamic)).isEmpty();
        verifyNoInteractions(provider);
        OkHttpClient altered = new OkHttpClient.Builder().addInterceptor(chain -> { throw new AssertionError("must not dispatch"); }).build();
        MinioClient custom = MinioClient.builder().endpoint("http://127.0.0.1:39000")
                .credentials("fixture-access", "fixture-secret").httpClient(altered).build();
        assertThat(MinioMonitoringConnections.inspect("s3", custom)).isEmpty();
    }

    @Test
    void totalBudgetShrinksAcrossRegionAndHeadAndExpiredRequestNeverDispatches() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.onExecute = () -> f.ticker.addAndGet(TimeUnit.SECONDS.toNanos(2));
            session.bucket("configured-bucket");
            assertThat(f.configurations).extracting(OkHttpClient::callTimeoutMillis).containsExactly(5000, 3000);
            f.ticker.set(TimeUnit.SECONDS.toNanos(6));
            assertFailure(() -> session.health(LIVE), MissingReason.TIMEOUT, false);
            assertThat(f.sent).hasSize(2);
        }
    }

    @Test
    void classifiesNetworkTlsAndTimeoutWithoutOriginalExceptionTextOrCause() throws Exception {
        List<IOException> failures = List.of(new ConnectException("private-endpoint"), new SSLException("private-certificate"), new SocketTimeoutException("private-request"));
        List<MissingReason> reasons = List.of(MissingReason.FAILED, MissingReason.TLS_FAILED, MissingReason.TIMEOUT);
        for (int i = 0; i < failures.size(); i++) try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.failure = failures.get(i);
            assertFailure(() -> session.health(LIVE), reasons.get(i), i == 0);
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void cancelAcknowledgementDoesNotReleaseCleanupBeforeExecuteReturns() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.releaseExecute = new CountDownLatch(1);
            CompletableFuture<Void> work = CompletableFuture.runAsync(() -> assertFailure(() -> session.health(LIVE), MissingReason.TIMEOUT, false));
            assertThat(f.started.await(2, TimeUnit.SECONDS)).isTrue();
            f.control.cancel(MissingReason.TIMEOUT);
            f.drain();
            assertThat(f.cancelled.get()).isTrue();
            assertThat(f.control.isCleanupComplete()).isFalse();
            f.releaseExecute.countDown();
            work.get(2, TimeUnit.SECONDS);
            assertThat(f.closedBodies.get()).isEqualTo(1);
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void responseCloseIsTheCleanupBarrierAndFailedCloseKeepsReservation() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.closeFailure = true;
            assertFailure(() -> session.health(LIVE), MissingReason.FAILED, false);
            assertThat(f.control.isCleanupComplete()).isFalse();
        }
    }

    @Test
    void actualHttpNeverFollowsRedirectOrRetryAfterZeroAndNativeStatusSurvives() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(307);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/should-not-follow");
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        try (Fixture f = new Fixture()) {
            f.settings.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort());
            try (var session = new MinioMonitoringConnections().open(f.request())) {
                assertThat(session.health(LIVE).status()).isEqualTo(307);
                assertThat(requests.get()).isEqualTo(1);
                status.set(503);
                assertThat(session.health(LIVE).status()).isEqualTo(503);
                assertThat(requests.get()).isEqualTo(2);
            }
            assertThat(f.control.isCleanupComplete()).isTrue();
        } finally { server.stop(0); }
    }

    private static void assertFailure(Runnable action, MissingReason reason, boolean connectionFailure) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(MinioMonitoringConnections.Failure.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.connectionFailure()).isEqualTo(connectionFailure);
            assertThat(failure.getMessage()).isEqualTo("MinIO monitoring operation unavailable");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getStackTrace()).isEmpty();
        });
    }

    private static final class Fixture implements AutoCloseable {
        private final Object owner;
        private final Map<String, Object> settings;
        private final AtomicLong ticker = new AtomicLong();
        private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        private final CollectionControl control;
        private final List<Request> sent = new ArrayList<>();
        private final List<OkHttpClient> configurations = new ArrayList<>();
        private final AtomicInteger closedBodies = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private List<String> buckets = List.of("configured-bucket");
        private int status = 200;
        private String location = REGION;
        private IOException failure;
        private Runnable onExecute = () -> {};
        private CountDownLatch releaseExecute;
        private boolean closeFailure;

        private Fixture() { this(new ChatProperties()); }
        private Fixture(Object owner) {
            this.owner = owner;
            settings = owner instanceof MinioClient nativeClient
                    ? new LinkedHashMap<>(MinioMonitoringConnections.inspect("s3", nativeClient).orElseThrow().settings())
                    : new LinkedHashMap<>(Map.of("endpoint", "http://127.0.0.1:39000", "accessKey", "fixture-access",
                            "secretKey", "fixture-secret", "bucket", "configured-bucket", "secure", false));
            Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
            control = new CollectionControl(new Object(), () -> true, clock, ticker::get, TimeUnit.SECONDS.toNanos(5),
                    cleanup, new MonitoringCounterStore(1, 8), "minio", "minioSource", CollectionKind.ORDINARY,
                    Duration.ofSeconds(15), owner);
        }
        private MinioMonitoringConnections access() {
            return new MinioMonitoringConnections(client -> {
                configurations.add(client);
                return request -> {
                    sent.add(request);
                    Call call = mock(Call.class);
                    try {
                        when(call.execute()).thenAnswer(invocation -> {
                            started.countDown();
                            if (releaseExecute != null && !releaseExecute.await(3, TimeUnit.SECONDS)) throw new AssertionError("test execute not released");
                            onExecute.run();
                            if (failure != null) throw failure;
                            byte[] bytes = (request.method().equals("GET") ? location : "").getBytes(StandardCharsets.UTF_8);
                            Buffer buffer = new Buffer().write(bytes);
                            BufferedSource source = Okio.buffer(new ForwardingSource(buffer) {
                                @Override public void close() throws IOException {
                                    if (closeFailure) throw new IllegalStateException("private-close-error");
                                    closedBodies.incrementAndGet();
                                    super.close();
                                }
                            });
                            ResponseBody body = new ResponseBody() {
                                @Override public MediaType contentType() { return MediaType.parse("application/xml"); }
                                @Override public long contentLength() { return bytes.length; }
                                @Override public BufferedSource source() { return source; }
                            };
                            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                                    .code(status).message("private-status").body(body).build();
                        });
                    } catch (IOException impossible) { throw new AssertionError(impossible); }
                    doAnswer(invocation -> { cancelled.set(true); return null; }).when(call).cancel();
                    return call;
                };
            });
        }
        private CollectionRequest request() {
            Instant now = control.now();
            return new CollectionRequest("minio", "minioSource", "minioSource", CollectionKind.ORDINARY, 1, 1,
                    now, now.plusSeconds(5), new MonitoringTarget.Scope(List.of(), List.of(), List.of(), buckets, List.of()),
                    owner, settings, control);
        }
        private void drain() throws Exception { cleanup.submit(() -> {}).get(2, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            if (releaseExecute != null) releaseExecute.countDown();
            control.cancel(MissingReason.FAILED);
            cleanup.shutdown();
            if (!cleanup.awaitTermination(3, TimeUnit.SECONDS)) {
                cleanup.shutdownNow();
                throw new AssertionError("MinIO test cleanup did not stop");
            }
        }
    }
}
