package com.hpj.admin.monitor;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.monitor.MonitoringConfiguration;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.mongodb.client.MongoClient;
import io.minio.MinioClient;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringCatalogTest {
    @Test
    void emptyConfigurationAlwaysListsAllSixDisabledTypes() {
        List<MonitoringCatalog.TypeCatalog> snapshot = new MonitoringCatalog(new MonitoringProperties()).snapshot(List.of());
        assertThat(snapshot).extracting(MonitoringCatalog.TypeCatalog::type)
                .containsExactly(MiddlewareType.MYSQL, MiddlewareType.REDIS, MiddlewareType.KAFKA,
                        MiddlewareType.MONGODB, MiddlewareType.ELASTICSEARCH, MiddlewareType.MINIO);
        assertThat(snapshot).allSatisfy(type -> {
            assertThat(type.status()).isEqualTo(DISABLED);
            assertThat(type.targets()).isEmpty();
            assertThat(type.configuredCount()).isZero();
            assertThat(type.missingCount()).isZero();
            assertThat(type.disabledCount()).isZero();
        });
    }

    @Test
    void sameTypeInstancesKeepStableIdentityAndIndependentThreeWayStatuses() {
        MonitoringProperties properties = enabledProperties(
                target("redis-ready", MiddlewareType.REDIS, true, "redisFactory"),
                target("redis-missing", MiddlewareType.REDIS, true, "absentFactory"),
                target("redis-off", MiddlewareType.REDIS, false, "redisFactory"));
        MonitoringCatalog catalog = new MonitoringCatalog(properties);

        MonitoringCatalog.TypeCatalog redis = type(catalog.snapshot(List.of(
                new MonitoringConnectionSource(MiddlewareType.REDIS, "redisFactory"))), MiddlewareType.REDIS);
        assertThat(redis.status()).isEqualTo(CONFIGURED);
        assertThat(redis.configuredCount()).isEqualTo(1);
        assertThat(redis.missingCount()).isEqualTo(1);
        assertThat(redis.disabledCount()).isEqualTo(1);
        assertThat(redis.targets()).extracting(MonitoringTarget::id)
                .containsExactly("redis-ready", "redis-missing", "redis-off");
        assertThat(redis.targets()).extracting(MonitoringTarget::status)
                .containsExactly(CONFIGURED, CONFIGURATION_MISSING, DISABLED);

        properties.getTargets().get(0).setName("Updated name");
        MonitoringCatalog.TypeCatalog later = type(catalog.snapshot(List.of()), MiddlewareType.REDIS);
        assertThat(later.status()).isEqualTo(CONFIGURATION_MISSING);
        assertThat(later.targets().get(0).id()).isEqualTo("redis-ready");
        assertThat(later.targets().get(0).name()).isEqualTo("Updated name");
        assertThat(redis.targets().get(0).name()).isEqualTo("Redis");
    }

    @Test
    void sourceDeclarationNeedsMatchingResolvedTypeAndReference() {
        MonitoringProperties properties = enabledProperties(
                target("declared", MiddlewareType.MYSQL, true, "sharedSource"),
                target("undeclared", MiddlewareType.MYSQL, true, null),
                target("blank-source", MiddlewareType.MYSQL, true, " "));
        MonitoringCatalog catalog = new MonitoringCatalog(properties);
        for (List<MonitoringConnectionSource> sources : List.of(
                List.<MonitoringConnectionSource>of(),
                List.of(new MonitoringConnectionSource(MiddlewareType.REDIS, "sharedSource")),
                List.of(new MonitoringConnectionSource(MiddlewareType.MYSQL, "otherSource")))) {
            assertThat(type(catalog.snapshot(sources), MiddlewareType.MYSQL).targets())
                    .allSatisfy(target -> assertThat(target.status()).isEqualTo(CONFIGURATION_MISSING));
        }
        List<MonitoringTarget> configured = type(catalog.snapshot(List.of(
                new MonitoringConnectionSource(MiddlewareType.MYSQL, "sharedSource"))), MiddlewareType.MYSQL).targets();
        assertThat(configured).extracting(MonitoringTarget::status)
                .containsExactly(CONFIGURED, CONFIGURATION_MISSING, CONFIGURATION_MISSING);
        assertThat(configured.get(0).connectionSources()).containsExactly("sharedSource");
        assertThat(configured.get(1).connectionSources()).isEmpty();
        assertThat(configured.get(2).connectionSources()).isEmpty();
    }

    @Test
    void globalDisableKeepsDeclaredTargetsVisibleWithoutPromotingResolvedSources() {
        MonitoringProperties properties = enabledProperties(target("db", MiddlewareType.MYSQL, true, "dataSource"));
        properties.setEnabled(false);
        MonitoringCatalog.TypeCatalog mysql = type(new MonitoringCatalog(properties).snapshot(List.of(
                new MonitoringConnectionSource(MiddlewareType.MYSQL, "dataSource"))), MiddlewareType.MYSQL);
        assertThat(mysql.targets()).hasSize(1);
        assertThat(mysql.status()).isEqualTo(DISABLED);
        assertThat(mysql.targets().get(0).status()).isEqualTo(DISABLED);
        assertThat(mysql.disabledCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void isolatedContextStartsWithoutInitializingAnyClientRegardlessOfChat(boolean monitorEnabled, boolean chatEnabled) {
        new ApplicationContextRunner().withUserConfiguration(MonitoringConfiguration.class, LazyClients.class)
                .withPropertyValues("monitor.enabled=" + monitorEnabled, "chat.enabled=" + chatEnabled,
                        "monitor.targets[0].id=redis-main", "monitor.targets[0].type=redis",
                        "monitor.targets[0].enabled=true", "monitor.targets[0].connection-source=redisFactory")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MonitoringCatalog.class);
                    MonitoringCatalog.TypeCatalog redis = type(context.getBean(MonitoringCatalog.class).snapshot(List.of()),
                            MiddlewareType.REDIS);
                    assertThat(redis.status()).isEqualTo(monitorEnabled ? CONFIGURATION_MISSING : DISABLED);
                    assertThat(context.getBean(MonitoringProperties.class).getAllowedUserIds()).isEmpty();
                    for (String client : List.of("dataSource", "redisFactory", "redisson", "kafkaAdmin", "mongoClient",
                            "elasticsearchClient", "minioClient")) {
                        assertThat(context.getBeanFactory().containsBeanDefinition(client)).isTrue();
                        assertThat(context.getBeanFactory().containsSingleton(client)).isFalse();
                    }
                });
    }

    @Test
    void publicSnapshotCopiesScopesAndExposesOnlySafeReferences() throws Exception {
        MonitoringProperties.Target declaration = target("mysql-primary", MiddlewareType.MYSQL, true, "mainDataSource");
        declaration.setName("Reporting database");
        declaration.getScope().setDatabases(new ArrayList<>(List.of("reporting")));
        declaration.getScope().setTopics(new ArrayList<>(List.of("orders")));
        declaration.getScope().setConsumerGroups(new ArrayList<>(List.of("consumer")));
        declaration.getScope().setBuckets(new ArrayList<>(List.of("attachments")));
        declaration.getScope().setIndices(new ArrayList<>(List.of("products")));
        MonitoringProperties properties = enabledProperties(declaration);
        List<MonitoringCatalog.TypeCatalog> snapshot = new MonitoringCatalog(properties).snapshot(List.of(
                new MonitoringConnectionSource(MiddlewareType.MYSQL, "mainDataSource")));
        MonitoringCatalog.TypeCatalog mysql = type(snapshot, MiddlewareType.MYSQL);
        MonitoringTarget target = mysql.targets().get(0);

        declaration.getScope().getDatabases().clear();
        declaration.getScope().getTopics().clear();
        declaration.getScope().getConsumerGroups().clear();
        declaration.getScope().getBuckets().clear();
        declaration.getScope().getIndices().clear();
        declaration.setConnectionSource("replacementSource");
        properties.getTargets().clear();

        assertThat(target.scope().databases()).containsExactly("reporting");
        assertThat(target.scope().topics()).containsExactly("orders");
        assertThat(target.scope().consumerGroups()).containsExactly("consumer");
        assertThat(target.scope().buckets()).containsExactly("attachments");
        assertThat(target.scope().indices()).containsExactly("products");
        assertThat(target.connectionSources()).containsExactly("mainDataSource");
        assertThatThrownBy(() -> snapshot.add(mysql)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> mysql.targets().add(target)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> target.connectionSources().add("anotherSource")).isInstanceOf(UnsupportedOperationException.class);
        for (List<String> scope : List.of(target.scope().databases(), target.scope().topics(), target.scope().consumerGroups(),
                target.scope().buckets(), target.scope().indices())) {
            assertThatThrownBy(() -> scope.add("extra")).isInstanceOf(UnsupportedOperationException.class);
        }

        String json = new ObjectMapper().writeValueAsString(snapshot);
        assertThat(json).contains("mainDataSource", "Reporting database", "CONFIGURED")
                .doesNotContain("replacementSource", "password", "secretKey", "accessKey", "jdbc:", "redis://");
        assertThat(target.toString()).contains("mainDataSource").doesNotContain("replacementSource");
    }

    private static MonitoringProperties enabledProperties(MonitoringProperties.Target... targets) {
        MonitoringProperties properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(new ArrayList<>(List.of(targets)));
        return properties;
    }

    private static MonitoringProperties.Target target(String id, MiddlewareType type, boolean enabled, String source) {
        MonitoringProperties.Target target = new MonitoringProperties.Target();
        target.setId(id);
        target.setType(type);
        target.setEnabled(enabled);
        target.setConnectionSource(source);
        return target;
    }

    private static MonitoringCatalog.TypeCatalog type(List<MonitoringCatalog.TypeCatalog> snapshot, MiddlewareType type) {
        return snapshot.stream().filter(entry -> entry.type() == type).findFirst().orElseThrow();
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyClients {
        @Bean @Lazy DataSource dataSource() { throw unexpected(); }
        @Bean @Lazy RedisConnectionFactory redisFactory() { throw unexpected(); }
        @Bean @Lazy RedissonClient redisson() { throw unexpected(); }
        @Bean @Lazy AdminClient kafkaAdmin() { throw unexpected(); }
        @Bean @Lazy MongoClient mongoClient() { throw unexpected(); }
        @Bean @Lazy ElasticsearchClient elasticsearchClient() { throw unexpected(); }
        @Bean @Lazy MinioClient minioClient() { throw unexpected(); }
        private static AssertionError unexpected() { return new AssertionError("Monitoring initialized an optional client"); }
    }
}
