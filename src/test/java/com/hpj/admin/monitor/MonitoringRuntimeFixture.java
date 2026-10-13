package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.monitor.MonitoringConfiguration;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.common.exception.MyExceptionHandler;
import com.hpj.admin.monitor.api.MonitoringReadController;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.metric.*;
import com.hpj.admin.monitor.minio.MinioMetricsAuthorization;
import com.hpj.admin.monitor.minio.MinioMonitoringConnections;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler;
import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Test-only application: all monitored sources are created here; arbitrary endpoints are never accepted. */
public final class MonitoringRuntimeFixture implements AutoCloseable {
    public record Source(String targetId, String beanName, MonitoringTestEnvironment environment, Object client) {
        @Override public String toString() { return "OwnedRuntimeSource[" + targetId + "]"; }
    }
    public static final class Ledger {
        public final AtomicInteger calls = new AtomicInteger();
        public final Map<String, CollectionResult> results = new ConcurrentHashMap<>();
        public final Map<CollectionKind, AtomicInteger> active = new ConcurrentHashMap<>(), peak = new ConcurrentHashMap<>();
        MonitoringAdapter wrap(MonitoringAdapter delegate) {
            return new MonitoringAdapter() {
                public MiddlewareType type() { return delegate.type(); }
                public CollectionResult collect(CollectionRequest request) {
                    calls.incrementAndGet();
                    int running = active.computeIfAbsent(request.kind(), ignored -> new AtomicInteger()).incrementAndGet();
                    peak.computeIfAbsent(request.kind(), ignored -> new AtomicInteger()).accumulateAndGet(running, Math::max);
                    try {
                        CollectionResult result = delegate.collect(request);
                        results.put(request.targetId() + "/" + request.kind(), result);
                        return result;
                    } finally { active.get(request.kind()).decrementAndGet(); }
                }
            };
        }
    }
    public final List<Source> sources = new ArrayList<>();
    public final Ledger ledger = new Ledger();
    private final List<AutoCloseable> clients = new ArrayList<>();
    private final List<MonitoringTestEnvironment> environments = new ArrayList<>();
    private final Set<String> paused = new HashSet<>();
    private DefaultClientResources redisResources;
    OwnedRedisProxy redisProxy;
    final CountDownLatch browserFinish = new CountDownLatch(1);
    private boolean browserMode, closed;
    public ConfigurableApplicationContext context;
    public String baseURL;
    public ObjectMapper json;

    private MonitoringRuntimeFixture() { }
    static MonitoringRuntimeFixture startDisabled() throws Exception {
        var fixture = new MonitoringRuntimeFixture();
        try { fixture.boot(false); return fixture; }
        catch (Exception | Error failure) { try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); } throw failure; }
    }

    public static MonitoringRuntimeFixture start(List<String> types, int redisTargets, boolean periodic) throws Exception {
        return start(types, redisTargets, periodic, false);
    }
    static MonitoringRuntimeFixture startBrowser() throws Exception { return start(List.of("mysql", "redis"), 1, true, true); }
    private static MonitoringRuntimeFixture start(List<String> types, int redisTargets, boolean periodic, boolean browserMode) throws Exception {
        if (types.isEmpty() || types.stream().distinct().count() != types.size() || redisTargets < 1 || redisTargets > 20) throw new IllegalArgumentException("Invalid owned runtime fixture types/count");
        for (String type : types) MonitoringTestEnvironment.normalizeType(type);
        if (redisTargets > 1 && !types.equals(List.of("redis"))) throw new IllegalArgumentException("Twenty-target mode owns only Redis fixtures");
        var fixture = new MonitoringRuntimeFixture();
        fixture.browserMode = browserMode;
        try {
            if (types.contains("redis")) fixture.redisResources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
            for (String type : types) for (int index = 0; index < (type.equals("redis") ? redisTargets : 1); index++) fixture.add(type, index);
            fixture.boot(periodic);
            return fixture;
        } catch (Exception | Error failure) {
            try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(new IllegalStateException("Owned runtime cleanup failed")); }
            throw failure;
        }
    }
    private void add(String type, int index) throws Exception {
        MonitoringTestEnvironment environment = MonitoringTestEnvironment.start(type);
        environments.add(environment); environment.prepareData();
        String id = type + "-" + String.format(Locale.ROOT, "%02d", index), name = "runtime" + type + index;
        Object source;
        switch (type) {
            case "mysql" -> {
                var pool = new HikariDataSource(); pool.setJdbcUrl(environment.jdbcUrl());
                pool.setUsername(environment.username()); pool.setPassword(environment.password());
                pool.setMaximumPoolSize(2); pool.setMinimumIdle(0); pool.setPoolName("owned-monitor-mysql");
                source = pool; clients.add(pool);
            }
            case "redis" -> {
                if (browserMode) redisProxy = new OwnedRedisProxy(new InetSocketAddress(environment.host(), environment.port()));
                var standalone = new RedisStandaloneConfiguration(environment.host(), browserMode ? redisProxy.port() : environment.port()); standalone.setPassword(environment.password());
                var configuration = LettuceClientConfiguration.builder().clientResources(redisResources).commandTimeout(Duration.ofSeconds(3)).build();
                var factory = new LettuceConnectionFactory(standalone, configuration);
                clients.add(factory::destroy); factory.afterPropertiesSet(); factory.start();
                try (var connection = factory.getConnection()) { assertThat(connection.ping()).isEqualTo("PONG"); }
                source = factory;
            }
            case "kafka" -> {
                var admin = new KafkaAdmin(Map.of("bootstrap.servers", environment.bootstrapServers(), "request.timeout.ms", 3000, "default.api.timeout.ms", 3000));
                admin.setAutoCreate(false); source = admin;
            }
            case "mongodb" -> { var client = environment.mongoClient(); source = client; clients.add(client); }
            case "elasticsearch" -> { var client = environment.elasticsearchClient(); source = client; clients.add(client); }
            case "minio" -> source = environment.minioClient();
            default -> throw new IllegalArgumentException("Unsupported owned fixture type");
        }
        sources.add(new Source(id, name, environment, source));
    }
    private void boot(boolean periodic) throws Exception {
        Map<String, Object> settings = new HashMap<>();
        String identity = "jdbc:h2:mem:monitor_runtime_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1";
        settings.put("spring.config.location", "classpath:/chat-test.yml");
        settings.put("spring.datasource.url", identity); settings.put("spring.datasource.username", "sa"); settings.put("spring.datasource.password", "");
        settings.put("spring.datasource.driver-class-name", "org.h2.Driver");
        settings.put("spring.flyway.url", identity); settings.put("spring.flyway.user", "sa"); settings.put("spring.flyway.password", "");
        settings.put("spring.flyway.enabled", true); settings.put("spring.flyway.locations", "classpath:db/migration");
        settings.put("server.address", "127.0.0.1"); settings.put("server.port", 0); settings.put("server.servlet.context-path", ""); settings.put("server.ssl.enabled", false);
        settings.put("chat.enabled", false); settings.put("monitor.enabled", true); settings.put("monitor.allowed-user-ids[0]", 1);
        settings.put("runtime.periodic", periodic);
        // Acceptance always exercises the PRD defaults, even if the launching shell has monitor overrides.
        settings.putAll(Map.of("monitor.ordinary-interval", "15s", "monitor.capacity-interval", "60s",
                "monitor.refresh-interval", "15s", "monitor.collection-timeout", "5s", "monitor.service-ttl", "45s",
                "monitor.metric-ttl-multiplier", 3, "monitor.ordinary-concurrency", 8, "monitor.capacity-concurrency", 2, "monitor.max-targets", 20));
        settings.put("logging.level.root", "WARN");
        Path ready = Files.createTempDirectory(Path.of("target"), "monitor-runtime-").resolve("ready.json");
        settings.put("monitor.e2e.ready-file", ready.toAbsolutePath().toString());
        int index = 0;
        for (Source source : sources) {
            String prefix = "monitor.targets[" + index++ + "]";
            settings.put(prefix + ".id", source.targetId()); settings.put(prefix + ".name", "隔离 " + source.environment().type() + " " + source.targetId());
            settings.put(prefix + ".type", source.environment().type().toUpperCase(Locale.ROOT)); settings.put(prefix + ".enabled", true); settings.put(prefix + ".connection-source", source.beanName());
            String scope = switch (source.environment().type()) { case "mysql", "mongodb" -> "databases"; case "kafka" -> "topics"; case "elasticsearch" -> "indices"; case "minio" -> "buckets"; default -> null; };
            if (scope != null) settings.put(prefix + ".scope." + scope + "[0]", source.environment().resourceName());
        }
        if (sources.size() < 6) for (MiddlewareType type : MiddlewareType.values()) {
            if (sources.stream().anyMatch(source -> source.environment().type().equals(type.name().toLowerCase(Locale.ROOT)))) continue;
            String prefix = "monitor.targets[" + index++ + "]";
            settings.put(prefix + ".id", "disabled-" + type.name().toLowerCase(Locale.ROOT)); settings.put(prefix + ".type", type.name()); settings.put(prefix + ".enabled", false);
        }
        Map<String, MinioMetricsAuthorization> authorizations = new HashMap<>();
        for (Source source : sources) if (source.environment().type().equals("minio")) authorizations.put(source.beanName() + "Authorization",
                new MinioMetricsAuthorization(source.client(), source.environment().httpEndpoint(), source.environment().minioMetricsBearer(), Set.of(MinioMonitoringConnections.MetricsEndpoint.NODE, MinioMonitoringConnections.MetricsEndpoint.CLUSTER)));
        var app = new SpringApplication(browserMode
                ? new Class<?>[]{MonitoringE2eApplication.class, RuntimeConfiguration.class, MonitoringRuntimeE2eApplication.Controls.class}
                : new Class<?>[]{MonitoringE2eApplication.class, RuntimeConfiguration.class});
        app.setDefaultProperties(Map.of("spring.config.location", "classpath:/chat-test.yml"));
        app.addInitializers(application -> {
            application.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-runtime-isolation", settings));
            application.getBeanFactory().registerSingleton("runtimeLedger", ledger);
            application.getBeanFactory().registerSingleton("ownedRuntimeFixture", this);
            for (Source source : sources) application.getBeanFactory().registerSingleton(source.beanName(), source.client());
            authorizations.forEach((name, value) -> application.getBeanFactory().registerSingleton(name, value));
        });
        context = app.run();
        baseURL = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        json = context.getBean(ObjectMapper.class);
        await().atMost(Duration.ofSeconds(15)).until(() -> scheduler().readState().resolutionState() == MonitoringScheduler.ResolutionState.READY);
        for (Source source : sources) assertThat(scheduler().targets()).anySatisfy(target -> {
            assertThat(target.display().id()).isEqualTo(source.targetId()); assertThat(target.reason().name()).isEqualTo("CONFIGURED");
        });
    }
    public MonitoringScheduler scheduler() { return context.getBean(MonitoringScheduler.class); }
    public void collect() {
        for (Source source : sources) for (CollectionKind kind : CollectionKind.values()) assertThat(scheduler().submitNow(source.targetId(), kind)).isTrue();
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            var diagnostics = scheduler().diagnostics();
            assertThat(diagnostics.ordinaryActive()).isLessThanOrEqualTo(8); assertThat(diagnostics.capacityActive()).isLessThanOrEqualTo(2);
            assertThat(diagnostics.ordinaryQueued()).isLessThanOrEqualTo(20); assertThat(diagnostics.capacityQueued()).isLessThanOrEqualTo(20);
            assertThat(diagnostics.workerThreads()).isLessThanOrEqualTo(10); assertThat(diagnostics.cleanupThreads()).isLessThanOrEqualTo(10);
            return diagnostics.inFlightTasks() == 0;
        });
        for (Source source : sources) assertThat(context.getBean(MonitoringSnapshotStore.class).snapshot(source.targetId()).orElseThrow().attempts()).hasSize(2);
    }
    public void pauseRedis(String targetId) {
        Source source = source(targetId); source.environment().pauseRedis(); paused.add(targetId);
    }
    public void resumeRedis(String targetId) {
        Source source = source(targetId); source.environment().resumeRedis(); paused.remove(targetId);
    }
    public Source source(String id) { return sources.stream().filter(source -> source.targetId().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown owned fixture target")); }
    public Browser login() throws Exception { var browser = new Browser(baseURL, json); browser.login(); return browser; }
    public static final class Browser {
        final String baseURL; final ObjectMapper json;
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
        Browser(String baseURL, ObjectMapper json) { this.baseURL = baseURL; this.json = json; }
        void login() throws Exception {
            var initial = http.send(HttpRequest.newBuilder(URI.create(baseURL + "/api/monitor/v1/csrf-token")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(initial.statusCode()).isEqualTo(200);
            JsonNode csrf = json.readTree(initial.body()); assertThat(csrf.path("token").asText()).isNotBlank();
            var response = http.send(HttpRequest.newBuilder(URI.create(baseURL + "/api/monitor/v1/sessions")).header("X-CSRF-TOKEN", csrf.path("token").asText()).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=monitor-allowed&password=Monitor-Test-26%21")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
        }
        public JsonNode get(String suffix) throws Exception {
            var response = http.send(HttpRequest.newBuilder(URI.create(baseURL + "/api/monitor/v1" + suffix)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            return json.readTree(response.body());
        }
    }
    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        boolean failed = false;
        for (String id : List.copyOf(paused)) try { resumeRedis(id); } catch (Exception failure) { failed = true; }
        if (context != null) {
            try { scheduler().close(); context.getBean(JdbcTemplate.class).execute("SHUTDOWN"); } catch (Exception failure) { failed = true; }
            try { context.close(); } catch (Exception failure) { failed = true; }
        }
        for (int index = clients.size() - 1; index >= 0; index--) try { clients.get(index).close(); } catch (Exception failure) { failed = true; }
        if (redisProxy != null) try { redisProxy.close(); } catch (Exception failure) { failed = true; }
        if (redisResources != null) try { redisResources.shutdown().get(5, TimeUnit.SECONDS); } catch (Exception failure) { failed = true; }
        for (int index = environments.size() - 1; index >= 0; index--) try { environments.get(index).close(); } catch (Exception failure) { failed = true; }
        if (failed) throw new IllegalStateException("Owned monitoring runtime cleanup failed; see sanitized fixture logs");
    }
    @Configuration(proxyBeanMethods = false)
    @Import({MonitoringConfiguration.class, MonitoringReadController.class, MyExceptionHandler.class})
    @ImportAutoConfiguration(HttpMessageConvertersAutoConfiguration.class)
    public static class RuntimeConfiguration {
        @Bean(name = "dataSource", destroyMethod = "close") @Primary
        HikariDataSource identityDataSource(Environment environment) {
            var source = new HikariDataSource(); source.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url")); source.setUsername("sa"); source.setPassword(""); source.setDriverClassName("org.h2.Driver"); source.setMaximumPoolSize(8); source.setMinimumIdle(0); return source;
        }
        @Bean @Primary MonitoringAdapterRegistry runtimeRegistry(List<MonitoringAdapter> adapters, Ledger ledger) { return new MonitoringAdapterRegistry(adapters.stream().map(ledger::wrap).toList()); }
        @Bean(destroyMethod = "close") @Primary
        MonitoringScheduler runtimeScheduler(MonitoringProperties properties, MonitoringConnectionResolver resolver, MonitoringAdapterRegistry adapters, MonitoringSnapshotStore snapshots, MonitoringCounterStore counters, Environment environment) {
            return new MonitoringScheduler(properties, resolver::resolve, adapters, snapshots, counters, environment.getProperty("runtime.periodic", Boolean.class, false));
        }
    }
}
