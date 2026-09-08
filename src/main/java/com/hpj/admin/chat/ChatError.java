package com.hpj.admin.chat;

/** REST 错误响应体；HTTP 状态由响应本身表达，客户端通过 code 区分业务原因。 */
public record ChatError(String code, String message) {
    public static ChatError from(ChatException exception) {
        return new ChatError(exception.getCode(), exception.getMessage());
    }
}
