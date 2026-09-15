package com.hpj.admin.monitor.elasticsearch;

import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.support.MonitoringTlsMaterial;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static com.hpj.admin.monitor.metric.MetricContract.CollectionKind.ORDINARY;
import static com.hpj.admin.monitor.metric.MetricContract.MissingReason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Native HTTP/TLS exchanges: mocks cannot prove that cancelled requests release the actual socket. */
class MonitoringElasticsearchNativeDeadlineTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void originalShortTimeoutBoundsHttpOrTlsHandshakeAndReleasesTheSocket(boolean tls) throws Exception {
        try (Peer peer = Peer.stalled(tls, 350)) {
            CompletableFuture<ElasticsearchMonitoringConnections.Failure> collected =
                    CompletableFuture.supplyAsync(peer::failure, peer.workers);
            assertThat(peer.received.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(collected.get(3, TimeUnit.SECONDS).reason()).isEqualTo(TIMEOUT);
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            peer.assertClean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitCancellationReleasesTheNativeSocketDuringHttpOrTlsHandshake(boolean tls) throws Exception {
        try (Peer peer = Peer.stalled(tls, 5000)) {
            CompletableFuture<ElasticsearchMonitoringConnections.Failure> collected =
                    CompletableFuture.supplyAsync(peer::failure, peer.workers);
            assertThat(peer.received.await(3, TimeUnit.SECONDS)).isTrue();
            peer.control.cancel(TIMEOUT);
            assertThat(collected.get(3, TimeUnit.SECONDS).reason()).isEqualTo(TIMEOUT);
            assertThat(peer.eof.get(2, TimeUnit.SECONDS)).isEqualTo(-1);
            peer.assertClean();
        }
    }

    @Test
    void originalTrustAndHeadersWorkAndBorrowedRestClientRemainsUsable() throws Exception {
        try (MonitoringTlsMaterial tls = MonitoringTlsMaterial.create();
             Peer peer = new Peer(tls.serverContext(), tls.trustedClientContext(), false, 3000, null)) {
            try (var session = new ElasticsearchMonitoringConnections().open(peer.request)) {
                assertThat(session.health().path("status").textValue()).isEqualTo("green");
            }
            peer.assertClean();
            assertThat(peer.client.performRequest(new Request("GET", "/business-read")).getStatusLine().getStatusCode())
                    .isEqualTo(200);
            assertThat(peer.headers.get()).contains("X-Owned-Source: same-client");
        }
    }

    @Test
    void untrustedCertificateIsTlsFailureWithoutAnAuthorizationOrRawExceptionLeak() throws Exception {
        try (MonitoringTlsMaterial tls = MonitoringTlsMaterial.create();
             Peer peer = new Peer(tls.serverContext(), tls.untrustedClientContext(), false, 3000, null)) {
            var failure = peer.failure();
            assertThat(failure.reason()).isEqualTo(TLS_FAILED);
            assertThat(failure.connectionFailure()).isFalse();
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("localhost", "127.0.0.1", "PKIX", "Certificate");
            peer.assertClean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void chunkedAndCompressedResponsesCannotBypassTheDecodedResponseLimit(boolean gzip) throws Exception {
        byte[] tooLarge = ("{\"value\":\"" + "x".repeat(2 * 1024 * 1024 + 1) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] payload;
        if (gzip) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream output = new GZIPOutputStream(compressed)) { output.write(tooLarge); }
            payload = response(compressed.toByteArray(), "Content-Encoding: gzip\r\n", false);
        } else {
            payload = response(tooLarge, "", true);
        }
        try (Peer peer = new Peer(null, null, false, 3000, payload)) {
            assertThat(peer.failure().reason()).isEqualTo(INVALID_VALUE);
            peer.assertClean();
            // The failed monitoring response must not close the shared application client.
            assertThat(peer.client.performRequest(new Request("GET", "/business-read")).getStatusLine().getStatusCode())
                    .isEqualTo(200);
        }
    }

    private static byte[] response(byte[] body, String extraHeaders, boolean chunked) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Elastic-Product: Elasticsearch\r\n"
                + "Connection: close\r\n" + extraHeaders
                + (chunked ? "Transfer-Encoding: chunked" : "Content-Length: " + body.length)
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (chunked) output.write((Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        if (chunked) output.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket listener;
        final AtomicReference<Socket> socket = new AtomicReference<>();
        final AtomicReference<String> headers = new AtomicReference<>();
        final CountDownLatch received = new CountDownLatch(1);
        final CompletableFuture<Integer> eof = new CompletableFuture<>();
        final ExecutorService workers = Executors.newFixedThreadPool(2, task -> daemon(task, "es-native-peer"));
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), task -> daemon(task, "es-native-cleanup"),
                new ThreadPoolExecutor.AbortPolicy());
        final RestClient client;
        final CollectionControl control;
        final CollectionRequest request;
        volatile boolean closed;

        static Peer stalled(boolean tls, int timeout) throws Exception {
            return new Peer(null, tls ? SSLContext.getDefault() : null, true, timeout, null);
        }

        Peer(SSLContext serverTls, SSLContext clientTls, boolean stalled, int readTimeout, byte[] firstResponse)
                throws Exception {
            listener = serverTls == null
                    ? new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
                    : serverTls.getServerSocketFactory().createServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
            var builder = RestClient.builder(new HttpHost("127.0.0.1", listener.getLocalPort(), clientTls == null ? "http" : "https"))
                    .setDefaultHeaders(new BasicHeader[]{new BasicHeader("X-Owned-Source", "same-client")})
                    .setRequestConfigCallback(config -> config.setConnectTimeout(readTimeout)
                            .setSocketTimeout(readTimeout).setConnectionRequestTimeout(readTimeout));
            if (clientTls != null) builder.setHttpClientConfigCallback(config -> config.setSSLContext(clientTls));
            client = builder.build();
            Instant at = Instant.now();
            Duration budget = Duration.ofSeconds(5);
            control = new CollectionControl(new Object(), () -> true, Clock.systemUTC(), System::nanoTime,
                    System.nanoTime() + budget.toNanos(), cleanup, new MonitoringCounterStore(1, 8),
                    "es", "es", ORDINARY, Duration.ofSeconds(15), client);
            request = new CollectionRequest("es", "restClient", "es", ORDINARY, 1, 1, at, at.plus(budget),
                    new MonitoringTarget.Scope(List.of(), List.of(), List.of(), List.of(), List.of()),
                    client, Map.of("restClient", client), control);
            workers.submit(() -> {
                int attempt = 0;
                while (!closed) {
                    try (Socket owned = listener.accept()) {
                        socket.set(owned);
                        owned.setSoTimeout(8000);
                        if (stalled) {
                            byte[] buffer = new byte[4096];
                            int total = 0;
                            int count;
                            while ((count = owned.getInputStream().read(buffer)) != -1) {
                                received.countDown();
                                total += count;
                                if (total > 1_048_576) throw new AssertionError("Unbounded handshake traffic");
                            }
                            eof.complete(-1);
                            return;
                        }
                        headers.set(readHeaders(owned.getInputStream()));
                        received.countDown();
                        byte[] bytes = attempt++ == 0 && firstResponse != null ? firstResponse
                                : response("{\"status\":\"green\"}".getBytes(StandardCharsets.US_ASCII), "", false);
                        owned.getOutputStream().write(bytes);
                        owned.getOutputStream().flush();
                    } catch (Exception failure) {
                        if (stalled) { eof.completeExceptionally(failure); return; }
                        if (closed) return;
                        // An untrusted TLS client or bounded consumer may close its own socket while the peer writes.
                    }
                }
            });
        }

        ElasticsearchMonitoringConnections.Failure failure() {
            try (var session = new ElasticsearchMonitoringConnections().open(request)) {
                session.health();
                throw new AssertionError("Expected native collection failure");
            } catch (ElasticsearchMonitoringConnections.Failure failure) { return failure; }
        }

        void assertClean() {
            await().atMost(Duration.ofSeconds(2)).until(control::isCleanupComplete);
            assertThat(control.hasCleanupFailure()).isFalse();
        }

        @Override public void close() throws Exception {
            closed = true;
            control.cancel(TIMEOUT);
            client.close();
            listener.close();
            Socket owned = socket.get();
            if (owned != null) owned.close();
            workers.shutdownNow();
            cleanup.shutdownNow();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }

        private static String readHeaders(InputStream input) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (bytes.size() < 16384) {
                int next = input.read();
                if (next == -1) throw new java.io.EOFException();
                bytes.write(next);
                if (next == '\n' && bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                    return bytes.toString(StandardCharsets.US_ASCII);
                }
            }
            throw new AssertionError("Unexpected oversized request headers");
        }

        private static Thread daemon(Runnable task, String name) {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
