package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Requests use the real CSRF endpoint and form filter; no manufactured Authentication or CSRF. */
@AutoConfigureMockMvc
abstract class MonitoringSecurityTestSupport {
    static final String MONITOR = "/api/monitor/v1";
    static final String CHAT = "/api/chat/v1";
    static final String PROBE = MONITOR + "/security-probe";
    static final String CONTEXT = "MONITOR_SECURITY_CONTEXT";
    static final String CSRF = "MONITOR_CSRF_TOKEN";
    static final String PASSWORD = "MonitorTestPassword123";
    static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    record Token(MockHttpSession session, String value) {}
    record Login(MockHttpSession session, String csrf, String anonymousCsrf) {}

    @BeforeEach
    void seedEmployees() {
        jdbc.update("DELETE FROM user");
        jdbc.update("INSERT INTO user(id,username,password,status) VALUES(1,'alice',?,TRUE)", HASH);
        jdbc.update("INSERT INTO user(id,username,password,status) VALUES(2,'bob',?,TRUE)", HASH);
        jdbc.update("INSERT INTO user(id,username,password,status) VALUES(3,'disabled',?,FALSE)", HASH);
    }

    Token token(String api, MockHttpSession session) throws Exception {
        var request = get(api + "/csrf-token");
        if (session != null) request.session(session);
        MvcResult result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN")).andReturn();
        String value = json.readTree(result.getResponse().getContentAsString()).path("token").asText();
        assertThat(value).isNotBlank();
        return new Token((MockHttpSession) result.getRequest().getSession(), value);
    }

    Login login(String username) throws Exception { return login(MONITOR, username, null); }

    Login login(String api, String username, MockHttpSession session) throws Exception {
        Token initial = token(api, session);
        String oldId = initial.session().getId();
        MvcResult result = mvc.perform(post(api + "/sessions").session(initial.session())
                        .header("X-CSRF-TOKEN", initial.value()).param("username", username).param("password", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", api + "/sessions/current"))
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist()).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(json.readTree(body).path("id").isTextual()).isTrue();
        assertThat(body).doesNotContain(PASSWORD, HASH);
        MockHttpSession authenticated = (MockHttpSession) result.getRequest().getSession();
        assertThat(authenticated.getId()).isNotEqualTo(oldId);
        Token authenticatedToken = token(api, authenticated);
        assertThat(authenticatedToken.value()).isNotEqualTo(initial.value());
        return new Login(authenticated, authenticatedToken.value(), initial.value());
    }
}
