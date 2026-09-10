package com.hpj.admin.monitor;

import com.hpj.admin.chat.security.ChatAccounts;
import com.hpj.admin.mapper.chat.ChatAccountMapper;
import com.hpj.admin.monitor.security.MonitoringAccounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = MonitoringSecurityTestApplication.class, properties = {
        "spring.config.location=classpath:/chat-test.yml", "chat.enabled=false", "monitor.enabled=true",
        "monitor.allowed-user-ids[0]=1",
        "spring.datasource.url=jdbc:h2:mem:monitor_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class MonitoringSecurityIntegrationTest extends MonitoringSecurityTestSupport {
    static final String SENSITIVE_FAILURE = "jdbc:mysql://private.example/employee?password=secret-sentinel";
    @Autowired ApplicationContext context;
    @SpyBean ChatAccountMapper accounts;

    @Test
    void realChainRequiresMonitoringIdentityEvenWithChatDisabled() throws Exception {
        assertThat(context.getBeansOfType(ChatAccounts.class)).isEmpty();
        assertThat(context.getBeansOfType(MonitoringAccounts.class)).hasSize(1);
        mvc.perform(get(PROBE)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(get(MONITOR + "/sessions/current")).andExpect(status().isUnauthorized());
        mvc.perform(get(MONITOR + "/unimplemented-route")).andExpect(status().isUnauthorized());
        Login alice = login("alice");
        mvc.perform(get(PROBE).session(alice.session())).andExpect(status().isOk())
                .andExpect(content().string("monitor"));
        mvc.perform(get(MONITOR + "/sessions/current").session(alice.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("1"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.password").doesNotExist());
        assertThat(alice.session().getAttribute(CONTEXT)).isNotNull();
        assertThat(alice.session().getAttribute(CSRF)).isNotNull();
        assertThat(alice.session().getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    @Test
    void employeeIdentityDoesNotGrantMonitoringAccessWithoutAllowlistMembership() throws Exception {
        Login bob = login("bob");
        mvc.perform(get(PROBE).session(bob.session())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get(MONITOR + "/sessions/current").session(bob.session())).andExpect(status().isForbidden());
        mvc.perform(delete(MONITOR + "/sessions/current").session(bob.session())
                .header("X-CSRF-TOKEN", bob.csrf())).andExpect(status().isNoContent());
    }

    @Test
    void absentWrongDisabledAndAmbiguousCredentialsFailWithoutLeakingAccountData() throws Exception {
        for (String username : new String[]{"missing", "alice", "disabled"}) {
            Token csrf = token(MONITOR, null);
            String body = mvc.perform(post(MONITOR + "/sessions").session(csrf.session())
                            .header("X-CSRF-TOKEN", csrf.value()).param("username", username)
                            .param("password", username.equals("alice") ? "wrong" : PASSWORD))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(PASSWORD, HASH, username);
            assertThat(csrf.session().getAttribute(CONTEXT)).isNull();
        }
        jdbc.update("INSERT INTO user(id,username,password,status) VALUES(4,'alice',?,TRUE)", HASH);
        Token csrf = token(MONITOR, null);
        mvc.perform(post(MONITOR + "/sessions").session(csrf.session()).header("X-CSRF-TOKEN", csrf.value())
                .param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginAndWritesRequireRealSessionBoundCsrfAndLoginRotatesToken() throws Exception {
        mvc.perform(post(MONITOR + "/sessions").param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        Token anonymous = token(MONITOR, null);
        mvc.perform(post(MONITOR + "/sessions").session(anonymous.session()).header("X-CSRF-TOKEN", "invalid")
                .param("username", "alice").param("password", PASSWORD)).andExpect(status().isForbidden());
        Login alice = login("alice");
        Token otherSession = token(MONITOR, null);
        mvc.perform(post(PROBE).session(alice.session())).andExpect(status().isForbidden());
        for (String rejected : new String[]{alice.anonymousCsrf(), otherSession.value()}) {
            mvc.perform(post(PROBE).session(alice.session()).header("X-CSRF-TOKEN", rejected))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }
        mvc.perform(post(PROBE).session(alice.session()).header("X-CSRF-TOKEN", alice.csrf()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE user SET status=FALSE WHERE id=1", "DELETE FROM user WHERE id=1",
            "UPDATE user SET username='renamed' WHERE id=1"})
    void changedEmployeesLoseTheirExistingMonitoringSession(String change) throws Exception {
        Login alice = login("alice");
        jdbc.update(change);
        mvc.perform(get(PROBE).session(alice.session())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        assertThat(alice.session().getAttribute(CONTEXT)).isNull();
        mvc.perform(get(MONITOR + "/sessions/current").session(alice.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void identityOutageIs503AndDoesNotDestroyAValidIdentity() throws Exception {
        Login alice = login("alice");
        doThrow(new DataAccessResourceFailureException(SENSITIVE_FAILURE)).when(accounts).findActiveAccount(1L);
        String response = mvc.perform(get(PROBE).session(alice.session()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("jdbc:", "private.example", "secret-sentinel", "DataAccess", "stackTrace");
        assertThat(alice.session().getAttribute(CONTEXT)).isNotNull();
        reset(accounts);
        mvc.perform(get(PROBE).session(alice.session())).andExpect(status().isOk());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void loginDatabaseFailureIsNotMisreportedAsInvalidCredentials(CapturedOutput output) throws Exception {
        Token csrf = token(MONITOR, null);
        doThrow(new DataAccessResourceFailureException(SENSITIVE_FAILURE)).when(accounts).findByUsername("alice");
        String response = mvc.perform(post(MONITOR + "/sessions").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.value()).param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SENSITIVE_FAILURE, "jdbc:", "DataAccess", "stackTrace", PASSWORD, HASH);
        assertThat(output.getAll()).doesNotContain(SENSITIVE_FAILURE, "private.example", "secret-sentinel", PASSWORD, HASH);
        assertThat(csrf.session().getAttribute(CONTEXT)).isNull();
        reset(accounts);
        login("alice");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void passwordUpgradeOutageIs503AndDoesNotPublishTheCredentialOrReplaceThePassword(CapturedOutput output) throws Exception {
        String weakHash = new BCryptPasswordEncoder(4).encode(PASSWORD);
        jdbc.update("UPDATE user SET password=? WHERE id=1", weakHash);
        doThrow(new DataAccessResourceFailureException(SENSITIVE_FAILURE)).when(accounts)
                .upgradePassword(eq(1L), eq(weakHash), anyString());
        Token csrf = token(MONITOR, null);
        String response = mvc.perform(post(MONITOR + "/sessions").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.value()).param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        verify(accounts).upgradePassword(eq(1L), eq(weakHash), anyString());
        assertThat(csrf.session().getAttribute(CONTEXT)).isNull();
        assertThat(jdbc.queryForObject("SELECT password FROM user WHERE id=1", String.class)).isEqualTo(weakHash);
        assertThat(response).doesNotContain(SENSITIVE_FAILURE, "jdbc:", "DataAccess", "stackTrace", PASSWORD, weakHash);
        assertThat(output.getAll()).doesNotContain(SENSITIVE_FAILURE, "private.example", "secret-sentinel", PASSWORD, weakHash);
        reset(accounts);
        login("alice");
        assertThat(jdbc.queryForObject("SELECT password FROM user WHERE id=1", String.class)).isNotEqualTo(weakHash);
    }

    @Test
    void onlyCsrfProtectedDeleteClearsTheMonitoringSessionAndDoesNotNeedTheDatabase() throws Exception {
        Login alice = login("alice");
        mvc.perform(delete(MONITOR + "/sessions/current").session(alice.session())).andExpect(status().isForbidden());
        mvc.perform(post(MONITOR + "/sessions/current").session(alice.session()).header("X-CSRF-TOKEN", alice.csrf()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/logout").session(alice.session())).andExpect(status().isNotFound());
        mvc.perform(get(MONITOR + "/sessions/current").session(alice.session())).andExpect(status().isOk());
        clearInvocations(accounts);
        doThrow(new DataAccessResourceFailureException(SENSITIVE_FAILURE)).when(accounts).findActiveAccount(1L);
        mvc.perform(delete(MONITOR + "/sessions/current").session(alice.session()).header("X-CSRF-TOKEN", alice.csrf()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(accounts, never()).findActiveAccount(1L);
        assertThat(alice.session().isInvalid()).isFalse();
        assertThat(alice.session().getAttribute(CONTEXT)).isNull();
        assertThat(alice.session().getAttribute(CSRF)).isNull();
        mvc.perform(get(PROBE).session(alice.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void monitoringCannotBeBypassedThroughEmployeeOrScriptAdministration() throws Exception {
        mvc.perform(get("/sys/users/security-probe")).andExpect(status().isForbidden());
        mvc.perform(post("/sys/users/security-probe")).andExpect(status().isForbidden());
        mvc.perform(post("/groovy/script-executions")).andExpect(status().isForbidden());
        Login alice = login("alice");
        mvc.perform(get("/sys/users/security-probe").session(alice.session())).andExpect(status().isForbidden());
        mvc.perform(post("/sys/users/security-probe").session(alice.session()).header("X-CSRF-TOKEN", alice.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/groovy/script-executions").session(alice.session()).header("X-CSRF-TOKEN", alice.csrf()))
                .andExpect(status().isForbidden());
    }
}
