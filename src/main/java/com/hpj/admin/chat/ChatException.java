package com.hpj.admin.chat;

public class ChatException extends RuntimeException {
    private final int status;
    private final String code;

    public ChatException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }

    public static ChatException unauthorized() {
        return new ChatException(401, "SESSION_EXPIRED", "登录已失效，请重新登录");
    }

    public static ChatException forbidden() {
        return new ChatException(403, "ACCESS_DENIED", "无权执行此操作");
    }

    public static ChatException notFound() {
        return new ChatException(404, "CONVERSATION_NOT_FOUND", "会话不存在或已解散");
    }
}
