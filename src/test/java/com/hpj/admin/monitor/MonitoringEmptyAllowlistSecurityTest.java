package com.hpj.admin.monitor;

import com.hpj.admin.common.config.monitor.MonitoringProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MonitoringSecurityTestApplication.class, properties = {
        "spring.config.location=classpath:/chat-test.yml", "chat.enabled=false", "monitor.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:monitor_empty_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class MonitoringEmptyAllowlistSecurityTest extends MonitoringSecurityTestSupport {
    @Autowired MonitoringProperties properties;

    @Test
    void defaultEmptyAllowlistDeniesEvenAnAuthenticatedEnabledEmployee() throws Exception {
        assertThat(properties.getAllowedUserIds()).isEmpty();
        Login alice = login("alice");
        mvc.perform(get(PROBE).session(alice.session())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get(MONITOR + "/sessions/current").session(alice.session())).andExpect(status().isForbidden());
        mvc.perform(delete(MONITOR + "/sessions/current").session(alice.session()).header("X-CSRF-TOKEN", alice.csrf()))
                .andExpect(status().isNoContent());
    }
}
