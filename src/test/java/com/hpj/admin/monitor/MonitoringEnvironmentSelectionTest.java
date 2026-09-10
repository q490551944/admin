package com.hpj.admin.monitor;

import com.hpj.admin.monitor.support.MonitoringTestEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringEnvironmentSelectionTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"production", "jdbc:mysql://production.invalid/real_data"})
    void rejectsUnknownTypesAndExternalEndpointsBeforeAccessingDocker(String type) {
        assertThatThrownBy(() -> MonitoringTestEnvironment.start(type))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
