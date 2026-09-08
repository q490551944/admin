package com.hpj.admin.chat.security;

import com.hpj.admin.chat.ChatException;
import com.hpj.admin.entity.User;
import com.hpj.admin.mapper.chat.ChatAccountMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import java.util.List;

/** 将现有员工账号适配为 Spring Security 用户，并在登录后按需升级密码。 */
@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAccounts implements UserDetailsService, UserDetailsPasswordService {
    private final ChatAccountMapper accounts;

    public ChatAccounts(ChatAccountMapper accounts) { this.accounts = accounts; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        List<User> matches = accounts.findByUsername(username);
        // 历史表没有用户名唯一约束，存在重名时拒绝认证，不能任取其中一个账号。
        if (matches.size() != 1) throw new UsernameNotFoundException("Invalid credentials");
        User account = matches.get(0);
        return new ChatPrincipal(account.getId(), account.getUsername(),
                account.getPassword() == null ? "" : account.getPassword(), Boolean.TRUE.equals(account.getStatus()));
    }

    /** 认证成功后由认证提供器调用；条件更新失败表示凭证已被其他请求修改。 */
    @Override
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        ChatPrincipal principal = (ChatPrincipal) user;
        if (accounts.upgradePassword(principal.getUserId(), principal.getPassword(), newPassword) != 1) {
            throw new UsernameNotFoundException("Account credentials changed");
        }
        return new ChatPrincipal(principal.getUserId(), principal.getUsername(), newPassword, principal.isEnabled());
    }

    /** 每次使用会话时重查账号，及时拒绝已停用、删除或更名的账号。 */
    public void requireEnabled(ChatPrincipal principal) {
        User current = accounts.findActiveAccount(principal.getUserId());
        if (current == null || !Boolean.TRUE.equals(current.getStatus())
                || !principal.getUsername().equals(current.getUsername())) throw ChatException.unauthorized();
    }
}
