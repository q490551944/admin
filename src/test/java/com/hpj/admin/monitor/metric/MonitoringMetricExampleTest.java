package com.hpj.admin.monitor.metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;

import static com.hpj.admin.monitor.metric.MetricContract.*;
import static org.assertj.core.api.Assertions.assertThat;

class MonitoringMetricExampleTest {
    @Test
    void documentedResponseSatisfiesTheRealContractAndPreservesValueMeaning() throws Exception {
        TargetSnapshot snapshot = new ObjectMapper().findAndRegisterModules().readValue(
                Path.of("docs/middleware-monitoring-metrics-example.json").toFile(), TargetSnapshot.class);
        assertThat(snapshot.metrics()).hasSize(4);
        assertThat(snapshot.metrics()).extracting(StoredMetric::bindingId).containsOnly("cache-main");
        assertThat(snapshot.metrics()).extracting(StoredMetric::source).containsOnly("redisConnectionFactory");
        assertThat(snapshot.attempts()).extracting(Attempt::bindingId).containsOnly("cache-main");
        assertThat(snapshot.attempts().get(1).reason()).isEqualTo(MissingReason.TIMEOUT);
        assertThat(snapshot.metrics().get(0).latestAttempt().value()).isEqualTo(BigDecimal.valueOf(200));
        assertThat(snapshot.metrics().get(1).latestAttempt().value()).isEqualTo(BigDecimal.ZERO);
        assertThat(snapshot.metrics().get(2).latestAttempt().value()).isNull();
        assertThat(snapshot.metrics().get(2).latestAttempt().missingReason()).isEqualTo(MissingReason.NOT_APPLICABLE);
        StoredMetric capacity = snapshot.metrics().get(3);
        assertThat(capacity.kind()).isEqualTo(CollectionKind.CAPACITY);
        assertThat(capacity.latestAttempt().missingReason()).isEqualTo(MissingReason.TIMEOUT);
        assertThat(capacity.lastSuccess().value()).isEqualTo(BigDecimal.valueOf(1024));
        assertThat(capacity.lastSuccess().sampledAt()).isEqualTo(Instant.parse("2026-09-10T00:00:00Z"));
        assertThat(capacity.lastSuccess().validUntil()).isEqualTo(Instant.parse("2026-09-10T00:03:00Z"));
        assertThat(snapshot.serviceProbes().get("cache-main").availability()).isEqualTo(ServiceAvailability.AVAILABLE);
    }
}
