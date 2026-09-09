package com.hpj.admin.controller.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpj.admin.chat.*;
import com.hpj.admin.chat.security.ChatIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatMessageController {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageController.class);
    private final ChatMessagingService service;
    private final ChatMessagePublisher publisher;
    private final ChatMetrics metrics;

    public ChatMessageController(ChatMessagingService service, ChatMessagePublisher publisher, ChatMetrics metrics) {
        this.service = service;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @GetMapping("/api/chat/v1/conversations/{id}/messages")
    public ChatMessagingService.HistoryPage history(Authentication authentication, @PathVariable long id,
            @RequestParam(required = false) String before, @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "30") int limit) {
        return service.history(ChatIdentity.require(authentication).getUserId(), id, before, after, limit);
    }

    @MessageMapping("/chat.messages.send")
    public void send(Authentication authentication, @Header("simpSessionId") String sessionId, @Payload JsonNode payload) {
        var principal = ChatIdentity.require(authentication);
        try {
            service.send(principal.getUserId(), ChatMessageRequest.from(payload), authentication.getName(), sessionId);
        } catch (Exception error) {
            metrics.rejected.increment();
            ChatException business = error instanceof ChatException chat ? chat
                    : new ChatException(500, "INTERNAL_ERROR", "消息发送失败，可使用原请求 ID 重试");
            if (!(error instanceof ChatException)) log.error("Chat message transaction failed", error);
            Map<String, Object> rejection = new LinkedHashMap<>();
            rejection.put("eventType", "MESSAGE_REJECTED");
            rejection.put("status", business.getStatus());
            rejection.put("code", business.getCode());
            rejection.put("message", business.getMessage());
            rejection.put("target", "/app/chat.messages.send");
            String requestId = payload.path("clientRequestId").asText("");
            if (requestId.length() <= 64) rejection.put("clientRequestId", requestId);
            publisher.sendPrivate(authentication.getName(), sessionId, "/queue/chat.errors", rejection);
        }
    }
}
