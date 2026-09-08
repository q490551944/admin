package com.hpj.admin.chat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.chat.ChatException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 将消息处理异常转换为 STOMP ERROR 帧，向客户端提供状态码和可识别的业务码。 */
public class ChatStompErrorHandler extends StompSubProtocolErrorHandler {
    private final ObjectMapper json;

    public ChatStompErrorHandler(ObjectMapper json) { this.json = json; }

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable error) {
        ChatException business = null;
        // 消息通道可能包装业务异常，沿原因链取出原始错误；未知异常只返回通用描述。
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ChatException chatError) { business = chatError; break; }
        }
        int status = business == null ? 500 : business.getStatus();
        String code = business == null ? "INTERNAL_ERROR" : business.getCode();
        String description = business == null ? "实时服务暂不可用" : business.getMessage();
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.ERROR);
        headers.setMessage(code);
        headers.setNativeHeader("content-type", "application/json");
        headers.setNativeHeader("status", String.valueOf(status));
        headers.setLeaveMutable(true);
        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of("status", status, "code", code, "message", description));
        } catch (Exception ignored) {
            body = code.getBytes(StandardCharsets.UTF_8);
        }
        return MessageBuilder.createMessage(body, headers.getMessageHeaders());
    }
}
