package com.hpj.admin.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MonitoringSecurityTestApplication.class, properties = {
        "spring.config.location=classpath:/chat-test.yml", "chat.enabled=false", "monitor.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:monitor_disabled_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class MonitoringDisabledSecurityTest extends MonitoringSecurityTestSupport {
    @Test
    void disabledMonitoringFailsClosedForTheWholeNamespaceButPreservesLegacyBehavior() throws Exception {
        for (String path : new String[]{PROBE, MONITOR + "/csrf-token", MONITOR + "/sessions/current",
                MONITOR + "/unimplemented-route"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MONITOR_DISABLED"));
        }
        mvc.perform(post(MONITOR + "/sessions").param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MONITOR_DISABLED"));
        mvc.perform(post(PROBE)).andExpect(status().isNotFound());
        mvc.perform(delete(MONITOR + "/sessions/current")).andExpect(status().isNotFound());
        mvc.perform(get("/sys/users/security-probe")).andExpect(status().isOk()).andExpect(content().string("legacy-employees"));
        mvc.perform(post("/sys/users/security-probe")).andExpect(status().isOk()).andExpect(content().string("legacy-employees"));
        mvc.perform(post("/groovy/script-executions")).andExpect(status().isOk()).andExpect(content().string("legacy-script"));
    }
}
