package com.hpj.admin.chat.security;

import com.hpj.admin.chat.ChatException;
import org.springframework.security.core.Authentication;

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
