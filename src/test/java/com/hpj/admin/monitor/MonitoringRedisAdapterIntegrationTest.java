package com.hpj.admin.monitor;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.connection.RedisConnectionInspector;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.redis.RedisMonitoringAdapter;
import com.hpj.admin.monitor.redis.RedisMonitoringConnections;
import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringSnapshotStore.Acceptance.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** An explicit Redis selection must start its own real server; unavailable Docker fails the selected run. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "redis")
class MonitoringRedisAdapterIntegrationTest {
    private static final List<String> SECTIONS = List.of("server", "clients", "stats", "persistence", "replication");
    private static final Set<String> READ_COMMANDS = Set.of("auth", "hello", "ping", "info", "select", "quit",
            "client|setinfo", "client|setname");

    @ParameterizedTest(name = "{0}: native values and deltas from the initialized client's actual target")
    @ValueSource(strings = {"lettuce", "redisson"})
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realMetricsPreserveRuntimeClientsAndOnlyReadTheOwnedServer(String clientType) throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("redis")) {
            environment.prepareData();
            try (var observer = environment.redisConnection();
                 RuntimeClient business = new RuntimeClient(clientType, environment, environment.username());
                 Collector collector = new Collector()) {
                var commands = observer.sync();
                String sentinel = commands.get(environment.resourceName());
                business.mutateOriginalTemplate();
                ResolvedTarget target = resolve(business);
                assertEffectiveEndpoint(target, environment, environment.username());
                business.guard = true;

                Map<String, Long> before = commandCounts(commands);
                CollectionResult first = collector.collect(target);
                assertNativeValues(first, collector.connections.observations.get(0));
                for (String key : List.of("redis.commands.rate", "redis.keyspace.hits.delta",
                        "redis.keyspace.misses.delta", "redis.keyspace.hit.percent", "redis.keys.evicted.delta",
                        "redis.keys.expired.delta")) {
                    assertThat(metric(first, key).missingReason()).as(key).isEqualTo(MissingReason.WAITING_SAMPLE);
                }
                assertOnlyReadCommands(before, commandCounts(commands));

                // PING and INFO contribute to command throughput, but neither performs a key lookup.
                // No fixture verification GET is issued between these two samples.
                CollectionResult idle = collector.collect(target);
                NativeObservation idleNative = collector.connections.observations.get(1);
                assertNativeValues(idle, idleNative);
                assertThat(metric(idle, "redis.keyspace.hit.percent").missingReason()).isEqualTo(MissingReason.NO_REQUESTS);
                assertThat(value(idle, "redis.keyspace.hits.delta")).isZero();
                assertThat(value(idle, "redis.keyspace.misses.delta")).isZero();
                assertRate(first, idle, collector.connections.observations.get(0), idleNative);
                assertOnlyReadCommands(before, commandCounts(commands));

                // These calls target only the random key owned by this fixture, plus a known absent sibling.
                for (int index = 0; index < 3; index++) {
                    assertThat(commands.get(environment.resourceName())).isEqualTo(sentinel);
                }
                assertThat(commands.get(environment.resourceName() + "-absent")).isNull();
                Map<String, Long> afterFixtureReads = commandCounts(commands);
                CollectionResult loaded = collector.collect(target);
                NativeObservation loadedNative = collector.connections.observations.get(2);
                assertNativeValues(loaded, loadedNative);
                assertThat(value(loaded, "redis.keyspace.hits.delta")).isEqualByComparingTo("3");
                assertThat(value(loaded, "redis.keyspace.misses.delta")).isEqualByComparingTo("1");
                assertThat(value(loaded, "redis.keyspace.hit.percent")).isEqualByComparingTo("75");
                assertThat(number(loadedNative, "stats", "keyspace_hits")
                        .subtract(number(idleNative, "stats", "keyspace_hits"))).isEqualByComparingTo("3");
                assertThat(number(loadedNative, "stats", "keyspace_misses")
                        .subtract(number(idleNative, "stats", "keyspace_misses"))).isEqualByComparingTo("1");
                assertRate(idle, loaded, idleNative, loadedNative);
                assertOnlyReadCommands(afterFixtureReads, commandCounts(commands));
                assertThat(commands.get(environment.resourceName())).isEqualTo(sentinel);

                // Reset is deliberate fixture administration, outside the adapter's read-only interval.
                // Redis preserves run_id on RESETSTAT: decreased counters must still invalidate their baselines.
                assertThat(commands.configResetstat()).isEqualTo("OK");
                Map<String, Long> afterReset = commandCounts(commands);
                CollectionResult reset = collector.collect(target);
                NativeObservation resetNative = collector.connections.observations.get(3);
                assertNativeValues(reset, resetNative);
                assertThat(resetNative.field("server", "run_id")).isEqualTo(loadedNative.field("server", "run_id"));
                assertThat(value(reset, "redis.keyspace.hits.total")).isZero();
                assertThat(value(reset, "redis.keyspace.misses.total")).isZero();
                for (String key : List.of("redis.commands.rate", "redis.keyspace.hits.delta",
                        "redis.keyspace.misses.delta", "redis.keyspace.hit.percent")) {
                    assertThat(metric(reset, key).missingReason()).as(key).isEqualTo(MissingReason.WAITING_SAMPLE);
                }
                assertOnlyReadCommands(afterReset, commandCounts(commands));
                assertThat(collector.connections.closed).isEqualTo(4);
                assertThat(collector.counters.seriesCount(target.display().id())).isEqualTo(6);
                business.guard = false;
                business.assertStillUsable();
                business.assertOriginalTemplateStillMutated();
                assertThat(commands.get(environment.resourceName())).isEqualTo(sentinel);
            }
            environment.verifyData();
        }
    }

    @ParameterizedTest(name = "native ACL: partial INFO access = {0}")
    @ValueSource(booleans = {false, true})
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeInfoPermissionsNeverTurnASuccessfulPingIntoAConnectionFailure(boolean partialInfo) throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("redis")) {
            environment.prepareData();
            try (var observer = environment.redisConnection()) {
                var commands = observer.sync();
                String user = partialInfo ? "mon_partial" : "mon_ping";
                // This ACL belongs solely to the disposable server. No key commands are granted.
                CommandArgs<String, String> acl = new CommandArgs<>(StringCodec.UTF8)
                        .add("SETUSER").add(user).add("reset").add("on").add(">" + environment.password())
                        .add("+@connection");
                // Redis 7.2 supports positive first-argument ACL rules for INFO (not real subcommands).
                if (partialInfo) acl.add("+info|server").add("+info|clients");
                assertThat(commands.dispatch(CommandType.ACL, new StatusOutput<>(StringCodec.UTF8), acl)).isEqualTo("OK");
                try (RuntimeClient business = new RuntimeClient("lettuce", environment, user);
                     Collector collector = new Collector()) {
                    ResolvedTarget target = resolve(business);
                    assertEffectiveEndpoint(target, environment, user);
                    business.guard = true;
                    Map<String, Long> before = commandCounts(commands);
                    CollectionResult result = collector.collect(target);
                    assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
                    assertThat(result.serviceProbe().reason()).isNull();
                    assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
                    assertThat(value(result, "redis.probe.duration")).isNotNegative();
                    NativeObservation nativeValues = collector.connections.observations.get(0);
                    assertThat(nativeValues.attempted).containsExactlyElementsOf(SECTIONS);
                    if (partialInfo) {
                        assertThat(nativeValues.responses.keySet()).containsExactly("server", "clients");
                        assertThat(value(result, "redis.clients.connected"))
                                .isEqualByComparingTo(number(nativeValues, "clients", "connected_clients"));
                        assertThat(value(result, "redis.clients.blocked"))
                                .isEqualByComparingTo(number(nativeValues, "clients", "blocked_clients"));
                    } else {
                        assertThat(nativeValues.responses).isEmpty();
                    }
                    result.metrics().stream().filter(sample -> !sample.definition().key().equals("redis.probe.duration"))
                            .filter(sample -> !partialInfo || !sample.definition().key().startsWith("redis.clients."))
                            .forEach(sample -> assertThat(sample.missingReason()).as(sample.definition().key())
                                    .isEqualTo(MissingReason.UNAUTHORIZED));
                    assertOnlyReadCommands(before, commandCounts(commands));
                    assertThat(collector.connections.closed).isEqualTo(1);
                    business.guard = false;
                    business.assertStillUsable();
                } finally {
                    assertThat(commands.aclDeluser(user)).isEqualTo(1);
                }
            }
            environment.verifyData();
        }
    }

    private static ResolvedTarget resolve(RuntimeClient business) {
        MonitoringProperties.Target declaration = new MonitoringProperties.Target();
        declaration.setId("redis-" + business.type);
        declaration.setType(MiddlewareType.REDIS);
        declaration.setEnabled(true);
        declaration.setConnectionSource("businessRedis");
        MonitoringProperties properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(List.of(declaration));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("businessRedis", business.source);
        var targets = new MonitoringConnectionResolver(properties, beans, List.of(new RedisConnectionInspector())).resolve();
        assertThat(targets).hasSize(1);
        ResolvedTarget target = targets.get(0);
        assertThat(target.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
        assertThat(target.bindings().get(0).connection().client()).isSameAs(business.source);
        return target;
    }

    private static void assertEffectiveEndpoint(ResolvedTarget target, MonitoringTestEnvironment environment, String user) {
        var settings = target.bindings().get(0).connection().settings();
        // Individual assertions avoid dumping the credential-bearing settings map on a failure.
        assertThat(settings.get("host")).isEqualTo(environment.host());
        assertThat(settings.get("port")).isEqualTo(environment.port());
        assertThat(settings.get("database")).isEqualTo(0);
        assertThat(settings.get("username")).isEqualTo(user);
        assertThat(environment.password().equals(settings.get("password"))).isTrue();
    }

    private static void assertNativeValues(CollectionResult result, NativeObservation observation) {
        assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        assertThat(result.metrics()).hasSize(21).allSatisfy(sample ->
                assertThat(sample.definition().scope().kind()).isEqualTo(ScopeKind.PROCESS));
        assertThat(observation.attempted).containsExactlyElementsOf(SECTIONS);
        assertThat(observation.responses.keySet()).containsExactlyElementsOf(SECTIONS);
        assertThat(value(result, "redis.probe.duration")).isNotNegative();
        assertThat(metric(result, "redis.probe.duration").definition().unit()).isEqualTo(Unit.MILLISECONDS);
        Map<String, String> numeric = Map.of(
                "redis.clients.connected", "clients:connected_clients", "redis.clients.blocked", "clients:blocked_clients",
                "redis.keyspace.hits.total", "stats:keyspace_hits", "redis.keyspace.misses.total", "stats:keyspace_misses",
                "redis.keys.evicted.total", "stats:evicted_keys", "redis.keys.expired.total", "stats:expired_keys");
        numeric.forEach((key, nativeKey) -> {
            String[] field = nativeKey.split(":");
            assertThat(value(result, key)).isEqualByComparingTo(number(observation, field[0], field[1]));
            assertThat(metric(result, key).definition().unit()).isEqualTo(Unit.COUNT);
        });
        assertNativeScalar(result, observation, "redis.role", "replication", "role", Unit.TEXT);
        Map<String, String> flags = Map.of("redis.persistence.loading", "loading",
                "redis.rdb.bgsave.in_progress", "rdb_bgsave_in_progress", "redis.aof.enabled", "aof_enabled",
                "redis.aof.rewrite.in_progress", "aof_rewrite_in_progress");
        flags.forEach((key, field) -> assertNativeScalar(result, observation, key, "persistence", field, Unit.BOOLEAN));
        Map<String, String> statuses = Map.of("redis.rdb.last_bgsave.status", "rdb_last_bgsave_status",
                "redis.aof.last_bgrewrite.status", "aof_last_bgrewrite_status",
                "redis.aof.last_write.status", "aof_last_write_status");
        statuses.forEach((key, field) -> assertNativeScalar(result, observation, key, "persistence", field, Unit.TEXT));
    }

    private static void assertNativeScalar(CollectionResult result, NativeObservation observation,
                                           String key, String section, String field, Unit unit) {
        MetricSample sample = metric(result, key);
        assertThat(sample.definition().unit()).isEqualTo(unit);
        String nativeValue = observation.field(section, field);
        if (nativeValue == null) {
            assertThat(sample.missingReason()).as(key).isEqualTo(MissingReason.UNSUPPORTED);
        } else {
            assertThat(sample.missingReason()).as(key).isNull();
            assertThat(sample.value()).as(key).isEqualTo(unit == Unit.BOOLEAN ? nativeValue.equals("1") : nativeValue);
        }
    }

    private static void assertRate(CollectionResult before, CollectionResult after,
                                   NativeObservation initial, NativeObservation latest) {
        BigDecimal delta = number(latest, "stats", "total_commands_processed")
                .subtract(number(initial, "stats", "total_commands_processed"));
        Duration interval = Duration.between(metric(before, "redis.commands.rate").sampledAt(),
                metric(after, "redis.commands.rate").sampledAt());
        BigDecimal elapsed = BigDecimal.valueOf(interval.getSeconds()).add(BigDecimal.valueOf(interval.getNano(), 9));
        assertThat(delta).isPositive();
        assertThat(elapsed).isPositive();
        assertThat(value(after, "redis.commands.rate")).isEqualByComparingTo(delta.divide(elapsed, MathContext.DECIMAL128));
        assertThat(metric(after, "redis.commands.rate").definition().unit()).isEqualTo(Unit.COUNT_PER_SECOND);
    }

    private static Map<String, Long> commandCounts(RedisCommands<String, String> commands) {
        Map<String, Long> counts = new LinkedHashMap<>();
        parse(commands.info("commandstats")).forEach((name, fields) -> {
            if (name.startsWith("cmdstat_")) {
                for (String field : fields.split(",")) {
                    if (field.startsWith("calls=")) counts.put(name.substring(8), Long.parseLong(field.substring(6)));
                }
            }
        });
        return counts;
    }

    private static void assertOnlyReadCommands(Map<String, Long> before, Map<String, Long> after) {
        Set<String> commands = new LinkedHashSet<>(before.keySet());
        commands.addAll(after.keySet());
        for (String command : commands) {
            if (!READ_COMMANDS.contains(command)) {
                assertThat(after.getOrDefault(command, 0L)).as("collector must not issue " + command)
                        .isEqualTo(before.getOrDefault(command, 0L));
            }
        }
    }

    private static MetricSample metric(CollectionResult result, String key) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)).findFirst().orElseThrow();
    }
    private static BigDecimal value(CollectionResult result, String key) {
        MetricSample sample = metric(result, key);
        assertThat(sample.missingReason()).as(key).isNull();
        return (BigDecimal) sample.value();
    }
    private static BigDecimal number(NativeObservation observation, String section, String key) {
        return new BigDecimal(observation.field(section, key));
    }
    private static Map<String, String> parse(String info) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : info.split("\\r?\\n")) {
            int separator = line.indexOf(':');
            if (!line.startsWith("#") && separator > 0) fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    /** Records the actual protocol response, without substituting native commands, data, or permissions. */
    private static final class RecordingConnections extends RedisMonitoringConnections {
        final List<NativeObservation> observations = new ArrayList<>();
        int closed;
        @Override public Session open(CollectionRequest request) {
            Session actual = super.open(request);
            NativeObservation observation = new NativeObservation();
            observations.add(observation);
            return new Session() {
                @Override public String ping() { return actual.ping(); }
                @Override public String info(String section) {
                    observation.attempted.add(section);
                    String response = actual.info(section);
                    observation.responses.put(section, parse(response));
                    return response;
                }
                @Override public void close() { actual.close(); closed++; }
            };
        }
    }
    private static final class NativeObservation {
        final List<String> attempted = new ArrayList<>();
        final Map<String, Map<String, String>> responses = new LinkedHashMap<>();
        String field(String section, String key) { return responses.getOrDefault(section, Map.of()).get(key); }
    }

    private static final class Collector implements AutoCloseable {
        final Object lifecycle = new Object();
        final Clock clock = Clock.systemUTC();
        final MonitoringProperties properties = new MonitoringProperties();
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 32);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 32, 2);
        final RecordingConnections connections = new RecordingConnections();
        final RedisMonitoringAdapter adapter = new RedisMonitoringAdapter(properties, connections, clock);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        long sequence;

        CollectionResult collect(ResolvedTarget target) throws Exception {
            String id = target.display().id();
            var binding = target.bindings().get(0);
            var source = binding.connection();
            snapshots.activate(id, 1);
            Instant scheduled = clock.instant();
            Duration timeout = properties.getCollectionTimeout();
            CollectionControl control = new CollectionControl(lifecycle, () -> true, clock, System::nanoTime,
                    System.nanoTime() + timeout.toNanos(), cleanup, counters, id, source.source(),
                    CollectionKind.ORDINARY, properties.getOrdinaryInterval(), source.client());
            CollectionRequest request = new CollectionRequest(id, source.source(), source.source(), CollectionKind.ORDINARY,
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

    private static final class RuntimeClient implements AutoCloseable {
        final String type;
        final Object source;
        final RedisStandaloneConfiguration lettuceTemplate;
        final Config redissonTemplate;
        boolean guard;

        RuntimeClient(String type, MonitoringTestEnvironment environment, String username) {
            this.type = type;
            if (type.equals("lettuce")) {
                lettuceTemplate = new RedisStandaloneConfiguration(environment.host(), environment.port());
                lettuceTemplate.setUsername(username);
                lettuceTemplate.setPassword(environment.password());
                redissonTemplate = null;
                LettuceConnectionFactory factory = new LettuceConnectionFactory(lettuceTemplate,
                        LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3))
                                .shutdownTimeout(Duration.ofSeconds(1)).build()) {
                    @Override public RedisConnection getConnection() {
                        if (guard) throw new AssertionError("Collector must not borrow a business Redis connection");
                        return super.getConnection();
                    }
                    @Override public void destroy() {
                        if (guard) throw new AssertionError("Collector must not destroy a business Redis client");
                        super.destroy();
                    }
                };
                source = factory;
                try {
                    factory.afterPropertiesSet();
                    factory.start();
                    assertStillUsable();
                } catch (RuntimeException | Error failure) {
                    factory.destroy();
                    throw failure;
                }
            } else {
                lettuceTemplate = null;
                redissonTemplate = new Config();
                redissonTemplate.setThreads(2).setNettyThreads(2);
                redissonTemplate.useSingleServer().setAddress("redis://" + environment.host() + ":" + environment.port())
                        .setUsername(username).setPassword(environment.password()).setDatabase(0)
                        .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2)
                        .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(1)
                        .setConnectTimeout(3000).setTimeout(3000).setRetryAttempts(0);
                RedissonClient client = Redisson.create(redissonTemplate);
                source = client;
                try {
                    assertStillUsable();
                } catch (RuntimeException | Error failure) {
                    client.shutdown(0, 5, TimeUnit.SECONDS);
                    throw failure;
                }
            }
        }
        void mutateOriginalTemplate() {
            if (lettuceTemplate != null) {
                lettuceTemplate.setHostName("127.0.0.2");
                lettuceTemplate.setPort(1);
            } else redissonTemplate.useSingleServer().setAddress("redis://127.0.0.2:1");
        }
        void assertOriginalTemplateStillMutated() {
            if (lettuceTemplate != null) {
                assertThat(lettuceTemplate.getHostName()).isEqualTo("127.0.0.2");
                assertThat(lettuceTemplate.getPort()).isEqualTo(1);
            } else assertThat(redissonTemplate.useSingleServer().getAddress()).isEqualTo("redis://127.0.0.2:1");
        }
        void assertStillUsable() {
            if (source instanceof LettuceConnectionFactory factory) {
                assertThat(factory.isRunning()).isTrue();
                try (var connection = factory.getConnection()) { assertThat(connection.ping()).isEqualTo("PONG"); }
            } else {
                RedissonClient client = (RedissonClient) source;
                assertThat(client.isShutdown()).isFalse();
                assertThat(client.getNodesGroup().pingAll()).isTrue();
            }
        }
        @Override public void close() {
            guard = false;
            if (source instanceof LettuceConnectionFactory factory) factory.destroy();
            else ((RedissonClient) source).shutdown(0, 5, TimeUnit.SECONDS);
        }
    }
}
