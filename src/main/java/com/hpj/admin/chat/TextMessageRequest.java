package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.JsonNode;

public record TextMessageRequest(long conversationId, String clientRequestId, String body) {
    public TextMessageRequest {
        if (conversationId <= 0 || clientRequestId == null || !clientRequestId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new ChatException(422, "INVALID_REQUEST", "会话 ID 或消息请求 ID 无效");
        body = body == null ? "" : body.strip();
        if (body.isEmpty()) throw new ChatException(422, "EMPTY_MESSAGE", "消息内容不能为空");
        if (body.codePointCount(0, body.length()) > 5000)
            throw new ChatException(413, "MESSAGE_TOO_LONG", "消息不能超过 5000 个字符");
        // 拒绝无法稳定往返 UTF-8 / 数据库的孤立代理字符及 STOMP 帧终止符。
        if (body.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF)))
            throw new ChatException(422, "INVALID_MESSAGE", "消息包含无效字符");
    }

    public static TextMessageRequest from(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("type").asText().equals("TEXT")
                || !body.path("body").isTextual() || !body.path("clientRequestId").isTextual()
                || (body.hasNonNull("attachmentId")))
            throw new ChatException(422, "INVALID_REQUEST", "当前发送入口仅支持文字消息");
        long id;
        try { id = Long.parseLong(body.path("conversationId").asText()); }
        catch (NumberFormatException error) { throw new ChatException(422, "INVALID_REQUEST", "会话 ID 无效"); }
        return new TextMessageRequest(id, body.path("clientRequestId").textValue(), body.path("body").textValue());
    }
}
