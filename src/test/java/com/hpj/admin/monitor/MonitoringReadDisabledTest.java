package com.hpj.admin.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {MonitoringSecurityTestApplication.class, MonitoringReadTestConfiguration.class}, properties = {
        "spring.config.location=classpath:/chat-test.yml", "chat.enabled=false", "monitor.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:monitor_read_disabled;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class MonitoringReadDisabledTest extends MonitoringSecurityTestSupport {
    @Test
    void disablingMonitoringReturns404BeforeAnyReadEndpointOrAuthentication() throws Exception {
        for (String path : new String[]{"/catalog", "/snapshots", "/targets/redis-main", "/targets/unknown"}) {
            mvc.perform(get(MONITOR + path)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MONITOR_DISABLED"));
        }
    }
}
