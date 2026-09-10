package com.hpj.admin.monitor.connection;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.DnsResolvers;
import io.lettuce.core.resource.SocketAddressResolver;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.DefaultCredentialsResolver;
import org.redisson.client.DefaultNettyHook;
import org.redisson.config.BaseConfig;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.config.ReplicatedServersConfig;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.redisson.connection.SequentialDnsAddressResolverFactory;
import org.redisson.misc.RedisURI;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisSocketConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.DefaultJedisSocketFactory;
import redis.clients.jedis.DefaultRedisCredentials;
import redis.clients.jedis.DefaultRedisCredentialsProvider;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisFactory;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads effective configuration only; even an unstarted factory must never be started here. */
public final class RedisConnectionInspector implements ConnectionInspector {
    @Override
    public Optional<ResolvedConnection> inspect(String source, Object singleton) {
        if (!MonitoringProperties.safeReference(source) || singleton == null || proxy(singleton)) {
            return Optional.empty();
        }
        try {
            if (singleton instanceof RedissonConnectionFactory factory) {
                Object owner = field(RedissonConnectionFactory.class, "redisson", factory);
                if (!(owner instanceof RedissonClient redisson) || proxy(owner)) return Optional.empty();
                return redisson(source, factory, redisson);
            }
            if (singleton instanceof RedissonClient redisson) return redisson(source, redisson, redisson);
            if (singleton instanceof LettuceConnectionFactory factory) {
                // getStandaloneConfiguration() also returns a default for static master/replica factories.
                Object effective = field(LettuceConnectionFactory.class, "configuration", factory);
                var client = factory.getClientConfiguration();
                Object nativeClient = field(LettuceConnectionFactory.class, "client", factory);
                if (nativeClient != null) return runningLettuce(source, factory, nativeClient, client);
                return spring(source, factory, effective, client, client.isUseSsl() || client.isStartTls(),
                        client.getRedisCredentialsProviderFactory().isPresent(),
                        client.getClientResources().isEmpty() || defaultLettuceRouting(client.getClientResources().get()));
            }
            if (singleton instanceof JedisConnectionFactory factory) {
                Object effective = field(JedisConnectionFactory.class, "configuration", factory);
                // Jedis stores its standalone configuration separately; null is its documented standalone path.
                if (effective == null) effective = factory.getStandaloneConfiguration();
                var client = factory.getClientConfiguration();
                Object pool = field(JedisConnectionFactory.class, "pool", factory);
                if (pool != null) return runningJedis(source, factory, effective, pool);
                // Without a pool, connection creation combines live host settings with an initialization-time config.
                if (factory.isRunning()) return Optional.empty();
                return spring(source, factory, effective, client, client.isUseSsl(), false, true);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Optional/unknown implementations must not break startup or echo credential-bearing errors.
        }
        return Optional.empty();
    }

    private Optional<ResolvedConnection> spring(String source, Object factory, Object effective,
                                                 Object clientConfiguration, boolean tls, boolean dynamic, boolean defaultRouting) {
        if (!(effective instanceof RedisConfiguration configuration)) return Optional.empty();
        Map<String, Object> settings = settings(configuration, clientConfiguration, tls, dynamic);
        settings.put("defaultRouting", defaultRouting);
        ConnectionIdentity identity = ConnectionIdentity.forOwner(factory);
        if (configuration instanceof RedisStandaloneConfiguration standalone) {
            if (!endpoint(standalone.getHostName(), standalone.getPort()) || standalone.getDatabase() < 0) {
                return Optional.empty();
            }
            settings.put("topology", "standalone");
            settings.put("host", standalone.getHostName());
            settings.put("port", standalone.getPort());
            settings.put("addresses", List.of(address(standalone.getHostName(), standalone.getPort(), tls)));
            if (!tls && !dynamic && defaultRouting) {
                identity = ConnectionIdentity.redisStandalone(factory, standalone.getHostName(), standalone.getPort(),
                        standalone.getDatabase(), standalone.getUsername(), password(standalone.getPassword()));
            }
        } else if (configuration instanceof RedisSentinelConfiguration sentinel) {
            if (sentinel.getMaster() == null || sentinel.getMaster().getName() == null
                    || sentinel.getMaster().getName().isBlank()) return Optional.empty();
            settings.put("topology", "sentinel");
            settings.put("masterName", sentinel.getMaster().getName());
            settings.put("addresses", nodes(sentinel.getSentinels(), tls));
        } else if (configuration instanceof RedisClusterConfiguration cluster) {
            settings.put("topology", "cluster");
            settings.put("addresses", nodes(cluster.getClusterNodes(), tls));
            settings.put("database", 0);
        } else if (configuration instanceof RedisStaticMasterReplicaConfiguration replicas) {
            settings.put("topology", "static-master-replica");
            settings.put("addresses", replicas.getNodes().stream()
                    .map(node -> address(node.getHostName(), node.getPort(), tls)).toList());
        } else if (configuration instanceof RedisSocketConfiguration socket) {
            if (socket.getSocket() == null || socket.getSocket().isBlank()) return Optional.empty();
            settings.put("topology", "socket");
            settings.put("socket", socket.getSocket());
        } else {
            return Optional.empty();
        }
        if (configuration instanceof RedisConfiguration.WithDatabaseIndex database) {
            if (database.getDatabase() < 0) return Optional.empty();
            settings.put("database", database.getDatabase());
        }
        if (configuration instanceof RedisConfiguration.WithAuthentication credentials) {
            settings.put("username", credentials.getUsername());
            settings.put("password", password(credentials.getPassword()));
        }
        if (settings.get("addresses") instanceof Collection<?> addresses && addresses.isEmpty()) return Optional.empty();
        return Optional.of(new ResolvedConnection(MiddlewareType.REDIS, source, factory, identity, settings));
    }

    private Optional<ResolvedConnection> runningLettuce(String source, LettuceConnectionFactory factory,
                                                        Object nativeClient, Object clientConfiguration)
            throws ReflectiveOperationException {
        if (proxy(nativeClient) || nativeClient.getClass() != RedisClient.class) return Optional.empty();
        Object value = field(RedisClient.class, "redisURI", nativeClient);
        if (!(value instanceof io.lettuce.core.RedisURI uri) || proxy(uri)) return Optional.empty();
        // Sentinel, socket and static replica clients require their running connection providers, outside this slice.
        if (uri.getSocket() != null || uri.getSentinelMasterId() != null || !uri.getSentinels().isEmpty()
                || !endpoint(uri.getHost(), uri.getPort()) || uri.getDatabase() < 0) return Optional.empty();
        Object resources = field(AbstractRedisClient.class, "clientResources", nativeClient);
        Object credentials = field(io.lettuce.core.RedisURI.class, "credentialsProvider", uri);
        boolean dynamic = credentials != null;
        boolean tls = uri.isSsl() || uri.isStartTls();
        boolean defaultRouting = defaultLettuceRouting(resources);
        String username = dynamic ? null : uri.getUsername();
        String password = dynamic || uri.getPassword() == null ? null : new String(uri.getPassword());
        Map<String, Object> settings = settings(uri, clientConfiguration, tls, dynamic);
        settings.put("nativeClient", nativeClient);
        settings.put("clientResources", resources);
        settings.put("credentialsProvider", credentials);
        settings.put("defaultRouting", defaultRouting);
        standaloneSettings(settings, uri.getHost(), uri.getPort(), uri.getDatabase(), username, password, tls);
        ConnectionIdentity identity = !tls && !dynamic && defaultRouting
                ? ConnectionIdentity.redisStandalone(factory, uri.getHost(), uri.getPort(), uri.getDatabase(), username, password)
                : ConnectionIdentity.forOwner(factory);
        return Optional.of(new ResolvedConnection(MiddlewareType.REDIS, source, factory, identity, settings));
    }

    private Optional<ResolvedConnection> runningJedis(String source, JedisConnectionFactory factory,
                                                      Object configuration, Object pool) throws ReflectiveOperationException {
        if (!(configuration instanceof RedisStandaloneConfiguration) || proxy(pool) || pool.getClass() != JedisPool.class) {
            return Optional.empty();
        }
        Object pooledFactory = field(GenericObjectPool.class, "factory", pool);
        if (pooledFactory == null || pooledFactory.getClass() != JedisFactory.class || proxy(pooledFactory)) return Optional.empty();
        Object socketValue = field(JedisFactory.class, "jedisSocketFactory", pooledFactory);
        Object configValue = field(JedisFactory.class, "clientConfig", pooledFactory);
        if (socketValue == null || socketValue.getClass() != DefaultJedisSocketFactory.class
                || !(configValue instanceof DefaultJedisClientConfig client) || proxy(client)) return Optional.empty();
        var socket = (DefaultJedisSocketFactory) socketValue;
        Object endpointValue = field(DefaultJedisSocketFactory.class, "hostAndPort", socket);
        if (!(endpointValue instanceof HostAndPort host) || !endpoint(host.getHost(), host.getPort())
                || client.getDatabase() < 0) return Optional.empty();
        Object provider = client.getCredentialsProvider();
        Object credentials = provider != null && provider.getClass() == DefaultRedisCredentialsProvider.class
                ? field(DefaultRedisCredentialsProvider.class, "credentials", provider) : null;
        boolean dynamic = credentials == null || credentials.getClass() != DefaultRedisCredentials.class;
        String username = dynamic ? null : ((DefaultRedisCredentials) credentials).getUser();
        char[] passwordChars = dynamic ? null : ((DefaultRedisCredentials) credentials).getPassword();
        String password = passwordChars == null ? null : new String(passwordChars);
        boolean tls = (boolean) field(DefaultJedisSocketFactory.class, "ssl", socket);
        boolean defaultRouting = field(DefaultJedisSocketFactory.class, "hostAndPortMapper", socket) == null;
        Map<String, Object> settings = settings(configuration, client, tls, dynamic);
        settings.put("pool", pool);
        settings.put("socketFactory", socket);
        settings.put("credentialsProvider", provider);
        settings.put("defaultRouting", defaultRouting);
        standaloneSettings(settings, host.getHost(), host.getPort(), client.getDatabase(), username, password, tls);
        ConnectionIdentity identity = !tls && !dynamic && defaultRouting
                ? ConnectionIdentity.redisStandalone(factory, host.getHost(), host.getPort(), client.getDatabase(), username, password)
                : ConnectionIdentity.forOwner(factory);
        return Optional.of(new ResolvedConnection(MiddlewareType.REDIS, source, factory, identity, settings));
    }

    private static boolean defaultLettuceRouting(Object resources) throws ReflectiveOperationException {
        if (resources == null || resources.getClass() != DefaultClientResources.class || proxy(resources)) return false;
        Object resolver = field(DefaultClientResources.class, "socketAddressResolver", resources);
        return resolver != null && resolver.getClass() == SocketAddressResolver.class
                && field(SocketAddressResolver.class, "dnsResolver", resolver) == DnsResolvers.UNRESOLVED
                && field(DefaultClientResources.class, "nettyCustomizer", resources) == DefaultClientResources.DEFAULT_NETTY_CUSTOMIZER
                && field(DefaultClientResources.class, "addressResolverGroup", resources) == DefaultClientResources.DEFAULT_ADDRESS_RESOLVER_GROUP;
    }

    private static void standaloneSettings(Map<String, Object> settings, String host, int port, int database,
                                           String username, String password, boolean tls) {
        settings.put("topology", "standalone");
        settings.put("host", host);
        settings.put("port", port);
        settings.put("database", database);
        settings.put("username", username);
        settings.put("password", password);
        settings.put("addresses", List.of(address(host, port, tls)));
    }

    private Optional<ResolvedConnection> redisson(String source, Object borrowedClient, RedissonClient redisson)
            throws ReflectiveOperationException {
        // getConfig() returns the caller's original mutable Config; Redisson runs with a separate copy.
        if (!(redisson instanceof Redisson implementation)) return Optional.empty();
        var serviceManager = implementation.getServiceManager();
        if (serviceManager == null || proxy(serviceManager)) return Optional.empty();
        Config configuration = serviceManager.getCfg();
        if (configuration == null || proxy(configuration)) return Optional.empty();
        ReadConfig view = new ReadConfig(configuration);
        BaseConfig<?> server;
        List<String> configuredAddresses;
        String topology;
        int database;
        if (view.single() != null) {
            server = view.single();
            configuredAddresses = List.of(view.single().getAddress());
            topology = "standalone";
            database = view.single().getDatabase();
        } else if (view.sentinel() != null) {
            server = view.sentinel();
            configuredAddresses = view.sentinel().getSentinelAddresses();
            topology = "sentinel";
            database = view.sentinel().getDatabase();
            if (view.sentinel().getMasterName() == null || view.sentinel().getMasterName().isBlank()) {
                return Optional.empty();
            }
        } else if (view.cluster() != null) {
            server = view.cluster();
            configuredAddresses = view.cluster().getNodeAddresses();
            topology = "cluster";
            database = 0;
        } else if (view.replicated() != null) {
            server = view.replicated();
            configuredAddresses = view.replicated().getNodeAddresses();
            topology = "replicated";
            database = view.replicated().getDatabase();
        } else if (view.masterSlave() != null) {
            server = view.masterSlave();
            configuredAddresses = new ArrayList<>();
            configuredAddresses.add(view.masterSlave().getMasterAddress());
            configuredAddresses.addAll(view.masterSlave().getSlaveAddresses());
            topology = "master-slave";
            database = view.masterSlave().getDatabase();
        } else {
            return Optional.empty();
        }
        if (database < 0 || configuredAddresses.isEmpty()) return Optional.empty();
        List<RedisURI> endpoints = configuredAddresses.stream().map(RedisURI::new).toList();
        if (endpoints.stream().anyMatch(uri -> !endpoint(uri.getHost(), uri.getPort()))) return Optional.empty();
        boolean tls = endpoints.stream().anyMatch(RedisURI::isSsl);
        boolean dynamic = server.getCredentialsResolver() == null
                || server.getCredentialsResolver().getClass() != DefaultCredentialsResolver.class;
        boolean defaultRouting = configuration.getNettyHook() != null
                && configuration.getNettyHook().getClass() == DefaultNettyHook.class
                && configuration.getAddressResolverGroupFactory() != null
                && configuration.getAddressResolverGroupFactory().getClass() == SequentialDnsAddressResolverFactory.class;
        Map<String, Object> settings = settings(configuration, server, tls, dynamic);
        settings.put("defaultRouting", defaultRouting);
        settings.put("redissonClient", redisson);
        settings.put("topology", topology);
        settings.put("database", database);
        settings.put("addresses", endpoints.stream().map(uri -> address(uri.getHost(), uri.getPort(), uri.isSsl())).toList());
        settings.put("username", server.getUsername());
        settings.put("password", server.getPassword());
        ConnectionIdentity identity = ConnectionIdentity.forOwner(redisson);
        if (topology.equals("standalone")) {
            RedisURI endpoint = endpoints.get(0);
            // Redisson's BaseConnectionHandler gives URI credentials precedence over resolver/config credentials.
            String username = endpoint.getUsername() != null ? endpoint.getUsername() : server.getUsername();
            String password = endpoint.getPassword() != null ? endpoint.getPassword() : server.getPassword();
            settings.put("host", endpoint.getHost());
            settings.put("port", endpoint.getPort());
            settings.put("username", username);
            settings.put("password", password);
            if (!tls && !dynamic && defaultRouting) {
                identity = ConnectionIdentity.redisStandalone(redisson, endpoint.getHost(), endpoint.getPort(),
                        database, username, password);
            }
        }
        return Optional.of(new ResolvedConnection(MiddlewareType.REDIS, source, borrowedClient, identity, settings));
    }

    private static Map<String, Object> settings(Object configuration, Object clientConfiguration, boolean tls, boolean dynamic) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("configuration", configuration);
        settings.put("clientConfiguration", clientConfiguration);
        settings.put("tls", tls);
        settings.put("dynamicCredentials", dynamic);
        return settings;
    }

    private static List<String> nodes(Collection<RedisNode> nodes, boolean tls) {
        return nodes.stream().map(node -> address(node.getHost(), node.getPort(), tls)).sorted().toList();
    }

    private static String address(String host, int port, boolean tls) {
        if (!endpoint(host, port)) throw new IllegalArgumentException("Incomplete Redis endpoint");
        return (tls ? "rediss://" : "redis://") + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host)
                + ":" + port;
    }

    private static boolean endpoint(String host, int port) {
        return host != null && !host.isBlank() && host.chars().noneMatch(Character::isWhitespace)
                && host.chars().noneMatch(Character::isISOControl) && !host.contains("/") && !host.contains("@")
                && !host.contains("?") && !host.contains("#") && port > 0 && port <= 65535;
    }

    private static String password(RedisPassword password) {
        return password == null ? null : password.toOptional().map(String::new).orElse(null);
    }

    private static boolean proxy(Object value) {
        return AopUtils.isAopProxy(value) || Proxy.isProxyClass(value.getClass());
    }

    private static Object field(Class<?> owner, String name, Object instance) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException("Redis metadata is not accessible");
        return field.get(instance);
    }

    /** Copies only configuration references into a read-only view; none of the original setters are called. */
    private static final class ReadConfig extends Config {
        ReadConfig(Config original) throws ReflectiveOperationException {
            // Config(Config) in Redisson 3.21.0 mutates original.codec when null. Do not use that constructor.
            setSingleServerConfig((SingleServerConfig) field(Config.class, "singleServerConfig", original));
            setSentinelServersConfig((SentinelServersConfig) field(Config.class, "sentinelServersConfig", original));
            setClusterServersConfig((ClusterServersConfig) field(Config.class, "clusterServersConfig", original));
            setReplicatedServersConfig((ReplicatedServersConfig) field(Config.class, "replicatedServersConfig", original));
            setMasterSlaveServersConfig((MasterSlaveServersConfig) field(Config.class, "masterSlaveServersConfig", original));
        }
        SingleServerConfig single() { return getSingleServerConfig(); }
        SentinelServersConfig sentinel() { return getSentinelServersConfig(); }
        ClusterServersConfig cluster() { return getClusterServersConfig(); }
        ReplicatedServersConfig replicated() { return getReplicatedServersConfig(); }
        MasterSlaveServersConfig masterSlave() { return getMasterSlaveServersConfig(); }
    }
}
