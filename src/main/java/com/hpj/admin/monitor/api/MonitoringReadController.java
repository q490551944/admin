package com.hpj.admin.monitor.api;

import com.hpj.admin.monitor.security.MonitoringError;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.hpj.admin.monitor.api.MonitoringResponses.*;

@RestController
@RequestMapping("/api/monitor/v1")
public class MonitoringReadController {
    private static final MonitoringError TARGET_NOT_FOUND = new MonitoringError("TARGET_NOT_FOUND", "监控目标不存在");
    private final MonitoringReadService reads;

    public MonitoringReadController(MonitoringReadService reads) { this.reads = reads; }

    @GetMapping("/catalog")
    public ResponseEntity<CatalogResponse> catalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reads.catalog());
    }

    @GetMapping("/snapshots")
    public ResponseEntity<SnapshotResponse> snapshots() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reads.snapshots());
    }

    @GetMapping("/targets/{targetId}")
    public ResponseEntity<?> detail(@PathVariable String targetId) {
        var detail = reads.detail(targetId);
        if (detail.isEmpty()) return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).body(TARGET_NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail.get());
    }
}
