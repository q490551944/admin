package com.hpj.admin.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatMessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(ChatMessagePublisher.class);
    private final SimpMessagingTemplate messaging;
    private final ChatMetrics metrics;
    private final SimpUserRegistry users;

    public ChatMessagePublisher(SimpMessagingTemplate messaging, ChatMetrics metrics, SimpUserRegistry users) {
        this.messaging = messaging;
        this.metrics = metrics;
        this.users = users;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(MessageCommitted event) {
        if (event.created()) {
            try {
                messaging.convertAndSend("/topic/chat/conversations/" + event.message().getConversationId(),
                        Map.of("eventType", "MESSAGE_CREATED", "message", event.message()));
                metrics.broadcast(event.acceptedAtNanos());
            } catch (RuntimeException error) {
                // 已提交的数据仍可通过历史接口补拉；广播失败不阻止给发送方回 ACK。
                log.error("Message {} broadcast failed", event.message().getId(), error);
                metrics.broadcastFailed.increment();
            }
            // 新私聊和未打开的私聊没有主题订阅；用户队列通知双方所有在线窗口。
            for (var recipient : event.directRecipients()) {
                var user = users.getUser(recipient.username());
                if (user == null) continue;
                var payload = Map.of("eventType", "MESSAGE_CREATED", "message", event.message(),
                        "conversation", recipient.conversation());
                // Spring 6.1 的有序入站路由在多窗口间复用消息头会失败，逐窗口创建投递。
                // 以服务端 Session ID 精确寻址，关闭窗口时也不会回退成向其他窗口广播。
                for (var session : user.getSessions())
                    sendPrivate(session.getId(), session.getId(), "/queue/chat.messages", payload);
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
        catch (RuntimeException error) {
            metrics.privateDeliveryFailed.increment();
            log.error("Chat private delivery failed for {}", destination, error);
        }
    }
}
