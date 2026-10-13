package com.hpj.admin.monitor.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpj.admin.monitor.MiddlewareType;
import com.hpj.admin.monitor.MonitoringTarget;
import com.hpj.admin.monitor.connection.ResolvedTarget;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler.ResolutionState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.hpj.admin.monitor.metric.MetricContract.*;

/** Public read models contain safe metadata and observations, never native connections or exceptions. */
public final class MonitoringResponses {
    private MonitoringResponses() { }

    public enum SchedulingState { ACTIVE, RESOLVING, DISABLED, CONFIGURATION_MISSING }
    public enum CapabilityState { UNKNOWN, OBSERVED }
    public enum Capability { UNKNOWN, AVAILABLE, UNSUPPORTED, UNAUTHORIZED, NOT_APPLICABLE }
    public enum ValueState { CURRENT, LAST_SUCCESS, EXPIRED, NO_DATA }
    public record FreshnessRules(long refreshIntervalMillis, long ordinaryIntervalMillis, long capacityIntervalMillis,
                                 long serviceTtlMillis, int metricTtlMultiplier, boolean expiresAfterValidUntil) { }
    public record MetricObservation(String source, String bindingId, CollectionKind kind,
                                    MetricSample latestAttempt, MetricSample lastSuccess, ValueState valueState,
                                    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant validUntil) { }

    public record Binding(String bindingId, String source, MonitoringTarget.Scope scope) { }
    public record DirectoryTarget(String id, MiddlewareType type, String name,
                                  MonitoringTarget.ConfigurationStatus configurationStatus,
                                  ResolvedTarget.Reason resolutionReason, List<String> connectionSources,
                                  MonitoringTarget.Scope scope, List<String> memberIds, List<Binding> bindings) {
        public DirectoryTarget {
            connectionSources = List.copyOf(connectionSources);
            memberIds = List.copyOf(memberIds);
            bindings = List.copyOf(bindings);
        }
    }
    public record TypeCatalog(MiddlewareType type, String name,
                              MonitoringTarget.ConfigurationStatus configurationStatus,
                              int configuredCount, int missingCount, int disabledCount, int resolvingCount,
                              List<DirectoryTarget> targets) {
        public TypeCatalog { targets = List.copyOf(targets); }
    }
    public record CatalogResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant serverTime,
                                  long generation, ResolutionState resolutionState,
                                  List<TypeCatalog> types) {
        public CatalogResponse { types = List.copyOf(types); }
    }
    public record CollectionObservation(String bindingId, String source, CollectionKind kind,
                                        CollectionStatus status, MissingReason reason, Attempt lastAttempt,
                                        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant validUntil,
                                        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant valuesValidUntil, boolean expired) { }
    public record ServiceObservation(String bindingId, String source, ServiceAvailability availability,
                                     MissingReason reason, ServiceProbe lastObservation,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) Instant validUntil, boolean expired) { }
    public record TargetOverview(DirectoryTarget target, SchedulingState schedulingState,
                                 List<CollectionObservation> collections,
                                 List<ServiceObservation> serviceObservations,
                                 int metricCount, int missingMetricCount, List<MetricObservation> coreMetrics,
                                 ServiceAvailability serviceAvailability, CollectionStatus collectionStatus, boolean exceptional) {
        public TargetOverview {
            collections = List.copyOf(collections);
            serviceObservations = List.copyOf(serviceObservations);
            coreMetrics = List.copyOf(coreMetrics);
        }
    }
    public record SnapshotResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant serverTime,
                                   long generation, ResolutionState resolutionState,
                                   List<TargetOverview> targets, FreshnessRules freshnessRules,
                                   Map<ServiceAvailability, Integer> serviceSummary) {
        public SnapshotResponse { targets = List.copyOf(targets); serviceSummary = Map.copyOf(serviceSummary); }
    }
    public record MetricCapability(String bindingId, String source, CollectionKind kind, Definition definition,
                                   Capability capability, MissingReason latestMissingReason,
                                   @JsonFormat(shape = JsonFormat.Shape.STRING) Instant sampledAt,
                                   @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastSuccessAt,
                                   @JsonFormat(shape = JsonFormat.Shape.STRING) Instant validUntil, ValueState valueState) { }
    public record DetailResponse(@JsonFormat(shape = JsonFormat.Shape.STRING) Instant serverTime,
                                 long generation, ResolutionState resolutionState,
                                 TargetOverview target, List<MetricObservation> metrics,
                                 CapabilityState capabilityState, List<MetricCapability> capabilities, FreshnessRules freshnessRules) {
        public DetailResponse {
            metrics = List.copyOf(metrics);
            capabilities = List.copyOf(capabilities);
        }
    }
}
