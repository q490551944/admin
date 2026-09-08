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

/**
 * STOMP 入站安全边界：复核 Session、校验 CONNECT 的 CSRF 凭证并限制订阅和发送目的地。
 * 此处只做鉴权与载荷检查，不负责消息落库或发送确认。
 */
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
        // 心跳和释放连接/订阅的命令不访问业务数据，允许失效客户端完成清理。
        if (command == null || command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE) return message;
        var authentication = ChatSessionHandshake.authenticatedSession(headers.getSessionAttributes());
        ChatPrincipal principal = ChatIdentity.require(authentication);
        accounts.requireEnabled(principal);
        // 以 HTTP Session 身份覆盖消息用户，不信任客户端的 login/passcode/user/sender 头。
        headers.setUser(authentication);

        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            CsrfToken csrf = (CsrfToken) headers.getSessionAttributes().get(ChatSessionHandshake.CSRF);
            String supplied = csrf == null ? null : headers.getFirstNativeHeader(csrf.getHeaderName());
            if (supplied == null || !MessageDigest.isEqual(csrf.getToken().getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8))) throw ChatException.forbidden();
        } else if (command == StompCommand.SUBSCRIBE) {
            // 私人 ACK/错误队列交给用户目的地机制路由；会话主题必须逐次检查访问权。
            String destination = headers.getDestination();
            if (PRIVATE_QUEUES.contains(destination == null ? "" : destination)) return message;
            var match = CONVERSATION.matcher(destination == null ? "" : destination);
            if (!match.matches()) throw ChatException.forbidden();
            rooms.requireAccess(principal.getUserId(), positiveId(match.group(1)));
        } else if (command == StompCommand.SEND) {
            // 只接受应用发送入口，禁止客户端直接向广播主题或任意队列投递。
            if (!"/app/chat.messages.send".equals(headers.getDestination())) throw ChatException.forbidden();
            JsonNode body;
            try {
                body = json.readTree((byte[]) message.getPayload());
            } catch (Exception error) {
                throw new ChatException(422, "INVALID_REQUEST", "无效消息载荷");
            }
            if (body == null || !body.isObject()) throw new ChatException(422, "INVALID_REQUEST", "无效消息载荷");
            // 显式拒绝载荷中的身份字段，发送者只能由服务端认证结果确定。
            for (String field : Set.of("senderId", "sender_id", "sender", "userId", "user_id", "ownerId")) {
                if (body.has(field)) throw new ChatException(403, "IDENTITY_FORGED", "发送者身份由服务端确定");
            }
            rooms.requireAccess(principal.getUserId(), positiveId(body.path("conversationId").asText()));
        } else {
            throw ChatException.forbidden();
        }
        return message;
    }

    /** 同时拒绝非数字、非正数和超出 Long 范围的会话标识。 */
    private long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {}
        throw new ChatException(422, "INVALID_REQUEST", "会话 ID 无效");
    }
}
