package com.hpj.admin.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Local wiring proof; the six native service paths remain mandatory in the CI matrix. */
@EnabledIfEnvironmentVariable(named = "MONITOR_E2E_CONTROL_TOKEN", matches = "[A-Fa-f0-9]{64}")
class MonitoringRuntimeWiringTest {
    @Test void realAuthenticationAndReadControllersUseTheOwnedIdentityDatabaseAndManualSchedulerWithoutSources() throws Exception {
        try (var fixture = MonitoringRuntimeFixture.startDisabled()) {
            assertThat(fixture.context.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM user", Integer.class)).isEqualTo(3);
            var browser = fixture.login();
            assertThat(browser.get("/catalog").path("types")).hasSize(6);
            assertThat(browser.get("/snapshots").path("targets")).hasSize(6);
            assertThat(fixture.ledger.calls.get()).isZero();
            assertThat(fixture.scheduler().diagnostics().inFlightTasks()).isZero();
        }
    }
}
