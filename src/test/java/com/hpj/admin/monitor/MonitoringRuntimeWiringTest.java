package com.hpj.admin.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Local wiring proof; the six native service paths remain mandatory in the CI matrix. */
@EnabledIfEnvironmentVariable(named = "MONITOR_E2E_CONTROL_TOKEN", matches = "[A-Fa-f0-9]{64}")
class MonitoringRuntimeWiringTest {
    @ResourceLock("java.lang.System.properties")
    @Test void realAuthenticationAndReadControllersUseTheOwnedIdentityDatabaseAndManualSchedulerWithoutSources() throws Exception {
        String inheritedOrdinary = System.getProperty("monitor.ordinary-interval"), inheritedCapacity = System.getProperty("monitor.capacity-interval");
        System.setProperty("monitor.ordinary-interval", "10s"); System.setProperty("monitor.capacity-interval", "40s");
        try (var fixture = MonitoringRuntimeFixture.startDisabled()) {
            var properties = fixture.context.getBean(MonitoringProperties.class);
            assertThat(properties.getOrdinaryInterval()).isEqualTo(java.time.Duration.ofSeconds(15));
            assertThat(properties.getCapacityInterval()).isEqualTo(java.time.Duration.ofSeconds(60));
            assertThat(fixture.context.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM user", Integer.class)).isEqualTo(3);
            var browser = fixture.login();
            assertThat(browser.get("/catalog").path("types")).hasSize(6);
            assertThat(browser.get("/snapshots").path("targets")).hasSize(6);
            assertThat(fixture.ledger.calls.get()).isZero();
            assertThat(fixture.scheduler().diagnostics().inFlightTasks()).isZero();
        } finally {
            if (inheritedOrdinary == null) System.clearProperty("monitor.ordinary-interval"); else System.setProperty("monitor.ordinary-interval", inheritedOrdinary);
            if (inheritedCapacity == null) System.clearProperty("monitor.capacity-interval"); else System.setProperty("monitor.capacity-interval", inheritedCapacity);
        }
    }
}
