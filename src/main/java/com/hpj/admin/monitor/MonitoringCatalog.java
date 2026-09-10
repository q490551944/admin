package com.hpj.admin.monitor;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MonitoringTarget.ConfigurationStatus;
import com.hpj.admin.monitor.connection.ResolvedTarget;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Pure projection: source discovery belongs to resolvers and never happens while building this catalog. */
public class MonitoringCatalog {
    private final MonitoringProperties properties;

    public MonitoringCatalog(MonitoringProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    public record TypeCatalog(MiddlewareType type, String name, ConfigurationStatus status,
                              int configuredCount, int missingCount, int disabledCount,
                              List<MonitoringTarget> targets) {
        public TypeCatalog { targets = List.copyOf(targets); }
    }

    /** A declaration alone is not evidence that its existing connection is configured. */
    public List<TypeCatalog> snapshot(Collection<MonitoringConnectionSource> configuredSources) {
        Set<MonitoringConnectionSource> available = Set.copyOf(configuredSources);
        return group(properties.getTargets().stream().map(target -> target(target, available)).toList());
    }

    /** Project resolved source bindings into the same public, credential-free catalog contract. */
    public List<TypeCatalog> resolvedSnapshot(List<ResolvedTarget> resolvedTargets) {
        return group(resolvedTargets.stream().map(ResolvedTarget::display).toList());
    }

    private List<TypeCatalog> group(List<MonitoringTarget> allTargets) {
        return Arrays.stream(MiddlewareType.values()).map(type -> {
            List<MonitoringTarget> targets = allTargets.stream().filter(target -> target.type() == type).toList();
            int configured = count(targets, ConfigurationStatus.CONFIGURED);
            int missing = count(targets, ConfigurationStatus.CONFIGURATION_MISSING);
            int disabled = count(targets, ConfigurationStatus.DISABLED);
            ConfigurationStatus status = configured > 0 ? ConfigurationStatus.CONFIGURED
                    : missing > 0 ? ConfigurationStatus.CONFIGURATION_MISSING : ConfigurationStatus.DISABLED;
            return new TypeCatalog(type, type.displayName(), status, configured, missing, disabled, targets);
        }).toList();
    }

    private MonitoringTarget target(MonitoringProperties.Target target, Set<MonitoringConnectionSource> available) {
        String source = target.getConnectionSource();
        boolean declared = source != null && !source.isBlank();
        boolean configured = declared && available.contains(new MonitoringConnectionSource(target.getType(), source));
        ConfigurationStatus status = !properties.isEnabled() || !target.isEnabled() ? ConfigurationStatus.DISABLED
                : configured ? ConfigurationStatus.CONFIGURED : ConfigurationStatus.CONFIGURATION_MISSING;
        var scope = target.getScope();
        return new MonitoringTarget(target.getId(), target.getType(),
                target.getName() == null || target.getName().isBlank() ? target.getType().displayName() : target.getName(),
                status, declared ? List.of(source) : List.of(),
                new MonitoringTarget.Scope(scope.getDatabases(), scope.getTopics(), scope.getConsumerGroups(),
                        scope.getBuckets(), scope.getIndices()));
    }

    private static int count(List<MonitoringTarget> targets, ConfigurationStatus status) {
        return (int) targets.stream().filter(target -> target.status() == status).count();
    }
}
