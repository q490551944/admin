package com.hpj.admin.monitor.metric;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MonitoringTarget;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-only input for one source and its own authorized scope. The caller owns the client and opaque
 * setting values; only the settings map container is copied. Adapters must not close the borrowed client.
 */
@JsonIgnoreType
public record CollectionRequest(
        @JsonIgnore String targetId,
        @JsonIgnore String source,
        @JsonIgnore String bindingId,
        @JsonIgnore MetricContract.CollectionKind kind,
        @JsonIgnore long generation,
        @JsonIgnore long sequence,
        @JsonIgnore Instant scheduledAt,
        @JsonIgnore Instant deadline,
        @JsonIgnore MonitoringTarget.Scope scope,
        @JsonIgnore Object client,
        @JsonIgnore Map<String, Object> settings,
        @JsonIgnore CollectionControl control) {

    public CollectionRequest(String targetId, String source, MetricContract.CollectionKind kind,
                             long generation, long sequence, Instant scheduledAt, Instant deadline,
                             MonitoringTarget.Scope scope, Object client, Map<String, Object> settings) {
        this(targetId, source, source, kind, generation, sequence, scheduledAt, deadline, scope, client, settings,
                detached(deadline));
    }

    public CollectionRequest(String targetId, String source, String bindingId, MetricContract.CollectionKind kind,
                             long generation, long sequence, Instant scheduledAt, Instant deadline,
                             MonitoringTarget.Scope scope, Object client, Map<String, Object> settings) {
        this(targetId, source, bindingId, kind, generation, sequence, scheduledAt, deadline, scope, client, settings,
                detached(deadline));
    }

    public CollectionRequest {
        require(MonitoringProperties.safeReference(targetId), "target ID must be a safe reference");
        require(MonitoringProperties.safeReference(source), "source must be a safe reference");
        require(MonitoringProperties.safeReference(bindingId), "binding ID must be a safe reference");
        require(kind != null, "collection kind is required");
        require(generation >= 0 && sequence >= 0, "generation and sequence must be nonnegative");
        require(scheduledAt != null && deadline != null, "collection timestamps are required");
        require(deadline.isAfter(scheduledAt), "deadline must be after scheduled time");
        require(scope != null, "source scope is required");
        require(client != null, "borrowed client is required");
        require(settings != null, "source settings are required");
        require(control != null, "collection control is required");
        // Map.copyOf rejects legitimate null option values. Preserve them and retain opaque credential/TLS references.
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    @Override
    public String toString() {
        return "CollectionRequest[" + targetId + ", " + source + ", " + bindingId + ", " + kind + ", sequence=" + sequence + "]";
    }

    private static CollectionControl detached(Instant deadline) {
        require(deadline != null, "collection timestamps are required");
        return CollectionControl.detached(deadline);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("Invalid monitoring collection request: " + message);
    }
}
