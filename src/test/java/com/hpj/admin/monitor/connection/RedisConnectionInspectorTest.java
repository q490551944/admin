package com.hpj.admin.monitor.connection;

import com.hpj.admin.monitor.MiddlewareType;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolvers;
import io.lettuce.core.resource.SocketAddressResolver;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.NettyHook;
import org.redisson.config.Config;
import org.redisson.config.CredentialsResolver;
import org.redisson.connection.ServiceManager;
import org.redisson.connection.AddressResolverGroupFactory;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisSocketConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.DefaultJedisSocketFactory;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.HostAndPortMapper;
import redis.clients.jedis.JedisFactory;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RedisConnectionInspectorTest {
    private final RedisConnectionInspector inspector = new RedisConnectionInspector();

    @Test
    void readsEffectiveFactoryConfigurationWithoutStartingOrConnecting() {
        RedisStandaloneConfiguration configuration = standalone("overridden.example", 6381, 4, "reader", "private-password");
        LettuceConnectionFactory factory = spy(new LettuceConnectionFactory(configuration));
        ResolvedConnection resolved = inspect("customFactory", factory);

        assertThat(resolved.type()).isEqualTo(MiddlewareType.REDIS);
        assertThat(resolved.source()).isEqualTo("customFactory");
        assertThat(resolved.client()).isSameAs(factory);
        assertThat(resolved.settings()).containsEntry("host", "overridden.example").containsEntry("port", 6381)
                .containsEntry("database", 4).containsEntry("username", "reader")
                .containsEntry("password", "private-password").containsEntry("tls", false)
                .containsEntry("addresses", List.of("redis://overridden.example:6381"));
        assertThat(resolved.settings().get("configuration")).isSameAs(configuration);
        assertThat(resolved.settings().get("clientConfiguration")).isSameAs(factory.getClientConfiguration());
        assertThat(resolved.toString()).doesNotContain("private-password", "overridden.example");
        assertThat(resolved.identity().toString()).doesNotContain("private-password", "overridden.example");
        verify(factory, never()).start();
        verify(factory, never()).afterPropertiesSet();
        verify(factory, never()).getConnection();
    }

    @Test
    void unstartedJedisAndLettuceFactoriesAgreeOnlyForEquivalentPlaintextIdentity() {
        RedisStandaloneConfiguration jedisConfiguration = standalone("CACHE.example", 6379, 2, "reader", "same-secret");
        JedisConnectionFactory jedis = spy(new JedisConnectionFactory(jedisConfiguration));
        LettuceConnectionFactory lettuce = new LettuceConnectionFactory(standalone("cache.example", 6379, 2, "reader", "same-secret"));

        ResolvedConnection first = inspect("jedis", jedis);
        ResolvedConnection second = inspect("lettuce", lettuce);
        assertThat(first.identity().sameAs(second.identity())).isTrue();
        assertThat(second.identity().sameAs(first.identity())).isTrue();
        assertThat(first.settings().get("configuration")).isSameAs(jedisConfiguration);
        verify(jedis, never()).start();
        verify(jedis, never()).afterPropertiesSet();
        verify(jedis, never()).getConnection();
    }

    @ParameterizedTest
    @CsvSource({"different.example,6379,2,reader,same-secret", "cache.example,6380,2,reader,same-secret",
            "cache.example,6379,3,reader,same-secret", "cache.example,6379,2,other-reader,same-secret",
            "cache.example,6379,2,reader,other-secret"})
    void differentEndpointDatabaseOrCredentialsCannotMerge(String host, int port, int database, String username, String password) {
        var baseline = inspect("baseline", new LettuceConnectionFactory(standalone("cache.example", 6379, 2, "reader", "same-secret")));
        var different = inspect("different", new JedisConnectionFactory(standalone(host, port, database, username, password)));
        assertThat(baseline.identity().sameAs(different.identity())).isFalse();
    }

    @Test
    void reproducesRedissonDefaultMetadataWithoutCreatingRedissonOrChangingItsConfig() {
        // This is the configuration constructed by Redisson 3.21.0's no-argument create(), without starting it.
        Config config = new Config();
        var originalSingle = config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        assertThat(config.getCodec()).isNull();
        RedissonClient client = redisson(config);

        var resolved = inspect("redisson", client);
        var matchingFactory = inspect("redisFactory", new LettuceConnectionFactory("127.0.0.1", 6379));
        assertThat(resolved.identity().sameAs(matchingFactory.identity())).isTrue();
        assertThat(resolved.settings()).containsEntry("database", 0).containsEntry("tls", false)
                .containsEntry("host", "127.0.0.1").containsEntry("port", 6379);
        assertThat(resolved.settings().get("configuration")).isSameAs(config);
        assertThat(config.getCodec()).isNull();
        assertThat(config.useSingleServer()).isSameAs(originalSingle);
        assertThat(originalSingle.getAddress()).isEqualTo("redis://127.0.0.1:6379");
        assertThat(originalSingle.getDatabase()).isZero();
        verify((Redisson) client).getServiceManager();
        verifyNoMoreInteractions(client);
    }

    @Test
    void readsRunningRedissonCopyInsteadOfTheCallersLaterChangedConfig() {
        Config running = new Config();
        running.useSingleServer().setAddress("redis://running.example:6379").setDatabase(2).setPassword("active-secret");
        Config original = new Config();
        original.useSingleServer().setAddress("redis://later-change.example:6380").setDatabase(4).setPassword("ignored-secret");
        Redisson client = (Redisson) redisson(running);
        when(client.getConfig()).thenReturn(original);

        var result = inspect("redisson", client);
        assertThat(result.settings()).containsEntry("host", "running.example").containsEntry("database", 2)
                .containsEntry("password", "active-secret");
        assertThat(result.settings().get("configuration")).isSameAs(running);
        verify(client, never()).getConfig();
        assertThat(running.getCodec()).isNull();
        assertThat(original.getCodec()).isNull();
    }

    @Test
    void readsExistingLettuceNativeUriAfterBoundConfigurationChangesWithoutCallingClientGetters() throws Exception {
        var bound = standalone("original.example", 6379, 2, "reader", "active-secret");
        var factory = spy(new LettuceConnectionFactory(bound));
        RedisURI running = RedisURI.Builder.redis("original.example", 6379).withDatabase(2)
                .withAuthentication("reader", "active-secret").build();
        RedisClient nativeClient = attachLettuce(factory, running, defaultResources());
        bound.setHostName("later-change.example");
        bound.setPort(6381);
        bound.setDatabase(7);
        bound.setUsername("ignored-user");
        bound.setPassword("ignored-secret");

        var result = inspect("lettuce", factory);
        var equivalent = inspect("equivalent", new LettuceConnectionFactory(
                standalone("original.example", 6379, 2, "reader", "active-secret")));
        assertThat(result.settings()).containsEntry("host", "original.example").containsEntry("port", 6379)
                .containsEntry("database", 2).containsEntry("username", "reader").containsEntry("password", "active-secret")
                .containsEntry("configuration", running).containsEntry("nativeClient", nativeClient);
        assertThat(result.identity().sameAs(equivalent.identity())).isTrue();
        verifyNoInteractions(nativeClient);
        verify(factory, never()).getNativeClient();
        verify(factory, never()).getConnection();
        verify(factory, never()).start();
        assertThat(bound.getHostName()).isEqualTo("later-change.example");
        assertThat(running.getHost()).isEqualTo("original.example");
    }

    @Test
    void readsPooledJedisSnapshotAfterBoundConfigurationChangesWithoutBorrowingAConnection() throws Exception {
        var bound = standalone("original.example", 6379, 2, "reader", "active-secret");
        var factory = spy(new JedisConnectionFactory(bound));
        var running = DefaultJedisClientConfig.builder().database(2).user("reader").password("active-secret").build();
        JedisPool pool = attachJedis(factory, "original.example", 6379, running);
        bound.setHostName("later-change.example");
        bound.setPort(6381);
        bound.setDatabase(7);
        bound.setPassword("ignored-secret");

        var result = inspect("jedis", factory);
        var equivalent = inspect("equivalent", new LettuceConnectionFactory(
                standalone("original.example", 6379, 2, "reader", "active-secret")));
        assertThat(result.settings()).containsEntry("host", "original.example").containsEntry("port", 6379)
                .containsEntry("database", 2).containsEntry("password", "active-secret").containsEntry("clientConfiguration", running);
        assertThat(result.identity().sameAs(equivalent.identity())).isTrue();
        verifyNoInteractions(pool);
        verify(factory, never()).getConnection();
        verify(factory, never()).start();
    }

    @Test
    void unknownRuntimeTopologyDoesNotFallBackToTheMutableStandaloneConfiguration() throws Exception {
        var factory = new LettuceConnectionFactory("misleading.example", 6379);
        attachLettuce(factory, RedisURI.Builder.sentinel("sentinel.example", 26379, "master").build(), defaultResources());
        assertThat(inspector.inspect("runtimeSentinel", factory)).isEmpty();
        var noPool = mock(JedisConnectionFactory.class);
        when(noPool.isRunning()).thenReturn(true);
        assertThat(inspector.inspect("runtimeWithoutPool", noPool)).isEmpty();
        verify(noPool, never()).getConnection();
    }

    @Test
    void customRoutingKeepsIndependentOwnersWithoutCallingRoutingCallbacks() throws Exception {
        var bound = new RedisStandaloneConfiguration("cache.example", 6379);
        var ordinary = inspect("ordinary", new LettuceConnectionFactory(bound));
        ClientResources unknownResources = mock(ClientResources.class);
        var unknown = inspect("unknown", new LettuceConnectionFactory(bound,
                LettuceClientConfiguration.builder().clientResources(unknownResources).build()));
        assertThat(unknown.identity().sameAs(ordinary.identity())).isFalse();
        verifyNoInteractions(unknownResources);

        var resources = defaultResources();
        var socketResolver = mock(SocketAddressResolver.class);
        set(DefaultClientResources.class, "socketAddressResolver", resources, socketResolver);
        var routed = inspect("routed", new LettuceConnectionFactory(bound,
                LettuceClientConfiguration.builder().clientResources(resources).build()));
        assertThat(routed.settings()).containsEntry("defaultRouting", false);
        assertThat(routed.identity().sameAs(ordinary.identity())).isFalse();
        verifyNoInteractions(socketResolver, resources);

        Config hooked = new Config();
        hooked.useSingleServer().setAddress("redis://cache.example:6379");
        NettyHook hook = mock(NettyHook.class);
        hooked.setNettyHook(hook);
        assertThat(inspect("hooked", redisson(hooked)).identity().sameAs(ordinary.identity())).isFalse();
        Config rerouted = new Config();
        rerouted.useSingleServer().setAddress("redis://cache.example:6379");
        AddressResolverGroupFactory groupFactory = mock(AddressResolverGroupFactory.class);
        rerouted.setAddressResolverGroupFactory(groupFactory);
        assertThat(inspect("rerouted", redisson(rerouted)).identity().sameAs(ordinary.identity())).isFalse();
        verifyNoInteractions(hook, groupFactory);
    }

    @Test
    void runtimeDynamicCredentialsTlsAndJedisMapperCannotMergeOrInvokeProviders() throws Exception {
        var ordinary = inspect("ordinary", new LettuceConnectionFactory("cache.example", 6379));
        var factory = new LettuceConnectionFactory("cache.example", 6379);
        var uri = RedisURI.Builder.redis("cache.example", 6379).build();
        var credentials = mock(io.lettuce.core.RedisCredentialsProvider.class);
        uri.setCredentialsProvider(credentials);
        attachLettuce(factory, uri, defaultResources());
        var dynamic = inspect("dynamic", factory);
        assertThat(dynamic.settings()).containsEntry("dynamicCredentials", true).containsEntry("credentialsProvider", credentials);
        assertThat(dynamic.identity().sameAs(ordinary.identity())).isFalse();
        verifyNoInteractions(credentials);

        var tlsFactory = new LettuceConnectionFactory("cache.example", 6379);
        attachLettuce(tlsFactory, RedisURI.Builder.redis("cache.example", 6379).withSsl(true).build(), defaultResources());
        var tls = inspect("tls", tlsFactory);
        assertThat(tls.settings()).containsEntry("tls", true);
        assertThat(tls.identity().sameAs(ordinary.identity())).isFalse();

        HostAndPortMapper mapper = mock(HostAndPortMapper.class);
        var jedis = new JedisConnectionFactory(new RedisStandaloneConfiguration("cache.example", 6379));
        attachJedis(jedis, "cache.example", 6379, DefaultJedisClientConfig.builder().hostAndPortMapper(mapper).build());
        assertThat(inspect("mappedJedis", jedis).identity().sameAs(ordinary.identity())).isFalse();
        verifyNoInteractions(mapper);

        @SuppressWarnings("unchecked") Supplier<redis.clients.jedis.RedisCredentials> provider = mock(Supplier.class);
        var dynamicJedis = new JedisConnectionFactory(new RedisStandaloneConfiguration("cache.example", 6379));
        attachJedis(dynamicJedis, "cache.example", 6379, DefaultJedisClientConfig.builder().credentialsProvider(provider).build());
        var jedisResult = inspect("dynamicJedis", dynamicJedis);
        assertThat(jedisResult.settings()).containsEntry("dynamicCredentials", true).containsEntry("credentialsProvider", provider);
        assertThat(jedisResult.identity().sameAs(ordinary.identity())).isFalse();
        verifyNoInteractions(provider);
    }

    @Test
    void redissonUriCredentialsOverrideConfigCredentialsLikeTheActualHandshake() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://uri-user:uri-secret@cache.example:6379")
                .setUsername("ignored-user").setPassword("ignored-secret").setDatabase(3);
        var resolved = inspect("redisson", redisson(config));
        var matching = inspect("factory", new LettuceConnectionFactory(standalone("cache.example", 6379, 3, "uri-user", "uri-secret")));
        assertThat(resolved.settings()).containsEntry("username", "uri-user").containsEntry("password", "uri-secret")
                .containsEntry("addresses", List.of("redis://cache.example:6379"));
        assertThat(resolved.identity().sameAs(matching.identity())).isTrue();
        assertThat(resolved.toString()).doesNotContain("uri-user", "uri-secret", "ignored-secret");
        assertThat(config.useSingleServer().getPassword()).isEqualTo("ignored-secret");
    }

    @Test
    void recognizesSharedRedissonWrapperAndDifferentWrapperOwners() {
        Config sharedConfig = new Config();
        sharedConfig.useSingleServer().setAddress("rediss://cache.example:6380").setPassword("secret");
        RedissonClient shared = redisson(sharedConfig);
        RedissonConnectionFactory wrapper = new RedissonConnectionFactory(shared);
        var direct = inspect("redisson", shared);
        var wrapped = inspect("redisConnectionFactory", wrapper);

        assertThat(wrapped.client()).isSameAs(wrapper);
        assertThat(wrapped.settings().get("redissonClient")).isSameAs(shared);
        assertThat(wrapped.identity().sameAs(direct.identity())).isTrue();
        var independent = inspect("separateFactory", new RedissonConnectionFactory(redisson(sharedConfig)));
        assertThat(independent.identity().sameAs(direct.identity())).isFalse();
        assertThat(inspector.inspect("notInitialized", new RedissonConnectionFactory(new Config()))).isEmpty();
    }

    @Test
    void tlsAndDynamicCredentialsKeepIndependentOwnersWithoutResolvingCredentials() {
        var config = standalone("cache.example", 6379, 0, "reader", "secret");
        var plaintext = inspect("plain", new LettuceConnectionFactory(config));
        var ssl = LettuceClientConfiguration.builder().useSsl().build();
        var tls = inspect("tls", new LettuceConnectionFactory(config, ssl));
        var sameTlsConfig = inspect("otherTls", new LettuceConnectionFactory(config, ssl));
        var jedisTls = inspect("jedisTls", new JedisConnectionFactory(config, JedisClientConfiguration.builder().useSsl().build()));
        assertThat(tls.identity().sameAs(plaintext.identity())).isFalse();
        assertThat(tls.identity().sameAs(sameTlsConfig.identity())).isFalse();
        assertThat(tls.identity().sameAs(jedisTls.identity())).isFalse();

        RedisCredentialsProviderFactory provider = mock(RedisCredentialsProviderFactory.class);
        var dynamicClient = LettuceClientConfiguration.builder().redisCredentialsProviderFactory(provider).build();
        var dynamic = inspect("dynamic", new LettuceConnectionFactory(config, dynamicClient));
        assertThat(dynamic.settings()).containsEntry("dynamicCredentials", true);
        assertThat(dynamic.identity().sameAs(plaintext.identity())).isFalse();
        verifyNoInteractions(provider);

        Config redissonConfig = new Config();
        CredentialsResolver resolver = mock(CredentialsResolver.class);
        redissonConfig.useSingleServer().setAddress("redis://cache.example:6379")
                .setUsername("reader").setPassword("secret").setCredentialsResolver(resolver);
        var redissonDynamic = inspect("redissonDynamic", redisson(redissonConfig));
        assertThat(redissonDynamic.settings()).containsEntry("dynamicCredentials", true);
        assertThat(redissonDynamic.identity().sameAs(plaintext.identity())).isFalse();
        verifyNoInteractions(resolver);
    }

    @Test
    void sentinelClusterAndStaticReplicaMetadataNeverFallBackToStandaloneDefaults() {
        RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration().master("project-master")
                .sentinel("sentinel.example", 26379);
        sentinel.setDatabase(6);
        sentinel.setUsername("data-reader");
        sentinel.setPassword("data-secret");
        sentinel.setSentinelPassword("sentinel-secret");
        var sentinelResult = inspect("sentinel", new LettuceConnectionFactory(sentinel));
        assertThat(sentinelResult.settings()).containsEntry("topology", "sentinel").containsEntry("database", 6)
                .containsEntry("masterName", "project-master").containsEntry("addresses", List.of("redis://sentinel.example:26379"));
        assertThat(sentinelResult.settings().get("configuration")).isSameAs(sentinel);
        assertThat(sentinelResult.settings()).doesNotContainKey("host");

        RedisClusterConfiguration cluster = new RedisClusterConfiguration(List.of("cluster.example:7001", "cluster.example:7002"));
        var clusterResult = inspect("cluster", new JedisConnectionFactory(cluster));
        assertThat(clusterResult.settings()).containsEntry("topology", "cluster").containsEntry("database", 0)
                .containsEntry("addresses", List.of("redis://cluster.example:7001", "redis://cluster.example:7002"));
        assertThat(clusterResult.identity().sameAs(inspect("secondCluster", new JedisConnectionFactory(cluster)).identity())).isFalse();

        RedisStaticMasterReplicaConfiguration replicas = new RedisStaticMasterReplicaConfiguration("primary.example", 6379)
                .node("replica.example", 6380);
        replicas.setDatabase(4);
        var replicaResult = inspect("replicas", new LettuceConnectionFactory(replicas));
        assertThat(replicaResult.settings()).containsEntry("topology", "static-master-replica").containsEntry("database", 4)
                .containsEntry("addresses", List.of("redis://primary.example:6379", "redis://replica.example:6380"));
        assertThat(replicaResult.settings()).doesNotContainKey("host");
    }

    @Test
    void socketAndRedissonMultiNodeConfigurationsRetainTheirActualSettings() {
        RedisSocketConfiguration socket = new RedisSocketConfiguration("/run/project/redis.sock");
        socket.setDatabase(7);
        var socketResult = inspect("socket", new LettuceConnectionFactory(socket));
        assertThat(socketResult.settings()).containsEntry("topology", "socket").containsEntry("database", 7)
                .containsEntry("socket", "/run/project/redis.sock").doesNotContainKey("host");

        Config sentinel = new Config();
        sentinel.useSentinelServers().setMasterName("main").setDatabase(5)
                .addSentinelAddress("rediss://sentinel.example:26379").setSentinelPassword("sentinel-secret");
        Config cluster = new Config();
        cluster.useClusterServers().addNodeAddress("redis://cluster.example:7001");
        Config replicated = new Config();
        replicated.useReplicatedServers().addNodeAddress("redis://replica.example:6379").setDatabase(8);
        Config masterSlave = new Config();
        masterSlave.useMasterSlaveServers().setMasterAddress("redis://primary.example:6379")
                .addSlaveAddress("redis://replica.example:6379").setDatabase(9);
        for (Config config : List.of(sentinel, cluster, replicated, masterSlave)) {
            var resolved = inspect("redisson", redisson(config));
            assertThat(resolved.settings().get("configuration")).isSameAs(config);
            assertThat((List<?>) resolved.settings().get("addresses")).isNotEmpty();
            assertThat(resolved.identity().sameAs(inspect("other", redisson(config)).identity())).isFalse();
            assertThat(config.getCodec()).isNull();
        }
    }

    @Test
    void rejectsUnknownIncompleteAndUnsafeSourcesWithoutSurfacingCredentialErrors() {
        assertThat(inspector.inspect("unknown", new Object())).isEmpty();
        assertThat(inspector.inspect("unknown", null)).isEmpty();
        assertThat(inspector.inspect("redis://user:private-secret@host", new LettuceConnectionFactory())).isEmpty();
        assertThat(inspector.inspect("empty", redisson(new Config()))).isEmpty();
        assertThat(inspector.inspect("cluster", new LettuceConnectionFactory(new RedisClusterConfiguration()))).isEmpty();
        Config malformed = new Config();
        malformed.useSingleServer().setAddress("redis://user:private-secret@host");
        assertThatCode(() -> assertThat(inspector.inspect("malformed", redisson(malformed))).isEmpty())
                .doesNotThrowAnyException();
        Redisson broken = mock(Redisson.class);
        when(broken.getServiceManager()).thenThrow(new IllegalStateException("private-secret"));
        assertThatCode(() -> assertThat(inspector.inspect("broken", broken)).isEmpty()).doesNotThrowAnyException();
        RedissonClient unknownImplementation = mock(RedissonClient.class);
        assertThat(inspector.inspect("unknownRedisson", unknownImplementation)).isEmpty();
        verifyNoInteractions(unknownImplementation);
    }

    @Test
    void neverUnwrapsOrInvokesLazyAndJdkProxies() {
        AtomicInteger resolutions = new AtomicInteger();
        ProxyFactory factory = new ProxyFactory();
        factory.setInterfaces(RedissonClient.class);
        factory.setTargetSource(new TargetSource() {
            @Override public Class<?> getTargetClass() { return RedissonClient.class; }
            @Override public boolean isStatic() { return false; }
            @Override public Object getTarget() { resolutions.incrementAndGet(); throw new AssertionError("Lazy client initialized"); }
            @Override public void releaseTarget(Object target) { throw new AssertionError("Unexpected target release"); }
        });
        assertThat(inspector.inspect("lazyRedisson", factory.getProxy())).isEmpty();
        assertThat(resolutions).hasValue(0);
        Object jdk = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RedisConnectionFactory.class},
                (proxy, method, arguments) -> { throw new AssertionError("Proxy invoked"); });
        assertThat(inspector.inspect("jdkFactory", jdk)).isEmpty();
    }

    private ResolvedConnection inspect(String source, Object singleton) {
        return inspector.inspect(source, singleton).orElseThrow(() -> new AssertionError("Expected resolved source " + source));
    }

    private static RedisStandaloneConfiguration standalone(String host, int port, int database, String username, String password) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        configuration.setDatabase(database);
        configuration.setUsername(username);
        configuration.setPassword(password);
        return configuration;
    }

    private static RedissonClient redisson(Config config) {
        Redisson client = mock(Redisson.class);
        ServiceManager manager = mock(ServiceManager.class);
        when(manager.getCfg()).thenReturn(config);
        when(client.getServiceManager()).thenReturn(manager);
        return client;
    }

    private static RedisClient attachLettuce(LettuceConnectionFactory factory, RedisURI uri, Object resources) throws Exception {
        RedisClient nativeClient = mock(RedisClient.class);
        set(RedisClient.class, "redisURI", nativeClient, uri);
        set(AbstractRedisClient.class, "clientResources", nativeClient, resources);
        set(LettuceConnectionFactory.class, "client", factory, nativeClient);
        return nativeClient;
    }

    private static JedisPool attachJedis(JedisConnectionFactory factory, String host, int port,
                                         DefaultJedisClientConfig configuration) throws Exception {
        JedisPool pool = mock(JedisPool.class);
        JedisFactory pooledFactory = mock(JedisFactory.class);
        set(JedisFactory.class, "jedisSocketFactory", pooledFactory,
                new DefaultJedisSocketFactory(new HostAndPort(host, port), configuration));
        set(JedisFactory.class, "clientConfig", pooledFactory, configuration);
        set(GenericObjectPool.class, "factory", pool, pooledFactory);
        set(JedisConnectionFactory.class, "pool", factory, pool);
        return pool;
    }

    private static DefaultClientResources defaultResources() throws Exception {
        // Metadata-only test doubles: constructing real client resources would create executors/timers.
        DefaultClientResources resources = mock(DefaultClientResources.class);
        set(DefaultClientResources.class, "socketAddressResolver", resources, SocketAddressResolver.create(DnsResolvers.UNRESOLVED));
        set(DefaultClientResources.class, "nettyCustomizer", resources, DefaultClientResources.DEFAULT_NETTY_CUSTOMIZER);
        set(DefaultClientResources.class, "addressResolverGroup", resources, DefaultClientResources.DEFAULT_ADDRESS_RESOLVER_GROUP);
        return resources;
    }

    private static void set(Class<?> owner, String name, Object target, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
