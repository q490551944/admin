package com.hpj.admin.common.config.monitor;

import com.hpj.admin.monitor.MiddlewareType;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Monitoring configuration contains references to existing connections, never credentials or URLs. */
@Getter
@Setter
@ConfigurationProperties(prefix = "monitor")
public class MonitoringProperties {
    private boolean enabled;
    private Duration ordinaryInterval = Duration.ofSeconds(15);
    private Duration capacityInterval = Duration.ofSeconds(60);
    private Duration refreshInterval = Duration.ofSeconds(15);
    private Duration collectionTimeout = Duration.ofSeconds(5);
    private Duration serviceTtl = Duration.ofSeconds(45);
    private int metricTtlMultiplier = 3;
    private int ordinaryConcurrency = 8;
    private int capacityConcurrency = 2;
    private int maxTargets = 20;
    private List<Long> allowedUserIds = new ArrayList<>();
    private Limits limits = new Limits();
    private List<Target> targets = new ArrayList<>();

    @Getter
    @Setter
    public static class Target {
        private String id;
        private MiddlewareType type;
        private String name;
        private boolean enabled;
        private String connectionSource;
        private Scope scope = new Scope();
    }

    @Getter
    @Setter
    public static class Scope {
        private List<String> databases = new ArrayList<>();
        private List<String> topics = new ArrayList<>();
        private List<String> consumerGroups = new ArrayList<>();
        private List<String> buckets = new ArrayList<>();
        private List<String> indices = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Limits {
        private int databases = 20;
        private int topics = 100;
        private int consumerGroups = 100;
        private int buckets = 20;
        private int indices = 100;
        private int partitions = 1000;
        private int nodes = 100;
        private int logDirectories = 64;
    }

    @PostConstruct
    public void validate() {
        positive(ordinaryInterval, "ordinary-interval");
        positive(capacityInterval, "capacity-interval");
        positive(refreshInterval, "refresh-interval");
        positive(collectionTimeout, "collection-timeout");
        positive(serviceTtl, "service-ttl");
        require(collectionTimeout.compareTo(ordinaryInterval) <= 0
                && collectionTimeout.compareTo(capacityInterval) <= 0,
                "collection-timeout must not exceed a collection interval");
        require(metricTtlMultiplier > 0 && metricTtlMultiplier <= 100, "invalid metric-ttl-multiplier");
        require(ordinaryConcurrency > 0 && ordinaryConcurrency <= 64, "invalid ordinary-concurrency");
        require(capacityConcurrency > 0 && capacityConcurrency <= 64, "invalid capacity-concurrency");
        require(maxTargets > 0 && maxTargets <= 1000, "invalid max-targets");
        require(allowedUserIds != null && allowedUserIds.stream().allMatch(id -> id != null && id > 0),
                "allowed-user-ids must contain positive user IDs");
        require(limits != null, "limits are required");
        for (int limit : List.of(limits.databases, limits.topics, limits.consumerGroups, limits.buckets,
                limits.indices, limits.partitions, limits.nodes, limits.logDirectories)) {
            require(limit > 0 && limit <= 100_000, "limits must be between 1 and 100000");
        }
        require(targets != null && targets.size() <= maxTargets, "target count exceeds max-targets");
        Set<String> ids = new HashSet<>();
        for (Target target : targets) {
            require(target != null, "target must not be null");
            require(safeReference(target.id), "target ID must be a safe reference of at most 96 characters");
            require(ids.add(target.id), "target IDs must be unique");
            require(target.type != null, "target type is required");
            require(target.name == null || (target.name.length() <= 120 && target.name.chars().noneMatch(Character::isISOControl)),
                    "invalid target display name");
            require(target.connectionSource == null || target.connectionSource.isBlank() || safeReference(target.connectionSource),
                    "connection-source must be a reference, not a connection URL or credentials");
            require(target.scope != null, "target scope is required");
            scope(target.scope.databases, limits.databases);
            scope(target.scope.topics, limits.topics);
            scope(target.scope.consumerGroups, limits.consumerGroups);
            scope(target.scope.buckets, limits.buckets);
            scope(target.scope.indices, limits.indices);
        }
    }

    public static boolean safeReference(String value) {
        return value != null && value.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,95}");
    }

    private static void positive(Duration duration, String field) {
        require(duration != null && !duration.isNegative() && !duration.isZero()
                && duration.compareTo(Duration.ofDays(1)) <= 0, field + " must be positive and at most one day");
    }

    private static void scope(List<String> entries, int maximum) {
        require(entries != null && entries.size() <= maximum, "scope exceeds its configured item limit");
        require(entries.stream().allMatch(value -> value != null && !value.isBlank() && value.length() <= 255
                && value.chars().noneMatch(Character::isISOControl)), "scope entries must be nonblank names");
        require(new HashSet<>(entries).size() == entries.size(), "scope names must be unique");
    }

    private static void require(boolean condition, String message) {
        // Do not include invalid values: a mistaken URI in a reference field may contain credentials.
        if (!condition) throw new IllegalArgumentException("Invalid monitor configuration: " + message);
    }
}
