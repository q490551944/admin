package com.hpj.admin.chat.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.List;
import java.util.Map;

public class ChatPrincipal extends User {
    private final long userId;

    public ChatPrincipal(long userId, String username, String password, boolean enabled) {
        super(username, password, enabled, true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_CHAT_USER")));
        this.userId = userId;
    }

    public long getUserId() { return userId; }

    public Map<String, String> publicView() {
        return Map.of("id", Long.toString(userId), "name", getUsername(), "username", getUsername());
    }
}
