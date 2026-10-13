package com.hpj.admin.monitor.kafka;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import org.apache.kafka.common.TopicPartition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.hpj.admin.monitor.kafka.KafkaMonitoringConnections.*;
import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Scoped committed-progress differences. An incomplete sum never becomes the group's complete total. */
final class KafkaCommittedLag {
    static final int SCOPE_METRICS = 3;
    static final int GROUP_METRICS = 8;
    static final int PARTITION_METRICS = 4;
    private static final String SOURCE = "Admin.listOffsets(latest), listConsumerGroupOffsets(显式分区范围)";
    private static final String MEANING = "同分区末端位点减已提交位点，仅衡量配置 topic 范围的提交进度差；不代表未处理消息总数或处理耗时";
    private final MonitoringProperties properties;

    KafkaCommittedLag(MonitoringProperties properties) { this.properties = properties; }

    record Partitions(List<TopicPartition> requested, Long expected, MissingReason reason, boolean truncated) { }
    record Outcome(MissingReason reason, boolean inventoryComplete) { }

    static Partitions partitions(List<String> topics, Map<String, TopicRead> reads, int limit, boolean topicTruncated) {
        List<TopicPartition> requested = new ArrayList<>();
        long expected = 0;
        MissingReason reason = topicTruncated ? MissingReason.PARTIAL_SCOPE : null;
        boolean truncated = topicTruncated;
        for (String name : topics) {
            TopicRead read = reads == null ? null : reads.get(name);
            MissingReason missing = !KafkaMonitoringAdapter.validTopic(name) ? MissingReason.INVALID_VALUE
                    : read == null ? MissingReason.FAILED : read.failure() == null ? null : read.failure().reason();
            if (missing == null && (read.topic() == null || !name.equals(read.topic().name()) || read.topic().partitions() == null)) {
                missing = MissingReason.INVALID_VALUE;
            }
            if (missing != null) { if (reason == null) reason = missing; continue; }
            var partitions = read.topic().partitions();
            int count = Math.min(partitions.size(), Math.max(0, limit - requested.size()));
            boolean valid = true;
            for (int index = 0; index < count; index++) {
                if (partitions.get(index) == null || partitions.get(index).partition() != index) { valid = false; break; }
            }
            if (!valid) { if (reason == null) reason = MissingReason.INVALID_VALUE; continue; }
            expected += partitions.size();
            truncated |= count < partitions.size();
            for (int index = 0; index < count; index++) requested.add(new TopicPartition(name, index));
        }
        return new Partitions(List.copyOf(requested), reason == null ? expected : null, reason, truncated);
    }

    Outcome append(List<MetricSample> metrics, List<String> groups, Partitions partitions, LagRead read,
                   MissingReason failure, Instant at) {
        MissingReason reason = failure == null ? partitions.reason() : failure;
        for (int index = 0; index < groups.size(); index++) {
            String group = groups.get(index);
            Scope scope = new Scope(ScopeKind.CONSUMER_GROUP, validGroup(group) ? group : "invalid-group#" + index, null);
            if (!validGroup(group)) {
                for (Definition definition : definitions(scope)) metrics.add(MetricSample.missing(definition, MissingReason.INVALID_VALUE, at));
                if (reason == null) reason = MissingReason.INVALID_VALUE;
                continue;
            }
            Map<TopicPartition, OffsetRead> committed = read == null ? null : read.committed().get(group);
            int covered = 0;
            int anomalies = 0;
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal maximum = null;
            MissingReason problem = failure == null ? partitions.reason() : failure;
            for (TopicPartition partition : partitions.requested()) {
                // Bounded prefix IDs keep even 255-character groups plus 249-character topics below Scope's 512 limit.
                Scope partitionScope = new Scope(ScopeKind.PARTITION, group + "/" + partition.topic() + "#" + partition.partition(), null);
                OffsetRead end = read == null ? null : read.ends().get(partition);
                OffsetRead offset = committed == null ? null : committed.get(partition);
                MissingReason endFailure = offsetFailure(end, failure);
                MissingReason commitFailure = offsetFailure(offset, failure);
                MissingReason missing = commitFailure == null ? endFailure : commitFailure;
                BigDecimal difference = missing == null ? BigDecimal.valueOf(end.offset()).subtract(BigDecimal.valueOf(offset.offset())) : null;
                boolean anomalous = difference != null && difference.signum() < 0;
                metrics.add(sample(def("kafka.group.partition.end_offset", "分区末端位点", Unit.COUNT, partitionScope, "限定分区的原生 latest offset"),
                        endFailure == null ? end.offset() : null, endFailure, at));
                metrics.add(sample(def("kafka.group.partition.committed_offset", "消费组已提交位点", Unit.COUNT, partitionScope, "限定消费组与同分区的原生提交位点；无提交单独标记未知"),
                        commitFailure == null ? offset.offset() : null, commitFailure, at));
                metrics.add(sample(def("kafka.group.partition.lag", "分区提交进度差", Unit.COUNT, partitionScope, MEANING + "；负差保留原值并标为异常"), difference, missing, at));
                metrics.add(sample(def("kafka.group.partition.lag.anomaly", "提交进度差为负", Unit.BOOLEAN, partitionScope, "位点均可读且末端位点小于提交位点时为真；未知差值不能判断正常"),
                        missing == null ? anomalous : null, missing, at));
                if (missing != null) { if (problem == null) problem = missing; }
                else if (anomalous) { anomalies++; if (problem == null) problem = MissingReason.INVALID_VALUE; }
                else { covered++; sum = sum.add(difference); maximum = maximum == null ? difference : maximum.max(difference); }
            }
            boolean empty = partitions.expected() != null && partitions.expected() == 0;
            boolean complete = problem == null && !partitions.truncated() && partitions.expected() != null && covered == partitions.expected();
            MissingReason totalFailure = complete ? null : problem == null ? MissingReason.PARTIAL_SCOPE
                    : partitions.expected() == null ? problem : MissingReason.PARTIAL_SCOPE;
            MissingReason knownFailure = covered > 0 || empty ? null : problem == null ? MissingReason.PARTIAL_SCOPE : problem;
            List<Definition> definitions = definitions(scope);
            metrics.add(sample(definitions.get(0), complete ? sum : null, totalFailure, at));
            metrics.add(sample(definitions.get(1), complete && !empty ? maximum : null,
                    complete && empty ? MissingReason.NOT_APPLICABLE : totalFailure, at));
            metrics.add(sample(definitions.get(2), knownFailure == null ? sum : null, knownFailure, at));
            metrics.add(sample(definitions.get(3), maximum, empty ? MissingReason.NOT_APPLICABLE : knownFailure, at));
            metrics.add(sample(definitions.get(4), partitions.expected(), partitions.expected() == null ? problem == null ? MissingReason.PARTIAL_SCOPE : problem : null, at));
            metrics.add(success(definitions.get(5), covered, at));
            metrics.add(success(definitions.get(6), complete, at));
            metrics.add(success(definitions.get(7), anomalies, at));
            if (reason == null) reason = problem == null && !complete ? MissingReason.PARTIAL_SCOPE : problem;
        }
        // Deterministic bounds retire prior out-of-range rows; an unreadable topic inventory retains old evidence.
        return new Outcome(reason, partitions.reason() == null || partitions.reason() == MissingReason.PARTIAL_SCOPE);
    }

    private static MissingReason offsetFailure(OffsetRead read, MissingReason failure) {
        if (read == null) return failure == null ? MissingReason.FAILED : failure;
        if (read.failure() != null) return read.failure().reason();
        return read.offset() == null || read.offset() < 0 ? MissingReason.INVALID_VALUE : null;
    }

    private static List<Definition> definitions(Scope scope) {
        return List.of(
                def("kafka.group.lag.total", "配置范围完整提交进度差", Unit.COUNT, scope, MEANING + "；仅覆盖完整且无异常时返回总和"),
                def("kafka.group.lag.maximum", "配置范围完整最大分区进度差", Unit.COUNT, scope, MEANING + "；仅覆盖完整且无异常时返回最大分区值"),
                def("kafka.group.lag.known.total", "已知分区提交进度差小计", Unit.COUNT, scope, "只累计成功读取且非负的分区差；必须结合覆盖数，不能当成完整总量"),
                def("kafka.group.lag.known.maximum", "已知分区最大提交进度差", Unit.COUNT, scope, "只取成功读取且非负分区的最大差；不是覆盖未知分区的全组最大值"),
                def("kafka.group.partitions.expected", "配置范围期望分区数", Unit.COUNT, scope, "所有配置 topic 的原生分区数之和；topic 清单失败或省略时未知"),
                def("kafka.group.partitions.covered", "可用差值覆盖分区数", Unit.COUNT, scope, "末端和提交位点均可读且差值非负的已展示分区数；不包括未知或异常分区"),
                def("kafka.group.coverage.complete", "配置范围提交进度差完整", Unit.BOOLEAN, scope, "配置 topic 所有分区均读取且无异常、权限失败或条目截断时为真"),
                def("kafka.group.lag.anomalies", "已观测负差分区数", Unit.COUNT, scope, "本次已读取的负差分区数量；未知分区不计入，不能据此断言全组没有异常"));
    }

    private static Definition def(String key, String label, Unit unit, Scope scope, String calculation) {
        return new Definition(key, label, unit, scope, SOURCE, calculation);
    }
    private MetricSample sample(Definition definition, Object value, MissingReason reason, Instant at) {
        return reason == null ? success(definition, value, at) : MetricSample.missing(definition, reason, at);
    }
    private MetricSample success(Definition definition, Object value, Instant at) {
        return MetricSample.success(definition, value, at, properties.getOrdinaryInterval().multipliedBy(properties.getMetricTtlMultiplier()));
    }
    static boolean validGroup(String group) {
        return group != null && !group.isBlank() && group.length() <= 255 && group.chars().noneMatch(Character::isISOControl);
    }
}
