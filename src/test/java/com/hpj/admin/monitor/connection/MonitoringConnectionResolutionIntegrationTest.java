package com.hpj.admin.monitor.connection;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Resolves real initialized clients against one owned Redis; never accepts an external endpoint. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "redis")
class MonitoringConnectionResolutionIntegrationTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void runtimeConnectionsRemainAuthoritativeAfterOriginalConfigurationChanges() {
        MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("redis");
        LettuceConnectionFactory primary = null;
        LettuceConnectionFactory otherDatabase = null;
        RedissonClient redisson = null;
        try {
            RedisStandaloneConfiguration originalLettuce = standalone(environment, 0);
            primary = factory(originalLettuce);
            primary.afterPropertiesSet();
            primary.start();
            ping(primary);

            otherDatabase = factory(standalone(environment, 1));
            otherDatabase.afterPropertiesSet();
            otherDatabase.start();
            ping(otherDatabase);

            Config originalRedisson = new Config();
            originalRedisson.setThreads(2).setNettyThreads(2);
            originalRedisson.useSingleServer()
                    .setAddress("redis://" + environment.host() + ":" + environment.port())
                    .setUsername(environment.username()).setPassword(environment.password()).setDatabase(0)
                    .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2)
                    .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(1)
                    .setConnectTimeout(3000).setTimeout(3000).setRetryAttempts(0);
            redisson = Redisson.create(originalRedisson);
            assertThat(redisson.getNodesGroup().pingAll()).isTrue();

            // Mutate only caller-owned templates. The already-created clients still use the owned fixture.
            // These loopback values must never be used for a new connection by metadata inspection.
            originalLettuce.setHostName("127.0.0.2");
            originalLettuce.setPort(1);
            originalRedisson.useSingleServer().setAddress("redis://127.0.0.2:1");

            RedisConnectionInspector inspector = new RedisConnectionInspector();
            ResolvedConnection lettuceSource = inspector.inspect("lettucePrimary", primary).orElseThrow();
            ResolvedConnection redissonSource = inspector.inspect("redissonPrimary", redisson).orElseThrow();
            ResolvedConnection otherSource = inspector.inspect("lettuceSecondary", otherDatabase).orElseThrow();
            assertEndpoint(lettuceSource, environment, 0);
            assertEndpoint(redissonSource, environment, 0);
            assertEndpoint(otherSource, environment, 1);
            assertThat(lettuceSource.identity().sameAs(redissonSource.identity())).isTrue();
            assertThat(lettuceSource.identity().sameAs(otherSource.identity())).isFalse();
            assertThat(redissonSource.identity().sameAs(otherSource.identity())).isFalse();

            DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
            beans.registerSingleton("lettucePrimary", primary);
            beans.registerSingleton("redissonPrimary", redisson);
            beans.registerSingleton("lettuceSecondary", otherDatabase);
            MonitoringProperties properties = new MonitoringProperties();
            properties.setEnabled(true);
            properties.setTargets(List.of(target("cache-main", "lettucePrimary"),
                    target("cache-redisson", "redissonPrimary"), target("cache-other-db", "lettuceSecondary")));
            List<ResolvedTarget> resolved = new MonitoringConnectionResolver(properties, beans, List.of(inspector)).resolve();
            assertThat(resolved).hasSize(2);
            ResolvedTarget merged = resolved.stream().filter(target -> target.display().id().equals("cache-main"))
                    .findFirst().orElseThrow();
            assertThat(merged.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
            assertThat(merged.memberIds()).containsExactly("cache-main", "cache-redisson");
            assertThat(merged.display().connectionSources()).containsExactly("lettucePrimary", "redissonPrimary");
            assertThat(resolved.stream().filter(target -> target.display().id().equals("cache-other-db"))
                    .findFirst().orElseThrow().memberIds()).containsExactly("cache-other-db");

            // Both real clients continue reaching the fixture after inspection and template mutation.
            ping(primary);
            ping(otherDatabase);
            assertThat(redisson.getNodesGroup().pingAll()).isTrue();
            assertThat(originalLettuce.getHostName()).isEqualTo("127.0.0.2");
            assertThat(originalLettuce.getPort()).isEqualTo(1);
            assertThat(originalRedisson.useSingleServer().getAddress()).isEqualTo("redis://127.0.0.2:1");
        } finally {
            // The resolver only borrowed these objects. Close caller-owned clients before their container.
            try {
                if (otherDatabase != null) otherDatabase.destroy();
            } finally {
                try {
                    if (primary != null) primary.destroy();
                } finally {
                    try {
                        if (redisson != null) redisson.shutdown(0, 5, TimeUnit.SECONDS);
                    } finally {
                        environment.close();
                    }
                }
            }
        }
    }

    private static RedisStandaloneConfiguration standalone(MonitoringTestEnvironment environment, int database) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(environment.host(), environment.port());
        configuration.setDatabase(database);
        configuration.setUsername(environment.username());
        configuration.setPassword(environment.password());
        return configuration;
    }

    private static LettuceConnectionFactory factory(RedisStandaloneConfiguration configuration) {
        return new LettuceConnectionFactory(configuration, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ofSeconds(1)).build());
    }

    private static void ping(LettuceConnectionFactory factory) {
        try (var connection = factory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    private static void assertEndpoint(ResolvedConnection source, MonitoringTestEnvironment environment, int database) {
        assertThat(source.type()).isEqualTo(MiddlewareType.REDIS);
        // Assert individual public endpoint values, so a failure cannot dump the credential-bearing settings map.
        assertThat(source.settings().get("host")).isEqualTo(environment.host());
        assertThat(source.settings().get("port")).isEqualTo(environment.port());
        assertThat(source.settings().get("database")).isEqualTo(database);
    }

    private static MonitoringProperties.Target target(String id, String source) {
        MonitoringProperties.Target target = new MonitoringProperties.Target();
        target.setId(id);
        target.setType(MiddlewareType.REDIS);
        target.setEnabled(true);
        target.setConnectionSource(source);
        return target;
    }
}
