package com.hpj.admin.monitor;

import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in real service tests. An explicit type without Docker must fail, never silently skip. */
@EnabledIfSystemProperty(named = "monitor.test.type", matches = ".+")
class MonitoringEnvironmentIntegrationTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void twoEnvironmentsAreReadyIsolatedRepeatableAndCleanedEvenOnFailure() throws Exception {
        String type = System.getProperty("monitor.test.type");
        MonitoringTestEnvironment first = MonitoringTestEnvironment.start(type);
        MonitoringTestEnvironment second = null;
        try (first) {
            second = MonitoringTestEnvironment.start(type);
            try (MonitoringTestEnvironment other = second) {
                assertThat(first.isRunning()).isTrue();
                assertThat(other.isRunning()).isTrue();
                assertThat(first.id()).isNotEqualTo(other.id());
                assertThat(first.port()).isNotEqualTo(other.port());
                assertThat(first.resourceName()).isNotEqualTo(other.resourceName());
                assertThat(first.isDataPresent()).isFalse();
                assertThat(other.isDataPresent()).isFalse();

                first.prepareData();
                first.verifyData();
                assertThat(first.isDataPresent()).isTrue();
                assertThat(other.isDataPresent()).isFalse();
                first.cleanupData();
                first.cleanupData();
                assertThat(first.isDataPresent()).isFalse();

                // Repeat setup and exercise independent mutations through real client protocols.
                first.prepareData();
                other.prepareData();
                first.verifyData();
                other.verifyData();
                other.cleanupData();
                assertThat(first.isDataPresent()).isTrue();
                assertThat(other.isDataPresent()).isFalse();

                // A failing test body must still release only its own environment.
                assertThatThrownBy(() -> {
                    try (first) {
                        throw new IllegalStateException("simulated test body failure");
                    }
                }).isInstanceOf(IllegalStateException.class).hasMessage("simulated test body failure");
                assertThat(first.isRunning()).isFalse();
                assertThat(other.isRunning()).isTrue();
            }
        }
        assertThat(first.isRunning()).isFalse();
        assertThat(second).isNotNull();
        assertThat(second.isRunning()).isFalse();
        for (MonitoringTestEnvironment environment : new MonitoringTestEnvironment[]{first, second}) {
            assertThat(Files.isDirectory(environment.logDirectory())).isTrue();
            try (var paths = Files.walk(environment.logDirectory())) {
                var files = paths.filter(Files::isRegularFile).toList();
                assertThat(files).isNotEmpty();
                for (var file : files) {
                    if (environment.password() != null && !environment.password().isEmpty()) {
                        assertThat(Files.readString(file)).doesNotContain(environment.password());
                    }
                }
            }
        }
    }
}
