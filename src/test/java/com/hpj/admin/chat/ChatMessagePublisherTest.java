package com.hpj.admin.chat;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatMessagePublisherTest {
    @Test void failedBroadcastStillAcknowledgesCommittedMessage() {
        SimpMessagingTemplate transport = mock(SimpMessagingTemplate.class);
        doThrow(new IllegalStateException("broker unavailable")).when(transport)
                .convertAndSend(eq("/topic/chat/conversations/10"), any(Object.class));
        MessageView message = new MessageView();
        message.setId(20L); message.setConversationId(10L); message.setClientRequestId("stable");
        new ChatMessagePublisher(transport).committed(new MessageCommitted(message, "alice", "session-1", true));
        verify(transport).convertAndSendToUser(eq("alice"), eq("/queue/chat.acks"),
                argThat((Object value) -> ((Map<?, ?>) value).get("eventType").equals("MESSAGE_ACK")), anyMap());
    }
}
