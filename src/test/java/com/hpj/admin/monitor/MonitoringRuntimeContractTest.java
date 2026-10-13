package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MonitoringRuntimeContractTest {
    @Test void missingNativeValueCannotPassAZeroReferenceAssertion() throws Exception {
        var json = new ObjectMapper();
        String prefix = "{\"metrics\":[{\"latestAttempt\":{\"definition\":{\"key\":\"redis.memory.maxmemory.bytes\",\"unit\":\"BYTES\"},";
        var missing = json.readTree(prefix + "\"value\":null,\"missingReason\":\"UNAUTHORIZED\"}}]}");
        assertThatThrownBy(() -> MonitoringRuntimePipelineIntegrationTest.metric(missing, "redis.memory.maxmemory.bytes")).isInstanceOf(AssertionError.class);
        var zero = json.readTree(prefix + "\"value\":0,\"missingReason\":null}}]}");
        assertThat(MonitoringRuntimePipelineIntegrationTest.metric(zero, "redis.memory.maxmemory.bytes").path("value").asLong()).isZero();
    }
    @Test void failedCapacityCannotBeCertifiedByASuccessfulOrdinarySample() throws Exception {
        var json = new ObjectMapper();
        var failed = json.readTree("{\"target\":{\"collections\":[{\"kind\":\"ORDINARY\",\"status\":\"SUCCESS\"},{\"kind\":\"CAPACITY\",\"status\":\"FAILED\",\"reason\":\"TIMEOUT\"}]},\"metrics\":[]}");
        assertThatThrownBy(() -> MonitoringRuntimePipelineIntegrationTest.verifyKinds(failed, "redis")).isInstanceOf(AssertionError.class);
        var empty = json.readTree("{\"target\":{\"collections\":[{\"kind\":\"ORDINARY\",\"status\":\"SUCCESS\"},{\"kind\":\"CAPACITY\",\"status\":\"PARTIAL\",\"reason\":\"UNSUPPORTED\"}]},\"metrics\":[]}");
        assertThatThrownBy(() -> MonitoringRuntimePipelineIntegrationTest.verifyKinds(empty, "redis")).isInstanceOf(AssertionError.class);
    }
}
