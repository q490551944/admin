package com.hpj.admin.monitor;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.connection.MonitoringConnectionResolver;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.connection.StandardConnectionInspector;
import com.hpj.admin.monitor.kafka.KafkaMonitoringAdapter;
import com.hpj.admin.monitor.kafka.KafkaMonitoringConnections;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateAclsOptions;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.DeleteAclsOptions;
import org.apache.kafka.clients.admin.DeleteTopicsOptions;
import org.apache.kafka.clients.admin.DescribeConfigsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static com.hpj.admin.monitor.metric.MonitoringSnapshotStore.Acceptance.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The explicit Kafka selection starts owned real Kafka 3.8.0; missing Docker is a failure, never a skip. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = "kafka")
class MonitoringKafkaAdapterIntegrationTest {
    private static final int NATIVE_TIMEOUT_MS = 3000;
    private static final Set<String> CONFIG_KEYS = Set.of("cleanup.policy", "retention.ms", "min.insync.replicas");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeScopedMetadataAndLimitsDoNotModifyTopicsOffsetsOrConsumerGroups() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("kafka");
             AdminClient observer = environment.kafkaAdmin();
             Collector collector = new Collector()) {
            List<String> owned = topicNames(environment);
            try {
                prepareTopics(observer, owned);
                environment.prepareData(); // Writes one sentinel through the fixture's producer, never a consumer.
                KafkaAdmin business = source(environment);
                Map<String, Object> original = business.getConfigurationProperties();
                assertThat(original.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo(environment.bootstrapServers());
                AdminState before = state(observer, owned);
                assertThat(before.topics().get(owned.get(0)).latest().values().stream().mapToLong(Long::longValue).sum())
                        .isEqualTo(1);
                assertThat(before.groups()).isEmpty();

                ResolvedTarget target = resolve(business, "metadata", owned.subList(0, 2));
                CollectionResult complete = collector.collect(target);
                NativeObservation first = collector.connections.observations.get(0);
                assertNativeCluster(complete, first);
                assertCompleteTopics(complete, first, owned.subList(0, 2));
                assertThat(complete.status()).isEqualTo(CollectionStatus.SUCCESS);
                assertThat(complete.inventoryComplete()).isTrue();
                assertThat(value(complete, "kafka.topics.configured")).isEqualByComparingTo("2");
                assertThat(value(complete, "kafka.topics.observed")).isEqualByComparingTo("2");
                assertThat(value(complete, "kafka.partitions.observed")).isEqualByComparingTo("5");
                assertThat(flag(complete, "kafka.coverage.complete")).isTrue();
                assertThat(flag(complete, "kafka.coverage.truncated")).isFalse();
                assertThat(first.requestedTopics).containsExactlyElementsOf(owned.subList(0, 2));
                assertThat(first.topicReads).doesNotContainKey(owned.get(2));

                // Native metadata contains all partitions of a requested topic; the presentation inventory is bounded.
                collector.properties.getLimits().setPartitions(2);
                CollectionResult limited = collector.collect(resolve(business, "limited", owned.subList(0, 2)));
                NativeObservation limitedNative = collector.connections.observations.get(1);
                assertNativeCluster(limited, limitedNative);
                assertThat(limited.status()).isEqualTo(CollectionStatus.PARTIAL);
                assertThat(limited.inventoryComplete()).isTrue();
                assertThat(flag(limited, "kafka.coverage.complete")).isFalse();
                assertThat(flag(limited, "kafka.coverage.truncated")).isTrue();
                assertThat(value(limited, "kafka.partitions.observed")).isEqualByComparingTo("2");
                assertThat(value(limited, "kafka.limits.partitions")).isEqualByComparingTo("2");
                assertThat(value(limited, "kafka.topic.partitions", owned.get(0))).isEqualByComparingTo("2");
                assertThat(value(limited, "kafka.topic.partitions.observed", owned.get(0))).isEqualByComparingTo("2");
                assertThat(value(limited, "kafka.topic.partitions", owned.get(1))).isEqualByComparingTo("3");
                assertThat(value(limited, "kafka.topic.partitions.observed", owned.get(1))).isZero();
                assertThat(limited.metrics().stream().filter(sample -> sample.definition().scope().kind() == ScopeKind.PARTITION))
                        .hasSize(10).allSatisfy(sample -> assertThat(sample.definition().scope().id())
                                .startsWith(owned.get(0) + "#"));

                collector.properties.getLimits().setPartitions(1000);
                String absent = environment.resourceName() + "_absent";
                CollectionResult missing = collector.collect(resolve(business, "missing", List.of(owned.get(0), absent)));
                assertNativeCluster(missing, collector.connections.observations.get(2));
                assertThat(missing.status()).isEqualTo(CollectionStatus.PARTIAL);
                assertThat(flag(missing, "kafka.coverage.complete")).isFalse();
                assertThat(flag(missing, "kafka.coverage.truncated")).isFalse();
                assertThat(metric(missing, "kafka.topic.partitions", absent).missingReason())
                        .isEqualTo(MissingReason.NOT_APPLICABLE);
                assertThat(value(missing, "kafka.topics.observed")).isEqualByComparingTo("1");
                assertThat(topicSet(observer)).doesNotContain(absent);

                assertThat(collector.connections.closed).isEqualTo(3);
                assertThat(state(observer, owned)).isEqualTo(before);
                assertThat(business.getConfigurationProperties()).isEqualTo(original);
                // The caller-owned AdminClient is still connected and usable after each owned collector has closed.
                assertThat(observer.describeCluster().nodes().get(5, TimeUnit.SECONDS)).isNotEmpty();
            } finally {
                deleteOwnedTopics(observer, owned);
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realTopicAclDenialIsPartialWhileClusterDiscoveryAndOtherTopicsRemainAvailable() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.startKafkaWithAuthorizer();
             AdminClient observer = environment.kafkaAdmin();
             Collector collector = new Collector()) {
            List<String> owned = topicNames(environment);
            String denied = owned.get(1);
            AclBinding denial = new AclBinding(new ResourcePattern(ResourceType.TOPIC, denied, PatternType.LITERAL),
                    new AccessControlEntry("User:ANONYMOUS", "*", AclOperation.DESCRIBE, AclPermissionType.DENY));
            boolean created = false;
            try {
                prepareTopics(observer, owned);
                environment.prepareData();
                AdminState before = state(observer, owned);
                observer.createAcls(List.of(denial), new CreateAclsOptions().timeoutMs(NATIVE_TIMEOUT_MS))
                        .all().get(5, TimeUnit.SECONDS);
                created = true;
                await().atMost(Duration.ofSeconds(15)).until(() -> topicIsDenied(observer, denied));
                // DescribeCluster in Kafka 3.8 returns discovery data independently of topic DESCRIBE authorization.
                assertThat(observer.describeCluster().nodes().get(5, TimeUnit.SECONDS)).isNotEmpty();
                KafkaAdmin business = source(environment);
                CollectionResult result = collector.collect(resolve(business, "acl", owned.subList(0, 2)));
                NativeObservation observation = collector.connections.observations.get(0);
                assertNativeCluster(result, observation);
                assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
                assertThat(result.inventoryComplete()).isFalse();
                assertThat(flag(result, "kafka.coverage.complete")).isFalse();
                assertThat(flag(result, "kafka.coverage.truncated")).isFalse();
                assertThat(value(result, "kafka.topics.configured")).isEqualByComparingTo("2");
                assertThat(value(result, "kafka.topics.observed")).isEqualByComparingTo("1");
                assertThat(value(result, "kafka.partitions.observed")).isEqualByComparingTo("2");
                assertThat(observation.requestedTopics).containsExactlyElementsOf(owned.subList(0, 2));
                assertThat(observation.topicReads.get(denied).failure().reason()).isEqualTo(MissingReason.UNAUTHORIZED);
                for (String key : List.of("kafka.topic.partitions", "kafka.topic.partitions.observed", "kafka.topic.coverage.complete")) {
                    assertThat(metric(result, key, denied).missingReason()).as(key).isEqualTo(MissingReason.UNAUTHORIZED);
                }
                assertTopic(result, observation.topicReads.get(owned.get(0)).topic());
                assertThat(groups(observer)).isEqualTo(before.groups());
                assertThat(collector.connections.closed).isEqualTo(1);

                // Exact test ACL removal restores the observer; no unrelated ACL or production target is touched.
                observer.deleteAcls(List.of(denial.toFilter()), new DeleteAclsOptions().timeoutMs(NATIVE_TIMEOUT_MS))
                        .all().get(5, TimeUnit.SECONDS);
                created = false;
                await().atMost(Duration.ofSeconds(15)).until(() -> topicIsReadable(observer, denied));
                assertThat(state(observer, owned)).isEqualTo(before);
            } finally {
                if (created) observer.deleteAcls(List.of(denial.toFilter()), new DeleteAclsOptions().timeoutMs(NATIVE_TIMEOUT_MS))
                        .all().get(5, TimeUnit.SECONDS);
                deleteOwnedTopics(observer, owned);
            }
        }
    }

    private static List<String> topicNames(MonitoringTestEnvironment environment) {
        return List.of(environment.resourceName(), environment.resourceName() + "_second", environment.resourceName() + "_excluded");
    }

    private static void prepareTopics(AdminClient client, List<String> topics) throws Exception {
        client.createTopics(List.of(new NewTopic(topics.get(0), 2, (short) 1),
                        new NewTopic(topics.get(1), 3, (short) 1), new NewTopic(topics.get(2), 1, (short) 1)),
                new CreateTopicsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(15)).ignoreException(ExecutionException.class).until(() -> {
            var descriptions = client.describeTopics(topics, new DescribeTopicsOptions().timeoutMs(NATIVE_TIMEOUT_MS))
                    .allTopicNames().get(5, TimeUnit.SECONDS);
            return descriptions.values().stream().flatMap(topic -> topic.partitions().stream())
                    .allMatch(partition -> partition.leader() != null && partition.leader().id() >= 0
                            && partition.isr().size() == partition.replicas().size());
        });
    }

    private static void deleteOwnedTopics(AdminClient client, List<String> owned) throws Exception {
        Set<String> present = topicSet(client);
        List<String> remaining = owned.stream().filter(present::contains).toList();
        if (!remaining.isEmpty()) client.deleteTopics(remaining, new DeleteTopicsOptions().timeoutMs(NATIVE_TIMEOUT_MS))
                .all().get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(10)).until(() -> topicSet(client).stream().noneMatch(owned::contains));
    }

    private static boolean topicIsDenied(AdminClient client, String topic) throws Exception {
        try {
            client.describeTopics(List.of(topic), new DescribeTopicsOptions().timeoutMs(NATIVE_TIMEOUT_MS))
                    .topicNameValues().get(topic).get(5, TimeUnit.SECONDS);
            return false;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof TopicAuthorizationException) return true;
            throw failure;
        }
    }

    private static boolean topicIsReadable(AdminClient client, String topic) throws Exception {
        try {
            client.describeTopics(List.of(topic), new DescribeTopicsOptions().timeoutMs(NATIVE_TIMEOUT_MS))
                    .topicNameValues().get(topic).get(5, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof TopicAuthorizationException) return false;
            throw failure;
        }
    }

    private static KafkaAdmin source(MonitoringTestEnvironment environment) {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.2:1");
        declared.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, NATIVE_TIMEOUT_MS);
        declared.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, NATIVE_TIMEOUT_MS);
        KafkaAdmin source = new KafkaAdmin(declared);
        source.setBootstrapServersSupplier(environment::bootstrapServers);
        return source;
    }

    private static ResolvedTarget resolve(KafkaAdmin source, String id, List<String> topics) {
        MonitoringProperties.Target declaration = new MonitoringProperties.Target();
        declaration.setId(id);
        declaration.setType(MiddlewareType.KAFKA);
        declaration.setEnabled(true);
        declaration.setConnectionSource("businessKafka");
        declaration.getScope().setTopics(topics);
        MonitoringProperties properties = new MonitoringProperties();
        properties.setEnabled(true);
        properties.setTargets(List.of(declaration));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("businessKafka", source);
        var targets = new MonitoringConnectionResolver(properties, beans, List.of(new StandardConnectionInspector())).resolve();
        assertThat(targets).hasSize(1);
        ResolvedTarget target = targets.get(0);
        assertThat(target.reason()).isEqualTo(ResolvedTarget.Reason.CONFIGURED);
        assertThat(target.bindings().get(0).connection().client()).isSameAs(source);
        assertThat(target.bindings().get(0).connection().settings().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG))
                .isEqualTo(source.getConfigurationProperties().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
        return target;
    }

    private static void assertNativeCluster(CollectionResult result, NativeObservation observation) {
        assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
        assertThat(value(result, "kafka.probe.duration")).isNotNegative();
        assertThat(metric(result, "kafka.probe.duration").definition().unit()).isEqualTo(Unit.MILLISECONDS);
        assertThat(observation.cluster.nodesFailure()).isNull();
        assertThat(observation.cluster.clusterIdFailure()).isNull();
        assertThat(observation.cluster.nodes()).hasSize(1);
        assertThat(observation.cluster.clusterId()).isNotBlank();
        assertThat(value(result, "kafka.brokers.discovered")).isEqualByComparingTo(BigDecimal.valueOf(observation.cluster.nodes().size()));
        assertThat(value(result, "kafka.brokers.observed")).isEqualByComparingTo("1");
        assertThat(metric(result, "kafka.brokers.discovered").definition().scope().kind()).isEqualTo(ScopeKind.CLUSTER);
        assertThat(flag(result, "kafka.cluster.identity.known")).isTrue();
        assertThat(result.metrics()).noneSatisfy(sample -> assertThat(sample.value()).isEqualTo(observation.cluster.clusterId()));
    }

    private static void assertCompleteTopics(CollectionResult result, NativeObservation observation, List<String> topics) {
        int partitions = 0;
        for (String topic : topics) {
            var read = observation.topicReads.get(topic);
            assertThat(read.failure()).isNull();
            assertTopic(result, read.topic());
            partitions += read.topic().partitions().size();
        }
        assertThat(value(result, "kafka.partitions.observed")).isEqualByComparingTo(BigDecimal.valueOf(partitions));
        assertThat(value(result, "kafka.partitions.state.known")).isEqualByComparingTo(BigDecimal.valueOf(partitions));
        assertThat(value(result, "kafka.partitions.no_leader")).isZero();
        assertThat(value(result, "kafka.partitions.under_replicated")).isZero();
    }

    private static void assertTopic(CollectionResult result, TopicDescription topic) {
        assertThat(value(result, "kafka.topic.partitions", topic.name())).isEqualByComparingTo(BigDecimal.valueOf(topic.partitions().size()));
        assertThat(value(result, "kafka.topic.partitions.observed", topic.name())).isEqualByComparingTo(BigDecimal.valueOf(topic.partitions().size()));
        assertThat(metric(result, "kafka.topic.coverage.complete", topic.name()).value()).isEqualTo(true);
        for (TopicPartitionInfo partition : topic.partitions()) {
            String scope = topic.name() + "#" + partition.partition();
            assertThat(metric(result, "kafka.partition.leader.available", scope).definition().scope().kind()).isEqualTo(ScopeKind.PARTITION);
            assertThat(metric(result, "kafka.partition.leader.available", scope).value()).isEqualTo(true);
            assertThat(value(result, "kafka.partition.leader.id", scope)).isEqualByComparingTo(BigDecimal.valueOf(partition.leader().id()));
            assertThat(value(result, "kafka.partition.replicas", scope)).isEqualByComparingTo(BigDecimal.valueOf(partition.replicas().size()));
            assertThat(value(result, "kafka.partition.isr", scope)).isEqualByComparingTo(BigDecimal.valueOf(partition.isr().size()));
            assertThat(metric(result, "kafka.partition.under_replicated", scope).value())
                    .isEqualTo(partition.isr().size() < partition.replicas().size());
        }
    }

    /** All before/after checks use Admin APIs; this class never constructs, joins or commits a consumer. */
    private static AdminState state(AdminClient client, List<String> owned) throws Exception {
        Map<String, TopicDescription> descriptions = client.describeTopics(owned,
                new DescribeTopicsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).allTopicNames().get(5, TimeUnit.SECONDS);
        var resources = owned.stream().map(topic -> new ConfigResource(ConfigResource.Type.TOPIC, topic)).toList();
        var configs = client.describeConfigs(resources, new DescribeConfigsOptions().timeoutMs(NATIVE_TIMEOUT_MS))
                .all().get(5, TimeUnit.SECONDS);
        Map<TopicPartition, OffsetSpec> earliest = new LinkedHashMap<>();
        Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
        descriptions.forEach((name, description) -> description.partitions().forEach(partition -> {
            TopicPartition key = new TopicPartition(name, partition.partition());
            earliest.put(key, OffsetSpec.earliest());
            latest.put(key, OffsetSpec.latest());
        }));
        var beginnings = client.listOffsets(earliest, new ListOffsetsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(5, TimeUnit.SECONDS);
        var endings = client.listOffsets(latest, new ListOffsetsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(5, TimeUnit.SECONDS);
        Map<String, TopicState> topics = new TreeMap<>();
        descriptions.forEach((name, description) -> {
            Map<String, String> configuration = new TreeMap<>();
            configs.get(new ConfigResource(ConfigResource.Type.TOPIC, name)).entries().stream()
                    .filter(entry -> CONFIG_KEYS.contains(entry.name())).forEach(entry -> configuration.put(entry.name(), entry.value()));
            Map<Integer, Long> first = new TreeMap<>();
            Map<Integer, Long> last = new TreeMap<>();
            List<PartitionState> partitions = description.partitions().stream()
                    .sorted(Comparator.comparingInt(TopicPartitionInfo::partition)).map(partition -> {
                        TopicPartition key = new TopicPartition(name, partition.partition());
                        first.put(partition.partition(), beginnings.get(key).offset());
                        last.put(partition.partition(), endings.get(key).offset());
                        return new PartitionState(partition.partition(), partition.leader() == null ? -1 : partition.leader().id(),
                                partition.replicas().stream().map(Node::id).toList(), partition.isr().stream().map(Node::id).toList());
                    }).toList();
            topics.put(name, new TopicState(description.topicId(), partitions, configuration, first, last));
        });
        return new AdminState(topicSet(client), topics, groups(client));
    }

    private static Set<String> topicSet(AdminClient client) throws Exception {
        return client.listTopics(new ListTopicsOptions().listInternal(true).timeoutMs(NATIVE_TIMEOUT_MS))
                .names().get(5, TimeUnit.SECONDS);
    }
    private static Set<String> groups(AdminClient client) throws Exception {
        return client.listConsumerGroups(new ListConsumerGroupsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(5, TimeUnit.SECONDS)
                .stream().map(group -> group.groupId()).collect(Collectors.toSet());
    }
    private record PartitionState(int id, int leader, List<Integer> replicas, List<Integer> isr) { }
    private record TopicState(Uuid id, List<PartitionState> partitions, Map<String, String> configs,
                              Map<Integer, Long> earliest, Map<Integer, Long> latest) { }
    private record AdminState(Set<String> names, Map<String, TopicState> topics, Set<String> groups) { }

    private static MetricSample metric(CollectionResult result, String key) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)).findFirst().orElseThrow();
    }
    private static MetricSample metric(CollectionResult result, String key, String scope) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key)
                && sample.definition().scope().id().equals(scope)).findFirst().orElseThrow();
    }
    private static BigDecimal value(CollectionResult result, String key) {
        MetricSample sample = metric(result, key);
        assertThat(sample.missingReason()).as(key).isNull();
        return (BigDecimal) sample.value();
    }
    private static BigDecimal value(CollectionResult result, String key, String scope) {
        MetricSample sample = metric(result, key, scope);
        assertThat(sample.missingReason()).as(key).isNull();
        return (BigDecimal) sample.value();
    }
    private static boolean flag(CollectionResult result, String key) {
        MetricSample sample = metric(result, key);
        assertThat(sample.missingReason()).as(key).isNull();
        return (Boolean) sample.value();
    }

    /** Records original native result objects without replacing the transport or any permissions. */
    private static final class RecordingConnections extends KafkaMonitoringConnections {
        final List<NativeObservation> observations = new ArrayList<>();
        int closed;
        @Override public Session open(CollectionRequest request) {
            Session actual = super.open(request);
            NativeObservation observation = new NativeObservation();
            observations.add(observation);
            return new Session() {
                @Override public ClusterRead describeCluster() {
                    observation.cluster = actual.describeCluster();
                    return observation.cluster;
                }
                @Override public Map<String, TopicRead> describeTopics(List<String> topics) {
                    observation.requestedTopics.addAll(topics);
                    Map<String, TopicRead> reads = actual.describeTopics(topics);
                    observation.topicReads.putAll(reads);
                    return reads;
                }
                @Override public void close() { actual.close(); closed++; }
            };
        }
    }
    private static final class NativeObservation {
        KafkaMonitoringConnections.ClusterRead cluster;
        final List<String> requestedTopics = new ArrayList<>();
        final Map<String, KafkaMonitoringConnections.TopicRead> topicReads = new LinkedHashMap<>();
    }

    private static final class Collector implements AutoCloseable {
        final Object lifecycle = new Object();
        final Clock clock = Clock.systemUTC();
        final MonitoringProperties properties = new MonitoringProperties();
        final MonitoringCounterStore counters = new MonitoringCounterStore(4, 64);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(4, 64, 2);
        final RecordingConnections connections = new RecordingConnections();
        final KafkaMonitoringAdapter adapter = new KafkaMonitoringAdapter(properties, connections, clock);
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
}
