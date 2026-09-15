package com.hpj.admin.monitor.elasticsearch;

import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.instrumentation.Instrumentation;
import co.elastic.clients.transport.rest_client.RestClientOptions;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract.CollectionKind;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import org.apache.http.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.protocol.BasicHttpContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.elasticsearch.client.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fake lifecycle/configuration contracts; actual HTTP/TLS streaming fixtures are tested separately. */
class MonitoringElasticsearchConnectionsTest {
    private static final String NODE = "abcdefghijklmnopqrstuv";

    @Test
    void consecutiveDefaultJavaTransportsKeepTheirActualOptionsAndBuiltInTelemetryPolicy() throws Exception {
        // Build and close a test-owned client without sending requests or contacting a service.
        try (Fixture f = new Fixture(); RestClient client = RestClient.builder(new HttpHost("127.0.0.1", 9))
                .setHttpClientConfigCallback(builder -> builder.setDefaultIOReactorConfig(
                        IOReactorConfig.custom().setIoThreadCount(1).build())).build()) {
            RestClientOptions options = new RestClientOptions(RequestOptions.DEFAULT.toBuilder()
                    .addHeader("Authorization", "private-transport-token").build());
            for (int i = 0; i < 2; i++) {
                RestClientTransport transport = new RestClientTransport(client, new JacksonJsonpMapper(), options);
                var resolved = ElasticsearchMonitoringConnections.configuration(f.request(client, transport));
                assertThat(resolved.client()).isSameAs(client);
                assertThat(resolved.options()).isSameAs(((RestClientOptions) transport.options()).restClientRequestOptions());
                assertThat(resolved.options().getHeaders()).anySatisfy(header -> {
                    assertThat(header.getName()).isEqualTo("Authorization");
                    assertThat(header.getValue()).isEqualTo("private-transport-token");
                });
            }
            Instrumentation custom = mock(Instrumentation.class);
            RestClientTransport transport = new RestClientTransport(client, new JacksonJsonpMapper(), options, custom);
            assertFailure(() -> ElasticsearchMonitoringConnections.configuration(f.request(client, transport)), MissingReason.UNSUPPORTED, false);
            verifyNoInteractions(custom);
        }
    }

    @Test
    void keepsSourceHeadersParametersProxyAndShorterTimeoutsWhileBoundingOnlyThisRequest() throws Exception {
        try (Fixture f = new Fixture()) {
            RequestConfig original = RequestConfig.custom().setConnectTimeout(300).setSocketTimeout(900)
                    .setConnectionRequestTimeout(40).setProxy(new HttpHost("private-proxy", 8080))
                    .setMaxRedirects(2).setNormalizeUri(false).setCookieSpec("standard").build();
            f.config = original;
            f.options = RequestOptions.DEFAULT.toBuilder().addHeader("Authorization", "private-test-token")
                    .addParameter("pretty", "false").setRequestConfig(original).build();
            try (var session = f.access().open(f.request())) { session.health(); }
            Request sent = f.sent.get(0);
            assertThat(sent.getMethod()).isEqualTo("GET");
            assertThat(sent.getEntity()).isNull();
            assertThat(sent.getOptions().getHeaders()).containsExactlyElementsOf(f.options.getHeaders());
            assertThat(sent.getOptions().getParameters()).containsEntry("pretty", "false").containsEntry("level", "cluster");
            RequestConfig owned = sent.getOptions().getRequestConfig();
            assertThat(owned).isNotSameAs(original);
            assertThat(owned.getConnectTimeout()).isEqualTo(300);
            assertThat(owned.getSocketTimeout()).isEqualTo(900);
            assertThat(owned.getConnectionRequestTimeout()).isEqualTo(40);
            assertThat(owned.getProxy()).isEqualTo(original.getProxy());
            assertThat(owned.getMaxRedirects()).isEqualTo(2);
            assertThat(owned.isNormalizeUri()).isFalse();
            assertThat(owned.getCookieSpec()).isEqualTo("standard");
            assertThat(f.options.getParameters()).doesNotContainKey("level");
            verify(f.client, never()).close();
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void remainingBudgetShrinksAllNativeTimeoutsWithoutMutatingSource() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ticker.set(TimeUnit.MILLISECONDS.toNanos(4750));
            RequestConfig source = RequestConfig.custom().setConnectTimeout(1000).setSocketTimeout(30000).setConnectionRequestTimeout(-1).build();
            RequestConfig result = ElasticsearchMonitoringConnections.bounded(source, f.control);
            assertThat(result.getConnectTimeout()).isEqualTo(250);
            assertThat(result.getSocketTimeout()).isEqualTo(250);
            assertThat(result.getConnectionRequestTimeout()).isEqualTo(250);
            assertThat(source.getSocketTimeout()).isEqualTo(30000);
            assertThat(source.getConnectionRequestTimeout()).isEqualTo(-1);
        }
    }

    @Test
    void preservesSmallerNativeConsumerLimitAndDeclinesUnknownOrInheritedPolicies() throws Exception {
        try (Fixture f = new Fixture()) {
            RequestOptions source = RequestOptions.DEFAULT.toBuilder().setRequestConfig(f.config)
                    .setHttpAsyncResponseConsumerFactory(new HttpAsyncResponseConsumerFactory.HeapBufferedResponseConsumerFactory(256)).build();
            var result = ElasticsearchMonitoringConnections.configuration(f.client, source);
            assertThat(result.limit()).isEqualTo(256);
            assertThat(result.options()).isSameAs(source);
            assertThat(result.config()).isSameAs(f.config);
            RequestOptions unknown = source.toBuilder().setHttpAsyncResponseConsumerFactory(() -> null).build();
            assertFailure(() -> ElasticsearchMonitoringConnections.configuration(f.client, unknown), MissingReason.UNSUPPORTED, false);
            for (int timeout : List.of(0, -1)) {
                RequestConfig inherited = RequestConfig.copy(f.config).setSocketTimeout(timeout).build();
                RequestOptions options = source.toBuilder().setRequestConfig(inherited).build();
                assertFailure(() -> ElasticsearchMonitoringConnections.configuration(f.client, options), MissingReason.UNSUPPORTED, false);
            }
        }
    }

    @Test
    void fixedParametersDoNotMergeWithConflictingGlobalOptionsOrLeaveAReservation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.options = RequestOptions.DEFAULT.toBuilder().addParameter("level", "indices").build();
            try (var session = f.access().open(f.request())) {
                assertFailure(session::health, MissingReason.UNSUPPORTED, false);
            }
            assertThat(f.sent).isEmpty();
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
        try (Fixture f = new Fixture()) {
            f.options = RequestOptions.DEFAULT.toBuilder().addParameter("level", "cluster").build();
            try (var session = f.access().open(f.request())) { session.health(); }
            assertThat(f.sent.get(0).getOptions().getParameters()).containsEntry("level", "cluster");
        }
    }

    @Test
    void sendsOnlyConfiguredExactIndicesAndPreviouslyObservedNodeIds() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.json = "{\"nodes\":{\"" + NODE + "\":{\"thread_pool\":{}}}}";
            session.threadPools();
            session.nodeStarts(List.of(NODE));
            f.json = "{\"indices\":{}}";
            session.indexStats(List.of("configured-index"), CollectionKind.ORDINARY);
            assertThat(f.sent.stream().map(Request::getEndpoint)).containsExactly(
                    "/_nodes/stats/thread_pool", "/_nodes/" + NODE + "/jvm", "/configured-index/_stats/docs,indexing,search");
            assertThat(f.sent.get(1).getOptions().getParameters().get("filter_path")).contains("_nodes").contains("start_time_in_millis");
            assertThat(f.sent.get(2).getOptions().getParameters()).containsEntry("level", "shards").containsEntry("expand_wildcards", "none");
            assertFailure(() -> session.indexStats(List.of("outside-index"), CollectionKind.ORDINARY), MissingReason.UNSUPPORTED, false);
            assertFailure(() -> session.indexStats(List.of("configured-index"), CollectionKind.CAPACITY), MissingReason.UNSUPPORTED, false);
            assertFailure(() -> session.nodeStarts(List.of("zyxwvutsrqponmlkjihgfe")), MissingReason.UNSUPPORTED, false);
            assertFailure(() -> session.nodeStarts(List.of("_all")), MissingReason.UNSUPPORTED, false);
            assertThat(f.sent).hasSize(3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "_all", "a,b", "a/b", "a?x=y", "-hidden", "a\\b", "a#b"})
    void evenConfiguredNamesCannotBecomeWildcardSelectorsOrArbitraryPaths(String index) throws Exception {
        try (Fixture f = new Fixture()) {
            f.indices = List.of(index);
            try (var session = f.access().open(f.request())) {
                assertFailure(() -> session.indexStats(List.of(index), CollectionKind.ORDINARY), MissingReason.UNSUPPORTED, false);
            }
            assertThat(f.sent).isEmpty();
        }
    }

    @Test
    void capacityReadUsesStoreAndDoesNotAddOrdinaryTraffic() throws Exception {
        try (Fixture f = new Fixture()) {
            f.kind = CollectionKind.CAPACITY;
            try (var session = f.access().open(f.request())) { session.indexStats(f.indices, CollectionKind.CAPACITY); }
            assertThat(f.sent).hasSize(1);
            assertThat(f.sent.get(0).getEndpoint()).isEqualTo("/configured-index/_stats/store");
            assertThat(f.sent.get(0).getOptions().getParameters()).containsEntry("level", "indices");
        }
    }

    @Test
    void exactIndexValidationUsesUtf8BytesAndLowercase() {
        assertThat(ElasticsearchMonitoringConnections.validIndex("lowercase-index")).isTrue();
        assertThat(ElasticsearchMonitoringConnections.validIndex("Uppercase-index")).isFalse();
        assertThat(ElasticsearchMonitoringConnections.validIndex("数".repeat(85))).isTrue();
        assertThat(ElasticsearchMonitoringConnections.validIndex("数".repeat(86))).isFalse();
        assertThat(ElasticsearchMonitoringConnections.validIndex("index[1]")).isFalse();
    }

    @Test
    void terminalCallbackDoesNotReleaseWorkerUntilConsumerClose() throws Exception {
        try (Fixture f = new Fixture()) {
            f.closeOnSuccess = false;
            f.closeOnCancel = false;
            CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> { try (var session = f.access().open(f.request())) { session.health(); } });
            assertThat(f.dispatched.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(f.callback.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.isDone()).isFalse();
            assertThat(f.control.isCleanupComplete()).isFalse();
            f.consumer.get().close();
            worker.get(2, TimeUnit.SECONDS);
            assertThat(f.control.isCleanupComplete()).isTrue();
            verify(f.client, never()).close();
        }
    }

    @Test
    void cancellationCallbackStillRetainsSlotUntilNativeConsumerIsClosed() throws Exception {
        try (Fixture f = new Fixture()) {
            f.autoSuccess = false;
            f.closeOnCancel = false;
            CompletableFuture<MissingReason> worker = CompletableFuture.supplyAsync(() -> {
                try (var session = f.access().open(f.request())) { session.health(); return null; }
                catch (ElasticsearchMonitoringConnections.Failure failure) { return failure.reason(); }
            });
            assertThat(f.dispatched.await(2, TimeUnit.SECONDS)).isTrue();
            f.control.cancel(MissingReason.TIMEOUT);
            assertThat(f.callback.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.isDone()).isFalse();
            assertThat(f.control.isCleanupComplete()).isFalse();
            f.consumer.get().close();
            assertThat(worker.get(2, TimeUnit.SECONDS)).isEqualTo(MissingReason.TIMEOUT);
            f.drain();
            assertThat(f.control.isCleanupComplete()).isTrue();
            verify(f.handle, atLeastOnce()).cancel();
        }
    }

    @Test
    void synchronousCallbackBeforeDispatchReturnsCannotReleaseAcquisitionReservation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.releaseDispatch = new CountDownLatch(1);
            CompletableFuture<MissingReason> worker = CompletableFuture.supplyAsync(() -> {
                try (var session = f.access().open(f.request())) { session.health(); return null; }
                catch (ElasticsearchMonitoringConnections.Failure failure) { return failure.reason(); }
            });
            try {
                assertThat(f.callback.await(2, TimeUnit.SECONDS)).isTrue();
                f.control.cancel(MissingReason.TIMEOUT);
                assertThat(f.control.isCleanupComplete()).isFalse();
                assertThat(worker.isDone()).isFalse();
            } finally { f.releaseDispatch.countDown(); }
            assertThat(worker.get(2, TimeUnit.SECONDS)).isEqualTo(MissingReason.TIMEOUT);
            f.drain();
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void cleanupWaitRestoresInterruptionAndRequiresEveryRetryConsumerClose() throws Exception {
        try (Fixture f = new Fixture()) {
            f.extraAttempt = true;
            f.closeOnSuccess = false;
            f.closeOnCancel = false;
            AtomicBoolean interruptRestored = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try (var session = f.access().open(f.request())) { session.health(); }
                catch (Throwable unexpected) { failure.set(unexpected); }
                finally { interruptRestored.set(Thread.currentThread().isInterrupted()); }
            });
            worker.start();
            assertThat(f.callback.await(2, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            f.consumer.get().close();
            assertThat(f.control.isCleanupComplete()).isFalse();
            f.previousConsumer.get().close();
            worker.join(2000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(interruptRestored.get()).isTrue();
            assertThat(f.control.isCleanupComplete()).isTrue();
        }
    }

    @Test
    void classificationDoesNotExposeNativeMessagesAndTlsDoesNotProveServerDown() throws Exception {
        try (Fixture f = new Fixture(); var session = f.access().open(f.request())) {
            f.error = new SSLHandshakeException("private-certificate-details");
            assertFailure(session::health, MissingReason.TLS_FAILED, false);
            f.error = new java.net.SocketTimeoutException("private-timeout-address");
            assertFailure(session::health, MissingReason.TIMEOUT, true);
            f.error = null;
            f.status = 403;
            assertFailure(session::health, MissingReason.UNAUTHORIZED, false);
            f.status = 404;
            assertFailure(() -> session.indexStats(f.indices, CollectionKind.ORDINARY), MissingReason.NOT_APPLICABLE, false);
            f.status = 200;
            assertThat(session.health().isObject()).isTrue();
        }
    }

    @Test
    void boundedConsumerHandlesExactLimitAndRejectsOversizedDeclaredChunkedAndGzipBodies() throws Exception {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        var exact = consume(payload, -1, null, 16);
        assertThat(exact.getResult().getEntity().getContent().readAllBytes()).isEqualTo(payload);
        assertThatThrownBy(() -> consume(payload, 17, null, 16)).isInstanceOf(ContentTooLongException.class);
        assertThatThrownBy(() -> consume(payload, -1, null, 15)).isInstanceOf(ContentTooLongException.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(new byte[256]); }
        var inflated = consume(output.toByteArray(), -1, "gzip", 64);
        assertThat(inflated.getException()).isInstanceOf(ContentTooLongException.class);
    }

    @Test
    void delegateReleaseOrCancelDoesNotReplaceFinalNativeCloseAcknowledgement() throws Exception {
        AtomicBoolean released = new AtomicBoolean();
        var consumer = new ElasticsearchMonitoringConnections.TrackedConsumer(
                new ElasticsearchMonitoringConnections.BoundedConsumer(32), released::set);
        consumer.cancel();
        assertThat(released.get()).isFalse();
        consumer.close();
        assertThat(released.get()).isTrue();
        consumer.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}{}", "{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{\"x\":", "{\"x\":1e9999999999}"})
    void rejectsInvalidRootDuplicateTrailingAndMalformedJson(String json) throws Exception {
        try (Fixture f = new Fixture()) {
            assertFailure(() -> ElasticsearchMonitoringConnections.parse(json.getBytes(StandardCharsets.UTF_8), f.control), MissingReason.INVALID_VALUE, false);
        }
    }

    @Test
    void finitePrescanRejectsDepthStringNumberAndFineGrainedTokenExpansionBeforeTree() throws Exception {
        List<String> payloads = List.of(
                "{\"a\":".repeat(40) + "0" + "}".repeat(40),
                "{\"a\":\"" + "x".repeat(65537) + "\"}",
                "{\"a\":" + "9".repeat(129) + "}",
                "{\"a\":[" + "0,".repeat(50001) + "0]}");
        for (String payload : payloads) try (Fixture f = new Fixture()) {
            assertFailure(() -> ElasticsearchMonitoringConnections.parse(payload.getBytes(StandardCharsets.UTF_8), f.control), MissingReason.INVALID_VALUE, false);
        }
    }

    private static ElasticsearchMonitoringConnections.BoundedConsumer consume(byte[] content, long declared, String encoding, int limit) throws Exception {
        var consumer = new ElasticsearchMonitoringConnections.BoundedConsumer(limit);
        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 200, "OK");
        BasicHttpEntity entity = new BasicHttpEntity();
        entity.setContentLength(declared);
        if (encoding != null) entity.setContentEncoding(encoding);
        response.setEntity(entity);
        consumer.responseReceived(response);
        consumer.consumeContent(new ContentDecoder() {
            private int position;
            @Override public int read(ByteBuffer target) {
                if (position == content.length) return -1;
                int length = Math.min(target.remaining(), content.length - position);
                target.put(content, position, length);
                position += length;
                return length;
            }
            @Override public boolean isCompleted() { return position == content.length; }
        }, null);
        consumer.responseCompleted(new BasicHttpContext());
        return consumer;
    }
    private static void assertFailure(ThrowingCallable call, MissingReason reason, boolean connection) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ElasticsearchMonitoringConnections.Failure.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.connectionFailure()).isEqualTo(connection);
            assertThat(failure.getMessage()).isEqualTo("Elasticsearch monitoring operation unavailable");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getStackTrace()).isEmpty();
        });
    }

    private static final class Fixture implements AutoCloseable {
        private final RestClient client = mock(RestClient.class);
        private final Cancellable handle = mock(Cancellable.class);
        private final AtomicLong ticker = new AtomicLong();
        private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        private final CollectionControl control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), ticker::get,
                TimeUnit.SECONDS.toNanos(5), cleanup, new MonitoringCounterStore(1, 8), "es", "esClient",
                CollectionKind.ORDINARY, Duration.ofSeconds(15), client);
        private RequestConfig config = RequestConfig.custom().setConnectTimeout(1000).setSocketTimeout(30000).build();
        private RequestOptions options = RequestOptions.DEFAULT;
        private CollectionKind kind = CollectionKind.ORDINARY;
        private List<String> indices = List.of("configured-index");
        private final List<Request> sent = new ArrayList<>();
        private final AtomicReference<HttpAsyncResponseConsumer<HttpResponse>> consumer = new AtomicReference<>();
        private final AtomicReference<HttpAsyncResponseConsumer<HttpResponse>> previousConsumer = new AtomicReference<>();
        private final AtomicReference<ResponseListener> listener = new AtomicReference<>();
        private final CountDownLatch dispatched = new CountDownLatch(1);
        private final CountDownLatch callback = new CountDownLatch(1);
        private CountDownLatch releaseDispatch;
        private boolean autoSuccess = true;
        private boolean closeOnSuccess = true;
        private boolean closeOnCancel = true;
        private boolean extraAttempt;
        private Exception error;
        private String json = "{}";
        private int status = 200;
        private Fixture() throws Exception {
            when(client.performRequestAsync(any(), any())).thenAnswer(call -> {
                Request request = call.getArgument(0);
                ResponseListener callbackListener = call.getArgument(1);
                sent.add(request);
                listener.set(callbackListener);
                var factory = request.getOptions().getHttpAsyncResponseConsumerFactory();
                if (extraAttempt) previousConsumer.set(factory.createHttpAsyncResponseConsumer());
                consumer.set(factory.createHttpAsyncResponseConsumer());
                dispatched.countDown();
                if (autoSuccess) {
                    if (error != null) callbackListener.onFailure(error);
                    else callbackListener.onSuccess(response());
                    callback.countDown();
                    if (closeOnSuccess) consumer.get().close();
                }
                if (releaseDispatch != null) releaseDispatch.await();
                return handle;
            });
            doAnswer(call -> {
                ResponseListener registered = listener.get();
                if (registered != null) {
                    registered.onFailure(new CancellationException("private-native-cancel"));
                    callback.countDown();
                    if (closeOnCancel && consumer.get() != null) consumer.get().close();
                }
                return null;
            }).when(handle).cancel();
        }
        private Response response() {
            Response response = mock(Response.class);
            BasicStatusLine line = new BasicStatusLine(HttpVersion.HTTP_1_1, status, "private-status-text");
            ByteArrayEntity entity = new ByteArrayEntity(json.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
            when(response.getStatusLine()).thenReturn(line);
            when(response.getEntity()).thenReturn(entity);
            return response;
        }
        private ElasticsearchMonitoringConnections access() {
            return new ElasticsearchMonitoringConnections(ignored ->
                    new ElasticsearchMonitoringConnections.Configuration(client, options, config, ElasticsearchMonitoringConnections.MAX_REPLY_BYTES));
        }
        private CollectionRequest request() {
            Instant now = Instant.now();
            return new CollectionRequest("es", "esClient", "esClient", kind, 1, 1, now, now.plusSeconds(5),
                    new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), indices), client,
                    Map.of("restClient", client), control);
        }
        private CollectionRequest request(RestClient actualClient, RestClientTransport transport) {
            Instant now = Instant.now();
            return new CollectionRequest("es", "esClient", "esClient", kind, 1, 1, now, now.plusSeconds(5),
                    new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), indices), actualClient,
                    Map.of("restClient", actualClient, "transport", transport), control);
        }
        private void drain() throws Exception { cleanup.submit(() -> {}).get(2, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            if (releaseDispatch != null) releaseDispatch.countDown();
            if (consumer.get() != null) consumer.get().close();
            if (previousConsumer.get() != null) previousConsumer.get().close();
            control.cancel(MissingReason.FAILED);
            cleanup.shutdown();
            if (!cleanup.awaitTermination(3, TimeUnit.SECONDS)) {
                cleanup.shutdownNow();
                throw new AssertionError("Elasticsearch test cleanup did not stop");
            }
        }
    }
}
