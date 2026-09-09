package com.hpj.admin.chat;

import io.micrometer.core.instrument.*;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Low-cardinality operational metrics; never tag with user, conversation or message IDs. */
@Component
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatMetrics {
    private final MeterRegistry registry;
    public final Counter persisted, persistenceFailed, broadcastFailed, privateDeliveryFailed, rejected,
            uploadFailed, uploaded, cleanupFailed;
    private final Timer persistence, broadcast, history;

    public ChatMetrics(MeterRegistry registry) {
        this.registry = registry;
        persisted = registry.counter("chat.message.persist.success");
        persistenceFailed = registry.counter("chat.message.persist.failed");
        broadcastFailed = registry.counter("chat.message.broadcast.failed");
        privateDeliveryFailed = registry.counter("chat.message.private.failed");
        rejected = registry.counter("chat.message.rejected");
        uploadFailed = registry.counter("chat.attachment.upload.failed");
        uploaded = registry.counter("chat.attachment.upload.success");
        cleanupFailed = registry.counter("chat.attachment.cleanup.failed");
        persistence = Timer.builder("chat.message.persist.duration").publishPercentileHistogram().register(registry);
        broadcast = Timer.builder("chat.message.push.latency").publishPercentileHistogram().register(registry);
        history = Timer.builder("chat.history.duration").publishPercentileHistogram().register(registry);
    }

    public void trackPersistence(long started) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    persisted.increment();
                    persistence.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                } else persistenceFailed.increment();
            }
        });
    }

    public void broadcast(long started) { broadcast.record(System.nanoTime() - started, TimeUnit.NANOSECONDS); }
    public void history(long started) { history.record(System.nanoTime() - started, TimeUnit.NANOSECONDS); }
    public <T> void sockets(T state, ToDoubleFunction<T> count) { registry.gauge("chat.websocket.connections", state, count); }
}
