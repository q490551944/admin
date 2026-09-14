package com.hpj.admin.monitor.api;

import com.hpj.admin.monitor.scheduling.MonitoringScheduler;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import static com.hpj.admin.monitor.api.MonitoringResponses.*;

/** HTTP reads never discover connections, enqueue collection, or refresh stored observation times. */
public final class MonitoringReadService {
    private final MonitoringScheduler scheduler;
    private final MonitoringResponseProjector projector;
    private final Clock clock;

    public MonitoringReadService(MonitoringScheduler scheduler, MonitoringResponseProjector projector, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.projector = Objects.requireNonNull(projector);
        this.clock = Objects.requireNonNull(clock);
    }

    public CatalogResponse catalog() { return projector.catalog(scheduler.readState(), clock.instant()); }
    public SnapshotResponse snapshots() { return projector.project(scheduler.readState(), clock.instant()); }
    public Optional<DetailResponse> detail(String targetId) {
        return projector.detail(scheduler.readState(), clock.instant(), targetId);
    }
}
