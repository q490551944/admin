package com.hpj.admin.chat.security;

import com.hpj.admin.chat.ChatException;
import org.springframework.security.core.Authentication;

/** 统一提取聊天身份，拒绝匿名身份及其他认证机制产生的 Principal。 */
public final class ChatIdentity {
    private ChatIdentity() {}

    public static ChatPrincipal require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ChatPrincipal principal)) {
            throw ChatException.unauthorized();
        }
        return principal;
    }
}
