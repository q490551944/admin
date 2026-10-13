package com.hpj.admin.monitor.minio;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import okhttp3.HttpUrl;

import java.util.Objects;
import java.util.Set;

/** An application's already-provisioned metrics bearer authorization, bound to its existing S3 owner/origin. */
@JsonIgnoreType
public final class MinioMetricsAuthorization {
    private final Object owner;
    private final HttpUrl origin;
    private final String bearer;
    private final Set<MinioMonitoringConnections.MetricsEndpoint> endpoints;

    public MinioMetricsAuthorization(Object owner, String existingEndpoint, String existingBearer,
                                    Set<MinioMonitoringConnections.MetricsEndpoint> endpoints) {
        this.owner = Objects.requireNonNull(owner);
        this.origin = HttpUrl.get(Objects.requireNonNull(existingEndpoint));
        if (!origin.encodedPath().equals("/") || origin.query() != null || origin.fragment() != null
                || !origin.username().isEmpty() || !origin.password().isEmpty()
                || existingBearer == null || existingBearer.length() > 16384 || !existingBearer.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException("Invalid existing MinIO metrics authorization");
        }
        this.bearer = existingBearer;
        this.endpoints = Set.copyOf(endpoints);
    }

    boolean matches(Object client, HttpUrl endpoint) { return owner == client && origin.equals(endpoint); }
    boolean permits(MinioMonitoringConnections.MetricsEndpoint endpoint) { return endpoints.contains(endpoint); }
    String bearer() { return bearer; }
    @Override public String toString() { return "MinioMetricsAuthorization[server-only]"; }
}
