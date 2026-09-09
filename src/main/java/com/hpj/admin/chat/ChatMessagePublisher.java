package com.hpj.admin.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatMessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(ChatMessagePublisher.class);
    private final SimpMessagingTemplate messaging;

    public ChatMessagePublisher(SimpMessagingTemplate messaging) { this.messaging = messaging; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(MessageCommitted event) {
        if (event.created()) {
            try {
                messaging.convertAndSend("/topic/chat/conversations/" + event.message().getConversationId(),
                        Map.of("eventType", "MESSAGE_CREATED", "message", event.message()));
            } catch (RuntimeException error) {
                // 已提交的数据仍可通过历史接口补拉；广播失败不阻止给发送方回 ACK。
                log.error("Message {} broadcast failed", event.message().getId(), error);
            }
        }
        if (event.username() != null) sendPrivate(event.username(), event.sessionId(), "/queue/chat.acks",
                Map.of("eventType", "MESSAGE_ACK", "clientRequestId", event.message().getClientRequestId(),
                        "message", event.message()));
    }

    public void sendPrivate(String username, String sessionId, String destination, Object payload) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        try { messaging.convertAndSendToUser(username, destination, payload, headers.getMessageHeaders()); }
        catch (RuntimeException error) { log.error("Chat private delivery failed for {}", destination, error); }
    }
}
