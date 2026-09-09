package com.hpj.admin.chat;

import com.hpj.admin.chat.security.*;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ChatWindowSessionsTest {
    private final ChatWindowSessions windows = new ChatWindowSessions(Duration.ofMinutes(30));
    private final Filter filter = windows.filter();

    MockHttpServletResponse request(String id, Consumer<HttpServletRequest> action) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/chat/v1/sessions/current");
        request.addHeader(ChatWindowSessions.HEADER, id);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> action.accept((HttpServletRequest) req));
        return response;
    }

    String login() throws Exception {
        return request("new", request -> {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    new ChatPrincipal(1L, "alice", "", true), null, List.of()));
            request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }).getHeader(ChatWindowSessions.HEADER);
    }

    @Test void aRequestAlreadyInFlightCannotRestoreALoggedOutSession() throws Exception {
        assertStaleRequestCannotRestore(false);
    }

    @Test void aRequestAlreadyInFlightCannotRestoreARotatedSessionId() throws Exception {
        assertStaleRequestCannotRestore(true);
    }

    private void assertStaleRequestCannotRestore(boolean rotate) throws Exception {
        String id = login();
        var captured = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = executor.submit(() -> {
                try {
                    request(id, request -> {
                        HttpSession session = request.getSession(false);
                        assertThat(session).isNotNull();
                        captured.countDown();
                        try { assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException error) { throw new AssertionError(error); }
                        session.setAttribute("late", true);
                    });
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assertThat(captured.await(3, TimeUnit.SECONDS)).isTrue();
            var response = request(id, request -> {
                if (rotate) request.changeSessionId();
                else request.getSession(false).invalidate();
            });
            finish.countDown();
            pending.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> windows.authentication(id, 1)).isInstanceOf(ChatException.class);
            if (rotate) assertThat(ChatIdentity.require(windows.authentication(response.getHeader(ChatWindowSessions.HEADER), 1))
                    .getUsername()).isEqualTo("alice");
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test void expiryAndIdentityChecksUseTheStoredSessionRatherThanAHandshakeSnapshot() throws Exception {
        String expiring = login(), active = login();
        request(expiring, request -> request.getSession(false).setMaxInactiveInterval(1));
        assertThatThrownBy(() -> windows.authentication(active, 2)).isInstanceOf(ChatException.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            windows.cleanExpired();
            assertThatThrownBy(() -> windows.authentication(expiring, 1)).isInstanceOf(ChatException.class);
        });
        assertThat(ChatIdentity.require(windows.authentication(active, 1)).getUsername()).isEqualTo("alice");
    }
}
