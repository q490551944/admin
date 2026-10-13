package com.hpj.admin.monitor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringCounterStore;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static com.hpj.admin.monitor.kafka.KafkaMonitoringConnections.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Actual adapter, native metadata DTOs and shared snapshot lifecycle; only Admin exchanges are substituted. */
class MonitoringKafkaAdapterTest {
    private static final Instant START = Instant.parse("2026-09-15T08:00:00Z");
    private static final String SECRET = "sasl-secret-sentinel@private-broker:9092";
    private static final String CLUSTER_ID = "private-cluster-identity-sentinel";
    private static final Node ZERO = new Node(0, "private-broker-zero", 9092, "private-rack-zero");
    private static final Node ONE = new Node(1, "private-broker-one", 9093, "private-rack-one");

    @Test
    void mapsNativeMetadataWithExplicitConfiguredScopeAndNoLeaderUnderReplicationStates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.collect(0);
            assertThat(fixture.adapter.type()).isEqualTo(MiddlewareType.KAFKA);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(result.reason()).isNull();
            assertThat(result.inventoryComplete()).isTrue();
            assertThat(result.metrics()).hasSize(38);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(number(result, "kafka.probe.duration", "broker-target")).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(metric(result, "kafka.probe.duration", "broker-target").definition().unit()).isEqualTo(Unit.MILLISECONDS);
            assertThat(number(result, "kafka.brokers.discovered", "broker-target")).isEqualByComparingTo("2");
            assertThat(number(result, "kafka.brokers.observed", "broker-target")).isEqualByComparingTo("2");
            assertThat(metric(result, "kafka.brokers.discovered", "broker-target").definition().scope().kind()).isEqualTo(ScopeKind.CLUSTER);
            assertValue(result, "kafka.cluster.identity.known", "broker-target", true);
            assertThat(number(result, "kafka.topics.configured", "broker-target")).isEqualByComparingTo("2");
            assertThat(number(result, "kafka.topics.observed", "broker-target")).isEqualByComparingTo("2");
            assertThat(number(result, "kafka.partitions.observed", "broker-target")).isEqualByComparingTo("3");
            assertThat(number(result, "kafka.partitions.no_leader", "broker-target")).isEqualByComparingTo("1");
            assertThat(number(result, "kafka.partitions.under_replicated", "broker-target")).isEqualByComparingTo("1");
            assertThat(number(result, "kafka.partitions.state.known", "broker-target")).isEqualByComparingTo("3");
            assertValue(result, "kafka.coverage.complete", "broker-target", true);
            assertValue(result, "kafka.coverage.truncated", "broker-target", false);
            assertThat(number(result, "kafka.topic.partitions", "events")).isEqualByComparingTo("2");
            assertThat(metric(result, "kafka.topic.partitions", "events").definition().scope().kind()).isEqualTo(ScopeKind.TOPIC);
            assertThat(number(result, "kafka.partition.leader.id", "events#0")).isEqualByComparingTo("0");
            assertThat(metric(result, "kafka.partition.leader.id", "events#1").missingReason()).isEqualTo(MissingReason.NOT_APPLICABLE);
            assertValue(result, "kafka.partition.leader.available", "events#1", false);
            assertValue(result, "kafka.partition.under_replicated", "events#1", true);
            assertThat(number(result, "kafka.partition.replicas", "events#1")).isEqualByComparingTo("2");
            assertThat(number(result, "kafka.partition.isr", "events#1")).isEqualByComparingTo("1");
            assertThat(metric(result, "kafka.partition.isr", "events#1").validUntil()).isEqualTo(START.plusSeconds(45));
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().nodeId()).containsOnlyNulls();
            assertThat(fixture.counters.seriesCount("broker-target")).isZero();
            var ordered = inOrder(fixture.session);
            ordered.verify(fixture.session).describeCluster();
            ordered.verify(fixture.session).describeTopics(List.of("events", "audit"));
            ordered.verify(fixture.session).close();
            verifyNoMoreInteractions(fixture.session);
            assertSafe(result);
        }
    }

    @Test
    void emptyConfiguredScopeNeverEnumeratesTopicsAndNativeZeroBrokerCountRemainsZero() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeTopics = List.of();
            fixture.cluster = new ClusterRead(List.of(), CLUSTER_ID, null, null);
            CollectionResult result = fixture.collect(0);
            assertThat(result.metrics()).hasSize(17);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            assertThat(number(result, "kafka.brokers.discovered", "broker-target")).isEqualByComparingTo("0");
            assertThat(number(result, "kafka.topics.observed", "broker-target")).isEqualByComparingTo("0");
            assertThat(number(result, "kafka.partitions.observed", "broker-target")).isEqualByComparingTo("0");
            assertValue(result, "kafka.coverage.complete", "broker-target", true);
            verify(fixture.session, never()).describeTopics(anyList());
        }
    }

    @Test
    void onlyConfiguredTopicNamesAreRequestedAndExtraMetadataIsNeverPublished() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeTopics = List.of("events", "events");
            fixture.metadata.put("excluded", topic("excluded", partition(0, ZERO, List.of(ZERO), List.of(ZERO))));
            CollectionResult result = fixture.collect(0);
            verify(fixture.session).describeTopics(List.of("events"));
            assertThat(number(result, "kafka.topics.configured", "broker-target")).isEqualByComparingTo("1");
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().id()).doesNotContain("audit", "excluded", "audit#0", "excluded#0");
        }
    }

    @Test
    void partialTopicAclDenialKeepsReadableTopicsAndRetainsOldUnknownPartitionInventory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            fixture.metadata.put("audit", failed(MissingReason.UNAUTHORIZED));
            CollectionResult denied = fixture.collect(15_000);
            assertThat(denied.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(denied.reason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(denied.inventoryComplete()).isFalse();
            assertThat(denied.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            for (String key : topicKeys()) assertThat(metric(denied, key, "audit").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(number(denied, "kafka.topic.partitions", "events")).isEqualByComparingTo("2");
            assertValue(denied, "kafka.coverage.complete", "broker-target", false);
            assertValue(denied, "kafka.coverage.truncated", "broker-target", false);
            assertThat(fixture.snapshots.snapshot("broker-target").orElseThrow().metrics())
                    .anyMatch(stored -> stored.latestAttempt().definition().scope().id().equals("audit#0")
                            && stored.latestAttempt().sampledAt().equals(START));
            fixture.metadata.put("audit", topic("audit"));
            CollectionResult recovered = fixture.collect(30_000);
            assertThat(recovered.inventoryComplete()).isTrue();
            assertThat(fixture.snapshots.snapshot("broker-target").orElseThrow().metrics())
                    .noneMatch(stored -> stored.latestAttempt().definition().scope().id().equals("audit#0"));
        }
    }

    @Test
    void unknownTopicIsNotApplicableWithoutInventingAPartitionCountOrAutoDiscovery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.metadata.put("audit", failed(MissingReason.NOT_APPLICABLE));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.NOT_APPLICABLE);
            for (String key : topicKeys()) assertThat(metric(result, key, "audit").missingReason()).isEqualTo(MissingReason.NOT_APPLICABLE);
            assertThat(number(result, "kafka.partitions.observed", "broker-target")).isEqualByComparingTo("2");
            assertValue(result, "kafka.coverage.complete", "broker-target", false);
            verify(fixture.session).describeTopics(List.of("events", "audit"));
        }
    }

    @Test
    void missingTopicReplyIsAnIndividualFailureAndNeverAssumedToHaveZeroPartitions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.metadata.remove("audit");
            CollectionResult result = fixture.collect(0);
            assertThat(metric(result, "kafka.topic.partitions", "audit").missingReason()).isEqualTo(MissingReason.FAILED);
            assertThat(number(result, "kafka.topics.observed", "broker-target")).isEqualByComparingTo("1");
            assertThat(result.inventoryComplete()).isFalse();
        }
    }

    @Test
    void clusterFieldPermissionFailuresRemainIndependentOfAvailableMetadataAndTopicReads() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.cluster = new ClusterRead(null, CLUSTER_ID, new Failure(MissingReason.UNAUTHORIZED, false), null);
            CollectionResult result = fixture.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(metric(result, "kafka.brokers.discovered", "broker-target").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertValue(result, "kafka.cluster.identity.known", "broker-target", true);
            assertThat(number(result, "kafka.topic.partitions", "events")).isEqualByComparingTo("2");
            assertValue(result, "kafka.coverage.complete", "broker-target", false);
        }
        try (Fixture fixture = new Fixture()) {
            fixture.cluster = new ClusterRead(List.of(ZERO), null, null, new Failure(MissingReason.UNAUTHORIZED, false));
            CollectionResult result = fixture.collect(0);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(number(result, "kafka.brokers.discovered", "broker-target")).isEqualByComparingTo("1");
            assertThat(metric(result, "kafka.cluster.identity.known", "broker-target").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
        }
    }

    @Test
    void anUnidentifiedClusterIsExplicitlyUnknownWhileReadableMetadataIsPreserved() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.cluster = new ClusterRead(List.of(ZERO), null, null, null);
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertValue(result, "kafka.cluster.identity.known", "broker-target", false);
            assertValue(result, "kafka.coverage.complete", "broker-target", false);
            assertThat(number(result, "kafka.topic.partitions", "events")).isEqualByComparingTo("2");
        }
    }

    @Test
    void deniedClusterProbeDoesNotHideReadableTopicMetadataOrClaimConnectionFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.describeCluster()).thenThrow(new Failure(MissingReason.UNAUTHORIZED, false));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.UNKNOWN);
            assertThat(result.serviceProbe().reason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(metric(result, "kafka.probe.duration", "broker-target").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(number(result, "kafka.topic.partitions", "events")).isEqualByComparingTo("2");
            verify(fixture.session).describeTopics(List.of("events", "audit"));
        }
    }

    @Test
    void topicLimitIsExplicitAndItsCompleteDisplayInventoryRetiresPreviouslyDisplayedTopics() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            clearInvocations(fixture.session);
            fixture.properties.getLimits().setTopics(1);
            CollectionResult result = fixture.collect(15_000);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.inventoryComplete()).isTrue();
            assertValue(result, "kafka.coverage.truncated", "broker-target", true);
            assertValue(result, "kafka.coverage.complete", "broker-target", false);
            assertThat(number(result, "kafka.topics.configured", "broker-target")).isEqualByComparingTo("2");
            assertThat(number(result, "kafka.topics.requested", "broker-target")).isEqualByComparingTo("1");
            verify(fixture.session).describeTopics(List.of("events"));
            assertThat(fixture.snapshots.snapshot("broker-target").orElseThrow().metrics())
                    .noneMatch(stored -> stored.latestAttempt().definition().scope().id().startsWith("audit"));
        }
    }

    @Test
    void partitionLimitReportsNativeTopicCountsSeparatelyFromDisplayedCoverage() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.getLimits().setPartitions(1);
            CollectionResult result = fixture.collect(0);
            assertThat(result.metrics()).hasSize(28);
            assertThat(result.inventoryComplete()).isTrue();
            assertValue(result, "kafka.coverage.truncated", "broker-target", true);
            assertThat(number(result, "kafka.topic.partitions", "events")).isEqualByComparingTo("2");
            assertThat(number(result, "kafka.topic.partitions.observed", "events")).isEqualByComparingTo("1");
            assertThat(number(result, "kafka.topic.partitions", "audit")).isEqualByComparingTo("1");
            assertThat(number(result, "kafka.topic.partitions.observed", "audit")).isEqualByComparingTo("0");
            assertValue(result, "kafka.topic.coverage.complete", "events", false);
            assertValue(result, "kafka.topic.coverage.complete", "audit", false);
            assertThat(number(result, "kafka.partitions.no_leader", "broker-target")).isEqualByComparingTo("0");
            assertThat(number(result, "kafka.partitions.state.known", "broker-target")).isEqualByComparingTo("1");
        }
    }

    @Test
    void nodeLimitPreservesNativeCardinalityAndMarksUncheckedReplicaStateUnavailable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.getLimits().setNodes(1);
            CollectionResult result = fixture.collect(0);
            assertThat(number(result, "kafka.brokers.discovered", "broker-target")).isEqualByComparingTo("2");
            assertThat(number(result, "kafka.brokers.observed", "broker-target")).isEqualByComparingTo("1");
            assertThat(number(result, "kafka.partition.replicas", "events#0")).isEqualByComparingTo("2");
            assertThat(metric(result, "kafka.partition.under_replicated", "events#0").missingReason()).isEqualTo(MissingReason.UNSUPPORTED);
            assertValue(result, "kafka.coverage.truncated", "broker-target", true);
            assertValue(result, "kafka.coverage.complete", "broker-target", false);
            assertThat(number(result, "kafka.partitions.state.known", "broker-target")).isEqualByComparingTo("1");
        }
    }

    @Test
    void totalMetricBudgetBoundsManyConfiguredTopicsBeforeAnyAdminRequest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.getLimits().setTopics(100_000);
            fixture.scopeTopics = IntStream.range(0, 2000).mapToObj(index -> "topic-" + index).toList();
            fixture.metadata.clear();
            fixture.scopeTopics.forEach(name -> fixture.metadata.put(name, topic(name)));
            CollectionResult result = fixture.collect(0);
            assertThat(result.metrics()).hasSize(4499);
            assertThat(number(result, "kafka.limits.topics", "broker-target")).isEqualByComparingTo("1494");
            assertThat(number(result, "kafka.limits.partitions", "broker-target")).isEqualByComparingTo("0");
            assertThat(number(result, "kafka.topics.requested", "broker-target")).isEqualByComparingTo("1494");
            assertValue(result, "kafka.coverage.truncated", "broker-target", true);
            verify(fixture.session).describeTopics(fixture.scopeTopics.subList(0, 1494));
        }
    }

    @Test
    void totalMetricBudgetBoundsPartitionsAndLaterTruncationRetiresExcessSeries() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.getLimits().setPartitions(100_000);
            fixture.metadata.put("events", new TopicRead(new TopicDescription("events", false,
                    IntStream.range(0, 2000).mapToObj(index -> partition(index, ZERO, List.of(ZERO), List.of(ZERO))).toList()), null));
            CollectionResult result = fixture.collect(0);
            assertThat(result.metrics()).hasSize(4498);
            assertThat(number(result, "kafka.limits.partitions", "broker-target")).isEqualByComparingTo("895");
            assertThat(number(result, "kafka.partitions.observed", "broker-target")).isEqualByComparingTo("895");
            assertThat(result.inventoryComplete()).isTrue();
            fixture.properties.getLimits().setPartitions(1);
            fixture.collect(15_000);
            assertThat(fixture.snapshots.snapshot("broker-target").orElseThrow().metrics()).hasSize(28);
        }
    }

    @Test
    void leaderChangesDoNotCreateNewSeriesKeysOrAccumulateOldLeaderScopes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.collect(0);
            for (int attempt = 1; attempt <= 6; attempt++) {
                Node leader = attempt % 2 == 0 ? ZERO : ONE;
                fixture.metadata.put("events", topic("events", partition(0, leader, List.of(ZERO, ONE), List.of(ZERO, ONE)),
                        partition(1, null, List.of(ZERO, ONE), List.of(ZERO))));
                CollectionResult result = fixture.collect(attempt * 15_000L);
                assertThat(number(result, "kafka.partition.leader.id", "events#0")).isEqualByComparingTo(Integer.toString(leader.id()));
                assertThat(fixture.snapshots.snapshot("broker-target").orElseThrow().metrics()).hasSize(38);
            }
        }
    }

    @Test
    void nullAndNoNodeLeaderRepresentKnownAbsenceAndEmptyIsrIsKnownUnderReplication() throws Exception {
        for (Node leader : new Node[] {null, Node.noNode()}) {
            try (Fixture fixture = new Fixture()) {
                fixture.metadata.put("events", topic("events", partition(0, leader, List.of(ZERO), List.of())));
                CollectionResult result = fixture.collect(0);
                assertValue(result, "kafka.partition.leader.available", "events#0", false);
                assertThat(metric(result, "kafka.partition.leader.id", "events#0").missingReason()).isEqualTo(MissingReason.NOT_APPLICABLE);
                assertThat(number(result, "kafka.partition.isr", "events#0")).isEqualByComparingTo("0");
                assertValue(result, "kafka.partition.under_replicated", "events#0", true);
                assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            }
        }
    }

    @Test
    void invalidLeaderOutsideAssignedReplicasIsUnknownRatherThanHealthy() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.metadata.put("events", topic("events", partition(0, new Node(99, SECRET, 9092), List.of(ZERO), List.of(ZERO))));
            CollectionResult result = fixture.collect(0);
            assertThat(metric(result, "kafka.partition.leader.available", "events#0").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(metric(result, "kafka.partition.leader.id", "events#0").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertValue(result, "kafka.topic.coverage.complete", "events", false);
            assertValue(result, "kafka.coverage.complete", "broker-target", false);
            assertSafe(result);
        }
    }

    @Test
    void invalidReplicaAndIsrSetsCannotProduceInventedHealthyState() throws Exception {
        List<TopicPartitionInfo> invalid = List.of(
                partition(0, ZERO, List.of(ZERO, ZERO), List.of(ZERO)),
                partition(0, ZERO, List.of(ZERO), List.of(ZERO, ZERO)),
                partition(0, ZERO, List.of(ZERO), List.of(ONE)),
                partition(0, null, List.of(), List.of()));
        for (TopicPartitionInfo partition : invalid) {
            try (Fixture fixture = new Fixture()) {
                fixture.metadata.put("events", topic("events", partition));
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, "kafka.partition.under_replicated", "events#0").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertValue(result, "kafka.coverage.complete", "broker-target", false);
                assertThat(number(result, "kafka.partitions.state.known", "broker-target")).isEqualByComparingTo("1");
            }
        }
    }

    @Test
    void malformedPartitionIdentityOrMismatchedTopicNameCannotEscapeTheConfiguredScope() throws Exception {
        List<TopicRead> invalid = List.of(topic("unexpected-topic", partition(0, ZERO, List.of(ZERO), List.of(ZERO))),
                topic("events", partition(7, ZERO, List.of(ZERO), List.of(ZERO))), new TopicRead(null, null));
        for (TopicRead read : invalid) {
            try (Fixture fixture = new Fixture()) {
                fixture.metadata.put("events", read);
                CollectionResult result = fixture.collect(0);
                for (String key : topicKeys()) assertThat(metric(result, key, "events").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertThat(result.inventoryComplete()).isFalse();
                assertThat(result.metrics()).extracting(sample -> sample.definition().scope().id()).doesNotContain("events#7", "unexpected-topic", "unexpected-topic#0");
            }
        }
    }

    @Test
    void invalidConfiguredTopicNamesAreNeitherSentNorReflectedAsScopeLabels() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeTopics = List.of(SECRET, "events", "invalid-topic-0");
            fixture.metadata.put("invalid-topic-0", topic("invalid-topic-0"));
            CollectionResult result = fixture.collect(0);
            verify(fixture.session).describeTopics(List.of("events", "invalid-topic-0"));
            assertThat(metric(result, "kafka.topic.partitions", "invalid-topic#0").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(number(result, "kafka.topic.partitions", "invalid-topic-0")).isZero();
            assertThat(result.inventoryComplete()).isFalse();
            assertSafe(result);
        }
    }

    @Test
    void malformedBrokerMetadataDoesNotBecomeAZeroBrokerHealthClaim() throws Exception {
        for (List<Node> nodes : List.of(List.of(ZERO, ZERO), List.of(Node.noNode()))) {
            try (Fixture fixture = new Fixture()) {
                fixture.cluster = new ClusterRead(nodes, CLUSTER_ID, null, null);
                CollectionResult result = fixture.collect(0);
                assertThat(metric(result, "kafka.brokers.discovered", "broker-target").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
                assertValue(result, "kafka.coverage.complete", "broker-target", false);
                assertThat(number(result, "kafka.topic.partitions", "events")).isEqualByComparingTo("2");
            }
        }
    }

    @Test
    void openFailuresHaveDistinctSafeReasonsAndDoNotInvokeMetadataMethods() throws Exception {
        List<MissingReason> reasons = List.of(MissingReason.FAILED, MissingReason.UNAUTHORIZED, MissingReason.UNSUPPORTED, MissingReason.TIMEOUT);
        List<CollectionStatus> statuses = List.of(CollectionStatus.FAILED, CollectionStatus.UNAUTHORIZED, CollectionStatus.UNSUPPORTED, CollectionStatus.FAILED);
        for (int index = 0; index < reasons.size(); index++) {
            try (Fixture fixture = new Fixture()) {
                when(fixture.connections.open(any())).thenThrow(new Failure(reasons.get(index), index == 0));
                CollectionResult result = fixture.collect(0);
                assertThat(result.status()).isEqualTo(statuses.get(index));
                assertThat(result.reason()).isEqualTo(reasons.get(index));
                assertThat(result.serviceProbe().availability()).isEqualTo(index == 0 ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN);
                assertThat(metric(result, "kafka.topic.partitions", "events").missingReason()).isEqualTo(reasons.get(index));
                verifyNoInteractions(fixture.session);
                assertSafe(result);
            }
        }
    }

    @Test
    void exhaustedProbeDeadlineStopsTopicReadsAndCannotPublishTheLateResult() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.describeCluster()).thenAnswer(call -> { fixture.ticker.set(Duration.ofSeconds(5).toNanos()); return fixture.cluster; });
            CollectionRequest request = fixture.request(0, CollectionKind.ORDINARY);
            CollectionResult result = fixture.adapter.collect(request);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            verify(fixture.session, never()).describeTopics(anyList());
            verify(fixture.session).close();
            assertThat(request.control().complete(request, result, fixture.snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.DEADLINE_EXCEEDED);
            assertThat(fixture.snapshots.snapshot("broker-target").orElseThrow().metrics()).isEmpty();
        }
    }

    @Test
    void aTopicFailureAfterSuccessfulProbeKeepsProbeAvailabilityAndClosesTheOwnedSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.session.describeTopics(anyList())).thenThrow(new Failure(MissingReason.TIMEOUT, false));
            CollectionResult result = fixture.collect(0);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            for (String name : fixture.scopeTopics) assertThat(metric(result, "kafka.topic.partitions", name).missingReason()).isEqualTo(MissingReason.TIMEOUT);
            verify(fixture.session).close();
        }
    }

    @Test
    void capacityIsDeferredWithoutOpeningAnyClientOrCreatingInventory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CollectionResult result = fixture.adapter.collect(fixture.request(0, CollectionKind.CAPACITY));
            assertThat(result.status()).isEqualTo(CollectionStatus.UNSUPPORTED);
            assertThat(result.reason()).isEqualTo(MissingReason.NOT_APPLICABLE);
            assertThat(result.inventoryComplete()).isTrue();
            assertThat(result.metrics()).isEmpty();
            assertThat(result.serviceProbe()).isNull();
            verifyNoInteractions(fixture.connections, fixture.session);
        }
    }

    @Test
    void scopedCommittedProgressUsesSamePartitionOffsetsAndPublishesCompleteTotalsAndCoverage() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeGroups = List.of("readers", "readers");
            fixture.lag = lag("readers", 4, 3, 5);
            CollectionResult result = fixture.collect(0);
            assertThat(result.metrics()).hasSize(61);
            assertThat(result.status()).isEqualTo(CollectionStatus.SUCCESS);
            verify(fixture.session).readCommittedLag(List.of("readers"), List.of(tp("events", 0), tp("events", 1), tp("audit", 0)));
            assertThat(number(result, "kafka.group.partition.lag", "readers/events#0")).isEqualByComparingTo("8");
            assertThat(number(result, "kafka.group.partition.lag", "readers/events#1")).isEqualByComparingTo("5");
            assertThat(number(result, "kafka.group.partition.lag", "readers/audit#0")).isEqualByComparingTo("0");
            assertThat(number(result, "kafka.group.lag.total", "readers")).isEqualByComparingTo("13");
            assertThat(number(result, "kafka.group.lag.maximum", "readers")).isEqualByComparingTo("8");
            assertThat(number(result, "kafka.group.partitions.expected", "readers")).isEqualByComparingTo("3");
            assertThat(number(result, "kafka.group.partitions.covered", "readers")).isEqualByComparingTo("3");
            assertValue(result, "kafka.group.coverage.complete", "readers", true);
            assertValue(result, "kafka.group.partition.lag.anomaly", "readers/events#0", false);
            MetricSample total = metric(result, "kafka.group.lag.total", "readers");
            assertThat(total.definition().scope().kind()).isEqualTo(ScopeKind.CONSUMER_GROUP);
            assertThat(total.definition().calculation()).contains("提交进度差", "不代表未处理消息总数或处理耗时");
            assertThat(total.validUntil()).isEqualTo(START.plusSeconds(45));
            assertThat(fixture.counters.seriesCount("broker-target")).isZero();
            assertSafe(result);
        }
    }

    @Test
    void missingCommitAndFailedEndOffsetRemainUnknownAndOnlyReadablePartitionsContributeToSubtotal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeGroups = List.of("readers");
            fixture.lag = lag("readers", 4, 3, 5);
            fixture.collect(0);
            Map<TopicPartition, OffsetRead> ends = new HashMap<>(fixture.lag.ends());
            ends.put(tp("audit", 0), new OffsetRead(null, new Failure(MissingReason.TIMEOUT, false)));
            Map<TopicPartition, OffsetRead> commits = new HashMap<>(fixture.lag.committed().get("readers"));
            commits.put(tp("events", 1), new OffsetRead(null, new Failure(MissingReason.NO_COMMITTED_OFFSET, false)));
            fixture.lag = new LagRead(ends, Map.of("readers", commits));
            CollectionResult result = fixture.collect(15_000);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.NO_COMMITTED_OFFSET);
            assertThat(metric(result, "kafka.group.partition.lag", "readers/events#1").missingReason()).isEqualTo(MissingReason.NO_COMMITTED_OFFSET);
            assertThat(metric(result, "kafka.group.partition.lag.anomaly", "readers/events#1").value()).isNull();
            assertThat(metric(result, "kafka.group.partition.lag", "readers/audit#0").missingReason()).isEqualTo(MissingReason.TIMEOUT);
            assertThat(metric(result, "kafka.group.lag.total", "readers").missingReason()).isEqualTo(MissingReason.PARTIAL_SCOPE);
            assertThat(metric(result, "kafka.group.lag.maximum", "readers").value()).isNull();
            assertThat(number(result, "kafka.group.lag.known.total", "readers")).isEqualByComparingTo("8");
            assertThat(number(result, "kafka.group.lag.known.maximum", "readers")).isEqualByComparingTo("8");
            assertThat(number(result, "kafka.group.partitions.expected", "readers")).isEqualByComparingTo("3");
            assertThat(number(result, "kafka.group.partitions.covered", "readers")).isEqualByComparingTo("1");
            assertValue(result, "kafka.group.coverage.complete", "readers", false);
            assertThat(fixture.snapshots.snapshot("broker-target").orElseThrow().metrics())
                    .filteredOn(stored -> stored.latestAttempt().definition().key().equals("kafka.group.lag.total")).singleElement().satisfies(stored -> {
                    assertThat(stored.lastSuccess().value()).isEqualTo(new BigDecimal("13"));
                    assertThat(stored.lastSuccess().sampledAt()).isEqualTo(START);
            });
        }
    }

    @Test
    void negativeProgressIsKeptAndFlaggedWithoutClampingOrClaimingACompleteTotal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeGroups = List.of("readers");
            fixture.lag = lag("readers", 15, 3, 5);
            CollectionResult result = fixture.collect(0);
            assertThat(number(result, "kafka.group.partition.lag", "readers/events#0")).isEqualByComparingTo("-3");
            assertValue(result, "kafka.group.partition.lag.anomaly", "readers/events#0", true);
            assertThat(result.status()).isEqualTo(CollectionStatus.PARTIAL);
            assertThat(result.reason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(number(result, "kafka.group.lag.anomalies", "readers")).isEqualByComparingTo("1");
            assertThat(number(result, "kafka.group.partitions.covered", "readers")).isEqualByComparingTo("2");
            assertThat(number(result, "kafka.group.lag.known.total", "readers")).isEqualByComparingTo("5");
            assertThat(metric(result, "kafka.group.lag.total", "readers").value()).isNull();
        }
    }

    @Test
    void groupPermissionFailuresAndUnexpectedResultKeysDoNotHideReadableGroupsOrLeakOtherScope() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeGroups = List.of("readers", "denied");
            LagRead good = lag("readers", 4, 3, 5);
            Map<TopicPartition, OffsetRead> denied = new HashMap<>();
            good.ends().keySet().forEach(partition -> denied.put(partition, new OffsetRead(null, new Failure(MissingReason.UNAUTHORIZED, false))));
            Map<TopicPartition, OffsetRead> ends = new HashMap<>(good.ends());
            ends.put(tp("excluded", 0), new OffsetRead(99L, null));
            fixture.lag = new LagRead(ends, Map.of("readers", good.committed().get("readers"), "denied", denied,
                    "excluded-group", Map.of(tp("excluded", 0), new OffsetRead(0L, null))));
            CollectionResult result = fixture.collect(0);
            assertThat(number(result, "kafka.group.lag.total", "readers")).isEqualByComparingTo("13");
            assertThat(metric(result, "kafka.group.partition.committed_offset", "denied/events#0").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(metric(result, "kafka.group.lag.known.total", "denied").value()).isNull();
            assertThat(number(result, "kafka.group.partitions.covered", "denied")).isEqualByComparingTo("0");
            assertThat(result.serviceProbe().availability()).isEqualTo(ServiceAvailability.AVAILABLE);
            assertThat(result.metrics()).extracting(sample -> sample.definition().scope().id()).noneMatch(id -> id.contains("excluded"));
            verify(fixture.session).readCommittedLag(eq(List.of("readers", "denied")), anyList());
        }
    }

    @Test
    void partitionAndGroupLimitsKeepExpectedDenominatorAndRetireOnlyDeterministicOutOfScopeRows() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeGroups = List.of("readers", "workers");
            fixture.collect(0);
            fixture.properties.getLimits().setConsumerGroups(1);
            fixture.properties.getLimits().setPartitions(2);
            CollectionResult bounded = fixture.collect(15_000);
            assertThat(number(bounded, "kafka.group.partitions.expected", "readers")).isEqualByComparingTo("3");
            assertThat(number(bounded, "kafka.group.partitions.covered", "readers")).isEqualByComparingTo("2");
            assertThat(metric(bounded, "kafka.group.lag.total", "readers").missingReason()).isEqualTo(MissingReason.PARTIAL_SCOPE);
            assertValue(bounded, "kafka.coverage.truncated", "broker-target", true);
            verify(fixture.session).readCommittedLag(List.of("readers"), List.of(tp("events", 0), tp("events", 1)));
            assertThat(fixture.snapshots.snapshot("broker-target").orElseThrow().metrics()).noneMatch(stored ->
                    stored.latestAttempt().definition().scope().id().startsWith("workers")
                            || stored.latestAttempt().definition().scope().id().equals("readers/audit#0"));
            fixture.metadata.put("audit", failed(MissingReason.UNAUTHORIZED));
            CollectionResult unknownInventory = fixture.collect(30_000);
            assertThat(metric(unknownInventory, "kafka.group.partitions.expected", "readers").missingReason()).isEqualTo(MissingReason.UNAUTHORIZED);
            assertThat(unknownInventory.inventoryComplete()).isFalse();
        }
    }

    @Test
    void emptyTopicScopeAndInvalidGroupNamesNeverIssueOffsetsReads() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.scopeTopics = List.of();
            fixture.scopeGroups = List.of("readers");
            CollectionResult empty = fixture.collect(0);
            assertThat(number(empty, "kafka.group.partitions.expected", "readers")).isEqualByComparingTo("0");
            assertThat(number(empty, "kafka.group.lag.total", "readers")).isEqualByComparingTo("0");
            assertThat(metric(empty, "kafka.group.lag.maximum", "readers").missingReason()).isEqualTo(MissingReason.NOT_APPLICABLE);
            verify(fixture.session, never()).readCommittedLag(anyList(), anyList());
        }
        try (Fixture fixture = new Fixture()) {
            fixture.scopeGroups = List.of("unsafe\nname", "x".repeat(256));
            CollectionResult invalid = fixture.collect(0);
            assertThat(metric(invalid, "kafka.group.lag.total", "invalid-group#0").missingReason()).isEqualTo(MissingReason.INVALID_VALUE);
            assertThat(invalid.status()).isEqualTo(CollectionStatus.PARTIAL);
            verify(fixture.session, never()).readCommittedLag(anyList(), anyList());
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(invalid)).doesNotContain("unsafe", "x".repeat(256));
        }
    }

    @Test
    void groupPartitionCartesianProductStaysInside4500SeriesBudgetAndLongTotalsDoNotOverflow() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.properties.getLimits().setConsumerGroups(100_000);
            fixture.properties.getLimits().setPartitions(100_000);
            fixture.scopeGroups = IntStream.range(0, 100_000).mapToObj(index -> "readers-" + index).toList();
            CollectionResult bounded = fixture.collect(0);
            assertThat(bounded.metrics().size()).isLessThanOrEqualTo(4500);
            assertValue(bounded, "kafka.coverage.truncated", "broker-target", true);
            verify(fixture.session).readCommittedLag(argThat(groups -> groups.size() == 372), eq(List.of(tp("events", 0))));
        }
        try (Fixture fixture = new Fixture()) {
            fixture.scopeGroups = List.of("readers");
            LagRead base = lag("readers", 0, 0, 0);
            fixture.lag = new LagRead(Map.of(tp("events", 0), new OffsetRead(Long.MAX_VALUE, null),
                    tp("events", 1), new OffsetRead(Long.MAX_VALUE, null), tp("audit", 0), new OffsetRead(0L, null)), base.committed());
            CollectionResult result = fixture.collect(0);
            assertThat(number(result, "kafka.group.lag.total", "readers")).isEqualByComparingTo(new BigDecimal(Long.MAX_VALUE).multiply(BigDecimal.valueOf(2)));
        }
    }

    private static TopicPartition tp(String topic, int partition) { return new TopicPartition(topic, partition); }
    private static LagRead lag(String group, long eventsZero, long eventsOne, long auditZero) {
        return new LagRead(Map.of(tp("events", 0), new OffsetRead(12L, null), tp("events", 1), new OffsetRead(8L, null),
                tp("audit", 0), new OffsetRead(5L, null)), Map.of(group, Map.of(tp("events", 0), new OffsetRead(eventsZero, null),
                tp("events", 1), new OffsetRead(eventsOne, null), tp("audit", 0), new OffsetRead(auditZero, null))));
    }

    private static TopicPartitionInfo partition(int id, Node leader, List<Node> replicas, List<Node> isr) {
        return new TopicPartitionInfo(id, leader, replicas, isr);
    }
    private static TopicRead topic(String name, TopicPartitionInfo... partitions) {
        return new TopicRead(new TopicDescription(name, false, List.of(partitions)), null);
    }
    private static TopicRead failed(MissingReason reason) { return new TopicRead(null, new Failure(reason, false)); }
    private static List<String> topicKeys() { return List.of("kafka.topic.partitions", "kafka.topic.partitions.observed", "kafka.topic.coverage.complete"); }
    private static MetricSample metric(CollectionResult result, String key, String scope) {
        return result.metrics().stream().filter(sample -> sample.definition().key().equals(key) && sample.definition().scope().id().equals(scope)).findFirst().orElseThrow();
    }
    private static BigDecimal number(CollectionResult result, String key, String scope) { return (BigDecimal) metric(result, key, scope).value(); }
    private static void assertValue(CollectionResult result, String key, String scope, Object expected) { assertThat(metric(result, key, scope).value()).isEqualTo(expected); }
    private static void assertSafe(CollectionResult result) throws Exception {
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(result))
                .doesNotContain(SECRET, CLUSTER_ID, "private-broker", "private-rack", "stackTrace");
    }

    private static final class MutableClock extends Clock {
        private Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final AtomicLong ticker = new AtomicLong();
        final Object borrowed = new Object() {
            @Override public String toString() { throw new AssertionError("Borrowed Kafka client must not be read or closed"); }
        };
        final MonitoringProperties properties = new MonitoringProperties();
        final KafkaMonitoringConnections connections = mock(KafkaMonitoringConnections.class);
        final Session session = mock(Session.class);
        final Map<String, TopicRead> metadata = new HashMap<>();
        ClusterRead cluster = new ClusterRead(List.of(ZERO, ONE), CLUSTER_ID, null, null);
        List<String> scopeTopics = List.of("events", "audit");
        List<String> scopeGroups = List.of();
        LagRead lag;
        final MonitoringCounterStore counters = new MonitoringCounterStore(2, 5000);
        final MonitoringSnapshotStore snapshots = new MonitoringSnapshotStore(2, 5000, 4);
        final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "kafka-adapter-unit-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        final List<CollectionControl> controls = new ArrayList<>();
        final KafkaMonitoringAdapter adapter = new KafkaMonitoringAdapter(properties, connections, clock);
        long sequence;

        Fixture() {
            metadata.put("events", topic("events", partition(0, ZERO, List.of(ZERO, ONE), List.of(ZERO, ONE)),
                    partition(1, null, List.of(ZERO, ONE), List.of(ZERO))));
            metadata.put("audit", topic("audit", partition(0, ONE, List.of(ONE), List.of(ONE))));
            when(connections.open(any())).thenReturn(session);
            when(session.describeCluster()).thenAnswer(call -> cluster);
            when(session.describeTopics(anyList())).thenAnswer(call -> Map.copyOf(metadata));
            when(session.readCommittedLag(anyList(), anyList())).thenAnswer(call -> {
                if (lag != null) return lag;
                List<String> groups = call.getArgument(0);
                List<TopicPartition> partitions = call.getArgument(1);
                Map<TopicPartition, OffsetRead> ends = new HashMap<>();
                partitions.forEach(partition -> ends.put(partition, new OffsetRead(10L, null)));
                Map<String, Map<TopicPartition, OffsetRead>> committed = new HashMap<>();
                for (String group : groups) {
                    Map<TopicPartition, OffsetRead> offsets = new HashMap<>();
                    partitions.forEach(partition -> offsets.put(partition, new OffsetRead(0L, null)));
                    committed.put(group, offsets);
                }
                return new LagRead(ends, committed);
            });
            snapshots.activate("broker-target", 1);
        }

        CollectionRequest request(long millis, CollectionKind kind) {
            clock.now = START.plusMillis(millis);
            CollectionControl control = new CollectionControl(new Object(), () -> true, clock, ticker::get,
                    ticker.get() + Duration.ofSeconds(5).toNanos(), cleanup, counters, "broker-target", "kafka-binding",
                    kind, Duration.ofSeconds(15), borrowed);
            controls.add(control);
            return new CollectionRequest("broker-target", "kafkaTemplate", "kafka-binding", kind, 1, sequence++, clock.now,
                    clock.now.plusSeconds(5), new MonitoringTarget.Scope(List.of(), scopeTopics, scopeGroups, List.of(), List.of()),
                    borrowed, Map.of("private-settings", SECRET), control);
        }

        CollectionResult collect(long millis) {
            CollectionRequest request = request(millis, CollectionKind.ORDINARY);
            CollectionResult result = adapter.collect(request);
            assertThat(request.control().complete(request, result, snapshots)).isEqualTo(MonitoringSnapshotStore.Acceptance.ACCEPTED);
            return result;
        }

        @Override public void close() throws InterruptedException {
            controls.forEach(control -> control.cancel(MissingReason.FAILED));
            cleanup.shutdownNow();
            assertThat(cleanup.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
