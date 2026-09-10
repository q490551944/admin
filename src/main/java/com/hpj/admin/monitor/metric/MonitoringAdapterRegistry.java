package com.hpj.admin.monitor.metric;

import com.hpj.admin.monitor.MiddlewareType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Discovers adapters by type without collecting metrics or creating middleware clients. */
public final class MonitoringAdapterRegistry {
    private final EnumMap<MiddlewareType, MonitoringAdapter> adapters = new EnumMap<>(MiddlewareType.class);
    private final Set<MiddlewareType> registeredTypes;

    public MonitoringAdapterRegistry(List<MonitoringAdapter> adapters) {
        if (adapters == null) throw new IllegalArgumentException("Monitoring adapters are required");
        for (MonitoringAdapter adapter : adapters) {
            if (adapter == null) throw new IllegalArgumentException("Monitoring adapter must not be null");
            MiddlewareType type = adapter.type();
            if (type == null) throw new IllegalArgumentException("Monitoring adapter type is required");
            if (this.adapters.putIfAbsent(type, adapter) != null) {
                // Only the fixed enum is safe to include; adapter.toString() may expose a client or credentials.
                throw new IllegalArgumentException("Duplicate monitoring adapter type: " + type);
            }
        }
        EnumSet<MiddlewareType> types = EnumSet.noneOf(MiddlewareType.class);
        types.addAll(this.adapters.keySet());
        this.registeredTypes = Collections.unmodifiableSet(types);
    }

    public Optional<MonitoringAdapter> find(MiddlewareType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    public Set<MiddlewareType> registeredTypes() {
        return registeredTypes;
    }
}
