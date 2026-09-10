package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises actual container cookies and form authentication without injecting a security identity. */
@SpringBootTest(classes = MonitoringSecurityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/chat-test.yml", "monitor.enabled=true",
                "monitor.allowed-user-ids[0]=1", "chat.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:monitor_cookie_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class MonitoringCookieSessionTest {
    private static final String CSRF = "/api/monitor/v1/csrf-token";
    private static final String SESSIONS = "/api/monitor/v1/sessions";
    private static final String CURRENT = SESSIONS + "/current";
    private static final String DATA = "/api/monitor/v1/security-probe";

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedEmployee() {
        jdbc.update("DELETE FROM user");
        jdbc.update("INSERT INTO user(id,username,password,status) VALUES(1,'alice',?,TRUE)",
                MonitoringSecurityTestSupport.HASH);
    }

    @Test
    void containerSessionRotationLogoutAndCsrfRotationRejectCapturedOldCredentials() throws Exception {
        Browser first = new Browser();
        Token anonymousToken = first.csrf();
        String anonymousSessionId = first.sessionId();
        first.login(anonymousToken);
        String authenticatedSessionId = first.sessionId();
        assertThat(authenticatedSessionId).isNotEqualTo(anonymousSessionId);
        assertThat(first.request("GET", CURRENT, null, null).statusCode()).isEqualTo(200);
        assertThat(first.request("GET", DATA, null, null).body()).isEqualTo("monitor");
        assertThat(replaySession(anonymousSessionId).statusCode()).isEqualTo(401);

        Token firstLoginToken = first.csrf();
        assertThat(firstLoginToken.value()).isNotEqualTo(anonymousToken.value());
        assertThat(first.request("DELETE", CURRENT, null, anonymousToken).statusCode()).isEqualTo(403);
        assertThat(first.request("GET", DATA, null, null).statusCode()).isEqualTo(200);
        assertThat(first.request("DELETE", CURRENT, null, firstLoginToken).statusCode()).isEqualTo(204);
        // Logout clears only monitoring authentication; a shared chat session may keep the container cookie alive.
        assertThat(first.request("GET", DATA, null, null).statusCode()).isEqualTo(401);
        assertThat(replaySession(authenticatedSessionId).statusCode()).isEqualTo(401);

        Browser second = new Browser();
        second.login(second.csrf());
        assertThat(second.sessionId()).isNotEqualTo(authenticatedSessionId);
        assertThat(second.request("POST", DATA, null, firstLoginToken).statusCode()).isEqualTo(403);
        assertThat(second.request("GET", DATA, null, null).statusCode()).isEqualTo(200);
        Token secondLoginToken = second.csrf();
        assertThat(secondLoginToken.value()).isNotEqualTo(firstLoginToken.value());
        assertThat(second.request("DELETE", CURRENT, null, secondLoginToken).statusCode()).isEqualTo(204);
        assertThat(second.request("GET", DATA, null, null).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> replaySession(String sessionId) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(uri(DATA)).timeout(Duration.ofSeconds(8))
                        .header("Cookie", "JSESSIONID=" + sessionId).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private record Token(String header, String value) { }

    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();

        private Token csrf() throws Exception {
            HttpResponse<String> response = request("GET", CSRF, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            var body = json.readTree(response.body());
            Token token = new Token(body.path("headerName").asText(), body.path("token").asText());
            assertThat(token.header()).isNotBlank();
            assertThat(token.value()).isNotBlank();
            return token;
        }

        private void login(Token csrf) throws Exception {
            String form = "username=" + URLEncoder.encode("alice", StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(MonitoringSecurityTestSupport.PASSWORD, StandardCharsets.UTF_8);
            HttpResponse<String> response = request("POST", SESSIONS, form, csrf);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.headers().firstValue("Location")).contains(CURRENT);
            assertThat(json.readTree(response.body()).path("id").asText()).isEqualTo("1");
        }

        private String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "JSESSIONID".equals(cookie.getName()) && !cookie.hasExpired())
                    .map(HttpCookie::getValue).findFirst().orElseThrow();
        }

        private HttpResponse<String> request(String method, String path, String form, Token csrf) throws Exception {
            var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(8));
            if (csrf != null) request.header(csrf.header(), csrf.value());
            if (form != null) request.header("Content-Type", "application/x-www-form-urlencoded");
            return client.send(request.method(method, form == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
