package com.hpj.admin.chat.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.List;
import java.util.Map;

/** Session 中保存的员工身份，以稳定的数据库 userId 关联房间、参与记录和审计。 */
public class ChatPrincipal extends User {
    private final long userId;

    public ChatPrincipal(long userId, String username, String password, boolean enabled) {
        super(username, password, enabled, true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_CHAT_USER")));
        this.userId = userId;
    }

    public long getUserId() { return userId; }

    /** 暴露最小身份信息，并将 ID 转成字符串以保留前端整数精度。 */
    public Map<String, String> publicView() {
        return Map.of("id", Long.toString(userId), "name", getUsername(), "username", getUsername());
    }
}
