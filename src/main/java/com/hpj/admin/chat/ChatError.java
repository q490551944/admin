package com.hpj.admin.chat;

public record ChatError(String code, String message) {
    public static ChatError from(ChatException exception) {
        return new ChatError(exception.getCode(), exception.getMessage());
    }
}
