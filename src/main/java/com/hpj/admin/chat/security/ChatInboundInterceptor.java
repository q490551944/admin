package com.hpj.admin.chat.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.chat.ChatException;
import com.hpj.admin.chat.ChatRoomService;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.web.csrf.CsrfToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.regex.Pattern;

public class ChatInboundInterceptor implements ChannelInterceptor {
    private static final Pattern CONVERSATION = Pattern.compile("^/topic/chat/conversations/([1-9][0-9]{0,18})$");
    private static final Set<String> PRIVATE_QUEUES = Set.of("/user/queue/chat.acks", "/user/queue/chat.errors");
    private final ChatAccounts accounts;
    private final ChatRoomService rooms;
    private final ObjectMapper json;

    public ChatInboundInterceptor(ChatAccounts accounts, ChatRoomService rooms, ObjectMapper json) {
        this.accounts = accounts;
        this.rooms = rooms;
        this.json = json;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null) throw ChatException.forbidden();
        StompCommand command = headers.getCommand();
        if (command == null || command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE) return message;
        var authentication = ChatSessionHandshake.authenticatedSession(headers.getSessionAttributes());
        ChatPrincipal principal = ChatIdentity.require(authentication);
        accounts.requireEnabled(principal);
        // Never trust STOMP login/passcode/user/sender headers.
        headers.setUser(authentication);

        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            CsrfToken csrf = (CsrfToken) headers.getSessionAttributes().get(ChatSessionHandshake.CSRF);
            String supplied = csrf == null ? null : headers.getFirstNativeHeader(csrf.getHeaderName());
            if (supplied == null || !MessageDigest.isEqual(csrf.getToken().getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8))) throw ChatException.forbidden();
        } else if (command == StompCommand.SUBSCRIBE) {
            String destination = headers.getDestination();
            if (PRIVATE_QUEUES.contains(destination == null ? "" : destination)) return message;
            var match = CONVERSATION.matcher(destination == null ? "" : destination);
            if (!match.matches()) throw ChatException.forbidden();
            rooms.requireAccess(principal.getUserId(), positiveId(match.group(1)));
        } else if (command == StompCommand.SEND) {
            if (!"/app/chat.messages.send".equals(headers.getDestination())) throw ChatException.forbidden();
            JsonNode body;
            try {
                body = json.readTree((byte[]) message.getPayload());
            } catch (Exception error) {
                throw new ChatException(422, "INVALID_REQUEST", "无效消息载荷");
            }
            if (body == null || !body.isObject()) throw new ChatException(422, "INVALID_REQUEST", "无效消息载荷");
            for (String field : Set.of("senderId", "sender_id", "sender", "userId", "user_id", "ownerId")) {
                if (body.has(field)) throw new ChatException(403, "IDENTITY_FORGED", "发送者身份由服务端确定");
            }
            rooms.requireAccess(principal.getUserId(), positiveId(body.path("conversationId").asText()));
        } else {
            throw ChatException.forbidden();
        }
        return message;
    }

    private long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {}
        throw new ChatException(422, "INVALID_REQUEST", "会话 ID 无效");
    }
}
