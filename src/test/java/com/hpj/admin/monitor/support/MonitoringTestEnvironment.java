package com.hpj.admin.monitor.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Container;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.ReplaceOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import okhttp3.OkHttpClient;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.kafka.KafkaContainer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Owns one disposable middleware instance. Endpoints can only come from the container this
 * object starts; this fixture deliberately has no constructor accepting an external endpoint.
 * SDK clients returned by connection methods are caller-owned, except the MinIO client.
 */
public final class MonitoringTestEnvironment implements AutoCloseable {
    public static final Duration READY_TIMEOUT = Duration.ofSeconds(180);
    public static final Map<String, String> IMAGES = Map.of(
            "mysql", "mysql:8.0.36",
            "redis", "redis:7.2.4",
            "kafka", "apache/kafka-native:3.8.0",
            "mongodb", "mongo:6.0.11",
            "elasticsearch", "docker.elastic.co/elasticsearch/elasticsearch:8.10.4",
            "minio", "minio/minio:RELEASE.2025-09-07T16-13-09Z");
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(3);
    private static final String SENTINEL = "monitor-fixture-payload";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String type;
    private final String id = "monitor-" + UUID.randomUUID().toString().replace("-", "");
    private final String resourceName = "mon_" + UUID.randomUUID().toString().replace("-", "");
    private final String password = UUID.randomUUID().toString().replace("-", "");
    private final String username;
    private final int containerPort;
    private final Path logDirectory;
    private final GenericContainer<?> container;
    private final StringBuilder capturedLogs = new StringBuilder();
    private RedisClient redisClient;
    private OkHttpClient minioHttpClient;
    private MinioClient minioClient;
    private boolean closed;

    private MonitoringTestEnvironment(String type) {
        this.type = normalizeType(type);
        this.username = switch (this.type) {
            case "mysql", "mongodb" -> "root";
            case "redis" -> "default";
            case "elasticsearch" -> "elastic";
            case "minio" -> "mon" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
            default -> "";
        };
        this.containerPort = switch (this.type) {
            case "mysql" -> 3306;
            case "redis" -> 6379;
            case "kafka" -> 9092;
            case "mongodb" -> 27017;
            case "elasticsearch" -> 9200;
            case "minio" -> 9000;
            default -> throw new IllegalStateException("Unsupported fixture type");
        };
        this.logDirectory = Path.of("target", "monitor-environments", id).toAbsolutePath().normalize();
        this.container = createContainer();
        container.withReuse(false).withStartupAttempts(1)
                .withLabel("com.hpj.admin.monitor.fixture", id)
                .withExposedPorts(containerPort)
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"),
                                new ExposedPort(containerPort))))
                .withLogConsumer(frame -> capture(frame.getUtf8String()))
                .waitingFor(new ProtocolReadyWait().withStartupTimeout(READY_TIMEOUT));
    }

    public static MonitoringTestEnvironment start(String type) {
        MonitoringTestEnvironment environment = new MonitoringTestEnvironment(type);
        try {
            Files.createDirectories(environment.logDirectory);
            environment.container.start();
            environment.writeSummary("ready");
            return environment;
        } catch (Exception | LinkageError failure) {
            environment.diagnostic("start", failure);
            try {
                environment.close();
            } catch (Exception | LinkageError cleanupFailure) {
                environment.diagnostic("startup cleanup", cleanupFailure);
            }
            throw environment.failure("start");
        }
    }

    public static String normalizeType(String type) {
        if (type == null || !IMAGES.containsKey(type.strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Expected one of mysql, redis, kafka, mongodb, elasticsearch, minio");
        }
        return type.strip().toLowerCase(Locale.ROOT);
    }

    public String type() { return type; }
    public String id() { return id; }
    public String image() { return IMAGES.get(type); }
    public String host() { return "127.0.0.1"; }
    public int port() { return container.getMappedPort(containerPort); }
    public String resourceName() { return type.equals("minio") ? resourceName.replace('_', '-') : resourceName; }
    public String username() { return username; }
    public String password() { return type.equals("kafka") ? "" : password; }
    public Path logDirectory() { return logDirectory; }
    public boolean isRunning() {
        return ownedContainers().stream().anyMatch(instance -> "running".equals(instance.getState()));
    }
    public String bootstrapServers() { requireType("kafka"); return host() + ":" + port(); }
    public String httpEndpoint() {
        if (!type.equals("elasticsearch") && !type.equals("minio")) {
            throw new IllegalStateException("HTTP endpoint only available for HTTP middleware");
        }
        return "http://" + host() + ":" + port();
    }

    /** Connection URL deliberately excludes user/password; callers must never log credentials. */
    public String jdbcUrl() {
        requireType("mysql");
        return "jdbc:mysql://" + host() + ":" + port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=3000";
    }

    public Connection jdbcConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), username, password);
    }

    public synchronized StatefulRedisConnection<String, String> redisConnection() {
        requireType("redis");
        if (redisClient == null) {
            redisClient = RedisClient.create(RedisURI.Builder.redis(host(), port())
                    .withPassword(password.toCharArray()).withTimeout(CLIENT_TIMEOUT).build());
        }
        return redisClient.connect();
    }

    public AdminClient kafkaAdmin() {
        requireType("kafka");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        settings.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        settings.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        settings.put(AdminClientConfig.CLIENT_ID_CONFIG, id);
        return AdminClient.create(settings);
    }

    public MongoClient mongoClient() {
        requireType("mongodb");
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + username + ":" + password
                        + "@" + host() + ":" + port() + "/?authSource=admin"))
                .applyToClusterSettings(settings -> settings.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(settings -> settings.connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS)).build());
    }

    public RestClient elasticsearchClient() {
        requireType("elasticsearch");
        String authorization = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return RestClient.builder(new HttpHost(host(), port(), "http"))
                .setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " + authorization)})
                .setRequestConfigCallback(config -> config.setConnectTimeout(3000)
                        .setSocketTimeout(3000).setConnectionRequestTimeout(3000)).build();
    }

    /** The environment owns the shared HTTP resources behind this client. */
    public synchronized MinioClient minioClient() {
        requireType("minio");
        if (minioClient == null) {
            minioHttpClient = new OkHttpClient.Builder().connectTimeout(CLIENT_TIMEOUT)
                    .readTimeout(CLIENT_TIMEOUT).writeTimeout(CLIENT_TIMEOUT)
                    .callTimeout(Duration.ofSeconds(5)).build();
            minioClient = MinioClient.builder().endpoint(httpEndpoint())
                    .credentials(username, password).httpClient(minioHttpClient).build();
        }
        return minioClient;
    }

    public void prepareData() { operation("prepare", this::prepare); }
    public void cleanupData() {
        if (!closed) { operation("cleanup", this::cleanup); }
    }
    public boolean isDataPresent() { return operation("inspect", this::present); }
    public void verifyData() {
        operation("verify", () -> {
            if (!verify()) { throw new IllegalStateException("Fixture sentinel did not match"); }
            return null;
        });
    }

    private GenericContainer<?> createContainer() {
        GenericContainer<?> instance = type.equals("kafka")
                ? new KafkaContainer(image()) {
                    @Override public String getHost() { return "127.0.0.1"; }
                    @Override public String getLogs() { return redact(super.getLogs()); }
                }
                : new OwnedContainer();
        switch (type) {
            case "mysql" -> instance.withEnv("MYSQL_ROOT_PASSWORD", password).withEnv("MYSQL_ROOT_HOST", "%");
            case "redis" -> instance.withCommand("redis-server", "--requirepass", password, "--appendonly", "yes");
            case "mongodb" -> instance.withEnv("MONGO_INITDB_ROOT_USERNAME", username)
                    .withEnv("MONGO_INITDB_ROOT_PASSWORD", password);
            case "elasticsearch" -> instance.withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "true")
                    .withEnv("xpack.security.http.ssl.enabled", "false")
                    .withEnv("xpack.security.transport.ssl.enabled", "false")
                    .withEnv("ELASTIC_PASSWORD", password).withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
            case "minio" -> instance.withEnv("MINIO_ROOT_USER", username)
                    .withEnv("MINIO_ROOT_PASSWORD", password)
                    .withCommand("server", "/data", "--console-address", ":9001");
            default -> { /* Kafka's official module configures its isolated KRaft node. */ }
        }
        return instance;
    }

    private void ready() throws Exception {
        switch (type) {
            case "mysql" -> {
                try (Connection client = jdbcConnection(); var statement = client.createStatement();
                     var result = statement.executeQuery("SELECT 1")) {
                    if (!result.next() || result.getInt(1) != 1) { throw new IllegalStateException("MySQL not ready"); }
                }
            }
            case "redis" -> {
                try (var client = redisConnection()) {
                    if (!"PONG".equals(client.sync().ping())) { throw new IllegalStateException("Redis not ready"); }
                }
            }
            case "kafka" -> {
                var client = kafkaAdmin();
                try {
                    if (client.describeCluster().nodes().get(3, TimeUnit.SECONDS).isEmpty()) {
                        throw new IllegalStateException("Kafka not ready");
                    }
                } finally { client.close(Duration.ofSeconds(1)); }
            }
            case "mongodb" -> {
                try (var client = mongoClient()) {
                    client.getDatabase("admin").runCommand(new Document("ping", 1));
                }
            }
            case "elasticsearch" -> {
                try (var client = elasticsearchClient()) {
                    var response = client.performRequest(new Request("GET", "/_cluster/health"));
                    var body = JSON.readTree(response.getEntity().getContent());
                    if (body.path("status").asText().equals("red")) {
                        throw new IllegalStateException("Elasticsearch not ready");
                    }
                }
            }
            case "minio" -> minioClient().listBuckets();
            default -> throw new IllegalStateException("Unsupported fixture type");
        }
    }

    private Void prepare() throws Exception {
        String name = resourceName();
        switch (type) {
            case "mysql" -> {
                try (Connection client = jdbcConnection(); var statement = client.createStatement()) {
                    statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + name + "`");
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + name
                            + "`.fixture (id INT PRIMARY KEY, payload VARCHAR(100))");
                    statement.executeUpdate("INSERT INTO `" + name + "`.fixture VALUES (1, '" + SENTINEL
                            + "') ON DUPLICATE KEY UPDATE payload=VALUES(payload)");
                }
            }
            case "redis" -> {
                try (var client = redisConnection()) { client.sync().set(name, SENTINEL); }
            }
            case "kafka" -> {
                try (var client = kafkaAdmin()) {
                    if (!client.listTopics().names().get(3, TimeUnit.SECONDS).contains(name)) {
                        client.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
                    }
                }
                Properties settings = kafkaSettings();
                settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
                settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
                settings.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
                try (var producer = new KafkaProducer<String, String>(settings)) {
                    producer.send(new ProducerRecord<>(name, "fixture", SENTINEL)).get(10, TimeUnit.SECONDS);
                }
            }
            case "mongodb" -> {
                try (var client = mongoClient()) {
                    client.getDatabase(name).getCollection("fixture").replaceOne(new Document("_id", "fixture"),
                            new Document("_id", "fixture").append("payload", SENTINEL), new ReplaceOptions().upsert(true));
                }
            }
            case "elasticsearch" -> {
                try (var client = elasticsearchClient()) {
                    Request request = new Request("PUT", "/" + name + "/_doc/fixture");
                    request.addParameter("refresh", "true");
                    request.setJsonEntity("{\"payload\":\"" + SENTINEL + "\"}");
                    client.performRequest(request);
                }
            }
            case "minio" -> {
                var client = minioClient();
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(name).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(name).build());
                }
                byte[] bytes = SENTINEL.getBytes(StandardCharsets.UTF_8);
                client.putObject(PutObjectArgs.builder().bucket(name).object("fixture")
                        .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType("text/plain").build());
            }
            default -> throw new IllegalStateException("Unsupported fixture type");
        }
        return null;
    }

    private boolean present() throws Exception {
        String name = resourceName();
        return switch (type) {
            case "mysql" -> {
                try (Connection client = jdbcConnection(); var statement = client.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?")) {
                    statement.setString(1, name);
                    try (var result = statement.executeQuery()) { result.next(); yield result.getInt(1) == 1; }
                }
            }
            case "redis" -> {
                try (var client = redisConnection()) { yield client.sync().exists(name) == 1; }
            }
            case "kafka" -> {
                try (var client = kafkaAdmin()) { yield client.listTopics().names().get(3, TimeUnit.SECONDS).contains(name); }
            }
            case "mongodb" -> {
                try (var client = mongoClient()) {
                    yield client.getDatabase(name).getCollection("fixture").countDocuments() > 0;
                }
            }
            case "elasticsearch" -> {
                try (var client = elasticsearchClient()) {
                    yield elasticsearchExists(client, "/" + name);
                }
            }
            case "minio" -> minioClient().bucketExists(BucketExistsArgs.builder().bucket(name).build());
            default -> throw new IllegalStateException("Unsupported fixture type");
        };
    }

    private boolean verify() throws Exception {
        if (!present()) { return false; }
        String name = resourceName();
        return switch (type) {
            case "mysql" -> {
                try (Connection client = jdbcConnection(); var statement = client.createStatement();
                     var result = statement.executeQuery("SELECT payload FROM `" + name + "`.fixture WHERE id=1")) {
                    yield result.next() && SENTINEL.equals(result.getString(1));
                }
            }
            case "redis" -> {
                try (var client = redisConnection()) { yield SENTINEL.equals(client.sync().get(name)); }
            }
            case "kafka" -> {
                Properties settings = kafkaSettings();
                settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
                settings.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
                try (var consumer = new KafkaConsumer<String, String>(settings)) {
                    TopicPartition partition = new TopicPartition(name, 0);
                    consumer.assign(List.of(partition));
                    consumer.seekToBeginning(List.of(partition));
                    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    boolean found = false;
                    while (!found && System.nanoTime() < deadline) {
                        for (var record : consumer.poll(Duration.ofMillis(500))) {
                            if (SENTINEL.equals(record.value())) { found = true; }
                        }
                    }
                    yield found;
                }
            }
            case "mongodb" -> {
                try (var client = mongoClient()) {
                    var document = client.getDatabase(name).getCollection("fixture")
                            .find(new Document("_id", "fixture")).first();
                    yield document != null && SENTINEL.equals(document.getString("payload"));
                }
            }
            case "elasticsearch" -> {
                try (var client = elasticsearchClient()) {
                    var response = client.performRequest(new Request("GET", "/" + name + "/_doc/fixture"));
                    yield SENTINEL.equals(JSON.readTree(response.getEntity().getContent())
                            .path("_source").path("payload").asText());
                }
            }
            case "minio" -> {
                try (var object = minioClient().getObject(GetObjectArgs.builder().bucket(name).object("fixture").build())) {
                    yield SENTINEL.equals(new String(object.readNBytes(1024), StandardCharsets.UTF_8));
                }
            }
            default -> throw new IllegalStateException("Unsupported fixture type");
        };
    }

    private Void cleanup() throws Exception {
        String name = resourceName();
        switch (type) {
            case "mysql" -> {
                try (Connection client = jdbcConnection(); var statement = client.createStatement()) {
                    statement.executeUpdate("DROP DATABASE IF EXISTS `" + name + "`");
                }
            }
            case "redis" -> {
                try (var client = redisConnection()) { client.sync().del(name); }
            }
            case "kafka" -> {
                try (var client = kafkaAdmin()) {
                    if (client.listTopics().names().get(3, TimeUnit.SECONDS).contains(name)) {
                        client.deleteTopics(List.of(name)).all().get(10, TimeUnit.SECONDS);
                        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                        while (client.listTopics().names().get(3, TimeUnit.SECONDS).contains(name)) {
                            if (System.nanoTime() >= deadline) { throw new IllegalStateException("Topic deletion timeout"); }
                            Thread.sleep(100);
                        }
                    }
                }
            }
            case "mongodb" -> {
                try (var client = mongoClient()) { client.getDatabase(name).drop(); }
            }
            case "elasticsearch" -> {
                try (var client = elasticsearchClient()) {
                    if (elasticsearchExists(client, "/" + name)) {
                        client.performRequest(new Request("DELETE", "/" + name));
                    }
                }
            }
            case "minio" -> {
                var client = minioClient();
                if (client.bucketExists(BucketExistsArgs.builder().bucket(name).build())) {
                    for (var object : client.listObjects(ListObjectsArgs.builder().bucket(name).recursive(true).build())) {
                        client.removeObject(RemoveObjectArgs.builder().bucket(name).object(object.get().objectName()).build());
                    }
                    client.removeBucket(RemoveBucketArgs.builder().bucket(name).build());
                }
            }
            default -> throw new IllegalStateException("Unsupported fixture type");
        }
        return null;
    }

    private boolean elasticsearchExists(RestClient client, String path) throws IOException {
        try {
            return client.performRequest(new Request("HEAD", path)).getStatusLine().getStatusCode() == 200;
        } catch (ResponseException failure) {
            if (failure.getResponse().getStatusLine().getStatusCode() == 404) { return false; }
            throw failure;
        }
    }

    private Properties kafkaSettings() {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers());
        properties.put("client.id", id);
        properties.put("request.timeout.ms", 3000);
        return properties;
    }

    private void requireType(String required) {
        if (closed) { throw new IllegalStateException("Fixture is already closed"); }
        if (!type.equals(required)) { throw new IllegalStateException("Client unavailable for this fixture type"); }
    }

    private <T> T operation(String action, CheckedSupplier<T> operation) {
        if (closed) { throw new IllegalStateException("Fixture is already closed"); }
        try {
            return operation.get();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            diagnostic(action, failure);
            throw failure(action);
        }
    }

    private IllegalStateException failure(String action) {
        return new IllegalStateException("Monitoring " + type + " fixture " + action
                + " failed; sanitized diagnostics: " + logDirectory);
    }

    private synchronized void capture(String text) {
        // Retain bounded diagnostics even when a startup failure removes the container immediately.
        capturedLogs.append(redact(text));
        if (capturedLogs.length() > 2_000_000) { capturedLogs.delete(0, capturedLogs.length() - 2_000_000); }
    }

    private String redact(String text) {
        if (text == null) { return ""; }
        String result = text.replace(password, "[REDACTED]");
        if (!username.isEmpty() && !username.equals("root") && !username.equals("elastic") && !username.equals("default")) {
            result = result.replace(username, "[REDACTED]");
        }
        result = result.replace(Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8)), "[REDACTED]");
        return result.replaceAll("(?i)(password|secret|authorization|access[_-]?key)([=:]\\s*)[^\\s,;]+", "$1$2[REDACTED]");
    }

    private void diagnostic(String action, Throwable failure) {
        try {
            Files.createDirectories(logDirectory);
            Files.writeString(logDirectory.resolve("diagnostics.log"), Instant.now() + " " + action + ": "
                            + failure.getClass().getSimpleName() + " " + redact(failure.getMessage()) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Container cleanup must still run if diagnostic storage is unavailable.
        }
    }

    private void writeSummary(String status) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", id);
        report.put("type", type);
        report.put("image", image());
        report.put("resourceName", resourceName());
        report.put("status", status);
        report.put("recordedAt", Instant.now().toString());
        report.put("host", host());
        if (container.getContainerId() != null) { report.put("port", port()); }
        JSON.writerWithDefaultPrettyPrinter().writeValue(logDirectory.resolve("environment.json").toFile(), report);
    }

    @Override
    public synchronized void close() {
        if (closed) { return; }
        RuntimeException cleanupFailure = null;
        try {
            if (container.isRunning()) {
                try { cleanup(); } catch (Exception failure) {
                    diagnostic("cleanup on close", failure);
                    // The disposable container will be removed even if its protocol is no longer available.
                }
                capture(container.getLogs());
            }
            Files.createDirectories(logDirectory);
            Files.writeString(logDirectory.resolve("container.log"), capturedLogs.toString(), StandardCharsets.UTF_8);
            writeSummary("closing");
        } catch (Exception failure) {
            diagnostic("save logs", failure);
        } finally {
            try {
                if (redisClient != null) { redisClient.shutdown(Duration.ZERO, CLIENT_TIMEOUT); }
                if (minioHttpClient != null) {
                    minioHttpClient.dispatcher().executorService().shutdown();
                    minioHttpClient.connectionPool().evictAll();
                }
            } catch (Exception failure) { diagnostic("close clients", failure); }
            try {
                container.close();
                verifyOwnedContainersRemoved();
                closed = true;
                try { writeSummary("closed"); } catch (IOException failure) { diagnostic("save final status", failure); }
            } catch (Exception failure) {
                diagnostic("remove owned container", failure);
                cleanupFailure = failure("close");
            }
        }
        if (cleanupFailure != null) { throw cleanupFailure; }
    }

    private List<Container> ownedContainers() {
        List<Container> instances = DockerClientFactory.instance().client().listContainersCmd()
                .withShowAll(true).withLabelFilter(Map.of("com.hpj.admin.monitor.fixture", id)).exec();
        // Check the returned identity as well as the query filter before any destructive operation.
        if (instances.stream().anyMatch(instance -> instance.getLabels() == null
                || !id.equals(instance.getLabels().get("com.hpj.admin.monitor.fixture")))) {
            throw new IllegalStateException("Unexpected container identity in fixture ownership query");
        }
        return instances;
    }

    private void verifyOwnedContainersRemoved() {
        // Testcontainers may swallow Docker removal errors and clear its local container ID.
        // Query the daemon by this instance's random ownership label before declaring success.
        for (Container instance : ownedContainers()) {
            DockerClientFactory.instance().client().removeContainerCmd(instance.getId())
                    .withForce(true).withRemoveVolumes(true).exec();
        }
        if (!ownedContainers().isEmpty()) {
            throw new IllegalStateException("Owned fixture container remains after removal");
        }
    }

    @Override public String toString() { return "MonitoringTestEnvironment[" + type + ", " + id + "]"; }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }

    private final class OwnedContainer extends GenericContainer<OwnedContainer> {
        private OwnedContainer() { super(MonitoringTestEnvironment.this.image()); }
        @Override public String getHost() { return "127.0.0.1"; }
        @Override public String getLogs() { return redact(super.getLogs()); }
    }

    private final class ProtocolReadyWait extends AbstractWaitStrategy {
        @Override protected void waitUntilReady() {
            // Reserve enough of the 180-second budget for one final bounded client attempt.
            long deadline = System.nanoTime() + startupTimeout.minusSeconds(10).toNanos();
            Exception lastFailure = null;
            while (System.nanoTime() < deadline) {
                try {
                    ready();
                    return;
                } catch (Exception failure) {
                    lastFailure = failure;
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("Fixture readiness interrupted");
                    }
                    try { Thread.sleep(250); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Fixture readiness interrupted");
                    }
                }
            }
            if (lastFailure != null) { diagnostic("protocol readiness timeout", lastFailure); }
            throw new IllegalStateException("Fixture protocol readiness timed out within 180 seconds");
        }
    }
}
