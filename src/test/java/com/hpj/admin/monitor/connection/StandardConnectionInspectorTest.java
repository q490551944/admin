package com.hpj.admin.monitor.connection;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.hpj.admin.chat.MinioChatObjectStorage;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.internal.MongoClientImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.http.HttpHost;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.elasticsearch.client.Node;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.LazyInitTargetSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaResourceFactory;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StandardConnectionInspectorTest {
    private final StandardConnectionInspector inspector = new StandardConnectionInspector();

    @Test
    void hikariReadsEffectiveUrlAndDriverCredentialOverridesWithoutStartingPool() {
        NoConnectionHikari dataSource = new NoConnectionHikari();
        dataSource.setJdbcUrl("jdbc:mysql://old.example.invalid:3306/original");
        dataSource.setJdbcUrl("jdbc:mysql://effective.example.invalid:3306/current?sslMode=VERIFY_IDENTITY");
        dataSource.setUsername("outer-user");
        dataSource.setPassword("outer-password");
        dataSource.addDataSourceProperty("user", "driver-user");
        dataSource.addDataSourceProperty("password", "driver-secret");
        dataSource.addDataSourceProperty("serverTimezone", "UTC");

        ResolvedConnection resolved = inspector.inspect("primaryDataSource", dataSource).orElseThrow();
        assertThat(resolved.type()).isEqualTo(MiddlewareType.MYSQL);
        assertThat(resolved.client()).isSameAs(dataSource);
        assertThat(resolved.settings()).containsEntry("endpoint", dataSource.getJdbcUrl())
                .containsEntry("username", "driver-user").containsEntry("password", "driver-secret");
        assertThat(((Map<?, ?>) resolved.settings().get("properties")).containsKey("serverTimezone")).isTrue();
        dataSource.addDataSourceProperty("serverTimezone", "Asia/Shanghai");
        assertThat(((Map<?, ?>) resolved.settings().get("properties")).get("serverTimezone")).isEqualTo("UTC");
        assertThat(dataSource.getHikariPoolMXBean()).isNull();
        assertThat(dataSource.isClosed()).isFalse();
        assertThat(resolved.toString()).doesNotContain("effective.example.invalid", "outer-password", "driver-secret");
        assertThat(resolved.identity().toString()).doesNotContain("driver-user", "driver-secret");
    }

    @Test
    void hikariDoesNotUseAStaleUrlWhenAnExplicitDatasourceTakesPrecedence() {
        NoConnectionHikari dataSource = new NoConnectionHikari();
        dataSource.setJdbcUrl("jdbc:mysql://unused.example.invalid:3306/unused");
        DataSource actual = mock(DataSource.class);
        dataSource.setDataSource(actual);
        assertThat(inspector.inspect("routedDataSource", dataSource)).isEmpty();
        verifyNoInteractions(actual);
    }

    @Test
    void druidUsesItsCurrentMetadataWithoutInitializingOrClosingDatasource() {
        NoConnectionDruid dataSource = new NoConnectionDruid();
        dataSource.setUrl("jdbc:mysql://old.example.invalid:3306/original");
        dataSource.setUrl("jdbc:mysql://custom.example.invalid:3307/overridden");
        dataSource.setUsername("effective-user");
        dataSource.setPassword("private-secret");
        Properties properties = new Properties();
        properties.setProperty("useSSL", "true");
        dataSource.setConnectProperties(properties);

        ResolvedConnection resolved = inspector.inspect("druidDataSource", dataSource).orElseThrow();
        assertThat(resolved.type()).isEqualTo(MiddlewareType.MYSQL);
        assertThat(resolved.client()).isSameAs(dataSource);
        assertThat(resolved.settings()).containsEntry("endpoint", "jdbc:mysql://custom.example.invalid:3307/overridden")
                .containsEntry("username", "effective-user").containsEntry("password", "private-secret");
        assertThat(dataSource.isInited()).isFalse();
        assertThat(dataSource.isClosed()).isFalse();
        assertThat(resolved.toString()).doesNotContain("custom.example.invalid", "private-secret");
    }

    @Test
    void druidKeepsCredentialCallbacksWithoutCallingThem() {
        NoConnectionDruid dataSource = new NoConnectionDruid();
        dataSource.setUrl("jdbc:mysql://database.example.invalid:3306/project");
        dataSource.setUsername("unused-static-user");
        dataSource.setPassword("unused-static-password");
        NameCallback username = new NameCallback("Username") {
            @Override public String getName() { throw new AssertionError("Username callback was executed"); }
        };
        PasswordCallback password = new PasswordCallback("Password", false) {
            @Override public char[] getPassword() { throw new AssertionError("Password callback was executed"); }
        };
        dataSource.setUserCallback(username);
        dataSource.setPasswordCallback(password);

        ResolvedConnection resolved = inspector.inspect("druid", dataSource).orElseThrow();
        assertThat(resolved.settings().get("usernameCallback")).isSameAs(username);
        assertThat(resolved.settings().get("passwordCallback")).isSameAs(password);
        assertThat(resolved.settings().get("username")).isNull();
        assertThat(resolved.settings().get("password")).isNull();
        assertThat(dataSource.isInited()).isFalse();
    }

    @Test
    void kafkaInspectsAllThreeExistingFactoriesAndTheirEffectiveBootstrapSupplier() {
        String declared = "declared.example.invalid:9092";
        Map<String, Object> initial = Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, declared,
                "security.protocol", "SASL_SSL", "sasl.jaas.config", "private-configuration-secret");
        KafkaAdmin admin = new KafkaAdmin(initial);
        NoProducerFactory producer = new NoProducerFactory(initial);
        NoConsumerFactory consumer = new NoConsumerFactory(initial);
        List<KafkaResourceFactory> factories = List.of(admin, producer, consumer);
        AtomicInteger supplierCalls = new AtomicInteger();
        for (int i = 0; i < factories.size(); i++) {
            KafkaResourceFactory factory = factories.get(i);
            String effective = "effective-" + i + ".example.invalid:9093";
            factory.setBootstrapServersSupplier(() -> { supplierCalls.incrementAndGet(); return effective; });

            ResolvedConnection resolved = inspector.inspect("kafkaSource" + i, factory).orElseThrow();
            assertThat(resolved.type()).isEqualTo(MiddlewareType.KAFKA);
            assertThat(resolved.client()).isSameAs(factory);
            assertThat(resolved.settings()).containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, effective)
                    .containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.jaas.config", "private-configuration-secret");
            assertThat(resolved.toString()).doesNotContain(effective, "private-configuration-secret");
        }
        assertThat(supplierCalls).hasValue(3);
        assertThat(initial).containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, declared);
    }

    @Test
    void kafkaCopiesMutableConfigurationButRetainsOpaqueCredentialReferences() {
        List<String> bootstrap = new ArrayList<>(List.of("broker.example.invalid:9092"));
        Object credentialReference = new Object();
        KafkaAdmin factory = new KafkaAdmin(Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                "credential.reference", credentialReference));
        ResolvedConnection resolved = inspector.inspect("kafkaAdmin", factory).orElseThrow();
        bootstrap.add("second.example.invalid:9092");
        assertThat(resolved.settings().get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG))
                .isEqualTo(List.of("broker.example.invalid:9092"));
        assertThat(resolved.settings().get("credential.reference")).isSameAs(credentialReference);
        assertThatThrownBy(() -> resolved.settings().put("security.protocol", "PLAINTEXT"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mongodbReadsTheExistingClientSettingsAfterCustomizerOverrides() {
        MongoClientSettings template = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://template.example.invalid:27017"))
                .build();
        MongoCredential credential = MongoCredential.createCredential("monitor-user", "auth-scope", "private-secret".toCharArray());
        MongoClientSettings actual = MongoClientSettings.builder(template)
                .applyToClusterSettings(builder -> builder.hosts(List.of(new ServerAddress("actual.example.invalid", 27019))))
                .applyToSslSettings(builder -> builder.enabled(true))
                .credential(credential).build();
        MongoClientImpl client = mock(MongoClientImpl.class);
        when(client.getSettings()).thenReturn(actual);

        ResolvedConnection resolved = inspector.inspect("mongoClient", client).orElseThrow();
        assertThat(resolved.type()).isEqualTo(MiddlewareType.MONGODB);
        assertThat(resolved.client()).isSameAs(client);
        assertThat(resolved.settings().get("mongoSettings")).isSameAs(actual);
        assertThat(resolved.settings().get("nodes"))
                .isEqualTo(List.of(new ServerAddress("actual.example.invalid", 27019)));
        assertThat(resolved.settings().get("credential")).isSameAs(credential);
        assertThat(resolved.settings().get("tls")).isSameAs(actual.getSslSettings());
        assertThat(resolved.settings()).containsEntry("authenticationDatabase", "auth-scope");
        assertThat(template.getSslSettings().isEnabled()).isFalse();
        assertThat(template.getClusterSettings().getHosts().get(0).getHost()).isEqualTo("template.example.invalid");
        verify(client).getSettings();
        verifyNoMoreInteractions(client);
        assertThat(resolved.toString()).doesNotContain("actual.example.invalid", "monitor-user", "private-secret");
    }

    @Test
    void elasticsearchReadsRuntimeNodesAndKeepsItsAuthenticatedTransport() {
        RestClient restClient = mock(RestClient.class);
        Node node = new Node(new HttpHost("effective.example.invalid", 9243, "https"));
        List<Node> nodes = new ArrayList<>(List.of(node));
        when(restClient.getNodes()).thenReturn(nodes);
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);
        clearInvocations(restClient);

        ResolvedConnection resolved = inspector.inspect("elasticsearchClient", client).orElseThrow();
        assertThat(resolved.type()).isEqualTo(MiddlewareType.ELASTICSEARCH);
        assertThat(resolved.client()).isSameAs(client);
        assertThat(resolved.settings().get("transport")).isSameAs(transport);
        assertThat(resolved.settings().get("restClient")).isSameAs(restClient);
        nodes.clear();
        assertThat(resolved.settings().get("nodes")).isEqualTo(List.of(node));
        verify(restClient).getNodes();
        verifyNoMoreInteractions(restClient);
        assertThat(resolved.toString()).doesNotContain("effective.example.invalid");
    }

    @Test
    void standaloneRestClientIsSupportedButEqualAddressesDoNotMergeIndependentClients() {
        RestClient first = mock(RestClient.class);
        RestClient second = mock(RestClient.class);
        Node node = new Node(new HttpHost("same.example.invalid", 9200, "http"));
        when(first.getNodes()).thenReturn(List.of(node));
        when(second.getNodes()).thenReturn(List.of(node));
        ResolvedConnection one = inspector.inspect("firstClient", first).orElseThrow();
        ResolvedConnection two = inspector.inspect("secondClient", second).orElseThrow();
        ResolvedConnection alias = inspector.inspect("firstAlias", first).orElseThrow();
        assertThat(one.identity().sameAs(two.identity())).isFalse();
        assertThat(one.identity().sameAs(alias.identity())).isTrue();
        assertThat(one.settings().get("restClient")).isSameAs(first);
    }

    @ParameterizedTest
    @CsvSource({
            "http://minio.example.invalid:9000,true,https://minio.example.invalid:9000",
            "HTTP://minio.example.invalid:9000,true,https://minio.example.invalid:9000",
            "minio.example.invalid,true,https://minio.example.invalid",
            "https://minio.example.invalid:9443,false,https://minio.example.invalid:9443",
            "http://127.0.0.1:9000,false,http://127.0.0.1:9000"
    })
    void minioCopiesTheSameSecureRulesAsLazyStorageWithoutCreatingItsClient(String endpoint, boolean secure, String expected) {
        ChatProperties chat = minioProperties(endpoint);
        chat.getAttachment().setSecure(secure);
        MinioChatObjectStorage storage = new MinioChatObjectStorage(chat);

        ResolvedConnection resolved = inspector.inspect("chatProperties", chat).orElseThrow();
        assertThat(resolved.type()).isEqualTo(MiddlewareType.MINIO);
        assertThat(resolved.client()).isSameAs(chat);
        assertThat(resolved.settings()).containsEntry("endpoint", expected).containsEntry("accessKey", "private-access")
                .containsEntry("secretKey", "private-secret").containsEntry("bucket", "attachments");
        chat.getAttachment().setSecretKey("changed-secret");
        assertThat(resolved.settings()).containsEntry("secretKey", "private-secret");
        assertThat(chat.getAttachment().getEndpoint()).isEqualTo(endpoint);
        assertThat(ReflectionTestUtils.getField(storage, "client")).isNull();
        assertThat(inspector.inspect("storage", storage)).isEmpty();
        assertThat(resolved.toString()).doesNotContain(endpoint, "private-access", "private-secret");
    }

    @Test
    void missingSourcesAndUnsupportedClientTemplatesAreNotConfigured() {
        assertThat(inspector.inspect("missing", null)).isEmpty();
        assertThat(inspector.inspect("unknown", new Object())).isEmpty();
        assertThat(inspector.inspect("mysql", new NoConnectionHikari())).isEmpty();
        NoConnectionHikari otherDatabase = new NoConnectionHikari();
        otherDatabase.setJdbcUrl("jdbc:h2:mem:test");
        assertThat(inspector.inspect("otherDatabase", otherDatabase)).isEmpty();
        assertThat(inspector.inspect("kafka", new KafkaAdmin(Map.of()))).isEmpty();
        assertThat(inspector.inspect("kafka", new KafkaAdmin(Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, List.of())))).isEmpty();
        assertThat(inspector.inspect("kafka", new KafkaAdmin(Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, " , ")))).isEmpty();
        assertThat(inspector.inspect("mongoSettings", MongoClientSettings.builder().build())).isEmpty();
        MongoClient unknownMongo = mock(MongoClient.class);
        assertThat(inspector.inspect("customMongo", unknownMongo)).isEmpty();
        verifyNoInteractions(unknownMongo);
        ElasticsearchTransport unsupported = mock(ElasticsearchTransport.class);
        ElasticsearchClient unsupportedClient = new ElasticsearchClient(unsupported);
        clearInvocations(unsupported);
        assertThat(inspector.inspect("elastic", unsupportedClient)).isEmpty();
        verifyNoInteractions(unsupported);
        RestClient noNodes = mock(RestClient.class);
        when(noNodes.getNodes()).thenReturn(List.of());
        assertThat(inspector.inspect("noNodes", noNodes)).isEmpty();
        ChatProperties chat = minioProperties("http://minio.example.invalid");
        chat.setEnabled(false);
        assertThat(inspector.inspect("chat", chat)).isEmpty();
        chat.setEnabled(true);
        chat.getAttachment().setSecretKey("");
        assertThat(inspector.inspect("chat", chat)).isEmpty();
        chat.getAttachment().setSecretKey("private-secret");
        chat.getAttachment().setEndpoint(null);
        assertThat(inspector.inspect("chat", chat)).isEmpty();
    }

    @Test
    void sourceNamesAndMetadataExceptionsCannotLeakCredentials() {
        KafkaAdmin exploding = new KafkaAdmin(Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "broker.example.invalid:9092"));
        exploding.setBootstrapServersSupplier(() -> { throw new IllegalArgumentException("private-user:private-secret"); });
        assertThat(inspector.inspect("brokenFactory", exploding)).isEmpty();
        assertThat(inspector.inspect("redis://private-user:private-secret@host", exploding)).isEmpty();
        assertThat(inspector.inspect(null, exploding)).isEmpty();
    }

    @Test
    void aopLazyAndJdkProxiesAreRejectedWithoutInvokingOrInitializingTargets() {
        AtomicInteger creations = new AtomicInteger();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerBeanDefinition("lazyDataSource", new RootBeanDefinition(NoConnectionHikari.class, () -> {
            creations.incrementAndGet();
            throw new AssertionError("Lazy client was initialized");
        }));
        LazyInitTargetSource targetSource = new LazyInitTargetSource();
        targetSource.setTargetBeanName("lazyDataSource");
        targetSource.setBeanFactory(beans);
        ProxyFactory proxy = new ProxyFactory();
        proxy.setTargetSource(targetSource);
        proxy.setInterfaces(DataSource.class);
        assertThat(inspector.inspect("lazyProxy", proxy.getProxy())).isEmpty();
        assertThat(creations).hasValue(0);
        Object jdkProxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (object, method, args) -> { throw new AssertionError("JDK proxy was invoked"); });
        assertThat(inspector.inspect("jdkProxy", jdkProxy)).isEmpty();
    }

    private static ChatProperties minioProperties(String endpoint) {
        ChatProperties chat = new ChatProperties();
        chat.setEnabled(true);
        chat.getAttachment().setEndpoint(endpoint);
        chat.getAttachment().setAccessKey("private-access");
        chat.getAttachment().setSecretKey("private-secret");
        chat.getAttachment().setBucket("attachments");
        return chat;
    }

    static class NoConnectionHikari extends HikariDataSource {
        @Override public Connection getConnection() throws SQLException { throw new AssertionError("Unexpected JDBC connection"); }
        @Override public Connection getConnection(String username, String password) throws SQLException { throw new AssertionError("Unexpected JDBC connection"); }
    }

    static class NoConnectionDruid extends DruidDataSource {
        @Override public DruidPooledConnection getConnection() throws SQLException { throw new AssertionError("Unexpected JDBC connection"); }
    }

    static class NoProducerFactory extends DefaultKafkaProducerFactory<String, String> {
        NoProducerFactory(Map<String, Object> properties) { super(properties); }
        @Override public Producer<String, String> createProducer() { throw new AssertionError("Unexpected Kafka producer"); }
        @Override protected Producer<String, String> createRawProducer(Map<String, Object> properties) { throw new AssertionError("Unexpected Kafka producer"); }
    }

    static class NoConsumerFactory extends DefaultKafkaConsumerFactory<String, String> {
        NoConsumerFactory(Map<String, Object> properties) { super(properties); }
        @Override protected Consumer<String, String> createRawConsumer(Map<String, Object> properties) { throw new AssertionError("Unexpected Kafka consumer"); }
    }
}
