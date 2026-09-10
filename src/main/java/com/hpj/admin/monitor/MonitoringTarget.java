package com.hpj.admin.monitor;

import java.util.List;

/** Public directory data intentionally contains neither a client nor its connection settings. */
public record MonitoringTarget(String id, MiddlewareType type, String name, ConfigurationStatus status,
                               List<String> connectionSources, Scope scope) {
    public MonitoringTarget {
        connectionSources = List.copyOf(connectionSources);
    }

    public enum ConfigurationStatus { DISABLED, CONFIGURATION_MISSING, CONFIGURED }

    public record Scope(List<String> databases, List<String> topics, List<String> consumerGroups,
                        List<String> buckets, List<String> indices) {
        public Scope {
            databases = List.copyOf(databases);
            topics = List.copyOf(topics);
            consumerGroups = List.copyOf(consumerGroups);
            buckets = List.copyOf(buckets);
            indices = List.copyOf(indices);
        }
    }
}
