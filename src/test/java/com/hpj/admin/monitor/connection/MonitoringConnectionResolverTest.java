package com.hpj.admin.monitor.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringCatalog;
import com.hpj.admin.monitor.MonitoringTarget;
import com.mongodb.client.MongoClient;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.api.RedissonClient;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.KafkaAdmin;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus.CONFIGURATION_MISSING;
import static com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus.CONFIGURED;
import static com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus.DISABLED;
import static com.hpj.admin.monitor.connection.ResolvedTarget.Reason.AMBIGUOUS_SOURCE;
import static com.hpj.admin.monitor.connection.ResolvedTarget.Reason.SOURCE_MISSING;
import static com.hpj.admin.monitor.connection.ResolvedTarget.Reason.UNSUPPORTED_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MonitoringConnectionResolverTest {
    @ParameterizedTest
    @CsvSource({"false,true", "true,false", "false,false"})
    void disabledMonitoringDoesNotEvenEnumerateSingletonsOrInspectClients(boolean global, boolean targetEnabled) {
        var target = target("cache", MiddlewareType.REDIS, "cacheFactory");
        target.setEnabled(targetEnabled);
        var properties = properties(target);
        properties.setEnabled(global);
        var beans = new DefaultListableBeanFactory() {
            @Override public String[] getSingletonNames() {
                throw new AssertionError("Disabled monitoring must not enumerate singleton clients");
            }
        };
        var client = mock(RedisConnectionFactory.class);
        beans.registerSingleton("cacheFactory", client);
        var inspector = mock(ConnectionInspector.class);

        var resolved = new MonitoringConnectionResolver(properties, beans, List.of(inspector)).resolve();

        assertThat(resolved).singleElement().satisfies(result -> {
            assertThat(result.reason()).isEqualTo(ResolvedTarget.Reason.DISABLED);
            assertThat(result.display().status()).isEqualTo(DISABLED);
            assertThat(result.bindings()).isEmpty();
        });
        verifyNoInteractions(inspector, client);
    }

    @Test
    void anEnabledSiblingDoesNotCauseDisabledTargetInspection() {
        var enabled = target("enabled", MiddlewareType.REDIS, "existingCache");
        var disabled = target("disabled", MiddlewareType.REDIS, "disabledCache");
        disabled.setEnabled(false);
        var beans = new DefaultListableBeanFactory();
        var existing = mock(RedisConnectionFactory.class);
        var inactive = mock(RedisConnectionFactory.class);
        beans.registerSingleton("existingCache", existing);
        beans.registerSingleton("disabledCache", inactive);
        var inspectedNames = new ArrayList<String>();
        ConnectionInspector inspector = (name, client) -> {
            inspectedNames.add(name);
            return Optional.of(connection(MiddlewareType.REDIS, name, client, ConnectionIdentity.forOwner(client)));
        };

        var result = new MonitoringConnectionResolver(properties(enabled, disabled), beans, List.of(inspector)).resolve();

        assertThat(inspectedNames).containsExactly("existingCache");
        assertThat(result).extracting(ResolvedTarget::reason)
                .containsExactly(ResolvedTarget.Reason.DISABLED, ResolvedTarget.Reason.CONFIGURED);
        verifyNoInteractions(existing, inactive);
    }

    @Test
    void lazyDefinitionsFactoryProductsAndLazyAopTargetsAreNeverInitialized() {
        var beans = new DefaultListableBeanFactory();
        AtomicInteger creations = new AtomicInteger();
        var lazy = new RootBeanDefinition(RedisConnectionFactory.class);
        lazy.setLazyInit(true);
        lazy.setInstanceSupplier(() -> {
            creations.incrementAndGet();
            throw new AssertionError("Lazy client must not be initialized by discovery");
        });
        beans.registerBeanDefinition("lazyRedis", lazy);
        beans.registerSingleton("factoryRedis", new FactoryBean<RedisConnectionFactory>() {
            @Override public RedisConnectionFactory getObject() {
                creations.incrementAndGet();
                throw new AssertionError("Factory product must not be requested");
            }
            @Override public Class<?> getObjectType() {
                creations.incrementAndGet();
                throw new AssertionError("Factory type must not be introspected");
            }
        });
        var proxy = new ProxyFactory();
        proxy.setInterfaces(RedisConnectionFactory.class);
        proxy.setTargetSource(new TargetSource() {
            @Override public Class<?> getTargetClass() { return RedisConnectionFactory.class; }
            @Override public boolean isStatic() { return false; }
            @Override public Object getTarget() {
                creations.incrementAndGet();
                throw new AssertionError("Lazy proxy target must not be requested");
            }
            @Override public void releaseTarget(Object target) { }
        });
        beans.registerSingleton("proxiedRedis", proxy.getProxy());
        var inspector = mock(ConnectionInspector.class);
        var properties = properties(target("lazy", MiddlewareType.REDIS, "lazyRedis"),
                target("factory", MiddlewareType.REDIS, "factoryRedis"),
                target("proxy", MiddlewareType.REDIS, "proxiedRedis"),
                target("semantic", MiddlewareType.REDIS, "redisConnectionFactory"));

        var result = new MonitoringConnectionResolver(properties, beans, List.of(inspector)).resolve();

        assertThat(result).hasSize(4).allSatisfy(value -> {
            assertThat(value.reason()).isEqualTo(SOURCE_MISSING);
            assertThat(value.display().status()).isEqualTo(CONFIGURATION_MISSING);
        });
        assertThat(creations).hasValue(0);
        assertThat(beans.containsSingleton("lazyRedis")).isFalse();
        verifyNoInteractions(inspector);
    }

    @Test
    void exactBeanNameWinsOverOtherCandidatesAndInspectionIsCachedWithinOneResolution() {
        var beans = new DefaultListableBeanFactory();
        var exact = mock(RedisConnectionFactory.class);
        var unrelated = mock(RedisConnectionFactory.class);
        beans.registerSingleton("redisConnectionFactory", exact);
        beans.registerSingleton("otherRedis", unrelated);
        AtomicInteger inspections = new AtomicInteger();
        ConnectionInspector inspector = (name, client) -> {
            inspections.incrementAndGet();
            assertThat(name).isEqualTo("redisConnectionFactory");
            assertThat(client).isSameAs(exact);
            return Optional.of(connection(MiddlewareType.REDIS, name, client, ConnectionIdentity.forOwner(client)));
        };
        var resolver = new MonitoringConnectionResolver(properties(
                target("first", MiddlewareType.REDIS, "redisConnectionFactory"),
                target("second", MiddlewareType.REDIS, "redisConnectionFactory")), beans, List.of(inspector));

        var result = resolver.resolve();

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.memberIds()).containsExactly("first", "second");
            assertThat(value.display().connectionSources()).containsExactly("redisConnectionFactory");
            assertThat(value.bindings()).hasSize(2);
        });
        assertThat(inspections).hasValue(1);
        // A later pass re-reads metadata rather than permanently caching an old client's configuration.
        resolver.resolve();
        assertThat(inspections).hasValue(2);
        verifyNoInteractions(exact, unrelated);
    }

    @ParameterizedTest
    @MethodSource("semanticSources")
    void semanticSourceAliasUsesTheOnlyExistingTypedClientAndRetainsItsRealSourceName(
            MiddlewareType type, String alias, Object client) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("runtimeClient", client);
        beans.registerSingleton("unrelatedSettings", new Object());
        var connection = connection(type, "runtimeClient", client, ConnectionIdentity.forOwner(client));

        var result = new MonitoringConnectionResolver(properties(target("middleware", type, alias)),
                beans, List.of(inspector(Map.of("runtimeClient", connection)))).resolve();

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
            assertThat(value.display().connectionSources()).containsExactly("runtimeClient");
            assertThat(value.bindings()).singleElement().satisfies(binding -> {
                assertThat(binding.declaredSource()).isEqualTo(alias);
                assertThat(binding.connection().client()).isSameAs(client);
            });
        });
        verifyNoInteractions(client);
    }

    private static Stream<Arguments> semanticSources() {
        return Stream.of(
                Arguments.of(MiddlewareType.MYSQL, "spring.datasource", mock(DataSource.class)),
                Arguments.of(MiddlewareType.REDIS, "redisConnectionFactory", mock(RedisConnectionFactory.class)),
                Arguments.of(MiddlewareType.REDIS, "redisson", mock(RedissonClient.class)),
                Arguments.of(MiddlewareType.KAFKA, "spring.kafka", mock(KafkaAdmin.class)),
                Arguments.of(MiddlewareType.MONGODB, "spring.data.mongodb", mock(MongoClient.class)),
                Arguments.of(MiddlewareType.ELASTICSEARCH, "spring.elasticsearch", mock(ElasticsearchClient.class)),
                Arguments.of(MiddlewareType.ELASTICSEARCH, "spring.elasticsearch", mock(RestClient.class)),
                Arguments.of(MiddlewareType.MINIO, "chat.attachment", mock(ChatProperties.class)));
    }

    @Test
    void elasticsearchSemanticAliasPrefersTheExistingTypedClientOverItsLowLevelTransport() {
        var beans = new DefaultListableBeanFactory();
        var typed = mock(ElasticsearchClient.class);
        var transport = mock(RestClient.class);
        beans.registerSingleton("typedElasticClient", typed);
        beans.registerSingleton("elasticTransport", transport);
        var connection = connection(MiddlewareType.ELASTICSEARCH, "typedElasticClient", typed,
                ConnectionIdentity.forOwner(typed));

        var result = new MonitoringConnectionResolver(properties(target("search", MiddlewareType.ELASTICSEARCH,
                "spring.elasticsearch")), beans,
                List.of(inspector(Map.of("typedElasticClient", connection)))).resolve();

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
            assertThat(value.display().connectionSources()).containsExactly("typedElasticClient");
        });
        verifyNoInteractions(typed, transport);
    }

    @Test
    void semanticAliasRequiresASinglePrimaryWhenMultipleClientsAlreadyExist() {
        var beans = new DefaultListableBeanFactory();
        var first = mock(DataSource.class);
        var second = mock(DataSource.class);
        registerExisting(beans, "firstDataSource", first, false);
        registerExisting(beans, "secondDataSource", second, false);
        AtomicInteger inspections = new AtomicInteger();
        ConnectionInspector inspector = (name, client) -> {
            inspections.incrementAndGet();
            return Optional.of(connection(MiddlewareType.MYSQL, name, client, ConnectionIdentity.forOwner(client)));
        };
        var resolver = new MonitoringConnectionResolver(properties(target("database", MiddlewareType.MYSQL,
                "spring.datasource")), beans, List.of(inspector));

        assertThat(resolver.resolve()).singleElement().extracting(ResolvedTarget::reason).isEqualTo(AMBIGUOUS_SOURCE);
        assertThat(inspections).hasValue(0);

        beans.getBeanDefinition("secondDataSource").setPrimary(true);
        assertThat(resolver.resolve()).singleElement().satisfies(value -> {
            assertThat(value.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
            assertThat(value.display().connectionSources()).containsExactly("secondDataSource");
        });
        assertThat(inspections).hasValue(1);

        beans.getBeanDefinition("firstDataSource").setPrimary(true);
        assertThat(resolver.resolve()).singleElement().extracting(ResolvedTarget::reason).isEqualTo(AMBIGUOUS_SOURCE);
        assertThat(inspections).hasValue(1);
        verifyNoInteractions(first, second);
    }

    @Test
    void knownSameRedisTargetMergesDisplayButKeepsEachSourcesOriginalAllowedScope() {
        var beans = new DefaultListableBeanFactory();
        var lettuce = mock(RedisConnectionFactory.class);
        var redisson = mock(RedissonClient.class);
        beans.registerSingleton("lettuceFactory", lettuce);
        beans.registerSingleton("redissonClient", redisson);
        var alpha = target("alpha-cache", MiddlewareType.REDIS, "lettuceFactory");
        var zeta = target("zeta-locks", MiddlewareType.REDIS, "redissonClient");
        setScope(alpha, "alpha");
        setScope(zeta, "zeta");
        var identityA = ConnectionIdentity.redisStandalone(lettuce, "REDIS.EXAMPLE.INVALID", 6379, 0, null, "test-secret");
        var identityB = ConnectionIdentity.redisStandalone(redisson, "redis.example.invalid", 6379, 0, "default", "test-secret");
        var inspector = inspector(Map.of(
                "lettuceFactory", connection(MiddlewareType.REDIS, "lettuceFactory", lettuce, identityA),
                "redissonClient", connection(MiddlewareType.REDIS, "redissonClient", redisson, identityB)));
        var properties = properties(zeta, alpha);
        var resolver = new MonitoringConnectionResolver(properties, beans, List.of(inspector));

        var first = resolver.resolve();

        assertThat(first).singleElement().satisfies(value -> {
            assertThat(value.display().id()).isEqualTo("alpha-cache");
            assertThat(value.display().status()).isEqualTo(CONFIGURED);
            assertThat(value.memberIds()).containsExactly("alpha-cache", "zeta-locks");
            assertThat(value.display().connectionSources()).containsExactly("lettuceFactory", "redissonClient");
            assertThat(value.bindings()).extracting(ResolvedTarget.SourceBinding::targetId)
                    .containsExactly("alpha-cache", "zeta-locks");
            assertThat(value.bindings().get(0).scope().databases()).containsExactly("alpha-db", "shared-db");
            assertThat(value.bindings().get(1).scope().databases()).containsExactly("zeta-db", "shared-db");
            assertThat(value.display().scope().databases()).containsExactly("alpha-db", "shared-db", "zeta-db");
            assertThat(value.display().scope().topics()).containsExactly("alpha-topic", "shared-topic", "zeta-topic");
            assertThat(value.display().scope().consumerGroups()).containsExactly("alpha-group", "shared-group", "zeta-group");
            assertThat(value.display().scope().buckets()).containsExactly("alpha-bucket", "shared-bucket", "zeta-bucket");
            assertThat(value.display().scope().indices()).containsExactly("alpha-index", "shared-index", "zeta-index");
        });
        properties.setTargets(new ArrayList<>(List.of(alpha, zeta)));
        assertThat(resolver.resolve()).extracting(ResolvedTarget::display).containsExactly(first.get(0).display());

        // A later configuration edit cannot broaden a previously returned source binding.
        alpha.getScope().getDatabases().add("later-db");
        assertThat(first.get(0).bindings().get(0).scope().databases()).doesNotContain("later-db");
        assertThatThrownBy(() -> first.get(0).bindings().get(0).scope().databases().add("injected-db"))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(lettuce, redisson);
    }

    @Test
    void differentCredentialsDatabasesAndUnknownRedisIdentitiesRemainSeparate() {
        var beans = new DefaultListableBeanFactory();
        var clients = List.of(mock(RedisConnectionFactory.class), mock(RedisConnectionFactory.class),
                mock(RedisConnectionFactory.class), mock(RedisConnectionFactory.class), mock(RedisConnectionFactory.class));
        var connections = new java.util.LinkedHashMap<String, ResolvedConnection>();
        var targets = new ArrayList<MonitoringProperties.Target>();
        for (int index = 0; index < clients.size(); index++) {
            String source = "cache" + index;
            Object client = clients.get(index);
            beans.registerSingleton(source, client);
            ConnectionIdentity identity = index >= 3 ? ConnectionIdentity.forOwner(client)
                    : ConnectionIdentity.redisStandalone(client, "redis.example.invalid", 6379,
                    index == 2 ? 1 : 0, "default", index == 1 ? "other-secret" : "test-secret");
            connections.put(source, connection(MiddlewareType.REDIS, source, client, identity));
            targets.add(target(source, MiddlewareType.REDIS, source));
        }

        var result = new MonitoringConnectionResolver(properties(targets.toArray(MonitoringProperties.Target[]::new)),
                beans, List.of(inspector(connections))).resolve();

        assertThat(result).hasSize(5).allSatisfy(value -> {
            assertThat(value.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
            assertThat(value.memberIds()).hasSize(1);
        });
        assertThat(result).extracting(value -> value.display().id()).containsExactly("cache0", "cache1", "cache2", "cache3", "cache4");
        clients.forEach(client -> verifyNoInteractions(client));
    }

    @Test
    void nonRedisDeclarationsStaySeparateEvenWhenTheyHaveTheSameBorrowedClient() {
        var beans = new DefaultListableBeanFactory();
        var client = mock(DataSource.class);
        beans.registerSingleton("sharedDataSource", client);
        var connection = connection(MiddlewareType.MYSQL, "sharedDataSource", client, ConnectionIdentity.forOwner(client));

        var result = new MonitoringConnectionResolver(properties(
                target("beta-database", MiddlewareType.MYSQL, "sharedDataSource"),
                target("alpha-database", MiddlewareType.MYSQL, "sharedDataSource")), beans,
                List.of(inspector(Map.of("sharedDataSource", connection)))).resolve();

        assertThat(result).hasSize(2).extracting(value -> value.display().id())
                .containsExactly("alpha-database", "beta-database");
        assertThat(result).allSatisfy(value -> assertThat(value.memberIds()).hasSize(1));
        verifyNoInteractions(client);
    }

    @Test
    void missingUnsupportedAndMetadataFailuresYieldStableReasonsWithoutExposingSecrets() throws Exception {
        var beans = new DefaultListableBeanFactory();
        var cache = mock(RedisConnectionFactory.class);
        beans.registerSingleton("unsupportedObject", new Object());
        beans.registerSingleton("existingCache", cache);
        ConnectionInspector failing = (name, client) -> {
            throw new IllegalArgumentException("redis://private-user:private-secret@private-host.invalid:6379");
        };
        var properties = properties(target("absent", MiddlewareType.REDIS, "absentSource"),
                target("blank", MiddlewareType.REDIS, null),
                target("metadata", MiddlewareType.REDIS, "existingCache"),
                target("wrong-type", MiddlewareType.REDIS, "unsupportedObject"));

        var result = new MonitoringConnectionResolver(properties, beans, List.of(failing)).resolve();

        assertThat(result).extracting(ResolvedTarget::reason)
                .containsExactly(SOURCE_MISSING, SOURCE_MISSING, UNSUPPORTED_SOURCE, UNSUPPORTED_SOURCE);
        assertThat(result).allSatisfy(value -> {
            assertThat(value.display().status()).isEqualTo(CONFIGURATION_MISSING);
            assertThat(value.bindings()).isEmpty();
        });
        String json = new ObjectMapper().writeValueAsString(new MonitoringCatalog(properties).resolvedSnapshot(result));
        assertThat(json).doesNotContain("private-user", "private-secret", "private-host", "redis://");
        verifyNoInteractions(cache);
    }

    @Test
    void publicCatalogContainsOnlyDisplayDataWhileConnectionSettingsStayServerSide() throws Exception {
        var beans = new DefaultListableBeanFactory();
        var client = mock(DataSource.class);
        beans.registerSingleton("actualDataSource", client);
        var resolvedConnection = new ResolvedConnection(MiddlewareType.MYSQL, "actualDataSource", client,
                ConnectionIdentity.forOwner(client), Map.of("url", "jdbc:mysql://private-host.invalid:3306/private_database",
                "username", "private-user", "password", "private-secret", "ssl", true));
        var properties = properties(target("main-db", MiddlewareType.MYSQL, "spring.datasource"));
        var result = new MonitoringConnectionResolver(properties, beans,
                List.of(inspector(Map.of("actualDataSource", resolvedConnection)))).resolve();

        var catalog = new MonitoringCatalog(properties).resolvedSnapshot(result);
        String json = new ObjectMapper().writeValueAsString(catalog);

        assertThat(catalog).hasSize(6);
        assertThat(catalog.stream().filter(type -> type.type() == MiddlewareType.MYSQL).findFirst().orElseThrow()
                .configuredCount()).isEqualTo(1);
        assertThat(json).contains("main-db", "actualDataSource", "CONFIGURED")
                .doesNotContain("private-secret", "private-user", "private-host", "private_database", "jdbc:",
                        "password", "settings", "bindings", "client", "identity");
        assertThat(result.get(0).bindings().get(0).connection().settings()).containsEntry("password", "private-secret");
        assertThat(result.toString()).doesNotContain("private-secret", "private-user", "private-host");
        verifyNoInteractions(client);
    }

    private static MonitoringProperties properties(MonitoringProperties.Target... targets) {
        var properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(new ArrayList<>(List.of(targets)));
        return properties;
    }

    private static MonitoringProperties.Target target(String id, MiddlewareType type, String source) {
        var target = new MonitoringProperties.Target();
        target.setId(id);
        target.setType(type);
        target.setName(id);
        target.setEnabled(true);
        target.setConnectionSource(source);
        return target;
    }

    private static void setScope(MonitoringProperties.Target target, String prefix) {
        target.getScope().setDatabases(new ArrayList<>(List.of(prefix + "-db", "shared-db")));
        target.getScope().setTopics(new ArrayList<>(List.of(prefix + "-topic", "shared-topic")));
        target.getScope().setConsumerGroups(new ArrayList<>(List.of(prefix + "-group", "shared-group")));
        target.getScope().setBuckets(new ArrayList<>(List.of(prefix + "-bucket", "shared-bucket")));
        target.getScope().setIndices(new ArrayList<>(List.of(prefix + "-index", "shared-index")));
    }

    private static ResolvedConnection connection(MiddlewareType type, String source, Object client, ConnectionIdentity identity) {
        return new ResolvedConnection(type, source, client, identity, Map.of());
    }

    private static ConnectionInspector inspector(Map<String, ResolvedConnection> connections) {
        return (source, singleton) -> Optional.ofNullable(connections.get(source));
    }

    private static void registerExisting(DefaultListableBeanFactory beans, String name, DataSource dataSource, boolean primary) {
        var definition = new RootBeanDefinition(DataSource.class);
        definition.setPrimary(primary);
        beans.registerBeanDefinition(name, definition);
        beans.registerSingleton(name, dataSource);
    }
}
