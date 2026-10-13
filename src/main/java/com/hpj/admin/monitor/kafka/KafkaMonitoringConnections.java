package com.hpj.admin.monitor.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.hpj.admin.monitor.metric.CollectionControl;
import com.hpj.admin.monitor.metric.CollectionRequest;
import com.hpj.admin.monitor.metric.MetricContract.MissingReason;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.DescribeLogDirsOptions;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.security.JaasContext;
import org.apache.kafka.common.utils.AppInfoParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Creates one owned Kafka 3.6.1 AdminClient from the resolved native source. Only scoped read operations are exposed.
 * DNS, TLS and static PLAIN/SCRAM authentication retain Kafka's native behavior. Opaque code/config providers and
 * refresh mechanisms are declined before native configuration parsing can instantiate them.
 *
 * Cancellation's short close request is not a resource-exit barrier. The original collection worker retains a
 * separate reservation and joins through native close(ZERO), including after interruption, until the I/O thread exits.
 * A stuck native DNS operation therefore holds its original worker and reservation; it cannot spawn replacement work.
 */
public class KafkaMonitoringConnections {
    private static final AtomicLong CLIENT_SEQUENCE = new AtomicLong();
    private static final String SAFE_MESSAGE = "Kafka monitoring operation unavailable";
    private static final Set<String> SASL_MECHANISMS = Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");
    private static final Set<String> PROTOCOLS = Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    private static final Map<String, String> BUILTIN_CLASSES = Map.of(
            "ssl.engine.factory.class", "org.apache.kafka.common.security.ssl.DefaultSslEngineFactory",
            "sasl.login.class", "org.apache.kafka.common.security.authenticator.DefaultLogin",
            "sasl.login.callback.handler.class", "org.apache.kafka.common.security.authenticator.AbstractLogin$DefaultLoginCallbackHandler",
            "sasl.client.callback.handler.class", "org.apache.kafka.common.security.authenticator.SaslClientCallbackHandler");
    private final AdminFactory factory;

    public KafkaMonitoringConnections() {
        this(properties -> {
            // close(ZERO)'s join semantics and supported native hooks are explicitly audited for this pinned version.
            if (!"3.6.1".equals(AppInfoParser.getVersion())) throw unsupported();
            return Admin.create(properties);
        });
    }

    KafkaMonitoringConnections(AdminFactory factory) { this.factory = factory; }

    @FunctionalInterface interface AdminFactory { Admin create(Map<String, Object> properties); }

    public interface Session extends AutoCloseable {
        ClusterRead describeCluster();
        Map<String, TopicRead> describeTopics(List<String> allowedTopics);
        LagRead readCommittedLag(List<String> allowedGroups, List<TopicPartition> allowedPartitions);
        Map<Integer, DirectoriesRead> describeLogDirectories(List<Integer> allowedBrokers, List<TopicPartition> allowedPartitions);
        @Override void close();
    }

    @JsonIgnoreType
    public record ClusterRead(List<Node> nodes, String clusterId, Failure nodesFailure, Failure clusterIdFailure) {
        public ClusterRead { if (nodes != null) nodes = List.copyOf(nodes); }
    }

    @JsonIgnoreType
    public record TopicRead(TopicDescription topic, Failure failure) { }

    @JsonIgnoreType
    public record OffsetRead(Long offset, Failure failure) { }

    @JsonIgnoreType
    public record LagRead(Map<TopicPartition, OffsetRead> ends, Map<String, Map<TopicPartition, OffsetRead>> committed) {
        public LagRead {
            ends = Map.copyOf(ends);
            Map<String, Map<TopicPartition, OffsetRead>> groups = new LinkedHashMap<>();
            committed.forEach((group, offsets) -> groups.put(group, Map.copyOf(offsets)));
            committed = Collections.unmodifiableMap(groups);
        }
    }

    @JsonIgnoreType
    public record DirectoriesRead(Map<String, LogDirDescription> directories, Failure failure) {
        public DirectoriesRead { if (directories != null) directories = Collections.unmodifiableMap(new LinkedHashMap<>(directories)); }
    }

    /** Carries classifications only. Native exception text, credential-bearing causes and raw settings stay private. */
    public static final class Failure extends RuntimeException {
        private final MissingReason reason;
        private final boolean connectionFailure;
        public Failure(MissingReason reason, boolean connectionFailure) {
            super(SAFE_MESSAGE, null, false, false);
            this.reason = reason;
            this.connectionFailure = connectionFailure;
        }
        public MissingReason reason() { return reason; }
        public boolean connectionFailure() { return connectionFailure; }
    }

    public Session open(CollectionRequest request) {
        checkActive(request.control());
        Settings settings = settings(request);
        Attempt attempt = new Attempt(request.control(), request.client());
        boolean opened = false;
        try {
            Admin admin = factory.create(settings.properties());
            if (admin == null || admin == request.client()) throw unsupported();
            attempt.accept(admin);
            checkActive(request.control());
            opened = true;
            return new NativeSession(attempt, admin, request.scope().topics(), request.scope().consumerGroups(), settings.apiTimeoutMillis());
        } catch (RuntimeException failure) {
            throw sanitized(failure);
        } finally {
            if (!opened) attempt.close();
        }
    }

    private static Settings settings(CollectionRequest request) {
        try {
            Map<String, Object> source = request.settings();
            // These hooks can run before an Admin is returned. Decline them before parsing TYPE.CLASS options.
            for (var entry : source.entrySet()) {
                String name = entry.getKey();
                Object value = entry.getValue();
                if ((name.equals("config.providers") || name.startsWith("config.providers.") || name.equals("security.providers"))
                        && !empty(value)) throw unsupported();
                if (BUILTIN_CLASSES.containsKey(name) && !empty(value)
                        && !BUILTIN_CLASSES.get(name).equals(className(value))) throw unsupported();
            }
            if (!empty(source.get("metric.reporters"))) {
                for (Object reporter : classNames(source.get("metric.reporters"))) {
                    if (!"org.apache.kafka.common.metrics.JmxReporter".equals(className(reporter))) throw unsupported();
                }
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            // Producer/consumer serializers, interceptors and group settings have no Admin connection meaning.
            // Native Admin config names retain authentication, DNS, endpoint and transport settings without callbacks.
            Set<String> names = AdminClientConfig.configNames();
            source.forEach((name, value) -> { if (names.contains(name)) properties.put(name, value); });
            properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "admin-monitor-" + request.targetId() + "-" + CLIENT_SEQUENCE.incrementAndGet());
            AdminClientConfig parsed = new AdminClientConfig(properties); // doLog=false; never log failed raw configuration.
            String protocol = parsed.getString("security.protocol");
            if (!PROTOCOLS.contains(protocol)) throw unsupported();
            validateBootstrap(parsed.getList(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
            if (protocol.startsWith("SASL_")) validateSasl(parsed);
            int originalRequest = parsed.getInt(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG);
            int originalApi = parsed.getInt(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG);
            if (source.containsKey(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG) && originalApi < originalRequest) {
                throw unsupported();
            }
            long apiMillis = Math.max(originalApi, originalRequest);
            int boundedApi = bound(apiMillis, request.control());
            properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.min(bound(originalRequest, request.control()), boundedApi));
            properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, boundedApi);
            properties.put("socket.connection.setup.timeout.ms", bound(parsed.getLong("socket.connection.setup.timeout.ms"), request.control()));
            properties.put("socket.connection.setup.timeout.max.ms", bound(parsed.getLong("socket.connection.setup.timeout.max.ms"), request.control()));
            return new Settings(Collections.unmodifiableMap(properties), apiMillis);
        } catch (Failure safe) { throw safe; }
        catch (RuntimeException invalidConfiguration) { throw unsupported(); }
    }

    private static void validateBootstrap(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) throw unsupported();
        for (String address : addresses) {
            if (address == null || address.isBlank() || address.chars().anyMatch(Character::isWhitespace)
                    || address.chars().anyMatch(Character::isISOControl) || address.contains("/") || address.contains("@")
                    || address.contains("?") || address.contains("#")) throw unsupported();
            String host = org.apache.kafka.common.utils.Utils.getHost(address);
            Integer port = org.apache.kafka.common.utils.Utils.getPort(address);
            if (host == null || host.isBlank() || port == null || port < 1 || port > 65535) throw unsupported();
        }
    }

    private static void validateSasl(AdminClientConfig parsed) {
        String mechanism = parsed.getString("sasl.mechanism");
        Password jaas = parsed.getPassword("sasl.jaas.config");
        if (!SASL_MECHANISMS.contains(mechanism) || jaas == null) throw unsupported();
        // With an explicit Password this native call only parses JAAS. It does not load global JAAS or instantiate a module.
        var entries = JaasContext.loadClientContext(Map.of("sasl.jaas.config", jaas)).configurationEntries();
        String expected = mechanism.equals("PLAIN") ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                : "org.apache.kafka.common.security.scram.ScramLoginModule";
        if (entries.size() != 1 || !expected.equals(entries.get(0).getLoginModuleName())
                || !(entries.get(0).getOptions().get("username") instanceof String)
                || !(entries.get(0).getOptions().get("password") instanceof String)) throw unsupported();
    }

    private static boolean empty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty();
    }

    private static Collection<?> classNames(Object value) {
        if (value instanceof Collection<?> collection) return collection;
        if (value instanceof String text) return List.of(text.split(",", -1));
        throw unsupported();
    }

    private static String className(Object value) {
        if (value instanceof Class<?> type) return type.getName();
        if (value instanceof String text) return text.trim();
        throw unsupported();
    }

    private static int bound(long configured, CollectionControl control) {
        checkActive(control);
        long remaining = Math.max(1, control.remaining().toMillis());
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.min(configured, remaining)));
    }

    private static void checkActive(CollectionControl control) {
        try { control.checkActive(); }
        catch (CollectionControl.InactiveCollectionException inactive) { throw new Failure(inactive.reason(), false); }
    }

    private static Failure unsupported() { return new Failure(MissingReason.UNSUPPORTED, false); }
    static Failure classify(Throwable failure) { return sanitized(failure); }

    private static Failure sanitized(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++) {
            if (failure instanceof Failure safe) return safe;
            if (failure instanceof CollectionControl.InactiveCollectionException inactive) return new Failure(inactive.reason(), false);
            if (failure instanceof AuthenticationException || failure instanceof AuthorizationException) {
                return new Failure(MissingReason.UNAUTHORIZED, false);
            }
            if (failure instanceof UnsupportedVersionException) return unsupported();
            if (failure instanceof UnknownTopicOrPartitionException) return new Failure(MissingReason.NOT_APPLICABLE, false);
            if (failure instanceof TimeoutException || failure instanceof org.apache.kafka.common.errors.TimeoutException) {
                return new Failure(MissingReason.TIMEOUT, false);
            }
            if (failure instanceof DisconnectException || failure instanceof java.net.ConnectException
                    || failure instanceof java.net.UnknownHostException) return new Failure(MissingReason.FAILED, true);
            Throwable next = failure.getCause();
            if (next == failure) break;
            failure = next;
        }
        return new Failure(MissingReason.FAILED, false);
    }

    private record Settings(Map<String, Object> properties, long apiTimeoutMillis) { }

    private static final class Attempt {
        private final CollectionControl control;
        private final Object borrowed;
        private final CollectionControl.Registration exitBarrier;
        private final CollectionControl.Registration cancellation;
        private final AtomicReference<Admin> admin = new AtomicReference<>();
        private volatile boolean closing;

        private Attempt(CollectionControl control, Object borrowed) {
            this.control = control;
            this.borrowed = borrowed;
            CollectionControl.Registration reserved = null;
            try {
                reserved = control.reserveOwnedCancellation();
                exitBarrier = reserved;
                cancellation = control.reserveOwnedCancellation();
            } catch (RuntimeException failure) {
                if (reserved != null) reserved.close();
                throw sanitized(failure);
            }
        }

        private void accept(Admin acquired) {
            if (acquired == borrowed) throw unsupported();
            admin.set(acquired);
            cancellation.attach(acquired, () -> {
                closing = true;
                acquired.close(Duration.ofMillis(1));
            });
            checkActive(control);
        }

        private void close() {
            closing = true;
            Admin owned = admin.get();
            boolean interrupted = Thread.interrupted();
            try {
                if (owned != null) {
                    boolean interruptedJoin;
                    do {
                        // Kafka 3.6.1 uses thread.join(0), even after a prior close(1ms). Run only on the original worker.
                        // Native close catches InterruptedException and restores the flag; retry until an uninterrupted join.
                        owned.close(Duration.ZERO);
                        interruptedJoin = Thread.interrupted();
                        interrupted |= interruptedJoin;
                    } while (interruptedJoin);
                    admin.compareAndSet(owned, null);
                }
                cancellation.close();
                exitBarrier.close();
            } catch (RuntimeException failure) {
                // Keep the exit reservation: a failed native close is not proof that the resource terminated.
                throw new Failure(MissingReason.FAILED, false);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static final class NativeSession implements Session {
        private final Attempt attempt;
        private final Admin admin;
        private final Set<String> scope;
        private final Set<String> groups;
        private final Map<String, TopicDescription> knownTopics = new LinkedHashMap<>();
        private final long apiTimeout;

        private NativeSession(Attempt attempt, Admin admin, List<String> scope, List<String> groups, long apiTimeout) {
            this.attempt = attempt;
            this.admin = admin;
            this.scope = Set.copyOf(scope);
            this.groups = Set.copyOf(groups);
            this.apiTimeout = apiTimeout;
        }

        private int timeout() {
            checkActive(attempt.control);
            if (attempt.closing) throw new Failure(MissingReason.FAILED, false);
            return bound(apiTimeout, attempt.control);
        }

        @Override public ClusterRead describeCluster() {
            try {
                var result = admin.describeCluster(new DescribeClusterOptions().timeoutMs(timeout()).includeAuthorizedOperations(false));
                Map<String, KafkaFuture<?>> requested = new LinkedHashMap<>();
                requested.put("nodes", result.nodes());
                requested.put("clusterId", result.clusterId());
                Map<String, Read> reads = readAll(requested);
                Read nodes = reads.get("nodes");
                Read id = reads.get("clusterId");
                List<Node> visible = null;
                Failure nodesFailure = nodes.failure();
                if (nodesFailure == null) {
                    if (nodes.value() instanceof Collection<?> collection && collection.stream().allMatch(Node.class::isInstance)) {
                        visible = collection.stream().map(Node.class::cast).toList();
                    } else nodesFailure = new Failure(MissingReason.INVALID_VALUE, false);
                }
                String clusterId = id.value() instanceof String text ? text : null;
                Failure idFailure = id.failure();
                if (idFailure == null && clusterId == null) idFailure = new Failure(MissingReason.UNSUPPORTED, false);
                return new ClusterRead(visible, clusterId, nodesFailure, idFailure);
            } catch (RuntimeException failure) {
                Failure safe = sanitized(failure);
                return new ClusterRead(null, null, safe, safe);
            }
        }

        @Override public Map<String, TopicRead> describeTopics(List<String> allowedTopics) {
            if (allowedTopics == null || allowedTopics.stream().anyMatch(topic -> topic == null || !scope.contains(topic))) throw unsupported();
            List<String> requestedNames = List.copyOf(new LinkedHashSet<>(allowedTopics));
            if (requestedNames.isEmpty()) return Map.of();
            requestedNames.forEach(knownTopics::remove);
            try {
                var result = admin.describeTopics(requestedNames,
                        new DescribeTopicsOptions().timeoutMs(timeout()).includeAuthorizedOperations(false));
                Map<String, KafkaFuture<?>> requested = new LinkedHashMap<>();
                for (String name : requestedNames) requested.put(name, result.topicNameValues().get(name));
                Map<String, Read> reads = readAll(requested);
                Map<String, TopicRead> output = new LinkedHashMap<>();
                for (String name : requestedNames) {
                    Read read = reads.get(name);
                    if (read.failure() != null) output.put(name, new TopicRead(null, read.failure()));
                    else if (read.value() instanceof TopicDescription description && name.equals(description.name())) {
                        output.put(name, new TopicRead(description, null));
                        knownTopics.put(name, description);
                    } else output.put(name, new TopicRead(null, new Failure(MissingReason.INVALID_VALUE, false)));
                }
                return Collections.unmodifiableMap(output);
            } catch (RuntimeException failure) {
                Map<String, TopicRead> output = new LinkedHashMap<>();
                Failure safe = sanitized(failure);
                requestedNames.forEach(name -> output.put(name, new TopicRead(null, safe)));
                return Collections.unmodifiableMap(output);
            }
        }

        @Override public LagRead readCommittedLag(List<String> allowedGroups, List<TopicPartition> allowedPartitions) {
            if (allowedGroups == null || allowedPartitions == null
                    || allowedGroups.stream().anyMatch(group -> group == null || !groups.contains(group))
                    || allowedPartitions.stream().anyMatch(partition -> partition == null || partition.partition() < 0
                            || !scope.contains(partition.topic()))) throw unsupported();
            List<String> requestedGroups = List.copyOf(new LinkedHashSet<>(allowedGroups));
            List<TopicPartition> partitions = List.copyOf(new LinkedHashSet<>(allowedPartitions));
            // A null native partition specification means all topics. Never issue either read for an empty scope.
            if (requestedGroups.isEmpty() || partitions.isEmpty()) return new LagRead(Map.of(), Map.of());
            Map<String, KafkaFuture<?>> futures = new LinkedHashMap<>();
            Map<String, Read> failed = new LinkedHashMap<>();
            try {
                Map<String, ListConsumerGroupOffsetsSpec> specs = new LinkedHashMap<>();
                requestedGroups.forEach(group -> specs.put(group, new ListConsumerGroupOffsetsSpec().topicPartitions(partitions)));
                var result = admin.listConsumerGroupOffsets(specs, new ListConsumerGroupOffsetsOptions().timeoutMs(timeout()));
                for (int index = 0; index < requestedGroups.size(); index++) {
                    futures.put("group#" + index, result.partitionsToOffsetAndMetadata(requestedGroups.get(index)));
                }
            } catch (RuntimeException failure) {
                Failure safe = sanitized(failure);
                for (int index = 0; index < requestedGroups.size(); index++) failed.put("group#" + index, new Read(null, safe));
            }
            try {
                Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
                partitions.forEach(partition -> latest.put(partition, OffsetSpec.latest()));
                var result = admin.listOffsets(latest, new ListOffsetsOptions().timeoutMs(timeout()));
                for (int index = 0; index < partitions.size(); index++) futures.put("end#" + index, result.partitionResult(partitions.get(index)));
            } catch (RuntimeException failure) {
                Failure safe = sanitized(failure);
                for (int index = 0; index < partitions.size(); index++) failed.put("end#" + index, new Read(null, safe));
            }
            // Both native batches have started before waiting. Completed partitions/groups survive another timeout.
            Map<String, Read> reads = readAll(futures);
            reads.putAll(failed);
            Map<TopicPartition, OffsetRead> ends = new LinkedHashMap<>();
            for (int index = 0; index < partitions.size(); index++) {
                Read read = reads.get("end#" + index);
                OffsetRead value = read.failure() != null ? new OffsetRead(null, read.failure())
                        : read.value() instanceof ListOffsetsResult.ListOffsetsResultInfo info && info.offset() >= 0
                        ? new OffsetRead(info.offset(), null) : invalidOffset();
                ends.put(partitions.get(index), value);
            }
            Map<String, Map<TopicPartition, OffsetRead>> committed = new LinkedHashMap<>();
            for (int index = 0; index < requestedGroups.size(); index++) {
                Read read = reads.get("group#" + index);
                Map<TopicPartition, OffsetRead> offsets = new LinkedHashMap<>();
                for (TopicPartition partition : partitions) {
                    OffsetRead value;
                    if (read.failure() != null) value = new OffsetRead(null, read.failure());
                    else if (!(read.value() instanceof Map<?, ?> nativeOffsets)) value = invalidOffset();
                    else if (!nativeOffsets.containsKey(partition)) {
                        // Kafka 3.6.1 omits per-partition errors from this result. Do not claim an absent key is zero/no commit.
                        value = new OffsetRead(null, new Failure(MissingReason.FAILED, false));
                    } else if (nativeOffsets.get(partition) == null) {
                        value = new OffsetRead(null, new Failure(MissingReason.NO_COMMITTED_OFFSET, false));
                    } else if (nativeOffsets.get(partition) instanceof OffsetAndMetadata offset && offset.offset() >= 0) {
                        value = new OffsetRead(offset.offset(), null);
                    } else value = invalidOffset();
                    offsets.put(partition, value);
                }
                committed.put(requestedGroups.get(index), offsets);
            }
            return new LagRead(ends, committed);
        }

        @Override public Map<Integer, DirectoriesRead> describeLogDirectories(List<Integer> allowedBrokers, List<TopicPartition> allowedPartitions) {
            if (allowedBrokers == null || allowedPartitions == null
                    || allowedPartitions.stream().anyMatch(partition -> partition == null || partition.partition() < 0 || !scope.contains(partition.topic()))) throw unsupported();
            List<Integer> brokers = List.copyOf(new LinkedHashSet<>(allowedBrokers));
            if (brokers.isEmpty()) return Map.of();
            Set<Integer> permitted = new LinkedHashSet<>();
            for (TopicPartition partition : allowedPartitions) {
                TopicDescription topic = knownTopics.get(partition.topic());
                if (topic == null || topic.partitions() == null || partition.partition() >= topic.partitions().size()) throw unsupported();
                var metadata = topic.partitions().get(partition.partition());
                if (metadata == null || metadata.partition() != partition.partition() || metadata.replicas() == null) throw unsupported();
                // Match capacity's bounded replica prefix; do not traverse a huge native list just to authorize a read.
                for (int index = 0; index < Math.min(metadata.replicas().size(), KafkaLogCapacity.MAX_REPLICA_NODES); index++) {
                    Node node = metadata.replicas().get(index);
                    if (node != null && node.id() >= 0) permitted.add(node.id());
                }
            }
            if (brokers.stream().anyMatch(broker -> broker == null || broker < 0 || !permitted.contains(broker))) throw unsupported();
            Map<Integer, DirectoriesRead> output = new LinkedHashMap<>();
            try {
                var result = admin.describeLogDirs(brokers, new DescribeLogDirsOptions().timeoutMs(timeout()));
                Map<String, KafkaFuture<?>> futures = new LinkedHashMap<>();
                brokers.forEach(broker -> futures.put("broker#" + broker, result.descriptions().get(broker)));
                Map<String, Read> reads = readAll(futures);
                for (Integer broker : brokers) {
                    Read read = reads.get("broker#" + broker);
                    if (read.failure() != null) output.put(broker, new DirectoriesRead(null, read.failure()));
                    else if (read.value() instanceof Map<?, ?> directories && directories.entrySet().stream().allMatch(entry ->
                            entry.getKey() instanceof String && entry.getValue() instanceof LogDirDescription)) {
                        @SuppressWarnings("unchecked") Map<String, LogDirDescription> typed = (Map<String, LogDirDescription>) directories;
                        output.put(broker, new DirectoriesRead(typed, null));
                    } else output.put(broker, new DirectoriesRead(null, new Failure(MissingReason.INVALID_VALUE, false)));
                }
                return Collections.unmodifiableMap(output);
            } catch (RuntimeException failure) {
                Failure safe = sanitized(failure);
                brokers.forEach(broker -> output.put(broker, new DirectoriesRead(null, safe)));
                return Collections.unmodifiableMap(output);
            }
        }

        private static OffsetRead invalidOffset() { return new OffsetRead(null, new Failure(MissingReason.INVALID_VALUE, false)); }

        private Map<String, Read> readAll(Map<String, KafkaFuture<?>> futures) {
            Map<String, Read> reads = new LinkedHashMap<>();
            List<String> pending = new ArrayList<>(futures.keySet());
            while (!pending.isEmpty()) {
                // Read all completed outcomes first. One pending topic must not hide other topics' already-completed data.
                for (String name : List.copyOf(pending)) {
                    KafkaFuture<?> future = futures.get(name);
                    if (future == null || future.isDone()) {
                        reads.put(name, read(future, false));
                        pending.remove(name);
                    }
                }
                if (pending.isEmpty()) break;
                String next = pending.get(0);
                Read waited = read(futures.get(next), true);
                reads.put(next, waited);
                pending.remove(0);
            }
            return reads;
        }

        private Read read(KafkaFuture<?> future, boolean wait) {
            if (future == null) return new Read(null, new Failure(MissingReason.INVALID_VALUE, false));
            try {
                if (!wait) return new Read(future.get(0, TimeUnit.MILLISECONDS), null);
                return new Read(future.get(timeout(), TimeUnit.MILLISECONDS), null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                try { checkActive(attempt.control); }
                catch (Failure inactive) { return new Read(null, inactive); }
                return new Read(null, new Failure(MissingReason.FAILED, false));
            } catch (ExecutionException | TimeoutException | RuntimeException failure) {
                return new Read(null, sanitized(failure));
            }
        }

        @Override public void close() { attempt.close(); }
        private record Read(Object value, Failure failure) { }
    }
}
