package com.hpj.admin.monitor;

import com.hpj.admin.common.config.monitor.MonitoringConfiguration;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MonitoringConfiguration.class);

    @Test
    void defaultsKeepMonitoringDisabledAndGrantNobodyAccess() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            MonitoringProperties properties = context.getBean(MonitoringProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getAllowedUserIds()).isEmpty();
            assertThat(properties.getTargets()).isEmpty();
            assertThat(properties.getOrdinaryInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.getRefreshInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.getCapacityInterval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.getCollectionTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getServiceTtl()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.getMetricTtlMultiplier()).isEqualTo(3);
            assertThat(properties.getOrdinaryConcurrency()).isEqualTo(8);
            assertThat(properties.getCapacityConcurrency()).isEqualTo(2);
            assertThat(properties.getMaxTargets()).isEqualTo(20);
        });
    }

    @Test
    void binderUsesEffectiveOverridesAndBindsExplicitScopes() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("defaults", Map.of(
                "monitor.ordinary-interval", "15s", "monitor.capacity-interval", "60s")));
        environment.getPropertySources().addFirst(new MapPropertySource("deployment", Map.ofEntries(
                Map.entry("monitor.enabled", "true"),
                Map.entry("monitor.ordinary-interval", "30s"),
                Map.entry("monitor.capacity-interval", "2m"),
                Map.entry("monitor.allowed-user-ids[0]", "42"),
                Map.entry("monitor.limits.databases", "1"),
                Map.entry("monitor.limits.topics", "1"),
                Map.entry("monitor.limits.consumer-groups", "1"),
                Map.entry("monitor.limits.buckets", "1"),
                Map.entry("monitor.limits.indices", "1"),
                Map.entry("monitor.limits.partitions", "10"),
                Map.entry("monitor.limits.nodes", "2"),
                Map.entry("monitor.limits.log-directories", "3"),
                Map.entry("monitor.targets[0].id", "primary-db"),
                Map.entry("monitor.targets[0].type", "mysql"),
                Map.entry("monitor.targets[0].enabled", "true"),
                Map.entry("monitor.targets[0].connection-source", "mainDataSource"),
                Map.entry("monitor.targets[0].scope.databases[0]", "reporting"),
                Map.entry("monitor.targets[1].id", "messaging"),
                Map.entry("monitor.targets[1].type", "kafka"),
                Map.entry("monitor.targets[1].scope.topics[0]", "orders"),
                Map.entry("monitor.targets[1].scope.consumer-groups[0]", "fulfillment"),
                Map.entry("monitor.targets[2].id", "objects"),
                Map.entry("monitor.targets[2].type", "minio"),
                Map.entry("monitor.targets[2].scope.buckets[0]", "attachments"),
                Map.entry("monitor.targets[3].id", "search"),
                Map.entry("monitor.targets[3].type", "elasticsearch"),
                Map.entry("monitor.targets[3].scope.indices[0]", "products")
        )));

        MonitoringProperties properties = Binder.get(environment)
                .bind("monitor", Bindable.of(MonitoringProperties.class)).get();

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getOrdinaryInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getCapacityInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getAllowedUserIds()).containsExactly(42L);
        assertThat(properties.getTargets()).hasSize(4);
        assertThat(properties.getTargets().get(0).getType()).isEqualTo(MiddlewareType.MYSQL);
        assertThat(properties.getTargets().get(0).getConnectionSource()).isEqualTo("mainDataSource");
        assertThat(properties.getTargets().get(0).getScope().getDatabases()).containsExactly("reporting");
        assertThat(properties.getTargets().get(1).getScope().getTopics()).containsExactly("orders");
        assertThat(properties.getTargets().get(1).getScope().getConsumerGroups()).containsExactly("fulfillment");
        assertThat(properties.getTargets().get(2).getScope().getBuckets()).containsExactly("attachments");
        assertThat(properties.getTargets().get(3).getScope().getIndices()).containsExactly("products");
        assertThat(properties.getTargets().get(3).getScope().getDatabases()).isEmpty();
        assertThat(properties.getLimits().getPartitions()).isEqualTo(10);
        assertThat(properties.getLimits().getNodes()).isEqualTo(2);
        assertThat(properties.getLimits().getLogDirectories()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void rejectsInvalidBudgetsAndScopes(String description, Consumer<MonitoringProperties> change) {
        MonitoringProperties properties = new MonitoringProperties();
        change.accept(properties);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid monitor configuration:");
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                invalid("negative ordinary interval", p -> p.setOrdinaryInterval(Duration.ofSeconds(-1))),
                invalid("zero capacity interval", p -> p.setCapacityInterval(Duration.ZERO)),
                invalid("null refresh interval", p -> p.setRefreshInterval(null)),
                invalid("overlong service TTL", p -> p.setServiceTtl(Duration.ofDays(2))),
                invalid("zero timeout", p -> p.setCollectionTimeout(Duration.ZERO)),
                invalid("timeout exceeds ordinary interval", p -> p.setCollectionTimeout(Duration.ofSeconds(16))),
                invalid("timeout exceeds capacity interval", p -> p.setCapacityInterval(Duration.ofSeconds(4))),
                invalid("zero TTL multiplier", p -> p.setMetricTtlMultiplier(0)),
                invalid("TTL multiplier too large", p -> p.setMetricTtlMultiplier(101)),
                invalid("negative ordinary concurrency", p -> p.setOrdinaryConcurrency(-1)),
                invalid("capacity concurrency too large", p -> p.setCapacityConcurrency(65)),
                invalid("zero target limit", p -> p.setMaxTargets(0)),
                invalid("target limit too large", p -> p.setMaxTargets(1001)),
                invalid("negative user ID", p -> p.setAllowedUserIds(List.of(-1L))),
                invalid("missing user list", p -> p.setAllowedUserIds(null)),
                invalid("zero database limit", p -> p.getLimits().setDatabases(0)),
                invalid("overlarge partition limit", p -> p.getLimits().setPartitions(100001)),
                invalid("negative node limit", p -> p.getLimits().setNodes(-1)),
                invalid("missing limits", p -> p.setLimits(null)),
                invalid("too many targets", p -> {
                    p.setMaxTargets(1);
                    p.setTargets(List.of(target("first", MiddlewareType.MYSQL), target("second", MiddlewareType.REDIS)));
                }),
                invalid("duplicate IDs across types", p -> p.setTargets(List.of(
                        target("same", MiddlewareType.MYSQL), target("same", MiddlewareType.REDIS)))),
                invalid("missing target type", p -> p.setTargets(List.of(target("first", null)))),
                invalid("overlong ID", p -> p.setTargets(List.of(target("a".repeat(97), MiddlewareType.MYSQL)))),
                invalid("unsafe ID", p -> p.setTargets(List.of(target("user@server", MiddlewareType.MYSQL)))),
                invalid("duplicate scope names", p -> addScopedTarget(p, s -> s.setDatabases(List.of("db", "db")))),
                invalid("blank scope name", p -> addScopedTarget(p, s -> s.setTopics(List.of(" ")))),
                invalid("control character in scope", p -> addScopedTarget(p, s -> s.setBuckets(List.of("bucket\n")))),
                invalid("overlong scope name", p -> addScopedTarget(p, s -> s.setIndices(List.of("a".repeat(256))))),
                invalid("missing scope list", p -> addScopedTarget(p, s -> s.setConsumerGroups(null))),
                invalid("database scope exceeds limit", p -> {
                    p.getLimits().setDatabases(1);
                    addScopedTarget(p, s -> s.setDatabases(List.of("one", "two")));
                }),
                invalid("topic scope exceeds limit", p -> {
                    p.getLimits().setTopics(1);
                    addScopedTarget(p, s -> s.setTopics(List.of("one", "two")));
                }),
                invalid("consumer group scope exceeds limit", p -> {
                    p.getLimits().setConsumerGroups(1);
                    addScopedTarget(p, s -> s.setConsumerGroups(List.of("one", "two")));
                }),
                invalid("bucket scope exceeds limit", p -> {
                    p.getLimits().setBuckets(1);
                    addScopedTarget(p, s -> s.setBuckets(List.of("one", "two")));
                }),
                invalid("index scope exceeds limit", p -> {
                    p.getLimits().setIndices(1);
                    addScopedTarget(p, s -> s.setIndices(List.of("one", "two")));
                })
        );
    }

    @Test
    void acceptsDocumentedUpperBoundsAndEmptyScopes() {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setOrdinaryInterval(Duration.ofDays(1));
        properties.setCapacityInterval(Duration.ofDays(1));
        properties.setRefreshInterval(Duration.ofDays(1));
        properties.setServiceTtl(Duration.ofDays(1));
        properties.setCollectionTimeout(Duration.ofDays(1));
        properties.setMetricTtlMultiplier(100);
        properties.setOrdinaryConcurrency(64);
        properties.setCapacityConcurrency(64);
        properties.setMaxTargets(1000);
        properties.getLimits().setPartitions(100000);
        properties.setTargets(List.of(target("a".repeat(96), MiddlewareType.MYSQL)));
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getTargets().get(0).getScope().getDatabases()).isEmpty();
    }

    @Test
    void springInvokesValidationBeforePublishingCatalog() {
        contextRunner.withPropertyValues("monitor.ordinary-concurrency=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("invalid ordinary-concurrency");
        });
    }

    @Test
    void explicitConfigurationAndProductionStyleScanShareOnePropertiesBean() {
        contextRunner.withUserConfiguration(PropertiesScan.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MonitoringProperties.class)
                    .hasSingleBean(MonitoringCatalog.class);
        });
    }

    @Test
    void documentedYamlBindsAndValidatesWithoutEnablingCollectors() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("monitor-example",
                new FileSystemResource("docs/middleware-monitoring-config.yml"))) {
            environment.getPropertySources().addFirst(source);
        }
        MonitoringProperties properties = Binder.get(environment)
                .bind("monitor", Bindable.of(MonitoringProperties.class)).get();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getAllowedUserIds()).isEmpty();
        assertThat(properties.getTargets()).extracting(MonitoringProperties.Target::getType)
                .containsExactlyInAnyOrder(MiddlewareType.values());
        assertThat(new MonitoringCatalog(properties).snapshot(List.of()))
                .allSatisfy(type -> assertThat(type.status()).isEqualTo(MonitoringTarget.ConfigurationStatus.DISABLED));
        properties.setEnabled(true);
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(new MonitoringCatalog(properties).snapshot(List.of()).stream()
                .filter(type -> type.type() == MiddlewareType.MONGODB).findFirst().orElseThrow().status())
                .isEqualTo(MonitoringTarget.ConfigurationStatus.CONFIGURATION_MISSING);
    }

    @Test
    void mistakenCredentialUriIsRejectedWithoutEchoingItsValue() {
        String credentialUri = "redis://private-user:private-secret@example.invalid:6379/0";
        MonitoringProperties properties = new MonitoringProperties();
        MonitoringProperties.Target target = target("redis-main", MiddlewareType.REDIS);
        target.setConnectionSource(credentialUri);
        properties.setTargets(List.of(target));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection-source")
                .hasMessageNotContaining(credentialUri)
                .hasMessageNotContaining("private-user")
                .hasMessageNotContaining("private-secret");
        assertThatThrownBy(() -> new MonitoringConnectionSource(MiddlewareType.REDIS, credentialUri))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private-user").hasMessageNotContaining("private-secret");
        assertThat(properties.toString()).doesNotContain("private-user", "private-secret");
        assertThat(target.toString()).doesNotContain("private-user", "private-secret");
    }

    private static Arguments invalid(String description, Consumer<MonitoringProperties> change) {
        return Arguments.of(description, change);
    }

    private static void addScopedTarget(MonitoringProperties properties, Consumer<MonitoringProperties.Scope> change) {
        MonitoringProperties.Target target = target("scope-check", MiddlewareType.MYSQL);
        change.accept(target.getScope());
        properties.setTargets(new ArrayList<>(List.of(target)));
    }

    private static MonitoringProperties.Target target(String id, MiddlewareType type) {
        MonitoringProperties.Target target = new MonitoringProperties.Target();
        target.setId(id);
        target.setType(type);
        return target;
    }

    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = MonitoringProperties.class)
    static class PropertiesScan {}
}
