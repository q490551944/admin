package com.hpj.admin.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.Map;

/** 将已提交的房间变更转换成会话主题上的实时通知。 */
@Component
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class RoomEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(RoomEventPublisher.class);
    private final SimpMessagingTemplate messaging;

    public RoomEventPublisher(SimpMessagingTemplate messaging) { this.messaging = messaging; }

    /** 只在事务成功提交后通知客户端，防止客户端看到最终回滚的解散状态。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDissolved(ConversationDissolved event) {
        try {
            messaging.convertAndSend("/topic/chat/conversations/" + event.conversationId(),
                Map.of("eventType", "CONVERSATION_DISSOLVED", "conversationId", String.valueOf(event.conversationId())));
        } catch (RuntimeException error) {
            // 此时数据库已提交；广播失败仅记录日志，会话状态仍以数据库为准。
            log.error("Failed to publish dissolution for conversation {}", event.conversationId(), error);
        }
    }
}
