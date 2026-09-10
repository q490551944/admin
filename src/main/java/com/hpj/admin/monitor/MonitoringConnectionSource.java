package com.hpj.admin.monitor;

import com.hpj.admin.common.config.monitor.MonitoringProperties;

/** A configured source reference supplied by a resolver; this does not assert service availability. */
public record MonitoringConnectionSource(MiddlewareType type, String reference) {
    public MonitoringConnectionSource {
        if (type == null || !MonitoringProperties.safeReference(reference)) {
            throw new IllegalArgumentException("A connection source requires a type and safe reference");
        }
    }
}
