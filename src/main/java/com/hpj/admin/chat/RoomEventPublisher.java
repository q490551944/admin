package com.hpj.admin.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class RoomEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(RoomEventPublisher.class);
    private final SimpMessagingTemplate messaging;

    public RoomEventPublisher(SimpMessagingTemplate messaging) { this.messaging = messaging; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDissolved(ConversationDissolved event) {
        try {
            messaging.convertAndSend("/topic/chat/conversations/" + event.conversationId(),
                Map.of("eventType", "CONVERSATION_DISSOLVED", "conversationId", String.valueOf(event.conversationId())));
        } catch (RuntimeException error) {
            // The database remains authoritative if delivery fails after commit.
            log.error("Failed to publish dissolution for conversation {}", event.conversationId(), error);
        }
    }
}
