package com.hpj.admin.monitor.connection;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Server-only equality evidence; never an API identifier or a diagnostic string. */
@JsonIgnoreType
public final class ConnectionIdentity {
    private final Object owner;
    private final List<?> comparison;

    private ConnectionIdentity(Object owner, List<?> comparison) {
        this.owner = Objects.requireNonNull(owner);
        this.comparison = comparison;
    }

    public static ConnectionIdentity forOwner(Object owner) {
        return new ConnectionIdentity(owner, null);
    }

    /** Only use when static plaintext credentials and a standalone topology are fully known. */
    public static ConnectionIdentity redisStandalone(Object owner, String host, int port, int database,
                                                       String username, String password) {
        return new ConnectionIdentity(owner, List.of("redis-standalone", host.toLowerCase(Locale.ROOT),
                port, database, username == null || username.isEmpty() ? "default" : username,
                password == null ? "" : password));
    }

    public boolean sameAs(ConnectionIdentity other) {
        return other != null && (owner == other.owner
                || comparison != null && comparison.equals(other.comparison));
    }

    @Override public String toString() { return "ConnectionIdentity[server-only]"; }
}
