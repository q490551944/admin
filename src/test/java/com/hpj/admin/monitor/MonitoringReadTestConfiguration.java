package com.hpj.admin.monitor;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.common.exception.MyExceptionHandler;
import com.hpj.admin.monitor.api.MonitoringReadController;
import com.hpj.admin.monitor.api.MonitoringReadService;
import com.hpj.admin.monitor.api.MonitoringResponseProjector;
import com.hpj.admin.monitor.metric.MonitoringSnapshotStore;
import com.hpj.admin.monitor.scheduling.MonitoringScheduler;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneOffset;

/** Imports the production controller and complete production authentication chain. */
@TestConfiguration(proxyBeanMethods = false)
@Import({MonitoringSecurityTestApplication.class, MonitoringReadController.class, MyExceptionHandler.class})
@ImportAutoConfiguration(HttpMessageConvertersAutoConfiguration.class)
class MonitoringReadTestConfiguration {
    @Bean MonitoringReadFixtures readFixtures(MonitoringProperties properties) {
        return new MonitoringReadFixtures(properties);
    }
    @Bean(destroyMethod = "close") MonitoringScheduler monitoringScheduler(MonitoringReadFixtures fixtures) {
        return fixtures.scheduler;
    }
    @Bean MonitoringSnapshotStore monitoringSnapshotStore(MonitoringReadFixtures fixtures) { return fixtures.store; }
    @Bean MonitoringResponseProjector monitoringResponseProjector() { return new MonitoringResponseProjector(); }
    @Bean MonitoringReadService monitoringReadService(MonitoringScheduler scheduler, MonitoringResponseProjector projector) {
        return new MonitoringReadService(scheduler, projector,
                Clock.fixed(MonitoringReadFixtures.SAMPLE_TIME.plusSeconds(1), ZoneOffset.UTC));
    }
}
