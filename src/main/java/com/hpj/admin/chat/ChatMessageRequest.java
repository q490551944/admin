package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpj.admin.entity.chat.MessageType;

public record ChatMessageRequest(long conversationId, String clientRequestId, MessageType type, String body, Long attachmentId) {
    public ChatMessageRequest {
        if (type == MessageType.TEXT) {
            body = new TextMessageRequest(conversationId, clientRequestId, body).body();
            if (attachmentId != null) throw invalid();
        } else if (type == MessageType.IMAGE) {
            if (conversationId <= 0 || clientRequestId == null || !clientRequestId.matches("[A-Za-z0-9_-]{1,64}")
                    || attachmentId == null || attachmentId <= 0 || body != null) throw invalid();
        } else throw invalid();
    }

    public static ChatMessageRequest from(JsonNode json) {
        if (json == null || !json.isObject() || !json.path("clientRequestId").isTextual()) throw invalid();
        try {
            var type = MessageType.valueOf(json.path("type").asText());
            if (type == MessageType.TEXT && !json.path("body").isTextual()) throw invalid();
            if (type == MessageType.IMAGE && json.hasNonNull("body")) throw invalid();
            return new ChatMessageRequest(Long.parseLong(json.path("conversationId").asText()),
                    json.path("clientRequestId").textValue(), type,
                    json.hasNonNull("body") ? json.path("body").textValue() : null,
                    json.hasNonNull("attachmentId") ? Long.parseLong(json.path("attachmentId").asText()) : null);
        } catch (IllegalArgumentException error) { throw invalid(); }
    }

    private static ChatException invalid() { return new ChatException(422, "INVALID_REQUEST", "消息格式或附件 ID 无效"); }
}
