package com.hpj.admin.monitor.connection;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.monitor.MiddlewareType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Borrowed clients and effective settings stay on the server and must not be closed by resolution. */
@JsonIgnoreType
public final class ResolvedConnection {
    private final MiddlewareType type;
    private final String source;
    private final Object client;
    private final ConnectionIdentity identity;
    private final Map<String, Object> settings;

    public ResolvedConnection(MiddlewareType type, String source, Object client,
                              ConnectionIdentity identity, Map<String, ?> settings) {
        if (!MonitoringProperties.safeReference(source)) throw new IllegalArgumentException("Invalid source reference");
        this.type = Objects.requireNonNull(type);
        this.source = source;
        this.client = Objects.requireNonNull(client);
        this.identity = Objects.requireNonNull(identity);
        this.settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    public MiddlewareType type() { return type; }
    public String source() { return source; }
    public Object client() { return client; }
    public ConnectionIdentity identity() { return identity; }
    public Map<String, Object> settings() { return settings; }
    @Override public String toString() { return "ResolvedConnection[" + type + ", " + source + "]"; }
}
