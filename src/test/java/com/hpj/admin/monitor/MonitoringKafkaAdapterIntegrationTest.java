package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.AlterConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
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
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
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

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeCommittedProgressCoversKnownMissingNegativeAndBoundedScopesWithoutChangingCommits() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.start("kafka");
             AdminClient observer = environment.kafkaAdmin(); Collector collector = new Collector()) {
            List<String> owned = topicNames(environment);
            List<String> groups = List.of(environment.resourceName() + "_known", environment.resourceName() + "_missing",
                    environment.resourceName() + "_negative");
            try {
                prepareTopics(observer, owned);
                environment.prepareData();
                List<TopicPartition> selected = partitions(observer, owned.subList(0, 2));
                List<TopicPartition> all = partitions(observer, owned);
                Map<TopicPartition, OffsetAndMetadata> zeros = new LinkedHashMap<>();
                all.forEach(partition -> zeros.put(partition, new OffsetAndMetadata(0)));
                prepareCommits(observer, groups.get(0), zeros);
                // This group has commits only outside the configured topics; queried partitions must remain unknown.
                prepareCommits(observer, groups.get(1), Map.of(new TopicPartition(owned.get(2), 0), new OffsetAndMetadata(0)));
                Map<TopicPartition, OffsetAndMetadata> negative = new LinkedHashMap<>(zeros);
                TopicPartition anomalous = selected.get(0);
                long end = observer.listOffsets(Map.of(anomalous, OffsetSpec.latest())).partitionResult(anomalous).get(5, TimeUnit.SECONDS).offset();
                negative.put(anomalous, new OffsetAndMetadata(end + 2));
                prepareCommits(observer, groups.get(2), negative);
                var before = committedState(observer, groups, all);
                Set<String> beforeGroups = groups(observer);
                KafkaAdmin business = source(environment);
                var settings = business.getConfigurationProperties();
                CollectionResult result = collector.collect(resolve(business, "committed", owned.subList(0, 2), groups));
                NativeObservation nativeRead = collector.connections.observations.get(0);
                assertThat(nativeRead.requestedGroups).containsExactlyElementsOf(groups);
                assertThat(nativeRead.requestedPartitions).containsExactlyElementsOf(selected);
                assertThat(nativeRead.lag.ends()).doesNotContainKey(new TopicPartition(owned.get(2), 0));
                String known = groups.get(0);
                assertThat(value(result, "kafka.group.lag.total", known)).isEqualByComparingTo("1");
                assertThat(value(result, "kafka.group.lag.maximum", known)).isEqualByComparingTo("1");
                assertThat(value(result, "kafka.group.partitions.expected", known)).isEqualByComparingTo("5");
                assertThat(value(result, "kafka.group.partitions.covered", known)).isEqualByComparingTo("5");
                assertThat(metric(result, "kafka.group.coverage.complete", known).value()).isEqualTo(true);
                for (TopicPartition partition : selected) {
                    var readEnd = nativeRead.lag.ends().get(partition);
                    var readCommit = nativeRead.lag.committed().get(known).get(partition);
                    assertThat(readEnd.failure()).isNull();
                    assertThat(readCommit.failure()).isNull();
                    assertThat(value(result, "kafka.group.partition.lag", lagScope(known, partition)))
                            .isEqualByComparingTo(BigDecimal.valueOf(readEnd.offset()).subtract(BigDecimal.valueOf(readCommit.offset())));
                    assertThat(metric(result, "kafka.group.partition.lag", lagScope(groups.get(1), partition)).missingReason())
                            .isEqualTo(MissingReason.NO_COMMITTED_OFFSET);
                }
                assertThat(metric(result, "kafka.group.lag.total", groups.get(1)).value()).isNull();
                assertThat(metric(result, "kafka.group.lag.known.total", groups.get(1)).value()).isNull();
                assertThat(value(result, "kafka.group.partitions.covered", groups.get(1))).isZero();
                assertThat(value(result, "kafka.group.partition.lag", lagScope(groups.get(2), anomalous))).isEqualByComparingTo("-2");
                assertThat(metric(result, "kafka.group.partition.lag.anomaly", lagScope(groups.get(2), anomalous)).value()).isEqualTo(true);
                assertThat(metric(result, "kafka.group.lag.total", groups.get(2)).value()).isNull();
                assertThat(value(result, "kafka.group.lag.anomalies", groups.get(2))).isEqualByComparingTo("1");

                collector.properties.getLimits().setPartitions(2);
                collector.properties.getLimits().setConsumerGroups(1);
                CollectionResult limited = collector.collect(resolve(business, "committed-limited", owned.subList(0, 2), groups));
                NativeObservation bounded = collector.connections.observations.get(1);
                assertThat(bounded.requestedGroups).containsExactly(known);
                assertThat(bounded.requestedPartitions).containsExactlyElementsOf(selected.subList(0, 2));
                assertThat(value(limited, "kafka.group.partitions.expected", known)).isEqualByComparingTo("5");
                assertThat(value(limited, "kafka.group.partitions.covered", known)).isEqualByComparingTo("2");
                assertThat(metric(limited, "kafka.group.lag.total", known).missingReason()).isEqualTo(MissingReason.PARTIAL_SCOPE);
                assertThat(metric(limited, "kafka.group.lag.known.total", known).missingReason()).isNull();
                assertThat(collector.connections.closed).isEqualTo(2);
                assertThat(committedState(observer, groups, all)).isEqualTo(before);
                assertThat(groups(observer)).isEqualTo(beforeGroups);
                assertThat(business.getConfigurationProperties()).isEqualTo(settings);
            } finally {
                observer.deleteConsumerGroups(groups).all().get(10, TimeUnit.SECONDS);
                deleteOwnedTopics(observer, owned);
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeGroupAclDenialKeepsOtherGroupProgressAndLeavesAllCommitsUnchanged() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.startKafkaWithAuthorizer();
             AdminClient observer = environment.kafkaAdmin(); Collector collector = new Collector()) {
            List<String> owned = topicNames(environment);
            List<String> groups = List.of(environment.resourceName() + "_allowed", environment.resourceName() + "_denied");
            AclBinding denial = new AclBinding(new ResourcePattern(ResourceType.GROUP, groups.get(1), PatternType.LITERAL),
                    new AccessControlEntry("User:ANONYMOUS", "*", AclOperation.DESCRIBE, AclPermissionType.DENY));
            boolean created = false;
            try {
                prepareTopics(observer, owned);
                environment.prepareData();
                List<TopicPartition> selected = partitions(observer, owned.subList(0, 2));
                Map<TopicPartition, OffsetAndMetadata> zeros = new LinkedHashMap<>();
                selected.forEach(partition -> zeros.put(partition, new OffsetAndMetadata(0)));
                for (String group : groups) prepareCommits(observer, group, zeros);
                var before = committedState(observer, groups, selected);
                observer.createAcls(List.of(denial), new CreateAclsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(5, TimeUnit.SECONDS);
                created = true;
                await().atMost(Duration.ofSeconds(15)).until(() -> groupIsDenied(observer, groups.get(1), selected));
                CollectionResult result = collector.collect(resolve(source(environment), "committed-acl", owned.subList(0, 2), groups));
                assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
                assertThat(result.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
                assertThat(value(result, "kafka.group.lag.total", groups.get(0))).isEqualByComparingTo("1");
                assertThat(metric(result, "kafka.group.lag.total", groups.get(1)).value()).isNull();
                for (TopicPartition partition : selected) {
                    assertThat(metric(result, "kafka.group.partition.committed_offset", lagScope(groups.get(1), partition)).missingReason())
                            .isEqualTo(MissingReason.UNAUTHORIZED);
                }
                assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
                observer.deleteAcls(List.of(denial.toFilter()), new DeleteAclsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(5, TimeUnit.SECONDS);
                created = false;
                await().atMost(Duration.ofSeconds(15)).until(() -> !groupIsDenied(observer, groups.get(1), selected));
                assertThat(committedState(observer, groups, selected)).isEqualTo(before);
                assertThat(collector.connections.closed).isEqualTo(1);
            } finally {
                if (created) observer.deleteAcls(List.of(denial.toFilter())).all().get(5, TimeUnit.SECONDS);
                observer.deleteConsumerGroups(groups).all().get(10, TimeUnit.SECONDS);
                deleteOwnedTopics(observer, owned);
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeCapacityReadsTwoSharedVolumeDirectoriesAndScopedCopiesWithIndependentTtlAndNoWrites() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.startKafkaWithLogDirectories();
             AdminClient observer = environment.kafkaAdmin(); Collector collector = new Collector()) {
            List<String> owned = topicNames(environment);
            try {
                prepareTopics(observer, owned);
                environment.prepareData();
                AdminState before = state(observer, owned);
                List<TopicPartition> selected = partitions(observer, owned.subList(0, 2));
                ResolvedTarget target = resolve(source(environment), "log-capacity", owned.subList(0, 2));
                CollectionResult capacity = collector.collect(target, CollectionKind.CAPACITY);
                NativeObservation nativeRead = collector.connections.observations.get(0);
                assertThat(capacity.status()).isEqualTo(CollectionStatus.SUCCESS);
                assertThat(capacity.serviceProbe()).isNull();
                assertThat(nativeRead.cluster).isNull();
                assertThat(nativeRead.requestedGroups).isEmpty();
                assertThat(nativeRead.requestedPartitions).containsExactlyInAnyOrderElementsOf(selected);
                assertThat(nativeRead.directories).hasSize(1);
                var broker = nativeRead.directories.entrySet().iterator().next();
                assertThat(broker.getValue().failure()).isNull();
                assertThat(broker.getValue().directories()).hasSize(2);
                List<BigDecimal> expectedCopies = new ArrayList<>();
                List<BigDecimal> totals = new ArrayList<>();
                List<BigDecimal> usable = new ArrayList<>();
                for (var directory : broker.getValue().directories().values()) {
                    assertThat(directory.error()).isNull();
                    assertThat(directory.totalBytes().isPresent()).isTrue();
                    assertThat(directory.usableBytes().isPresent()).isTrue();
                    totals.add(BigDecimal.valueOf(directory.totalBytes().getAsLong()));
                    usable.add(BigDecimal.valueOf(directory.usableBytes().getAsLong()));
                    directory.replicaInfos().forEach((partition, info) -> { if (selected.contains(partition)) expectedCopies.add(BigDecimal.valueOf(info.size())); });
                }
                assertThat(totals).hasSize(2).allSatisfy(total -> assertThat(total).isPositive());
                assertThat(totals.get(0)).isEqualByComparingTo(totals.get(1));
                assertThat(capacity.metrics().stream().filter(sample -> sample.definition().key().equals("kafka.replica.log.bytes")).map(MetricSample::value))
                        .containsExactlyInAnyOrderElementsOf(expectedCopies);
                assertThat(capacity.metrics().stream().filter(sample -> sample.definition().key().equals("kafka.log_directory.volume.total.bytes")).map(MetricSample::value))
                        .containsExactlyInAnyOrderElementsOf(totals);
                assertThat(capacity.metrics().stream().filter(sample -> sample.definition().key().equals("kafka.log_directory.volume.usable.bytes")).map(MetricSample::value))
                        .containsExactlyInAnyOrderElementsOf(usable);
                assertThat(capacity.metrics()).filteredOn(sample -> sample.definition().key().equals("kafka.log_directory.volume.identity.known"))
                        .hasSize(2).allSatisfy(sample -> assertThat(sample.value()).isEqualTo(false));
                assertThat(capacity.metrics()).allSatisfy(sample -> {
                    if (sample.value() != null) assertThat(Duration.between(sample.sampledAt(), sample.validUntil())).isEqualTo(Duration.ofSeconds(180));
                });
                assertThat(capacity.metrics()).noneMatch(sample -> sample.definition().scope().id().startsWith(owned.get(2) + "#"));
                assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(capacity)).doesNotContain("/tmp/monitor-log-", environment.bootstrapServers());
                collector.collect(target);
                assertThat(collector.snapshots.snapshot(target.display().id()).orElseThrow().metrics())
                        .filteredOn(stored -> stored.kind() == CollectionKind.CAPACITY)
                        .allSatisfy(stored -> assertThat(stored.latestAttempt().sampledAt()).isEqualTo(capacity.completedAt()));
                assertThat(collector.connections.closed).isEqualTo(2);
                assertThat(state(observer, owned)).isEqualTo(before);
            } finally { deleteOwnedTopics(observer, owned); }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void nativeLogDirectoryAclDenialPreservesPreviousCapacityEvidenceWithoutChangingTopics() throws Exception {
        try (MonitoringTestEnvironment environment = MonitoringTestEnvironment.startKafkaWithAuthorizer();
             AdminClient observer = environment.kafkaAdmin(); Collector collector = new Collector()) {
            List<String> owned = topicNames(environment);
            AclBinding denial = new AclBinding(new ResourcePattern(ResourceType.CLUSTER, "kafka-cluster", PatternType.LITERAL),
                    new AccessControlEntry("User:ANONYMOUS", "*", AclOperation.DESCRIBE, AclPermissionType.DENY));
            // Once a resource has ACLs, allow.everyone.if.no.acl.found no longer applies to its other operations.
            // Preserve this disposable fixture's baseline permissions, including controller forwarding and ACL cleanup.
            AclBinding baseline = new AclBinding(denial.pattern(),
                    new AccessControlEntry("User:ANONYMOUS", "*", AclOperation.ALL, AclPermissionType.ALLOW));
            List<AclBinding> bindings = List.of(baseline, denial);
            boolean created = false;
            try {
                prepareTopics(observer, owned);
                environment.prepareData();
                AdminState before = state(observer, owned);
                ResolvedTarget target = resolve(source(environment), "log-acl", owned.subList(0, 2));
                CollectionResult first = collector.collect(target, CollectionKind.CAPACITY);
                List<Integer> brokers = collector.connections.observations.get(0).requestedBrokers;
                observer.createAcls(bindings, new CreateAclsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(5, TimeUnit.SECONDS);
                created = true;
                await().atMost(Duration.ofSeconds(15)).until(() -> logDirectoriesDenied(observer, brokers));
                CollectionResult denied = collector.collect(target, CollectionKind.CAPACITY);
                assertThat(denied.status()).isEqualTo(CollectionStatus.UNAUTHORIZED);
                assertThat(denied.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
                assertThat(denied.serviceProbe()).isNull();
                assertThat(denied.inventoryComplete()).isFalse();
                assertThat(collector.connections.observations.get(1).cluster).isNull();
                assertThat(collector.snapshots.snapshot(target.display().id()).orElseThrow().metrics())
                        .filteredOn(stored -> stored.kind() == CollectionKind.CAPACITY && stored.latestAttempt().definition().key().equals("kafka.replica.log.bytes"))
                        .isNotEmpty().allSatisfy(stored -> assertThat(stored.lastSuccess().sampledAt()).isEqualTo(first.completedAt()));
                observer.deleteAcls(bindings.stream().map(AclBinding::toFilter).toList(), new DeleteAclsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(5, TimeUnit.SECONDS);
                created = false;
                await().atMost(Duration.ofSeconds(15)).until(() -> !logDirectoriesDenied(observer, brokers));
                assertThat(state(observer, owned)).isEqualTo(before);
                assertThat(collector.connections.closed).isEqualTo(2);
            } finally {
                if (created) observer.deleteAcls(bindings.stream().map(AclBinding::toFilter).toList()).all().get(5, TimeUnit.SECONDS);
                deleteOwnedTopics(observer, owned);
            }
        }
    }

    private static boolean logDirectoriesDenied(AdminClient observer, List<Integer> brokers) throws Exception {
        try { observer.describeLogDirs(brokers).allDescriptions().get(5, TimeUnit.SECONDS); return false; }
        catch (ExecutionException failure) { if (failure.getCause() instanceof ClusterAuthorizationException) return true; throw failure; }
    }

    private static List<TopicPartition> partitions(AdminClient observer, List<String> topics) throws Exception {
        var descriptions = observer.describeTopics(topics).allTopicNames().get(5, TimeUnit.SECONDS);
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : topics) for (TopicPartitionInfo partition : descriptions.get(topic).partitions()) {
            partitions.add(new TopicPartition(topic, partition.partition()));
        }
        return List.copyOf(partitions);
    }
    private static void prepareCommits(AdminClient observer, String group, Map<TopicPartition, OffsetAndMetadata> offsets) {
        // Writes only the owned fixture's offsets before the measured read-only interval; no consumer is created.
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> observer.alterConsumerGroupOffsets(group, offsets,
                new AlterConsumerGroupOffsetsOptions().timeoutMs(10000)).all().get(12, TimeUnit.SECONDS));
    }
    private static Map<String, Map<TopicPartition, OffsetAndMetadata>> committedState(AdminClient observer, List<String> groups,
                                                                                  List<TopicPartition> partitions) throws Exception {
        Map<String, ListConsumerGroupOffsetsSpec> specs = new LinkedHashMap<>();
        groups.forEach(group -> specs.put(group, new ListConsumerGroupOffsetsSpec().topicPartitions(partitions)));
        return observer.listConsumerGroupOffsets(specs, new ListConsumerGroupOffsetsOptions().timeoutMs(NATIVE_TIMEOUT_MS)).all().get(5, TimeUnit.SECONDS);
    }
    private static boolean groupIsDenied(AdminClient observer, String group, List<TopicPartition> partitions) throws Exception {
        try { committedState(observer, List.of(group), partitions); return false; }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof GroupAuthorizationException) return true;
            throw failure;
        }
    }
    private static String lagScope(String group, TopicPartition partition) { return group + "/" + partition.topic() + "#" + partition.partition(); }

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
        return resolve(source, id, topics, List.of());
    }

    private static ResolvedTarget resolve(KafkaAdmin source, String id, List<String> topics, List<String> groups) {
        MonitoringProperties.Target declaration = new MonitoringProperties.Target();
        declaration.setId(id);
        declaration.setType(MiddlewareType.KAFKA);
        declaration.setEnabled(true);
        declaration.setConnectionSource("businessKafka");
        declaration.getScope().setTopics(topics);
        declaration.getScope().setConsumerGroups(groups);
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
                @Override public LagRead readCommittedLag(List<String> groups, List<TopicPartition> partitions) {
                    observation.requestedGroups.addAll(groups);
                    observation.requestedPartitions.addAll(partitions);
                    observation.lag = actual.readCommittedLag(groups, partitions);
                    return observation.lag;
                }
                @Override public Map<Integer, DirectoriesRead> describeLogDirectories(List<Integer> brokers, List<TopicPartition> partitions) {
                    observation.requestedBrokers.addAll(brokers);
                    observation.requestedPartitions.addAll(partitions);
                    observation.directories = actual.describeLogDirectories(brokers, partitions);
                    return observation.directories;
                }
                @Override public void close() { actual.close(); closed++; }
            };
        }
    }
    private static final class NativeObservation {
        KafkaMonitoringConnections.ClusterRead cluster;
        final List<String> requestedTopics = new ArrayList<>();
        final Map<String, KafkaMonitoringConnections.TopicRead> topicReads = new LinkedHashMap<>();
        final List<String> requestedGroups = new ArrayList<>();
        final List<TopicPartition> requestedPartitions = new ArrayList<>();
        KafkaMonitoringConnections.LagRead lag;
        final List<Integer> requestedBrokers = new ArrayList<>();
        Map<Integer, KafkaMonitoringConnections.DirectoriesRead> directories;
    }

    private static final class Collector implements AutoCloseable {
        final Object lifecycle = new Object();
        final Clock clock = Clock.systemUTC();
        final MonitoringProperties properties = new MonitoringProperties();
        final MonitoringCounterStore counters = new MonitoringCounterStore(4, 5000);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(4, 5000, 2);
        final RecordingConnections connections = new RecordingConnections();
        final KafkaMonitoringAdapter adapter = new KafkaMonitoringAdapter(properties, connections, clock);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
        long sequence;

        CollectionResult collect(ResolvedTarget target) throws Exception {
            return collect(target, CollectionKind.ORDINARY);
        }
        CollectionResult collect(ResolvedTarget target, CollectionKind kind) throws Exception {
            String id = target.display().id();
            var binding = target.bindings().get(0);
            var source = binding.connection();
            snapshots.activate(id, 1);
            Instant scheduled = clock.instant();
            Duration timeout = properties.getCollectionTimeout();
            CollectionControl control = new CollectionControl(lifecycle, () -> true, clock, System::nanoTime,
                    System.nanoTime() + timeout.toNanos(), cleanup, counters, id, source.source(),
                    kind, kind == CollectionKind.CAPACITY ? properties.getCapacityInterval() : properties.getOrdinaryInterval(), source.client());
            CollectionRequest request = new CollectionRequest(id, source.source(), source.source(), kind,
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
