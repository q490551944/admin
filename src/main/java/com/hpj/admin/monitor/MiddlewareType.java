package com.hpj.admin.monitor;

public enum MiddlewareType {
    MYSQL("MySQL"), REDIS("Redis"), KAFKA("Kafka"), MONGODB("MongoDB"),
    ELASTICSEARCH("Elasticsearch"), MINIO("MinIO");

    private final String displayName;

    MiddlewareType(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}
