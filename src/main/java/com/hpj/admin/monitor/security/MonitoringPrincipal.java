package com.hpj.admin.monitor.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Map;

/** Employee identity for the monitoring context; a chat role never grants monitoring access. */
@JsonIgnoreType
public final class MonitoringPrincipal extends User {
    private final long userId;

    public MonitoringPrincipal(long userId, String username, String password, boolean enabled) {
        super(username, password, enabled, true, true, true, List.of());
        if (userId <= 0) throw new IllegalArgumentException("Monitoring employee ID must be positive");
        this.userId = userId;
    }

    public long getUserId() { return userId; }

    @Override
    @JsonIgnore
    public String getPassword() { return super.getPassword(); }

    public Map<String, String> publicView() {
        return Map.of("id", Long.toString(userId), "name", getUsername(), "username", getUsername());
    }

    @Override public String toString() { return "MonitoringPrincipal[userId=" + userId + "]"; }
}
