package com.hpj.admin.chat;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.*;
import java.util.Map;
import java.util.List;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatMessagePublisherTest {
    @Test void failedBroadcastStillAcknowledgesCommittedMessage() {
        SimpMessagingTemplate transport = mock(SimpMessagingTemplate.class);
        doThrow(new IllegalStateException("broker unavailable")).when(transport)
                .convertAndSend(eq("/topic/chat/conversations/10"), any(Object.class));
        MessageView message = new MessageView();
        message.setId(20L); message.setConversationId(10L); message.setClientRequestId("stable");
        var metrics = new ChatMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var conversation = new ConversationView(); conversation.setId(10L);
        var users = mock(SimpUserRegistry.class);
        var bob = mock(SimpUser.class);
        var session = mock(SimpSession.class);
        when(users.getUser("bob")).thenReturn(bob);
        when(bob.getSessions()).thenReturn(Set.of(session));
        when(session.getId()).thenReturn("bob-session");
        new ChatMessagePublisher(transport, metrics, users).committed(new MessageCommitted(message, "alice", "session-1", true,
                System.nanoTime(), List.of(new MessageCommitted.DirectRecipient("bob", conversation))));
        org.assertj.core.api.Assertions.assertThat(metrics.broadcastFailed.count()).isEqualTo(1);
        verify(transport).convertAndSendToUser(eq("alice"), eq("/queue/chat.acks"),
                argThat((Object value) -> ((Map<?, ?>) value).get("eventType").equals("MESSAGE_ACK")), anyMap());
        verify(transport).convertAndSendToUser(eq("bob-session"), eq("/queue/chat.messages"),
                argThat((Object value) -> ((Map<?, ?>) value).get("conversation") == conversation), anyMap());
    }
}
