package com.hpj.admin.monitor.kafka;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MonitoringAdapter;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.hpj.admin.monitor.kafka.KafkaMonitoringConnections.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Bounded observations of configured topics, never a claim about every topic or broker in a cluster. */
public final class KafkaMonitoringAdapter implements MonitoringAdapter {
    private static final int MAX_METRICS = 4500;
    private static final int BASE_METRICS = 17;
    private static final int TOPIC_METRICS = 3;
    private static final int PARTITION_METRICS = 5;
    private final MonitoringProperties properties;
    private final KafkaMonitoringConnections connections;
    private final Clock clock;

    public KafkaMonitoringAdapter(MonitoringProperties properties, KafkaMonitoringConnections connections, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public MiddlewareType type() { return MiddlewareType.KAFKA; }

    @Override public CollectionResult collect(CollectionRequest request) {
        Instant started = atLeast(request.scheduledAt());
        if (request.kind() == CollectionKind.CAPACITY) {
            return new CollectionResult(CollectionStatus.UNSUPPORTED, started, atLeast(started), List.of(), null,
                    true, MissingReason.NOT_APPLICABLE);
        }
        List<String> configured = new ArrayList<>(new LinkedHashSet<>(request.scope().topics()));
        int topicLimit = Math.min(properties.getLimits().getTopics(), (MAX_METRICS - BASE_METRICS) / TOPIC_METRICS);
        List<String> selected = configured.subList(0, Math.min(configured.size(), topicLimit));
        List<String> requested = selected.stream().filter(KafkaMonitoringAdapter::validTopic).toList();
        int partitionLimit = Math.min(properties.getLimits().getPartitions(),
                (MAX_METRICS - BASE_METRICS - selected.size() * TOPIC_METRICS) / PARTITION_METRICS);
        Scope clusterScope = new Scope(ScopeKind.CLUSTER, request.targetId(), null);
        Scope configuredScope = new Scope(ScopeKind.CONFIGURED_SCOPE, request.targetId(), null);
        ClusterRead cluster = null;
        Map<String, TopicRead> topics = Map.of();
        MissingReason operationFailure = null;
        MetricSample latency = null;
        ServiceProbe probe = null;
        Instant clusterAt = started;
        Instant topicsAt = started;
        long probeStarted = System.nanoTime();
        try {
            request.control().checkActive();
            try (Session session = connections.open(request)) {
                try {
                    cluster = session.describeCluster();
                } catch (Failure failure) {
                    cluster = new ClusterRead(null, null, failure, failure);
                }
                request.control().checkActive();
                clusterAt = atLeast(started);
                boolean answered = cluster != null && (cluster.nodesFailure() == null && cluster.nodes() != null
                        || cluster.clusterIdFailure() == null && validClusterId(cluster.clusterId()));
                MissingReason probeReason = answered ? null : clusterFailure(cluster);
                boolean connectionFailed = !answered && cluster != null && cluster.nodesFailure() != null
                        && cluster.nodesFailure().connectionFailure() && probeReason == MissingReason.FAILED;
                probe = new ServiceProbe(answered ? ServiceAvailability.AVAILABLE
                        : connectionFailed ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN,
                        probeReason, clusterScope, clusterAt, clusterAt.plus(properties.getServiceTtl()));
                if (answered) latency = success(probeDefinition(clusterScope),
                        BigDecimal.valueOf(Math.max(0, System.nanoTime() - probeStarted), 6), clusterAt);
                if (!requested.isEmpty()) {
                    request.control().checkActive();
                    topics = session.describeTopics(requested);
                    request.control().checkActive();
                }
                topicsAt = atLeast(clusterAt);
            }
        } catch (Failure failure) {
            operationFailure = failure.reason();
            if (probe == null) {
                clusterAt = atLeast(started);
                probe = new ServiceProbe(failure.connectionFailure() && failure.reason() == MissingReason.FAILED
                        ? ServiceAvailability.CONNECTION_FAILED : ServiceAvailability.UNKNOWN, failure.reason(),
                        clusterScope, clusterAt, clusterAt.plus(properties.getServiceTtl()));
            }
        } catch (CollectionControl.InactiveCollectionException inactive) {
            operationFailure = inactive.reason();
        }
        Instant finished = atLeast(topicsAt.isBefore(clusterAt) ? clusterAt : topicsAt);
        if (probe == null) probe = new ServiceProbe(ServiceAvailability.UNKNOWN,
                operationFailure == null ? MissingReason.FAILED : operationFailure, clusterScope, finished, finished);
        Observations observations = new Observations();
        observations.problem(operationFailure);
        observations.truncated = selected.size() < configured.size();
        List<MetricSample> metrics = new ArrayList<>();
        metrics.add(latency == null ? MetricSample.missing(probeDefinition(clusterScope),
                probe.reason() == null ? MissingReason.FAILED : probe.reason(), clusterAt) : latency);
        appendCluster(metrics, cluster, clusterScope, clusterAt, observations, operationFailure);
        for (int index = 0; index < selected.size(); index++) {
            String topic = selected.get(index);
            TopicRead read = validTopic(topic) && topics != null ? topics.get(topic) : null;
            MissingReason missing = !validTopic(topic) ? MissingReason.INVALID_VALUE
                    : read == null ? operationFailure == null ? MissingReason.FAILED : operationFailure
                    : read.failure() == null ? null : read.failure().reason();
            Scope topicScope = new Scope(ScopeKind.TOPIC, validTopic(topic) ? topic : "invalid-topic#" + index, null);
            if (missing == null && (read.topic() == null || !topic.equals(read.topic().name()) || read.topic().partitions() == null)) {
                missing = MissingReason.INVALID_VALUE;
            }
            if (missing != null) {
                appendMissingTopic(metrics, topicScope, missing, finished);
                observations.inventoryComplete = false;
                observations.problem(missing);
                continue;
            }
            appendTopic(metrics, read.topic(), topicScope, partitionLimit, finished, observations);
        }
        appendScope(metrics, configuredScope, configured.size(), requested.size(), topicLimit, partitionLimit, finished, observations);
        MissingReason reason = observations.reason;
        if (reason == null && latency == null) reason = probe.reason();
        CollectionStatus status = reason == null ? observations.truncated ? CollectionStatus.PARTIAL : CollectionStatus.SUCCESS
                : latency != null || observations.topicsObserved > 0 ? CollectionStatus.PARTIAL : failureStatus(reason);
        return new CollectionResult(status, started, atLeast(finished), metrics, probe, observations.inventoryComplete, reason);
    }

    private void appendCluster(List<MetricSample> metrics, ClusterRead cluster, Scope scope, Instant at,
                               Observations observations, MissingReason failure) {
        MissingReason missing = cluster == null ? failure == null ? MissingReason.FAILED : failure
                : cluster.nodesFailure() != null ? cluster.nodesFailure().reason()
                : cluster.nodes() == null ? MissingReason.UNSUPPORTED : null;
        List<Node> nodes = cluster == null ? null : cluster.nodes();
        int observed = missing == null ? Math.min(nodes.size(), properties.getLimits().getNodes()) : 0;
        if (missing == null) {
            Set<Integer> ids = new HashSet<>();
            for (int index = 0; index < observed; index++) {
                Node node = nodes.get(index);
                if (node == null || node.id() < 0 || !ids.add(node.id())) { missing = MissingReason.INVALID_VALUE; break; }
            }
            observations.truncated |= nodes.size() > observed;
        }
        observations.problem(missing);
        metrics.add(sample(def("kafka.brokers.discovered", "Admin 可发现 broker 条目数", Unit.COUNT, scope,
                "Admin.describeCluster().nodes", "此次 Admin 元数据返回的 broker 条目数；不是全体 broker 存活或集群健康结论"),
                missing == null ? nodes.size() : null, missing, at));
        metrics.add(sample(def("kafka.brokers.observed", "限额内已检查 broker 数", Unit.COUNT, scope,
                "Admin.describeCluster().nodes", "仅检查 nodes 限额内的元数据条目；不输出节点地址"),
                missing == null ? observed : null, missing, at));
        MissingReason identityFailure = cluster == null ? failure == null ? MissingReason.FAILED : failure
                : cluster.clusterIdFailure() == null ? null : cluster.clusterIdFailure().reason();
        boolean known = cluster != null && validClusterId(cluster.clusterId());
        observations.problem(identityFailure != null ? identityFailure : known ? null : MissingReason.UNSUPPORTED);
        metrics.add(sample(def("kafka.cluster.identity.known", "集群身份可识别", Unit.BOOLEAN, scope,
                "Admin.describeCluster().clusterId", "仅表示此次读取能否识别集群 ID；不输出 ID，也不据此判断集群整体健康"),
                identityFailure == null ? known : null, identityFailure, at));
    }

    private void appendTopic(List<MetricSample> metrics, TopicDescription topic, Scope scope, int partitionLimit,
                             Instant at, Observations observations) {
        List<TopicPartitionInfo> partitions = topic.partitions();
        int count = Math.min(partitions.size(), Math.max(0, partitionLimit - observations.partitionsObserved));
        // Kafka's TopicDescription contract indexes this list by partition ID. Inspect only the bounded prefix.
        for (int index = 0; index < count; index++) {
            if (partitions.get(index) == null || partitions.get(index).partition() != index) {
                appendMissingTopic(metrics, scope, MissingReason.INVALID_VALUE, at);
                observations.inventoryComplete = false;
                observations.problem(MissingReason.INVALID_VALUE);
                return;
            }
        }
        observations.topicsObserved++;
        boolean topicComplete = count == partitions.size();
        observations.truncated |= !topicComplete;
        for (int index = 0; index < count; index++) {
            boolean complete = appendPartition(metrics, scope.id(), partitions.get(index), at, observations);
            topicComplete &= complete;
        }
        metrics.add(success(topicPartitions(scope), partitions.size(), at));
        metrics.add(success(topicObserved(scope), count, at));
        metrics.add(success(topicComplete(scope), topicComplete, at));
    }

    private boolean appendPartition(List<MetricSample> metrics, String topic, TopicPartitionInfo partition,
                                    Instant at, Observations observations) {
        Scope scope = new Scope(ScopeKind.PARTITION, topic + "#" + partition.partition(), null);
        Nodes replicas = nodes(partition.replicas());
        Nodes isr = nodes(partition.isr());
        observations.truncated |= replicas.truncated() || isr.truncated();
        Node leader = partition.leader();
        boolean hasLeader = leader != null && leader.id() >= 0;
        MissingReason leaderFailure = leader != null && leader.id() < -1 ? MissingReason.INVALID_VALUE : null;
        if (hasLeader && replicas.reason() == null && !replicas.ids().contains(leader.id())) leaderFailure = MissingReason.INVALID_VALUE;
        MissingReason stateFailure = replicas.reason() != null ? replicas.reason() : isr.reason();
        if (stateFailure == null && (replicas.count() == 0 || !replicas.ids().containsAll(isr.ids()))) {
            stateFailure = MissingReason.INVALID_VALUE;
        }
        Boolean underReplicated = stateFailure == null ? isr.count() < replicas.count() : null;
        observations.problem(leaderFailure);
        observations.problem(stateFailure);
        observations.partitionsObserved++;
        if (leaderFailure == null && stateFailure == null) observations.statesKnown++;
        if (leaderFailure == null && !hasLeader) observations.noLeader++;
        if (Boolean.TRUE.equals(underReplicated)) observations.underReplicated++;
        metrics.add(sample(def("kafka.partition.leader.id", "Leader broker ID", Unit.COUNT, scope,
                "TopicPartitionInfo.leader", "原生 leader broker ID；原生无 leader 时不适用，不输出主机、端口或机架"),
                hasLeader && leaderFailure == null ? leader.id() : null,
                leaderFailure != null ? leaderFailure : hasLeader ? null : MissingReason.NOT_APPLICABLE, at));
        metrics.add(sample(def("kafka.partition.leader.available", "元数据中有 Leader", Unit.BOOLEAN, scope,
                "TopicPartitionInfo.leader", "null 或 Node.noNode 表示无 leader；其余有效 broker ID 表示元数据已分配 leader，不证明网络存活"),
                leaderFailure == null ? hasLeader : null, leaderFailure, at));
        metrics.add(sample(def("kafka.partition.replicas", "分配副本数", Unit.COUNT, scope,
                "TopicPartitionInfo.replicas", "此分区原生副本分配条目数；不是 min.insync.replicas 配置"),
                replicas.count(), replicas.count() == null ? replicas.reason() : null, at));
        metrics.add(sample(def("kafka.partition.isr", "同步副本数", Unit.COUNT, scope,
                "TopicPartitionInfo.isr", "此分区原生 ISR 条目数；只判断当前元数据"),
                isr.count(), isr.count() == null ? isr.reason() : null, at));
        metrics.add(sample(def("kafka.partition.under_replicated", "同步副本少于分配副本", Unit.BOOLEAN, scope,
                "TopicPartitionInfo.replicas, isr", "在副本 ID 唯一且 ISR 为副本子集时，ISR 数 < 分配副本数；不等同于低于 min.insync.replicas"),
                underReplicated, stateFailure, at));
        return leaderFailure == null && stateFailure == null;
    }

    private Nodes nodes(List<Node> nodes) {
        if (nodes == null) return new Nodes(null, Set.of(), MissingReason.INVALID_VALUE, false);
        if (nodes.size() > properties.getLimits().getNodes()) return new Nodes(nodes.size(), Set.of(), MissingReason.UNSUPPORTED, true);
        Set<Integer> ids = new HashSet<>();
        for (Node node : nodes) {
            if (node == null || node.id() < 0 || !ids.add(node.id())) return new Nodes(null, Set.of(), MissingReason.INVALID_VALUE, false);
        }
        return new Nodes(nodes.size(), Set.copyOf(ids), null, false);
    }

    private void appendMissingTopic(List<MetricSample> metrics, Scope scope, MissingReason reason, Instant at) {
        metrics.add(MetricSample.missing(topicPartitions(scope), reason, at));
        metrics.add(MetricSample.missing(topicObserved(scope), reason, at));
        metrics.add(MetricSample.missing(topicComplete(scope), reason, at));
    }

    private void appendScope(List<MetricSample> metrics, Scope scope, int configured, int requested, int topicLimit,
                             int partitionLimit, Instant at, Observations observations) {
        String source = "服务端配置范围与此次有界 Admin 元数据读取";
        metrics.add(success(def("kafka.topics.configured", "配置范围 Topic 数", Unit.COUNT, scope, source, "此连接服务端显式配置的去重 Topic 数；空范围不枚举 Topic"), configured, at));
        metrics.add(success(def("kafka.topics.requested", "纳入请求范围 Topic 数", Unit.COUNT, scope, source, "通过名称校验且纳入当前条目限额的配置 Topic 数；连接失败时可能尚未发出请求"), requested, at));
        metrics.add(success(def("kafka.topics.observed", "已读取 Topic 数", Unit.COUNT, scope, source, "本次成功读取元数据且分区身份有效的 Topic 数；不是集群 Topic 总数"), observations.topicsObserved, at));
        metrics.add(success(def("kafka.partitions.observed", "已展示分区数", Unit.COUNT, scope, source, "配置 Topic 范围内实际展示的分区条目数；受分区及总指标限额约束"), observations.partitionsObserved, at));
        metrics.add(success(def("kafka.partitions.no_leader", "已知无 Leader 分区数", Unit.COUNT, scope, source, "仅累计已展示且 leader 状态已知的无 leader 分区；未知行不视为正常"), observations.noLeader, at));
        metrics.add(success(def("kafka.partitions.under_replicated", "已知同步副本不足分区数", Unit.COUNT, scope, source, "仅累计已展示且副本集合有效、ISR 少于分配副本的分区；不是集群总量"), observations.underReplicated, at));
        metrics.add(success(def("kafka.partitions.state.known", "分区状态完整可判断数", Unit.COUNT, scope, source, "已展示且 leader、分配副本、ISR 均可判断的分区数"), observations.statesKnown, at));
        metrics.add(success(def("kafka.coverage.complete", "配置范围读取完整", Unit.BOOLEAN, scope, source, "仅在当前配置范围全部可读、集群身份可识别且没有限额截断时为真；不代表全体集群"), observations.reason == null && !observations.truncated, at));
        metrics.add(success(def("kafka.coverage.truncated", "本次展示触及条目限额", Unit.BOOLEAN, scope, source, "Topic、分区、节点检查或总指标预算省略了条目时为真；不是 Kafka 原生分页"), observations.truncated, at));
        metrics.add(success(def("kafka.limits.topics", "有效 Topic 展示上限", Unit.COUNT, scope, source, "配置 Topic 上限与总指标预算允许 Topic 行数的较小值"), topicLimit, at));
        metrics.add(success(def("kafka.limits.partitions", "有效分区展示上限", Unit.COUNT, scope, source, "配置分区上限与预留固定及 Topic 指标后的分区行预算的较小值"), partitionLimit, at));
        metrics.add(success(def("kafka.limits.nodes", "节点检查上限", Unit.COUNT, scope, source, "对 broker 元数据及单分区副本/ISR 集合应用的节点条目上限"), properties.getLimits().getNodes(), at));
        metrics.add(success(def("kafka.limits.metrics", "本适配器单次指标上限", Unit.COUNT, scope, source, "为同目标其他采集器预留空间；固定指标加 Topic 行、分区行合计不超过此值"), MAX_METRICS, at));
    }

    private static Definition probeDefinition(Scope scope) {
        return def("kafka.probe.duration", "Admin 连接探测耗时", Unit.MILLISECONDS, scope,
                "监控专用 AdminClient 与 describeCluster", "单调时钟测量创建连接及读取集群元数据的耗时；部分元数据成功证明连接可达，不证明集群健康");
    }
    private static Definition topicPartitions(Scope scope) {
        return def("kafka.topic.partitions", "Topic 原生分区数", Unit.COUNT, scope, "TopicDescription.partitions", "本次指定 Topic 的元数据分区数；不包含其他 Topic");
    }
    private static Definition topicObserved(Scope scope) {
        return def("kafka.topic.partitions.observed", "Topic 已展示分区数", Unit.COUNT, scope, "TopicDescription.partitions 与展示预算", "此 Topic 中实际展示的分区行数，可能小于原生分区数");
    }
    private static Definition topicComplete(Scope scope) {
        return def("kafka.topic.coverage.complete", "Topic 分区读取完整", Unit.BOOLEAN, scope, "TopicDescription.partitions 与展示预算", "所有原生分区均展示且分区状态可判断时为真");
    }
    private static Definition def(String key, String label, Unit unit, Scope scope, String source, String calculation) {
        return new Definition(key, label, unit, scope, source, calculation);
    }
    private MetricSample sample(Definition definition, Object value, MissingReason reason, Instant at) {
        return reason == null ? success(definition, value, at) : MetricSample.missing(definition, reason, at);
    }
    private MetricSample success(Definition definition, Object value, Instant at) {
        return MetricSample.success(definition, value, at,
                properties.getOrdinaryInterval().multipliedBy(properties.getMetricTtlMultiplier()));
    }
    private Instant atLeast(Instant previous) { Instant now = clock.instant(); return now.isBefore(previous) ? previous : now; }
    private static boolean validTopic(String topic) {
        return topic != null && topic.matches("[a-zA-Z0-9._-]{1,249}") && !topic.equals(".") && !topic.equals("..");
    }
    private static boolean validClusterId(String identity) {
        return identity != null && !identity.isBlank() && identity.length() <= 255 && identity.chars().noneMatch(Character::isISOControl);
    }
    private static MissingReason clusterFailure(ClusterRead cluster) {
        if (cluster == null) return MissingReason.FAILED;
        if (cluster.nodesFailure() != null) return cluster.nodesFailure().reason();
        if (cluster.clusterIdFailure() != null) return cluster.clusterIdFailure().reason();
        return MissingReason.UNSUPPORTED;
    }
    private static CollectionStatus failureStatus(MissingReason reason) {
        return switch (reason) {
            case UNAUTHORIZED -> CollectionStatus.UNAUTHORIZED;
            case UNSUPPORTED, NOT_APPLICABLE -> CollectionStatus.UNSUPPORTED;
            case BUSY -> CollectionStatus.BUSY;
            case WAITING_SAMPLE, NO_REQUESTS -> CollectionStatus.WAITING;
            default -> CollectionStatus.FAILED;
        };
    }
    private record Nodes(Integer count, Set<Integer> ids, MissingReason reason, boolean truncated) { }
    private static final class Observations {
        private MissingReason reason;
        private boolean truncated;
        private boolean inventoryComplete = true;
        private int topicsObserved;
        private int partitionsObserved;
        private int statesKnown;
        private int noLeader;
        private int underReplicated;
        private void problem(MissingReason next) { if (reason == null) reason = next; }
    }
}
