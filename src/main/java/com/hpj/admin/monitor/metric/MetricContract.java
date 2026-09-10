package com.hpj.admin.monitor.metric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable public metric data. Borrowed clients, connection identities and credentials belong elsewhere. */
public final class MetricContract {
    private MetricContract() { }

    public enum CollectionKind { ORDINARY, CAPACITY }
    public enum ServiceAvailability { AVAILABLE, DEGRADED, CONNECTION_FAILED, UNKNOWN }
    public enum CollectionStatus { SUCCESS, PARTIAL, UNAUTHORIZED, UNSUPPORTED, BUSY, FAILED, STALE, WAITING }
    public enum MissingReason {
        UNSUPPORTED, UNAUTHORIZED, NOT_APPLICABLE, WAITING_SAMPLE, NO_REQUESTS, BUSY, TIMEOUT, FAILED, INVALID_VALUE
    }
    public enum Unit { COUNT, COUNT_PER_SECOND, BYTES, BYTES_PER_SECOND, SECONDS, MILLISECONDS, PERCENT, CORES, TEXT, BOOLEAN }
    public enum ScopeKind {
        PROCESS, JVM_HEAP, CACHE, DATABASE, INDEX, TOPIC, PARTITION, CONSUMER_GROUP, BUCKET,
        NODE, CLUSTER, FILESYSTEM, NODE_REPORTED_OS, CONFIGURED_SCOPE, ENDPOINT
    }

    public record Scope(ScopeKind kind, String id, String nodeId) {
        public Scope {
            required(kind, "scope kind is required");
            text(id, 512, "scope id is required and must be bounded text");
            if (nodeId != null) text(nodeId, 256, "node id must be bounded text when present");
        }
    }

    public record Definition(String key, String label, Unit unit, Scope scope, String source, String calculation) {
        public Definition {
            reference(key, "metric key must be a stable safe reference");
            text(label, 160, "metric label is required and must be bounded text");
            required(unit, "metric unit is required");
            required(scope, "metric scope is required");
            text(source, 512, "metric source is required and must be bounded text");
            text(calculation, 2048, "metric calculation is required and must be bounded text");
        }
    }

    public record MetricSample(Definition definition, Object value, MissingReason missingReason,
                               Instant sampledAt, Instant lastSuccessAt, Instant validUntil) {
        public MetricSample {
            required(definition, "metric definition is required");
            ordered(sampledAt, validUntil, "metric validity must not precede its sample");
            require((value == null) != (missingReason == null), "metric must contain either a value or a missing reason");
            if (value != null) {
                value = scalar(value);
                require(sampledAt.equals(lastSuccessAt), "a successful sample must record its own success time");
                boolean appropriate = switch (definition.unit()) {
                    case TEXT -> value instanceof String;
                    case BOOLEAN -> value instanceof Boolean;
                    default -> value instanceof BigDecimal;
                };
                require(appropriate, "metric value must match its unit");
                if (value instanceof String text) {
                    require(text.length() <= 4096 && text.chars().noneMatch(Character::isISOControl),
                            "metric text value must be bounded text without control characters");
                }
            } else {
                require(lastSuccessAt == null, "a missing sample cannot claim a successful value");
            }
        }

        public static MetricSample success(Definition definition, Object value, Instant at, Duration ttl) {
            required(at, "sample time is required");
            required(ttl, "metric validity duration is required");
            require(!ttl.isNegative(), "metric validity duration must not be negative");
            Instant expires;
            try {
                expires = at.plus(ttl);
            } catch (DateTimeException | ArithmeticException failure) {
                throw invalid("metric validity duration is out of range");
            }
            return new MetricSample(definition, value, null, at, at, expires);
        }

        public static MetricSample missing(Definition definition, MissingReason reason, Instant at) {
            return new MetricSample(definition, null, reason, at, null, at);
        }
    }

    public record ServiceProbe(ServiceAvailability availability, MissingReason reason, Scope scope,
                               Instant sampledAt, Instant validUntil) {
        public ServiceProbe {
            required(availability, "service availability is required");
            required(scope, "service probe scope is required");
            ordered(sampledAt, validUntil, "service probe validity must not precede its sample");
            require(availability != ServiceAvailability.UNKNOWN || reason != null,
                    "an unknown service observation requires a reason");
            require(availability != ServiceAvailability.CONNECTION_FAILED
                            || reason != MissingReason.UNAUTHORIZED && reason != MissingReason.UNSUPPORTED,
                    "authorization and unsupported capabilities do not prove a connection failure");
        }
    }

    /** Complete inventory includes missing-value placeholders for every still-authorized metric series. */
    public record CollectionResult(CollectionStatus status, Instant startedAt, Instant completedAt,
                                   List<MetricSample> metrics, ServiceProbe serviceProbe, boolean inventoryComplete,
                                   MissingReason reason) {
        /** Unspecified inventory completeness must never retire a previously known series. */
        public CollectionResult(CollectionStatus status, Instant startedAt, Instant completedAt,
                                List<MetricSample> metrics, ServiceProbe serviceProbe) {
            this(status, startedAt, completedAt, metrics, serviceProbe, false, null);
        }

        public CollectionResult(CollectionStatus status, Instant startedAt, Instant completedAt,
                                List<MetricSample> metrics, ServiceProbe serviceProbe, boolean inventoryComplete) {
            this(status, startedAt, completedAt, metrics, serviceProbe, inventoryComplete, null);
        }

        public CollectionResult {
            required(status, "collection status is required");
            statusReason(status, reason);
            ordered(startedAt, completedAt, "collection completion must not precede its start");
            metrics = copy(metrics, "collection metrics must not be null");
            Set<SeriesKey> unique = new HashSet<>();
            for (MetricSample sample : metrics) {
                require(unique.add(series(sample.definition())), "collection contains a duplicate metric series");
            }
        }
    }

    public record StoredMetric(String source, String bindingId, CollectionKind kind,
                               MetricSample latestAttempt, MetricSample lastSuccess) {
        public StoredMetric(String source, CollectionKind kind, MetricSample latestAttempt, MetricSample lastSuccess) {
            this(source, source, kind, latestAttempt, lastSuccess);
        }

        public StoredMetric {
            reference(source, "stored metric source must be a safe reference");
            reference(bindingId, "stored metric binding ID must be a safe reference");
            required(kind, "stored metric collection kind is required");
            required(latestAttempt, "stored metric latest attempt is required");
            if (lastSuccess != null) {
                require(lastSuccess.value() != null && lastSuccess.missingReason() == null,
                        "stored metric last success must contain a successful value");
                require(latestAttempt.definition().equals(lastSuccess.definition()),
                        "stored metric samples must have identical definitions");
                require(!lastSuccess.sampledAt().isAfter(latestAttempt.sampledAt()),
                        "stored metric last success must not be newer than its latest attempt");
            }
            require(latestAttempt.value() == null || latestAttempt.equals(lastSuccess),
                    "a successful latest attempt must also be the stored last success");
        }
    }

    public record Attempt(String source, String bindingId, CollectionKind kind, long sequence, Instant startedAt,
                          Instant completedAt, CollectionStatus status, MissingReason reason) {
        public Attempt(String source, CollectionKind kind, long sequence, Instant startedAt,
                       Instant completedAt, CollectionStatus status) {
            this(source, source, kind, sequence, startedAt, completedAt, status, null);
        }

        public Attempt(String source, String bindingId, CollectionKind kind, long sequence, Instant startedAt,
                       Instant completedAt, CollectionStatus status) {
            this(source, bindingId, kind, sequence, startedAt, completedAt, status, null);
        }

        public Attempt {
            reference(source, "attempt source must be a safe reference");
            reference(bindingId, "attempt binding ID must be a safe reference");
            required(kind, "attempt collection kind is required");
            require(sequence >= 0, "attempt sequence must not be negative");
            required(status, "attempt status is required");
            statusReason(status, reason);
            ordered(startedAt, completedAt, "attempt completion must not precede its start");
        }
    }

    public record TargetSnapshot(String targetId, long generation, List<Attempt> attempts,
                                 List<StoredMetric> metrics, Map<String, ServiceProbe> serviceProbes) {
        public TargetSnapshot {
            reference(targetId, "target id must be a safe reference");
            require(generation >= 0, "target generation must not be negative");
            attempts = copy(attempts, "snapshot attempts must not be null");
            metrics = copy(metrics, "snapshot metrics must not be null");
            Set<BindingKind> attemptKeys = new HashSet<>();
            for (Attempt attempt : attempts) {
                require(attemptKeys.add(new BindingKind(attempt.bindingId(), attempt.kind())),
                        "snapshot contains a duplicate binding attempt");
            }
            Set<StoredKey> metricKeys = new HashSet<>();
            for (StoredMetric metric : metrics) {
                require(metricKeys.add(new StoredKey(metric.bindingId(), metric.kind(), series(metric.latestAttempt().definition()))),
                        "snapshot contains a duplicate stored metric series");
            }
            required(serviceProbes, "snapshot service probes must not be null");
            Map<String, ServiceProbe> probes = new LinkedHashMap<>();
            serviceProbes.forEach((bindingId, probe) -> {
                reference(bindingId, "service probe binding ID must be a safe reference");
                required(probe, "service probe must not be null");
                probes.put(bindingId, probe);
            });
            serviceProbes = Collections.unmodifiableMap(probes);
        }
    }

    private record SeriesKey(String key, Scope scope) { }
    private record BindingKind(String bindingId, CollectionKind kind) { }
    private record StoredKey(String bindingId, CollectionKind kind, SeriesKey series) { }
    private static SeriesKey series(Definition definition) { return new SeriesKey(definition.key(), definition.scope()); }

    /** A result-level reason also describes failures before any metric series has been discovered. */
    private static void statusReason(CollectionStatus status, MissingReason reason) {
        if (reason == null) return;
        boolean valid = switch (status) {
            case SUCCESS -> false;
            case PARTIAL, STALE -> true;
            case UNAUTHORIZED -> reason == MissingReason.UNAUTHORIZED;
            case UNSUPPORTED -> reason == MissingReason.UNSUPPORTED || reason == MissingReason.NOT_APPLICABLE;
            case BUSY -> reason == MissingReason.BUSY;
            case WAITING -> reason == MissingReason.WAITING_SAMPLE || reason == MissingReason.NO_REQUESTS;
            case FAILED -> reason == MissingReason.FAILED || reason == MissingReason.TIMEOUT || reason == MissingReason.INVALID_VALUE;
        };
        require(valid, "collection status and reason must agree");
    }

    private static Object scalar(Object value) {
        if (value instanceof String || value instanceof Boolean || value.getClass() == BigDecimal.class) return value;
        if (value.getClass() == BigInteger.class) return new BigDecimal((BigInteger) value);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Double number) {
            require(Double.isFinite(number), "metric value must be finite");
            return BigDecimal.valueOf(number);
        }
        if (value instanceof Float number) {
            require(Float.isFinite(number), "metric value must be finite");
            return new BigDecimal(Float.toString(number));
        }
        throw invalid("metric value must be an immutable supported scalar");
    }

    private static <T> List<T> copy(List<T> values, String message) {
        required(values, message);
        require(values.stream().noneMatch(value -> value == null), "metric collection must not contain null entries");
        return List.copyOf(values);
    }

    private static void ordered(Instant from, Instant until, String message) {
        require(from != null && until != null && !until.isBefore(from), message);
    }

    private static void reference(String value, String message) {
        require(value != null && value.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,95}"), message);
    }

    private static void text(String value, int maximumLength, String message) {
        require(value != null && !value.isBlank() && value.length() <= maximumLength
                && value.chars().noneMatch(Character::isISOControl), message);
    }

    private static void required(Object value, String message) { require(value != null, message); }
    private static void require(boolean valid, String message) { if (!valid) throw invalid(message); }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid metric contract: " + message);
    }
}
