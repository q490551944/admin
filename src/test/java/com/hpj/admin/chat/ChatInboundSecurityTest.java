package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.chat.security.*;
import org.junit.jupiter.api.*;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatInboundSecurityTest {
    ChatRoomService rooms = mock(ChatRoomService.class);
    ChatAccounts accounts = mock(ChatAccounts.class);
    ChatInboundInterceptor interceptor = new ChatInboundInterceptor(accounts, rooms, new ObjectMapper());
    MockHttpSession session;
    Map<String, Object> attributes;
    UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void session() {
        session = new MockHttpSession();
        ChatPrincipal principal = new ChatPrincipal(1, "alice", "hash", true);
        authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        attributes = new HashMap<>(Map.of(ChatSessionHandshake.SESSION, session,
                ChatSessionHandshake.SESSION_ID, session.getId(), ChatSessionHandshake.USER_ID, 1L,
                ChatSessionHandshake.CSRF, new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token")));
    }

    Message<byte[]> frame(StompCommand command, String destination, String payload, String csrf) {
        StompHeaderAccessor header = StompHeaderAccessor.create(command);
        header.setSessionAttributes(attributes);
        header.setSessionId("socket-1");
        if (destination != null) header.setDestination(destination);
        if (csrf != null) header.setNativeHeader("X-CSRF-TOKEN", csrf);
        header.setNativeHeader("login", "carol");
        header.setUser(() -> "forged-user");
        header.setLeaveMutable(true);
        return MessageBuilder.createMessage(payload.getBytes(StandardCharsets.UTF_8), header.getMessageHeaders());
    }

    @Test
    void connectUsesSessionPrincipalAndRequiresCsrf() {
        Message<byte[]> allowed = frame(StompCommand.CONNECT, null, "", "token");
        interceptor.preSend(allowed, null);
        assertThat(StompHeaderAccessor.wrap(allowed).getUser()).isEqualTo(authentication);
        assertDenied(frame(StompCommand.CONNECT, null, "", null), 403);
        assertDenied(frame(StompCommand.CONNECT, null, "", "wrong"), 403);
    }

    @Test
    void forgedIdentityBrokerInjectionAndWildcardSubscriptionsAreRejected() {
        assertDenied(frame(StompCommand.SEND, "/app/chat.messages.send", "{\"conversationId\":10,\"senderId\":2}", null), 403);
        assertDenied(frame(StompCommand.SEND, "/topic/chat/conversations/10", "hello", null), 403);
        assertDenied(frame(StompCommand.SUBSCRIBE, "/topic/chat/conversations/*", "", null), 403);
        assertDenied(frame(StompCommand.SUBSCRIBE, "/queue/chat.acks", "", null), 403);
        assertDenied(frame(StompCommand.SUBSCRIBE, "/user/bob/queue/chat.acks", "", null), 403);
        assertDenied(frame(StompCommand.SUBSCRIBE, "/queue/chat.messages", "", null), 403);
        assertDenied(frame(StompCommand.SUBSCRIBE, "/user/bob/queue/chat.messages", "", null), 403);
        assertDenied(frame(StompCommand.SEND, "/user/queue/chat.messages", "hello", null), 403);
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/chat.messages", "", null), null);
        interceptor.preSend(frame(StompCommand.SEND, "/app/chat.messages.send", "{\"conversationId\":\"10\",\"body\":\"hello\"}", null), null);
        verify(rooms).requireAccess(1, 10);
    }

    @Test
    void eachConnectSubscribeAndSendChecksSessionAndConversationAccess() {
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/chat/conversations/10", "", null), null);
        verify(rooms).requireAccess(1, 10);
        doThrow(ChatException.notFound()).when(rooms).requireAccess(1, 10);
        assertDenied(frame(StompCommand.SUBSCRIBE, "/topic/chat/conversations/10", "", null), 404);
        assertDenied(frame(StompCommand.SEND, "/app/chat.messages.send", "{\"conversationId\":10}", null), 404);
        session.invalidate();
        assertDenied(frame(StompCommand.CONNECT, null, "", "token"), 401);
        assertDenied(frame(StompCommand.SUBSCRIBE, "/user/queue/chat.acks", "", null), 401);
        assertDenied(frame(StompCommand.SEND, "/app/chat.messages.send", "{\"conversationId\":10}", null), 401);
    }

    @Test
    void anonymousAndReplacedSessionContextsCannotAuthenticate() {
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertDenied(frame(StompCommand.CONNECT, null, "", "token"), 401);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        attributes.put(ChatSessionHandshake.USER_ID, 2L);
        assertDenied(frame(StompCommand.CONNECT, null, "", "token"), 401);
    }

    private void assertDenied(Message<byte[]> message, int status) {
        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(ChatException.class)
                .extracting(error -> ((ChatException) error).getStatus()).isEqualTo(status);
    }
}
