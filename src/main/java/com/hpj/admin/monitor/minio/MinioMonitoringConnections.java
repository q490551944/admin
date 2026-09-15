package com.hpj.admin.monitor.minio;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.connection.ConnectionIdentity;
import com.hpj.admin.monitor.connection.ResolvedConnection;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.Signer;
import io.minio.credentials.Credentials;
import io.minio.credentials.StaticProvider;
import io.minio.http.HttpUtils;
import okhttp3.*;

import javax.net.ssl.SSLException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed, read-only health/HeadBucket requests and bounded GetBucketLocation on monitoring-owned connections. Business clients are never changed or
 * closed. Exact MinIO 8.5.17 clients preserve their static credentials, explicit region and HTTP/TLS policies;
 * they do not authorize native MinIO health endpoints merely by speaking S3. Chat attachment settings do.
 * A cancellation reservation remains outstanding until synchronous execute and response close have returned,
 * including non-interruptible DNS. Call.cancel alone is deliberately not treated as the cleanup barrier.
 */
public class MinioMonitoringConnections {
    public static final int MAX_BUCKETS = 100;
    static final int MAX_LOCATION_BYTES = 16 * 1024;
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private final Factory factory;

    public MinioMonitoringConnections() { this(client -> client); }
    MinioMonitoringConnections(Factory factory) { this.factory = Objects.requireNonNull(factory); }
    @FunctionalInterface interface Factory { Call.Factory create(OkHttpClient client); }

    public enum HealthEndpoint {
        LIVE("/minio/health/live"), READ_READY("/minio/health/cluster/read"), WRITE_READY("/minio/health/cluster");
        private final String path;
        HealthEndpoint(String path) { this.path = path; }
    }
    public record Observation(int status, Duration elapsed) {
        public Observation {
            if (status < 100 || status > 599 || elapsed == null || elapsed.isNegative()) {
                throw new IllegalArgumentException("Invalid MinIO observation");
            }
        }
    }
    @JsonIgnoreType
    public interface Session extends AutoCloseable {
        Observation health(HealthEndpoint endpoint);
        Observation bucket(String bucket);
        @Override void close();
    }
    @JsonIgnoreType
    public static final class Failure extends RuntimeException {
        private final MissingReason reason;
        private final boolean connectionFailure;
        public Failure(MissingReason reason, boolean connectionFailure) {
            super("MinIO monitoring operation unavailable", null, false, false);
            this.reason = reason;
            this.connectionFailure = connectionFailure;
        }
        public MissingReason reason() { return reason; }
        public boolean connectionFailure() { return connectionFailure; }
    }

    /** No network, lazy bean initialization, credentials logging, or mutation of the original SDK objects. */
    public static Optional<ResolvedConnection> inspect(String source, MinioClient client) {
        try {
            Configuration configuration = nativeConfiguration(client);
            return Optional.of(new ResolvedConnection(MiddlewareType.MINIO, source, client,
                    ConnectionIdentity.forOwner(client), Map.of("minioConfiguration", configuration)));
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return Optional.empty(); }
    }

    public Session open(CollectionRequest request) {
        checkActive(request.control());
        try { return new NativeSession(request, configuration(request), factory); }
        catch (RuntimeException failure) { throw sanitized(failure, request.control()); }
    }

    @JsonIgnoreType
    private record Configuration(Object owner, HttpUrl endpoint, Credentials credentials, String region,
                                 Map<String, String> bucketRegions, boolean nativeHealth, OkHttpClient original) {
        @Override public String toString() { return "MinioMonitoringConfiguration[server-only]"; }
    }

    private static Configuration configuration(CollectionRequest request) {
        if (request.settings().get("minioConfiguration") instanceof Configuration configuration) {
            if (configuration.owner() != request.client()) throw unsupported();
            return configuration;
        }
        if (!(request.client() instanceof ChatProperties)) throw unsupported();
        Object endpoint = request.settings().get("endpoint");
        Object accessKey = request.settings().get("accessKey");
        Object secretKey = request.settings().get("secretKey");
        if (!(endpoint instanceof String address) || !(accessKey instanceof String access) || access.isBlank()
                || !(secretKey instanceof String secret) || secret.isBlank()) throw unsupported();
        HttpUrl url = endpoint(address);
        // ChatObjectStorage has no region setting. Like SDK 8.5.17, resolve it with a bounded GetBucketLocation.
        OkHttpClient original = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS).build();
        try {
            // The same SDK builder can infer virtual-host routing or a region from an endpoint. Inspect a
            // monitoring-owned, unconnected descriptor instead of guessing those rules from host suffixes.
            // Its supplied HTTP client is not owned by the descriptor, and no SDK operation is invoked.
            MinioClient descriptor = MinioClient.builder().endpoint(url).credentials(access, secret).httpClient(original).build();
            Configuration parsed = nativeConfiguration(descriptor);
            return new Configuration(request.client(), parsed.endpoint(), parsed.credentials(), parsed.region(),
                    parsed.bucketRegions(), true, original);
        } catch (ReflectiveOperationException | RuntimeException unavailable) { throw unsupported(); }
    }

    private static Configuration nativeConfiguration(MinioClient client) throws ReflectiveOperationException {
        if (client == null || client.getClass() != MinioClient.class) throw unsupported();
        Object async = field(MinioClient.class, "asyncClient", client);
        if (async == null || async.getClass() != MinioAsyncClient.class) throw unsupported();
        Class<?> base = MinioAsyncClient.class.getSuperclass();
        if (!"io.minio.S3Base".equals(base.getName()) || field(base, "awsS3Prefix", async) != null
                || field(base, "awsDomainSuffix", async) != null || !Boolean.FALSE.equals(field(base, "useVirtualStyle", async))
                || !Boolean.FALSE.equals(field(base, "awsDualstack", async))) throw unsupported();
        Object provider = field(base, "provider", async);
        if (provider == null || provider.getClass() != StaticProvider.class) throw unsupported();
        Object http = field(base, "httpClient", async);
        if (!(http instanceof OkHttpClient original) || original.getClass() != OkHttpClient.class
                || !original.interceptors().isEmpty() || !original.networkInterceptors().isEmpty()
                || original.authenticator() != Authenticator.NONE || original.proxyAuthenticator() != Authenticator.NONE
                || original.cookieJar() != CookieJar.NO_COOKIES || original.cache() != null
                || !original.protocols().contains(Protocol.HTTP_1_1)) throw unsupported();
        HttpUrl url = (HttpUrl) field(base, "baseUrl", async);
        endpoint(url.toString());
        String region = (String) field(base, "region", async);
        if (region != null && !validRegion(region)) throw unsupported();
        Map<String, String> regions = new LinkedHashMap<>();
        Object cache = field(base, "regionCache", async);
        if (cache instanceof Map<?, ?> values) for (var entry : values.entrySet()) {
            if (regions.size() == MAX_BUCKETS) break;
            if (entry.getKey() instanceof String bucket && validBucket(bucket)
                    && entry.getValue() instanceof String value && validRegion(value)) regions.put(bucket, value);
        }
        return new Configuration(client, url, ((StaticProvider) provider).fetch(), region,
                Map.copyOf(regions), false, original);
    }

    private static Object field(Class<?> owner, String name, Object instance) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw unsupported();
        return field.get(instance);
    }
    private static HttpUrl endpoint(String address) {
        if (address == null || address.length() > 4096 || address.chars().anyMatch(Character::isWhitespace)
                || address.chars().anyMatch(Character::isISOControl) || address.indexOf('\\') >= 0) throw unsupported();
        HttpUrl url;
        try { url = HttpUtils.getBaseUrl(address); }
        catch (RuntimeException invalid) { throw unsupported(); }
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty() || !url.encodedPath().equals("/")
                || url.query() != null || url.fragment() != null) throw unsupported();
        return url;
    }
    private static boolean validRegion(String region) { return region != null && region.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}"); }
    static boolean validBucket(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                && !name.contains("..") && !name.contains(".-") && !name.contains("-.")
                && !name.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    private static final class NativeSession implements Session {
        private final CollectionRequest request;
        private final Configuration configuration;
        private final OkHttpClient client;
        private final Factory factory;
        private final Set<Call> active = ConcurrentHashMap.newKeySet();
        private final Map<String, String> regions = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private NativeSession(CollectionRequest request, Configuration configuration, Factory factory) {
            this.request = request;
            this.configuration = configuration;
            this.factory = factory;
            regions.putAll(configuration.bucketRegions());
            // Separate dispatcher and pool prevent monitoring from competing for or closing business connections.
            client = configuration.original().newBuilder().dispatcher(new Dispatcher())
                    .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                    // HTTP/2 can cause a 421 coalescing follow-up. The inspected native policy must already
                    // permit HTTP/1.1; selecting it here ensures one origin exchange and no coalesced retry.
                    .protocols(List.of(Protocol.HTTP_1_1))
                    .addNetworkInterceptor(chain -> {
                        Response response = chain.proceed(chain.request());
                        // OkHttp 4.12 may retry 503 + Retry-After: 0 even with retryOnConnectionFailure(false).
                        // This response header is not projected; suppress the follow-up while keeping the native status.
                        return response.code() == 503 ? response.newBuilder().header("Retry-After", "1").build() : response;
                    }).build();
        }

        @Override public Observation health(HealthEndpoint endpoint) {
            if (!configuration.nativeHealth() || endpoint == null) throw unsupported();
            return perform(configuration.endpoint().newBuilder().encodedPath(endpoint.path).build(), false, null, false).observation();
        }
        @Override public Observation bucket(String bucket) {
            if (!validBucket(bucket)) throw new Failure(MissingReason.INVALID_VALUE, false);
            List<String> scope = request.scope().buckets();
            if (scope.isEmpty()) {
                if (!bucket.equals(request.settings().get("bucket"))) throw unsupported();
            } else if (!scope.stream().distinct().limit(MAX_BUCKETS).toList().contains(bucket)) throw unsupported();
            long started = System.nanoTime();
            HttpUrl url = configuration.endpoint().newBuilder().addPathSegment(bucket).build();
            String region = configuration.region() == null ? regions.get(bucket) : configuration.region();
            if (region == null) {
                // This is the SDK's fixed S3 region discovery request, not target discovery or a redirected request.
                Reply location = perform(url.newBuilder().addQueryParameter("location", "").build(), true, "us-east-1", true);
                if (location.observation().status() != 200) return location.observation();
                region = parseRegion(location.body());
                if (regions.size() < MAX_BUCKETS) regions.put(bucket, region);
            }
            if (!validRegion(region)) throw unsupported();
            Reply head = perform(url, true, region, false);
            return new Observation(head.observation().status(), Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
        }

        private record Reply(Observation observation, byte[] body) { }
        private Reply perform(HttpUrl url, boolean signed, String region, boolean location) {
            if (closed.get()) throw new Failure(MissingReason.FAILED, false);
            checkActive(request.control());
            var worker = request.control().reserveOwnedCancellation();
            CollectionControl.Registration cancellation = null;
            Call call = null;
            boolean responseClosed = true;
            long started = System.nanoTime();
            try {
                cancellation = request.control().reserveOwnedCancellation();
                Request.Builder builder = new Request.Builder().url(url).method(location ? "GET" : "HEAD", null)
                        .header("Accept-Encoding", "identity");
                if (signed) {
                    String host = url.host().contains(":") ? "[" + url.host() + "]" : url.host();
                    if (url.port() != (url.isHttps() ? 443 : 80)) host += ":" + url.port();
                    builder.header("Host", host).header("x-amz-date", AMZ_DATE.format(request.control().now()))
                            .header("x-amz-content-sha256", EMPTY_SHA256);
                    String token = configuration.credentials().sessionToken();
                    if (token != null && !token.isEmpty()) builder.header("x-amz-security-token", token);
                }
                Request nativeRequest = builder.build();
                if (signed) nativeRequest = Signer.signV4S3(nativeRequest, region, configuration.credentials().accessKey(),
                        configuration.credentials().secretKey(), EMPTY_SHA256);
                int remaining = remainingMillis(request.control());
                OkHttpClient bounded = client.newBuilder().connectTimeout(bound(client.connectTimeoutMillis(), remaining), TimeUnit.MILLISECONDS)
                        .readTimeout(bound(client.readTimeoutMillis(), remaining), TimeUnit.MILLISECONDS)
                        .writeTimeout(bound(client.writeTimeoutMillis(), remaining), TimeUnit.MILLISECONDS)
                        .callTimeout(bound(client.callTimeoutMillis(), remaining), TimeUnit.MILLISECONDS).build();
                call = factory.create(bounded).newCall(nativeRequest);
                if (call == null) throw new Failure(MissingReason.FAILED, false);
                active.add(call);
                Call acquired = call;
                cancellation.attach(call, acquired::cancel);
                if (closed.get()) call.cancel();
                checkActive(request.control());
                Response response = call.execute();
                responseClosed = response == null;
                try {
                    checkActive(request.control());
                    if (response == null || response.code() < 100 || response.code() > 599) throw new Failure(MissingReason.INVALID_VALUE, false);
                    byte[] body = new byte[0];
                    if (location && response.code() == 200) {
                        if (response.body() == null || response.body().contentLength() > MAX_LOCATION_BYTES
                                || response.header("Content-Encoding") != null && !"identity".equalsIgnoreCase(response.header("Content-Encoding"))) {
                            throw new Failure(MissingReason.INVALID_VALUE, false);
                        }
                        body = response.body().byteStream().readNBytes(MAX_LOCATION_BYTES + 1);
                        if (body.length > MAX_LOCATION_BYTES) throw new Failure(MissingReason.INVALID_VALUE, false);
                    }
                    checkActive(request.control());
                    return new Reply(new Observation(response.code(), Duration.ofNanos(Math.max(0, System.nanoTime() - started))), body);
                } finally {
                    if (response != null) response.close();
                    responseClosed = true;
                }
            } catch (Exception failure) { throw sanitized(failure, request.control()); }
            finally {
                if (call != null) active.remove(call);
                // execute has returned and try-with-resources has closed the HEAD response before this point.
                if (responseClosed) {
                    if (cancellation != null) cancellation.close();
                    worker.close();
                }
            }
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            active.forEach(Call::cancel);
            client.connectionPool().evictAll();
            // Synchronous calls never start dispatcher workers. This closes only the owned dispatcher.
            client.dispatcher().executorService().shutdown();
        }
    }

    private static String parseRegion(byte[] body) {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            // Do not let the default SAX error handler print native response fragments to stderr.
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            var root = builder.parse(new ByteArrayInputStream(body)).getDocumentElement();
            String namespace = root.getNamespaceURI();
            if (!"LocationConstraint".equals(root.getLocalName())
                    || namespace != null && !namespace.isEmpty() && !"http://s3.amazonaws.com/doc/2006-03-01/".equals(namespace)
                    || root.getElementsByTagName("*").getLength() != 0) throw unsupported();
            String region = root.getTextContent().trim();
            if (region.isEmpty()) return "us-east-1";
            if (!validRegion(region)) throw unsupported();
            return region;
        } catch (Exception invalid) { throw new Failure(MissingReason.INVALID_VALUE, false); }
    }

    private static int bound(int original, int remaining) { return original <= 0 ? remaining : Math.min(original, remaining); }
    private static int remainingMillis(CollectionControl control) {
        checkActive(control);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, control.remaining().toMillis()));
    }
    private static void checkActive(CollectionControl control) {
        try { control.checkActive(); }
        catch (CollectionControl.InactiveCollectionException inactive) { throw new Failure(inactive.reason(), false); }
    }
    private static Failure sanitized(Throwable error, CollectionControl control) {
        if (error instanceof Failure safe) return safe;
        if (error instanceof CollectionControl.InactiveCollectionException inactive) return new Failure(inactive.reason(), false);
        if (!control.isActive()) return new Failure(control.cancellationReason() == null ? MissingReason.TIMEOUT : control.cancellationReason(), false);
        if (error instanceof SSLException) return new Failure(MissingReason.TLS_FAILED, false);
        if (error instanceof InterruptedIOException) return new Failure(MissingReason.TIMEOUT, false);
        if (error instanceof UnknownHostException || error instanceof ConnectException || error instanceof NoRouteToHostException
                || error instanceof SocketException) return new Failure(MissingReason.FAILED, true);
        if (error instanceof IOException) return new Failure(MissingReason.FAILED, true);
        return new Failure(MissingReason.FAILED, false);
    }
    private static Failure unsupported() { return new Failure(MissingReason.UNSUPPORTED, false); }
}
