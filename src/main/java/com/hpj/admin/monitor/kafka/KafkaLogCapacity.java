package com.hpj.admin.monitor.kafka;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.common.TopicPartition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

import static com.hpj.admin.monitor.kafka.KafkaMonitoringConnections.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Capacity-only log copies and directory-reported volumes. Volume identity is unknown, so volumes are never added. */
final class KafkaLogCapacity {
    private static final int MAX_METRICS = 500; // Ordinary Kafka collection reserves this remainder of the 5000-series snapshot.
    private static final int FIXED_METRICS = 9;
    private static final int BROKER_METRICS = 7;
    private static final int DIRECTORY_METRICS = 8;
    private static final int REPLICA_METRICS = 2;
    static final int MAX_REPLICA_NODES = (MAX_METRICS - FIXED_METRICS) / (BROKER_METRICS + DIRECTORY_METRICS + 2 * REPLICA_METRICS);
    private final MonitoringProperties properties;
    private final KafkaMonitoringConnections connections;
    private final Clock clock;

    KafkaLogCapacity(MonitoringProperties properties, KafkaMonitoringConnections connections, Clock clock) {
        this.properties = properties; this.connections = connections; this.clock = clock;
    }

    CollectionResult collect(CollectionRequest request) {
        Instant started = atLeast(request.scheduledAt());
        List<String> configured = new ArrayList<>(new LinkedHashSet<>(request.scope().topics()));
        int topicLimit = Math.min(properties.getLimits().getTopics(), 100);
        List<String> selected = configured.subList(0, Math.min(configured.size(), topicLimit));
        List<String> requested = selected.stream().filter(KafkaMonitoringAdapter::validTopic).toList();
        int nodeLimit = Math.min(properties.getLimits().getNodes(), MAX_REPLICA_NODES);
        State state = new State();
        state.truncated = selected.size() < configured.size();
        Map<Integer, Set<TopicPartition>> expected = Map.of();
        Map<Integer, DirectoriesRead> directories = Map.of();
        int directoryLimit = 0;
        int replicaLimit = 0;
        try {
            request.control().checkActive();
            if (!requested.isEmpty()) try (Session session = connections.open(request)) {
                Map<String, TopicRead> topics = session.describeTopics(requested);
                request.control().checkActive();
                expected = topology(selected, topics, nodeLimit, state);
                int remaining = MAX_METRICS - FIXED_METRICS - expected.size() * BROKER_METRICS;
                directoryLimit = Math.min(properties.getLimits().getLogDirectories(), remaining / (DIRECTORY_METRICS + 2 * REPLICA_METRICS));
                replicaLimit = (remaining - directoryLimit * DIRECTORY_METRICS) / REPLICA_METRICS;
                if (!expected.isEmpty()) {
                    List<TopicPartition> partitions = expected.values().stream().flatMap(Set::stream).distinct()
                            .sorted(java.util.Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition)).toList();
                    directories = session.describeLogDirectories(List.copyOf(expected.keySet()), partitions);
                    request.control().checkActive();
                }
            }
        } catch (Failure failure) { state.problem(failure.reason()); state.inventoryComplete = false; }
        catch (CollectionControl.InactiveCollectionException inactive) { state.problem(inactive.reason()); state.inventoryComplete = false; }
        if (selected.stream().anyMatch(topic -> !KafkaMonitoringAdapter.validTopic(topic))) state.problem(MissingReason.INVALID_VALUE);
        if (configured.isEmpty()) state.problem(MissingReason.NOT_APPLICABLE);
        Instant finished = atLeast(started);
        List<MetricSample> metrics = new ArrayList<>();
        int remainingDirectories = directoryLimit;
        int remainingReplicas = replicaLimit;
        int brokersLeft = expected.size();
        for (var broker : expected.entrySet()) {
            Scope scope = new Scope(ScopeKind.NODE, "broker-" + broker.getKey(), Integer.toString(broker.getKey()));
            DirectoriesRead read = directories == null ? null : directories.get(broker.getKey());
            MissingReason missing = read == null ? state.reason == null ? MissingReason.FAILED : state.reason
                    : read.failure() == null ? read.directories() == null ? MissingReason.INVALID_VALUE : null : read.failure().reason();
            Set<TopicPartition> current = new HashSet<>();
            int observed = 0;
            Integer discovered = missing == null ? read.directories().size() : null;
            boolean brokerComplete = missing == null;
            if (missing == null) {
                int allowance = brokersLeft == 0 ? 0 : (remainingDirectories + brokersLeft - 1) / brokersLeft;
                List<String> paths = boundedPaths(read.directories().keySet(), allowance, state);
                state.truncated |= paths.size() < discovered;
                brokerComplete &= paths.size() == discovered;
                for (String path : paths) {
                    LogDirDescription description = read.directories().get(path);
                    DirectoryOutcome outcome = directory(metrics, broker.getKey(), path, description, broker.getValue(), current,
                            remainingReplicas, finished, state);
                    remainingReplicas -= outcome.rows();
                    brokerComplete &= outcome.complete();
                    observed++; state.directoriesObserved++; remainingDirectories--;
                }
            } else { state.problem(missing); state.inventoryComplete = false; }
            brokersLeft--;
            brokerComplete &= current.containsAll(broker.getValue()) && !state.topologyIncomplete;
            if (!brokerComplete) state.logsComplete = false;
            if (missing == null && observed == discovered && !current.containsAll(broker.getValue()) && !state.truncated) state.problem(MissingReason.FAILED);
            String source = "Admin.describeLogDirs(配置 topic 副本所在 broker)";
            metrics.add(MetricSample.missing(def("kafka.broker.cpu.percent", "Broker CPU 占用率", Unit.PERCENT, scope,
                    "现有 Admin 协议不提供 broker 进程 CPU；不能用 AdminClient 所在进程替代"), MissingReason.UNSUPPORTED, finished));
            metrics.add(MetricSample.missing(def("kafka.broker.memory.bytes", "Broker 进程内存", Unit.BYTES, scope,
                    "现有 Admin 协议不提供 broker 进程内存；不能用 admin 应用或 AdminClient 内存替代"), MissingReason.UNSUPPORTED, finished));
            metrics.add(sample(new Definition("kafka.broker.log_directories.discovered", "原生日志目录数", Unit.COUNT, scope, source, "限定 broker 返回的目录条目数"), discovered, missing, finished));
            metrics.add(success(def("kafka.broker.log_directories.observed", "已展示日志目录数", Unit.COUNT, scope, "本次预算内实际展示的目录数；目录值失败另行表达"), observed, finished));
            metrics.add(success(def("kafka.broker.replicas.expected", "选定分区预期当前副本数", Unit.COUNT, scope, "所选配置 topic 分区元数据分配给此 broker 的当前副本数；结合全局截断标记"), broker.getValue().size(), finished));
            metrics.add(success(def("kafka.broker.replicas.current.covered", "已读取当前副本数", Unit.COUNT, scope, "本次目录中成功读取大小且非 future 的唯一配置分区副本数"), current.size(), finished));
            metrics.add(success(def("kafka.broker.log_coverage.complete", "配置副本日志读取完整", Unit.BOOLEAN, scope, "选定配置范围完整、目录未截断且每个当前副本和已返回 future 副本大小可读时为真"), brokerComplete && !state.truncated, finished));
        }
        state.logsComplete &= !state.topologyIncomplete && !state.truncated && state.inventoryComplete;
        if (!requested.isEmpty() && expected.isEmpty() && state.reason == null) state.problem(MissingReason.NOT_APPLICABLE);
        Scope scope = new Scope(ScopeKind.CONFIGURED_SCOPE, request.targetId(), null);
        metrics.add(success(def("kafka.capacity.topics.configured", "容量配置 Topic 数", Unit.COUNT, scope, "服务端容量采集范围的去重 topic 数；空范围不查询目录"), configured.size(), finished));
        metrics.add(success(def("kafka.capacity.topics.requested", "容量请求范围 Topic 数", Unit.COUNT, scope, "名称有效且纳入固定请求上限的 topic 数"), requested.size(), finished));
        metrics.add(success(def("kafka.capacity.partitions.observed", "容量选定分区数", Unit.COUNT, scope, "预算内检查元数据以定位 broker 的配置分区数"), state.partitions, finished));
        metrics.add(success(def("kafka.capacity.coverage.complete", "配置日志副本读取完整", Unit.BOOLEAN, scope, "当前配置日志副本均可读且未截断；卷容量能力按目录字段独立表达"), state.logsComplete && state.reason != MissingReason.NOT_APPLICABLE, finished));
        metrics.add(success(def("kafka.capacity.coverage.truncated", "容量范围触及限额", Unit.BOOLEAN, scope, "topic、分区、broker、目录或副本行因预算省略时为真"), state.truncated, finished));
        metrics.add(success(def("kafka.capacity.limits.nodes", "容量 broker 上限", Unit.COUNT, scope, "节点配置上限与容量序列预算允许 broker 数的较小值"), nodeLimit, finished));
        metrics.add(success(def("kafka.capacity.limits.directories", "容量目录上限", Unit.COUNT, scope, "目录配置上限与预留 broker/副本行后的目录预算"), directoryLimit, finished));
        metrics.add(success(def("kafka.capacity.limits.replicas", "容量副本行上限", Unit.COUNT, scope, "预留固定、broker、目录指标后可展示的副本行总数"), replicaLimit, finished));
        metrics.add(success(def("kafka.capacity.limits.metrics", "容量序列上限", Unit.COUNT, scope, "最多 500 条容量序列，与普通采集最多 4500 条共用单目标 5000 条快照预算"), MAX_METRICS, finished));
        CollectionStatus status = state.reason == null ? state.truncated ? CollectionStatus.PARTIAL : CollectionStatus.SUCCESS
                : state.directoriesObserved > 0 ? CollectionStatus.PARTIAL : switch (state.reason) {
                    case UNSUPPORTED, NOT_APPLICABLE -> CollectionStatus.UNSUPPORTED;
                    case UNAUTHORIZED -> CollectionStatus.UNAUTHORIZED;
                    case BUSY -> CollectionStatus.BUSY;
                    case PARTIAL_SCOPE -> CollectionStatus.PARTIAL;
                    default -> CollectionStatus.FAILED;
                };
        return new CollectionResult(status, started, finished, metrics, null, state.inventoryComplete, state.reason);
    }

    private Map<Integer, Set<TopicPartition>> topology(List<String> topics, Map<String, TopicRead> reads, int nodeLimit, State state) {
        Map<Integer, Set<TopicPartition>> replicas = new LinkedHashMap<>();
        int partitionLimit = Math.min(properties.getLimits().getPartitions(), 200);
        for (String name : topics) {
            TopicRead read = reads == null ? null : reads.get(name);
            if (read == null || read.failure() != null || read.topic() == null || !name.equals(read.topic().name()) || read.topic().partitions() == null) {
                state.problem(read != null && read.failure() != null ? read.failure().reason() : MissingReason.INVALID_VALUE);
                state.inventoryComplete = false; state.topologyIncomplete = true; continue;
            }
            var partitions = read.topic().partitions();
            int count = Math.min(partitions.size(), Math.max(0, partitionLimit - state.partitions));
            state.truncated |= count < partitions.size();
            for (int index = 0; index < count; index++) {
                var partition = partitions.get(index);
                if (partition == null || partition.partition() != index || partition.replicas() == null || partition.replicas().isEmpty()) {
                    state.problem(MissingReason.INVALID_VALUE); state.inventoryComplete = false; state.topologyIncomplete = true; continue;
                }
                state.partitions++;
                int nodes = Math.min(partition.replicas().size(), nodeLimit);
                state.truncated |= nodes < partition.replicas().size();
                Set<Integer> seen = new HashSet<>();
                for (int node = 0; node < nodes; node++) {
                    var replica = partition.replicas().get(node);
                    if (replica == null || replica.id() < 0 || !seen.add(replica.id())) {
                        state.problem(MissingReason.INVALID_VALUE); state.topologyIncomplete = true; state.inventoryComplete = false; continue;
                    }
                    replicas.computeIfAbsent(replica.id(), ignored -> new LinkedHashSet<>()).add(new TopicPartition(name, index));
                }
            }
        }
        List<Integer> selected = new TreeSet<>(replicas.keySet()).stream().limit(nodeLimit).toList();
        state.truncated |= selected.size() < replicas.size();
        Map<Integer, Set<TopicPartition>> output = new LinkedHashMap<>();
        selected.forEach(broker -> output.put(broker, Set.copyOf(replicas.get(broker))));
        return output;
    }

    private record DirectoryOutcome(int rows, boolean complete) { }
    private static List<String> boundedPaths(Set<String> paths, int limit, State state) {
        TreeSet<String> selected = new TreeSet<>();
        for (String path : paths) {
            if (path == null || path.isBlank() || path.length() > 4096 || path.chars().anyMatch(Character::isISOControl)) {
                state.problem(MissingReason.INVALID_VALUE); state.inventoryComplete = false; continue;
            }
            if (limit == 0) continue;
            selected.add(path);
            if (selected.size() > limit) selected.pollLast();
        }
        return List.copyOf(selected);
    }
    private DirectoryOutcome directory(List<MetricSample> metrics, int broker, String path, LogDirDescription description,
                                       Set<TopicPartition> allowed, Set<TopicPartition> current, int replicaLimit, Instant at, State state) {
        Scope scope = new Scope(ScopeKind.FILESYSTEM, directoryId(path), Integer.toString(broker));
        MissingReason missing = description == null ? MissingReason.INVALID_VALUE : description.error() == null ? null : classify(description.error()).reason();
        Map<TopicPartition, ReplicaInfo> replicas = Map.of();
        if (missing == null) try { replicas = description.replicaInfos(); }
        catch (RuntimeException invalid) { missing = MissingReason.INVALID_VALUE; }
        if (replicas == null) missing = MissingReason.INVALID_VALUE;
        Map<TopicPartition, ReplicaInfo> returned = replicas;
        List<TopicPartition> matching = missing == null ? allowed.stream().filter(returned::containsKey)
                .sorted(java.util.Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition)).toList() : List.of();
        List<TopicPartition> shown = matching.subList(0, Math.min(matching.size(), replicaLimit));
        boolean truncated = matching.size() > shown.size();
        state.truncated |= truncated;
        BigDecimal bytes = BigDecimal.ZERO;
        int future = 0;
        int validRows = 0;
        boolean complete = missing == null && !truncated && !state.topologyIncomplete && !state.truncated;
        for (TopicPartition partition : shown) {
            ReplicaInfo replica = replicas.get(partition);
            MissingReason invalid = replica == null || replica.size() < 0 ? MissingReason.INVALID_VALUE : null;
            Scope replicaScope = new Scope(ScopeKind.PARTITION, partition.topic() + "#" + partition.partition() + "@" + scope.id(), scope.nodeId());
            metrics.add(sample(def("kafka.replica.log.bytes", "分区副本日志段字节", Unit.BYTES, replicaScope, "原生 ReplicaInfo.size；包含此 broker 上的一个 current/future 副本，不是整盘使用量或无副本逻辑数据量"),
                    invalid == null ? replica.size() : null, invalid, at));
            metrics.add(sample(def("kafka.replica.log.future", "迁移中的 future 副本", Unit.BOOLEAN, replicaScope, "原生 ReplicaInfo.isFuture；current/future 复制行分别展示"),
                    replica == null ? null : replica.isFuture(), replica == null ? MissingReason.INVALID_VALUE : null, at));
            if (invalid == null) {
                validRows++;
                bytes = bytes.add(BigDecimal.valueOf(replica.size()));
                if (replica.isFuture()) future++;
                else if (!current.add(partition)) { invalid = MissingReason.INVALID_VALUE; }
            }
            if (invalid != null) { complete = false; state.problem(invalid); }
        }
        state.problem(missing);
        if (missing != null) state.inventoryComplete = false;
        MissingReason totalFailure = missing != null ? missing : complete ? null : MissingReason.PARTIAL_SCOPE;
        MissingReason knownFailure = missing != null ? missing : validRows > 0 || matching.isEmpty() ? null
                : truncated ? MissingReason.PARTIAL_SCOPE : MissingReason.INVALID_VALUE;
        metrics.add(sample(def("kafka.log_directory.scoped_replica.bytes", "目录配置范围副本日志字节", Unit.BYTES, scope, "此目录中配置范围 current/future 副本日志段之和，含副本；仅范围及副本行完整可读时返回，不是磁盘使用量"), complete ? bytes : null, totalFailure, at));
        metrics.add(sample(def("kafka.log_directory.known_replica.bytes", "目录已知副本日志字节小计", Unit.BYTES, scope, "仅本次展示且大小有效的配置副本行小计，含 current/future；结合完整标记，不能当成整盘容量"), knownFailure == null ? bytes : null, knownFailure, at));
        metrics.add(success(def("kafka.log_directory.replicas.observed", "目录已展示副本行数", Unit.COUNT, scope, "在此目录中实际展示的配置范围副本行数，受总预算约束"), shown.size(), at));
        metrics.add(success(def("kafka.log_directory.coverage.complete", "目录配置副本读取完整", Unit.BOOLEAN, scope, "目录可读、配置范围及副本行未截断且所有大小有效时为真"), complete, at));
        Long total = missing == null ? volume(description.totalBytes()) : null;
        Long usable = missing == null ? volume(description.usableBytes()) : null;
        MissingReason totalReason = missing != null ? missing : total == null ? MissingReason.UNSUPPORTED : total < 0 ? MissingReason.INVALID_VALUE : null;
        MissingReason usableReason = missing != null ? missing : usable == null ? MissingReason.UNSUPPORTED
                : usable < 0 || totalReason == null && usable > total ? MissingReason.INVALID_VALUE : null;
        state.problem(totalReason); state.problem(usableReason);
        metrics.add(sample(def("kafka.log_directory.volume.total.bytes", "目录所在卷原生总字节", Unit.BYTES, scope, "原生 totalBytes；可与其他目录共享同卷，不跨目录或 broker 相加"), totalReason == null ? total : null, totalReason, at));
        metrics.add(sample(def("kafka.log_directory.volume.usable.bytes", "目录所在卷原生可用字节", Unit.BYTES, scope, "原生 usableBytes；不从日志段之和推导使用率，不跨目录相加"), usableReason == null ? usable : null, usableReason, at));
        metrics.add(success(def("kafka.log_directory.volume.identity.known", "原生卷身份可识别", Unit.BOOLEAN, scope, "现有返回不提供可靠卷身份；相同容量也不能证明同卷，所以不输出跨目录卷总和"), false, at));
        metrics.add(sample(def("kafka.log_directory.replicas.future", "已知 future 副本行数", Unit.COUNT, scope, "本次已展示且大小可读的 future 副本行数；未知或省略行不计入"), knownFailure == null ? future : null, knownFailure, at));
        return new DirectoryOutcome(shown.size(), complete);
    }

    static String directoryId(String path) {
        try { return "logdir-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Required digest unavailable"); }
    }
    private static Long volume(OptionalLong value) { return value == null || value.isEmpty() ? null : value.getAsLong(); }
    private static Definition def(String key, String label, Unit unit, Scope scope, String calculation) {
        return new Definition(key, label, unit, scope, "Admin.describeLogDirs(配置 topic 副本所在 broker)", calculation);
    }
    private MetricSample sample(Definition definition, Object value, MissingReason reason, Instant at) {
        return reason == null ? success(definition, value, at) : MetricSample.missing(definition, reason, at);
    }
    private MetricSample success(Definition definition, Object value, Instant at) {
        return MetricSample.success(definition, value, at, properties.getCapacityInterval().multipliedBy(properties.getMetricTtlMultiplier()));
    }
    private Instant atLeast(Instant at) { Instant now = clock.instant(); return now.isBefore(at) ? at : now; }
    private static final class State {
        MissingReason reason;
        boolean inventoryComplete = true;
        boolean topologyIncomplete;
        boolean logsComplete = true;
        boolean truncated;
        int partitions;
        int directoriesObserved;
        void problem(MissingReason missing) { if (reason == null) reason = missing; }
    }
}
