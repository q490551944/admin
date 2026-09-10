package com.hpj.admin.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MonitoringSecurityTestApplication.class, properties = {
        "spring.config.location=classpath:/chat-test.yml", "chat.enabled=true", "monitor.enabled=true",
        "monitor.allowed-user-ids[0]=1",
        "spring.datasource.url=jdbc:h2:mem:monitor_chat_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class MonitoringChatSecurityIntegrationTest extends MonitoringSecurityTestSupport {
    @Test
    void chatRoleAloneCannotAuthenticateMonitoringAndMonitoringLogoutPreservesChat() throws Exception {
        Login chat = login(CHAT, "bob", null);
        mvc.perform(get(PROBE).session(chat.session())).andExpect(status().isUnauthorized());
        Object chatIdentity = chat.session().getAttribute("SPRING_SECURITY_CONTEXT");
        Login monitor = login(MONITOR, "alice", chat.session());
        assertThat(monitor.session()).isSameAs(chat.session());
        assertThat(monitor.session().getAttribute("SPRING_SECURITY_CONTEXT")).isSameAs(chatIdentity);
        mvc.perform(get(MONITOR + "/sessions/current").session(monitor.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("1"));
        mvc.perform(get(CHAT + "/sessions/current").session(monitor.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("2"));
        assertThat(token(CHAT, monitor.session()).value()).isEqualTo(chat.csrf());
        mvc.perform(post(PROBE).session(monitor.session()).header("X-CSRF-TOKEN", chat.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(CHAT + "/sessions/current").session(monitor.session()).header("X-CSRF-TOKEN", monitor.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(MONITOR + "/sessions/current").session(monitor.session()).header("X-CSRF-TOKEN", monitor.csrf()))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(monitor.session().isInvalid()).isFalse();
        assertThat(monitor.session().getAttribute(CONTEXT)).isNull();
        assertThat(monitor.session().getAttribute(CSRF)).isNull();
        mvc.perform(get(PROBE).session(monitor.session())).andExpect(status().isUnauthorized());
        mvc.perform(get(CHAT + "/sessions/current").session(monitor.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("bob"));
        assertThat(token(CHAT, monitor.session()).value()).isEqualTo(chat.csrf());
    }

    @Test
    void chatLoginCannotOverwriteAnExistingMonitoringIdentity() throws Exception {
        Login monitor = login("alice");
        mvc.perform(get(CHAT + "/sessions/current").session(monitor.session())).andExpect(status().isUnauthorized());
        Login chat = login(CHAT, "bob", monitor.session());
        mvc.perform(get(MONITOR + "/sessions/current").session(chat.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(get(CHAT + "/sessions/current").session(chat.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("bob"));
        assertThat(token(MONITOR, chat.session()).value()).isEqualTo(monitor.csrf());
    }

    @Test
    void invalidMonitoringEmployeeDoesNotInvalidateAnotherChatEmployee() throws Exception {
        Login chat = login(CHAT, "bob", null);
        Login monitor = login(MONITOR, "alice", chat.session());
        jdbc.update("UPDATE user SET status=FALSE WHERE id=1");
        mvc.perform(get(PROBE).session(monitor.session())).andExpect(status().isUnauthorized());
        assertThat(monitor.session().isInvalid()).isFalse();
        assertThat(monitor.session().getAttribute(CONTEXT)).isNull();
        mvc.perform(get(CHAT + "/sessions/current").session(monitor.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("2"));
    }
}
