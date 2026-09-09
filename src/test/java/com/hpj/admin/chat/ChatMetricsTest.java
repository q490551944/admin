package com.hpj.admin.chat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import static org.assertj.core.api.Assertions.*;

class ChatMetricsTest {
    @Test void actuatorPublishesChatCountersTimersAndConnectionGaugeWithoutIdentityTags() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ChatMetrics(registry);
            var connections = new java.util.concurrent.atomic.AtomicInteger(3);
            metrics.sockets(connections, java.util.concurrent.atomic.AtomicInteger::get);
            metrics.uploadFailed.increment(); metrics.broadcastFailed.increment();
            metrics.history(System.nanoTime()); metrics.broadcast(System.nanoTime());
            var endpoint = new MetricsEndpoint(registry);
            assertThat(endpoint.listNames().getNames()).contains("chat.websocket.connections", "chat.attachment.upload.failed",
                    "chat.message.broadcast.failed", "chat.message.persist.success", "chat.history.duration", "chat.message.push.latency");
            assertThat(endpoint.metric("chat.attachment.upload.failed", java.util.List.of()).getMeasurements().get(0).getValue()).isEqualTo(1);
            assertThat(registry.get("chat.websocket.connections").gauge().value()).isEqualTo(3);
            connections.set(0);
            assertThat(registry.get("chat.websocket.connections").gauge().value()).isZero();
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
        } finally { registry.close(); }
    }
}
