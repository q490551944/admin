package com.hpj.admin.monitor.elasticsearch;

import co.elastic.clients.transport.ElasticsearchTransportBase;
import co.elastic.clients.transport.rest_client.RestClientOptions;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract.CollectionKind;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import org.apache.http.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.protocol.AbstractAsyncResponseConsumer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.VersionInfo;
import org.elasticsearch.client.*;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/** Fixed read-only requests on the existing native client; only request handles and consumers are owned here. */
public class ElasticsearchMonitoringConnections {
    static final int MAX_REPLY_BYTES = 2 * 1024 * 1024;
    static final int MAX_JSON_TOKENS = 50_000;
    static final int MAX_NAMES = 128;
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxNumberLength(128).maxStringLength(65536).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES).disable(JsonFactory.Feature.INTERN_FIELD_NAMES).build();
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON);
    private final ConfigResolver resolver;

    public ElasticsearchMonitoringConnections() { this(ElasticsearchMonitoringConnections::configuration); }
    ElasticsearchMonitoringConnections(ConfigResolver resolver) { this.resolver = resolver; }
    @FunctionalInterface interface ConfigResolver { Configuration resolve(CollectionRequest request); }
    record Configuration(RestClient client, RequestOptions options, RequestConfig config, int limit) { }

    @JsonIgnoreType
    public interface Session extends AutoCloseable {
        JsonNode health();
        JsonNode indexStats(List<String> names, CollectionKind kind);
        JsonNode threadPools();
        JsonNode nodeStarts(List<String> ids);
        @Override void close();
    }
    @JsonIgnoreType
    public static final class Failure extends RuntimeException {
        private final MissingReason reason;
        private final boolean connectionFailure;
        public Failure(MissingReason reason, boolean connectionFailure) {
            super("Elasticsearch monitoring operation unavailable", null, false, false);
            this.reason = reason;
            this.connectionFailure = connectionFailure;
        }
        public MissingReason reason() { return reason; }
        public boolean connectionFailure() { return connectionFailure; }
    }

    public Session open(CollectionRequest request) {
        checkActive(request.control());
        try { return new NativeSession(request, resolver.resolve(request)); }
        catch (RuntimeException failure) { throw sanitized(failure, request.control()); }
    }

    static Configuration configuration(CollectionRequest request) {
        try {
            if (!"8.10.4".equals(RestClientBuilder.VERSION)
                    || !(request.settings().get("restClient") instanceof RestClient client)
                    || client.getClass() != RestClient.class) throw unsupported();
            VersionInfo version = VersionInfo.loadVersionInfo("org.apache.http.nio.client", client.getHttpClient().getClass().getClassLoader());
            if (version == null || !"4.1.5".equals(version.getRelease())
                    || !client.getHttpClient().getClass().getName().equals("org.apache.http.impl.nio.client.InternalHttpAsyncClient")) throw unsupported();
            RequestOptions options = RequestOptions.DEFAULT;
            Object transportObject = request.settings().get("transport");
            if (transportObject != null) {
                if (transportObject.getClass() != RestClientTransport.class) throw unsupported();
                RestClientTransport transport = (RestClientTransport) transportObject;
                if (transport.restClient() != client || !(transport.options() instanceof RestClientOptions nativeOptions)) throw unsupported();
                Object instrumentation = field(ElasticsearchTransportBase.class, "instrumentation", transport);
                ClassLoader loader = RestClientTransport.class.getClassLoader();
                Class<?> noop = Class.forName("co.elastic.clients.transport.instrumentation.NoopInstrumentation", false, loader);
                Class<?> telemetry = Class.forName("co.elastic.clients.transport.instrumentation.OpenTelemetryForElasticsearch", false, loader);
                // Both exact 8.10.4 built-ins only observe requests; custom instrumentation can inject
                // authentication/routing and cannot be bypassed by the low-level monitoring request.
                if (instrumentation == null || instrumentation.getClass() != noop && instrumentation.getClass() != telemetry) throw unsupported();
                options = nativeOptions.restClientRequestOptions();
            }
            return configuration(client, options);
        } catch (Failure safe) { throw safe; }
        catch (ReflectiveOperationException | RuntimeException unavailable) { throw unsupported(); }
    }

    static Configuration configuration(RestClient client, RequestOptions options) throws ReflectiveOperationException {
        RequestConfig config = options.getRequestConfig();
        if (config == null) config = (RequestConfig) field(client.getHttpClient().getClass(), "defaultConfig", client.getHttpClient());
        // Zero/negative socket/connect values can inherit reactor or previously leased connection settings.
        // Do not replace an unknown, potentially shorter native policy with a guessed default.
        if (config == null || config.getClass() != RequestConfig.class || config.getConnectTimeout() <= 0 || config.getSocketTimeout() <= 0) {
            throw unsupported();
        }
        var originalConsumer = options.getHttpAsyncResponseConsumerFactory();
        if (originalConsumer.getClass() != HttpAsyncResponseConsumerFactory.HeapBufferedResponseConsumerFactory.class) throw unsupported();
        int originalLimit = (Integer) field(originalConsumer.getClass(), "bufferLimit", originalConsumer);
        if (originalLimit <= 0) throw unsupported();
        return new Configuration(client, options, config, Math.min(originalLimit, MAX_REPLY_BYTES));
    }

    private static Object field(Class<?> owner, String name, Object instance) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw unsupported();
        return field.get(instance);
    }

    private static final class NativeSession implements Session {
        private final CollectionRequest request;
        private final Configuration configuration;
        private final Set<String> observedNodes = new HashSet<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private NativeSession(CollectionRequest request, Configuration configuration) {
            this.request = request;
            this.configuration = configuration;
        }
        @Override public JsonNode health() {
            return get("/_cluster/health", Map.of("level", "cluster"));
        }
        @Override public JsonNode threadPools() {
            JsonNode result = get("/_nodes/stats/thread_pool", Map.of("filter_path", "_nodes,nodes.*.thread_pool"));
            rememberNodes(result.path("nodes"));
            return result;
        }
        @Override public JsonNode indexStats(List<String> names, CollectionKind kind) {
            if (kind == null || kind != request.kind() || names == null || names.isEmpty() || names.size() > MAX_NAMES
                    || new HashSet<>(names).size() != names.size()) throw unsupported();
            for (String name : names) {
                if (!validIndex(name) || !request.scope().indices().contains(name)) throw unsupported();
            }
            String fields = kind == CollectionKind.CAPACITY ? "store" : "docs,indexing,search";
            JsonNode result = get("/" + encoded(names) + "/_stats/" + fields,
                    Map.of("level", kind == CollectionKind.CAPACITY ? "indices" : "shards", "expand_wildcards", "none",
                            "allow_no_indices", "false", "ignore_unavailable", "false"));
            JsonNode indices = result.path("indices");
            for (String name : names) {
                JsonNode shards = indices.path(name).path("shards");
                if (shards.isObject()) for (JsonNode copies : shards) if (copies.isArray()) for (JsonNode copy : copies) {
                    JsonNode node = copy.path("routing").path("node");
                    if (node.isTextual() && validNode(node.textValue()) && observedNodes.size() < MAX_NAMES) observedNodes.add(node.textValue());
                }
            }
            return result;
        }
        @Override public JsonNode nodeStarts(List<String> ids) {
            if (ids == null || ids.isEmpty() || ids.size() > MAX_NAMES || new HashSet<>(ids).size() != ids.size()) throw unsupported();
            for (String id : ids) if (!validNode(id) || !observedNodes.contains(id)) throw unsupported();
            return get("/_nodes/" + encoded(ids) + "/jvm", Map.of("filter_path", "_nodes,nodes.*.jvm.start_time_in_millis"));
        }
        private void rememberNodes(JsonNode nodes) {
            if (nodes.isObject()) nodes.fieldNames().forEachRemaining(id -> {
                if (validNode(id) && observedNodes.size() < MAX_NAMES) observedNodes.add(id);
            });
        }
        private JsonNode get(String path, Map<String, String> parameters) {
            if (closed.get()) throw new Failure(MissingReason.FAILED, false);
            checkActive(request.control());
            Attempt attempt = new Attempt(request.control(), configuration.limit());
            try {
                Request nativeRequest = new Request("GET", path);
                RequestOptions.Builder options = configuration.options().toBuilder();
                parameters.forEach((key, value) -> {
                    String original = configuration.options().getParameters().get(key);
                    if (original != null && !original.equals(value)) throw unsupported();
                    // addParameter merges repeated values with a comma; do not duplicate an existing value.
                    if (original == null) options.addParameter(key, value);
                });
                options.setRequestConfig(bounded(configuration.config(), request.control()));
                options.setHttpAsyncResponseConsumerFactory(attempt::consumer);
                nativeRequest.setOptions(options);
                attempt.dispatch(configuration.client(), nativeRequest);
                Response response = attempt.awaitResponse();
                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) throw httpFailure(status);
                byte[] bytes;
                try (InputStream input = response.getEntity() == null ? InputStream.nullInputStream() : response.getEntity().getContent()) {
                    bytes = input.readNBytes(configuration.limit() + 1);
                }
                if (bytes.length > configuration.limit()) throw invalid();
                JsonNode result = parse(bytes, request.control());
                checkActive(request.control());
                return result;
            } catch (RuntimeException | IOException failure) {
                throw sanitized(failure, request.control());
            } finally { attempt.finish(); }
        }
        @Override public void close() { closed.set(true); }
    }

    static RequestConfig bounded(RequestConfig original, CollectionControl control) {
        int remaining = remainingMillis(control);
        return RequestConfig.copy(original).setConnectTimeout(Math.min(remaining, original.getConnectTimeout()))
                .setSocketTimeout(Math.min(remaining, original.getSocketTimeout()))
                .setConnectionRequestTimeout(original.getConnectionRequestTimeout() <= 0 ? remaining
                        : Math.min(remaining, original.getConnectionRequestTimeout())).build();
    }

    /** One endpoint, including native failover attempts. Listener completion is deliberately not the cleanup barrier. */
    private static final class Attempt {
        private final CollectionControl control;
        private final int limit;
        private final CollectionControl.Registration worker;
        private final CollectionControl.Registration cancellation;
        private final Object lock = new Object();
        private Cancellable handle;
        private Response response;
        private Failure failure;
        private int activeConsumers;
        private int consumersCreated;
        private boolean dispatchReturned;
        private boolean dispatchStarted;
        private boolean terminal;
        private boolean cleanupFailed;

        private Attempt(CollectionControl control, int limit) {
            this.control = control;
            this.limit = limit;
            worker = control.reserveOwnedCancellation();
            try { cancellation = control.reserveOwnedCancellation(); }
            catch (RuntimeException failure) { worker.close(); throw failure; }
        }
        private HttpAsyncResponseConsumer<HttpResponse> consumer() {
            checkActive(control);
            synchronized (lock) {
                if (++consumersCreated > 32) throw unsupported();
                activeConsumers++;
            }
            return new TrackedConsumer(new BoundedConsumer(limit), this::consumerClosed);
        }
        private void consumerClosed(boolean successful) {
            synchronized (lock) {
                activeConsumers--;
                cleanupFailed |= !successful;
                lock.notifyAll();
            }
        }
        private void dispatch(RestClient client, Request request) {
            synchronized (lock) { dispatchStarted = true; }
            try {
                checkActive(control);
                Cancellable acquired = client.performRequestAsync(request, new ResponseListener() {
                    @Override public void onSuccess(Response result) {
                        synchronized (lock) {
                            if (!terminal) { response = result; terminal = true; }
                            lock.notifyAll();
                        }
                    }
                    @Override public void onFailure(Exception error) { failed(error); }
                });
                synchronized (lock) { handle = acquired; }
                if (acquired == null) throw new Failure(MissingReason.FAILED, true);
                cancellation.attach(this, this::cancel);
            } catch (RuntimeException error) { failed(error); }
            finally { synchronized (lock) { dispatchReturned = true; lock.notifyAll(); } }
        }
        private void failed(Exception error) {
            Failure safe = sanitized(error, control);
            synchronized (lock) {
                if (!terminal) { failure = safe; terminal = true; }
                lock.notifyAll();
            }
        }
        private Response awaitResponse() {
            synchronized (lock) {
                while (!terminal) {
                    int wait = remainingMillis(control);
                    try { lock.wait(wait); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        checkActive(control);
                        throw new Failure(MissingReason.FAILED, false);
                    }
                }
                checkActive(control);
                if (failure != null) throw failure;
                if (response == null) throw invalid();
                return response;
            }
        }
        private void cancel() {
            Cancellable acquired;
            synchronized (lock) { acquired = handle; }
            if (acquired != null) {
                try { acquired.cancel(); }
                catch (RuntimeException nativeFailure) {
                    synchronized (lock) { cleanupFailed = true; lock.notifyAll(); }
                    throw new Failure(MissingReason.FAILED, false);
                }
            }
        }
        private void finish() {
            boolean interrupted = Thread.interrupted();
            try {
                boolean drained;
                synchronized (lock) {
                    if (!dispatchStarted) { dispatchReturned = true; terminal = true; }
                    drained = terminal && activeConsumers == 0;
                }
                if (!drained) cancel();
                synchronized (lock) {
                    while (!dispatchReturned || !terminal || activeConsumers != 0) {
                        try { lock.wait(); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (!cleanupFailed) {
                        cancellation.close();
                        worker.close();
                    }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    /** Last close is invoked by native releaseResources after connection release/discard and external callback. */
    static final class TrackedConsumer implements HttpAsyncResponseConsumer<HttpResponse> {
        private final HttpAsyncResponseConsumer<HttpResponse> delegate;
        private final java.util.function.Consumer<Boolean> closedCallback;
        private boolean closed;
        TrackedConsumer(HttpAsyncResponseConsumer<HttpResponse> delegate, java.util.function.Consumer<Boolean> callback) {
            this.delegate = delegate;
            this.closedCallback = callback;
        }
        @Override public synchronized void responseReceived(HttpResponse response) throws IOException, HttpException { delegate.responseReceived(response); }
        @Override public synchronized void consumeContent(ContentDecoder decoder, IOControl io) throws IOException { delegate.consumeContent(decoder, io); }
        @Override public synchronized void responseCompleted(HttpContext context) { delegate.responseCompleted(context); }
        @Override public synchronized void failed(Exception error) { delegate.failed(error); }
        @Override public synchronized boolean cancel() { return delegate.cancel(); }
        @Override public synchronized Exception getException() { return delegate.getException(); }
        @Override public synchronized HttpResponse getResult() { return delegate.getResult(); }
        @Override public synchronized boolean isDone() { return delegate.isDone(); }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            boolean success = false;
            try { delegate.close(); success = true; }
            finally { closedCallback.accept(success); }
        }
    }

    /** Apache still decodes HTTP framing; storage and gzip expansion are bounded before RestClient sees a response. */
    static final class BoundedConsumer extends AbstractAsyncResponseConsumer<HttpResponse> {
        private final int limit;
        private HttpResponse response;
        private ByteBuffer bytes;
        private final ByteBuffer probe = ByteBuffer.allocate(1);
        private ContentType type;
        BoundedConsumer(int limit) { this.limit = limit; }
        @Override protected void onResponseReceived(HttpResponse response) { this.response = response; }
        @Override protected void onEntityEnclosed(HttpEntity entity, ContentType type) throws IOException {
            long length = entity.getContentLength();
            if (length > limit) throw tooLarge();
            this.type = type;
            // Fixed maximum capacity handles unknown-length/chunked bodies without unbounded growth.
            bytes = ByteBuffer.allocate(length >= 0 ? (int) length : limit);
        }
        @Override protected void onContentReceived(ContentDecoder decoder, IOControl control) throws IOException {
            while (!decoder.isCompleted()) {
                if (bytes.hasRemaining()) {
                    if (decoder.read(bytes) <= 0) return;
                } else {
                    probe.clear();
                    int extra = decoder.read(probe);
                    if (extra > 0) throw tooLarge();
                    if (extra <= 0) return;
                }
            }
        }
        @Override protected HttpResponse buildResult(HttpContext context) throws IOException {
            if (bytes == null) return response;
            byte[] data = bytes.array();
            int length = bytes.position();
            Header encoding = response.getEntity().getContentEncoding();
            if (encoding != null && !encoding.getValue().equalsIgnoreCase("identity")) {
                if (!encoding.getValue().equalsIgnoreCase("gzip")) throw new IOException("Unsupported monitoring response encoding");
                byte[] decoded = new byte[limit];
                int used = 0;
                try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data, 0, length))) {
                    while (used < limit) {
                        int read = gzip.read(decoded, used, limit - used);
                        if (read < 0) break;
                        used += read;
                    }
                    if (used == limit && gzip.read() != -1) throw tooLarge();
                }
                data = decoded;
                length = used;
                response.removeHeaders("Content-Encoding");
            }
            response.removeHeaders("Content-Length");
            response.setEntity(new ByteArrayEntity(data, 0, length, type));
            return response;
        }
        @Override protected void releaseResources() { response = null; bytes = null; }
        private static ContentTooLongException tooLarge() { return new ContentTooLongException("Monitoring response limit exceeded"); }
    }

    static JsonNode parse(byte[] bytes, CollectionControl control) throws IOException {
        int tokens = 0;
        try (JsonParser parser = JSON.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
            int depth = 1;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (++tokens > MAX_JSON_TOKENS) throw invalid();
                if (token == JsonToken.FIELD_NAME && parser.currentName().length() > 1024) throw invalid();
                if (token == JsonToken.VALUE_STRING && parser.getTextLength() > 65536) throw invalid();
                if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                    parser.getDecimalValue();
                    if (!Double.isFinite(parser.getDoubleValue())) throw invalid();
                }
                if (token.isStructStart()) depth++;
                else if (token.isStructEnd() && --depth == 0) {
                    if (parser.nextToken() != null) throw invalid();
                    break;
                }
                if ((tokens & 255) == 0) checkActive(control);
            }
            if (depth != 0) throw invalid();
        } catch (JsonProcessingException malformed) { throw invalid(); }
        checkActive(control);
        try { return MAPPER.readTree(bytes); }
        catch (JsonProcessingException malformed) { throw invalid(); }
    }

    static boolean validIndex(String name) {
        return name != null && !name.isBlank() && name.getBytes(StandardCharsets.UTF_8).length <= 255
                && name.equals(name.toLowerCase(Locale.ROOT)) && !name.equals(".") && !name.equals("..")
                && "_+-".indexOf(name.charAt(0)) < 0 && name.chars().noneMatch(c -> Character.isWhitespace(c)
                    || Character.isISOControl(c) || "*?,/\\#:\"<>|[]".indexOf(c) >= 0);
    }
    private static boolean validNode(String name) {
        return name != null && name.matches("[a-zA-Z0-9_-]{22}")
                && !Set.of("_all", "_local", "_master", "_data", "_ingest", "_coordinating_only").contains(name);
    }
    private static String encoded(List<String> names) {
        return names.stream().map(name -> URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining(","));
    }
    private static int remainingMillis(CollectionControl control) {
        checkActive(control);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, control.remaining().toMillis()));
    }
    private static void checkActive(CollectionControl control) {
        try { control.checkActive(); }
        catch (CollectionControl.InactiveCollectionException inactive) { throw new Failure(inactive.reason(), false); }
    }
    private static Failure unsupported() { return new Failure(MissingReason.UNSUPPORTED, false); }
    private static Failure invalid() { return new Failure(MissingReason.INVALID_VALUE, false); }
    private static Failure sanitized(Exception failure, CollectionControl control) {
        if (failure instanceof Failure safe) return safe;
        try { checkActive(control); } catch (Failure inactive) { return inactive; }
        Throwable nested = failure;
        for (int i = 0; nested != null && i < 8; i++, nested = nested.getCause()) {
            if (nested instanceof SSLException) return new Failure(MissingReason.TLS_FAILED, false);
            if (nested instanceof SocketTimeoutException || nested instanceof org.apache.http.conn.ConnectTimeoutException
                    || nested instanceof java.util.concurrent.TimeoutException) return new Failure(MissingReason.TIMEOUT, true);
            if (nested instanceof ContentTooLongException || nested instanceof JsonProcessingException) return invalid();
        }
        if (failure instanceof ResponseException error) {
            int status = error.getResponse().getStatusLine().getStatusCode();
            return httpFailure(status);
        }
        return new Failure(MissingReason.FAILED, !(failure instanceof CancellationException));
    }
    private static Failure httpFailure(int status) {
        return new Failure(switch (status) {
                case 401, 403 -> MissingReason.UNAUTHORIZED;
                case 404 -> MissingReason.NOT_APPLICABLE;
                case 405 -> MissingReason.UNSUPPORTED;
                case 408, 504 -> MissingReason.TIMEOUT;
                case 429 -> MissingReason.BUSY;
                default -> MissingReason.FAILED;
            }, false);
    }
}
