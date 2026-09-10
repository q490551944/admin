package com.hpj.admin.monitor.connection;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.alibaba.druid.pool.DruidDataSource;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.internal.MongoClientImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.CommonClientConfigs;
import org.elasticsearch.client.RestClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Reads metadata from known, already-created sources without borrowing a connection or unwrapping proxies. */
public final class StandardConnectionInspector implements ConnectionInspector {
    @Override
    public Optional<ResolvedConnection> inspect(String source, Object singleton) {
        if (!MonitoringProperties.safeReference(source) || singleton == null
                || AopUtils.isAopProxy(singleton) || Proxy.isProxyClass(singleton.getClass())) {
            return Optional.empty();
        }
        try {
            if (singleton instanceof HikariDataSource dataSource) {
                // An explicitly supplied DataSource wins over jdbcUrl; do not mistake the unused URL for the target.
                if (dataSource.getDataSource() != null || present(dataSource.getDataSourceClassName())) return Optional.empty();
                Properties driver = dataSource.getDataSourceProperties();
                return mysql(source, singleton, dataSource.getJdbcUrl(),
                        driver.getProperty("user", dataSource.getUsername()),
                        driver.getProperty("password", dataSource.getPassword()), driver, Map.of());
            }
            if (singleton instanceof DruidDataSource dataSource) {
                Properties driver = dataSource.getConnectProperties();
                var userCallback = dataSource.getUserCallback();
                var passwordCallback = dataSource.getPasswordCallback();
                Map<String, Object> callbacks = new LinkedHashMap<>();
                if (userCallback != null) callbacks.put("usernameCallback", userCallback);
                if (passwordCallback != null) callbacks.put("passwordCallback", passwordCallback);
                String username = userCallback == null ? druidCredential(dataSource.getUsername(), driver, "user") : null;
                String password = passwordCallback == null ? druidCredential(dataSource.getPassword(), driver, "password") : null;
                return mysql(source, singleton, dataSource.getUrl(), username, password, driver, callbacks);
            }
            if (singleton instanceof KafkaAdmin admin) {
                return kafka(source, singleton, admin.getConfigurationProperties());
            }
            if (singleton instanceof DefaultKafkaProducerFactory<?, ?> producer) {
                return kafka(source, singleton, producer.getConfigurationProperties());
            }
            if (singleton instanceof DefaultKafkaConsumerFactory<?, ?> consumer) {
                return kafka(source, singleton, consumer.getConfigurationProperties());
            }
            if (singleton instanceof MongoClientImpl mongo) return mongo(source, mongo);
            if (singleton instanceof ElasticsearchClient elasticsearch) {
                if (!(elasticsearch._transport() instanceof RestClientTransport transport)
                        || AopUtils.isAopProxy(transport)) return Optional.empty();
                return elasticsearch(source, singleton, transport.restClient(), transport);
            }
            if (singleton instanceof RestClient restClient) return elasticsearch(source, singleton, restClient, null);
            if (singleton instanceof ChatProperties chat) return minio(source, chat);
        } catch (RuntimeException ignored) {
            // Metadata can contain credentials. A failed inspection is missing evidence, not a loggable raw exception.
        }
        return Optional.empty();
    }

    private Optional<ResolvedConnection> mysql(String source, Object client, String endpoint,
                                                String username, String password, Properties properties,
                                                Map<String, Object> references) {
        if (!present(endpoint) || !endpoint.startsWith("jdbc:mysql:")) return Optional.empty();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("endpoint", endpoint);
        settings.put("username", username);
        settings.put("password", password);
        settings.put("properties", copyValue(properties));
        settings.putAll(references);
        return resolved(MiddlewareType.MYSQL, source, client, settings);
    }

    private Optional<ResolvedConnection> kafka(String source, Object client, Map<String, Object> properties) {
        if (properties == null || !hasBootstrapServers(properties.get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG))) {
            return Optional.empty();
        }
        // The factory getter applies its current bootstrap supplier. Keep native Kafka property names intact.
        Map<String, Object> settings = new LinkedHashMap<>();
        properties.forEach((key, value) -> settings.put(key, copyValue(value)));
        return resolved(MiddlewareType.KAFKA, source, client, settings);
    }

    private Optional<ResolvedConnection> mongo(String source, MongoClientImpl client) {
        MongoClientSettings effective = client.getSettings();
        if (effective == null) return Optional.empty();
        var cluster = effective.getClusterSettings();
        if (!present(cluster.getSrvHost()) && cluster.getHosts().isEmpty()) return Optional.empty();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("mongoSettings", effective);
        settings.put("nodes", List.copyOf(cluster.getHosts()));
        settings.put("srvHost", cluster.getSrvHost());
        settings.put("tls", effective.getSslSettings());
        settings.put("credential", effective.getCredential());
        if (effective.getCredential() != null) {
            settings.put("username", effective.getCredential().getUserName());
            settings.put("authenticationDatabase", effective.getCredential().getSource());
        }
        return resolved(MiddlewareType.MONGODB, source, client, settings);
    }

    private Optional<ResolvedConnection> elasticsearch(String source, Object client, RestClient restClient,
                                                       RestClientTransport transport) {
        if (restClient == null || AopUtils.isAopProxy(restClient)) return Optional.empty();
        var nodes = restClient.getNodes();
        if (nodes == null || nodes.isEmpty()) return Optional.empty();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("nodes", List.copyOf(nodes));
        // Reuse the exact client/transport: TLS, authentication and request options are not inferred from node addresses.
        settings.put("restClient", restClient);
        if (transport != null) settings.put("transport", transport);
        return resolved(MiddlewareType.ELASTICSEARCH, source, client, settings);
    }

    private Optional<ResolvedConnection> minio(String source, ChatProperties chat) {
        if (!chat.isEnabled()) return Optional.empty();
        var attachment = chat.getAttachment();
        if (attachment == null || !present(attachment.getEndpoint()) || !present(attachment.getAccessKey())
                || !present(attachment.getSecretKey()) || !present(attachment.getBucket())) return Optional.empty();
        String endpoint = attachment.getEndpoint();
        if (attachment.isSecure()) {
            if (endpoint.regionMatches(true, 0, "http://", 0, 7)) endpoint = "https://" + endpoint.substring(7);
            else if (!endpoint.contains("://")) endpoint = "https://" + endpoint;
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("endpoint", endpoint);
        settings.put("accessKey", attachment.getAccessKey());
        settings.put("secretKey", attachment.getSecretKey());
        settings.put("bucket", attachment.getBucket());
        settings.put("secure", attachment.isSecure());
        return resolved(MiddlewareType.MINIO, source, chat, settings);
    }

    private Optional<ResolvedConnection> resolved(MiddlewareType type, String source, Object client,
                                                   Map<String, Object> settings) {
        return Optional.of(new ResolvedConnection(type, source, client, ConnectionIdentity.forOwner(client), settings));
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    private static String druidCredential(String configured, Properties properties, String key) {
        return configured != null && !configured.isEmpty() ? configured : properties == null ? null : properties.getProperty(key);
    }

    private static boolean hasBootstrapServers(Object value) {
        if (value instanceof String text) {
            return !text.isBlank() && List.of(text.split(",", -1)).stream().allMatch(StandardConnectionInspector::present);
        }
        if (value instanceof Collection<?> values) {
            return !values.isEmpty() && values.stream().allMatch(entry -> entry instanceof String text && present(text));
        }
        return false;
    }

    /** Copy mutable containers while retaining opaque authentication/TLS callback references on the server. */
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(key, copyValue(entry)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(entry -> copy.add(copyValue(entry)));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>();
            collection.forEach(entry -> copy.add(copyValue(entry)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            for (int i = 0; i < length; i++) Array.set(copy, i, copyValue(Array.get(value, i)));
            return copy;
        }
        return value;
    }
}
