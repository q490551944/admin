package com.hpj.admin.chat.security;

import com.hpj.admin.chat.ChatException;
import com.hpj.admin.entity.User;
import com.hpj.admin.mapper.chat.ChatAccountMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAccounts implements UserDetailsService, UserDetailsPasswordService {
    private final ChatAccountMapper accounts;

    public ChatAccounts(ChatAccountMapper accounts) { this.accounts = accounts; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        List<User> matches = accounts.findByUsername(username);
        // Legacy schema has no username unique key: ambiguous accounts must never authenticate.
        if (matches.size() != 1) throw new UsernameNotFoundException("Invalid credentials");
        User account = matches.get(0);
        return new ChatPrincipal(account.getId(), account.getUsername(),
                account.getPassword() == null ? "" : account.getPassword(), Boolean.TRUE.equals(account.getStatus()));
    }

    @Override
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        ChatPrincipal principal = (ChatPrincipal) user;
        if (accounts.upgradePassword(principal.getUserId(), principal.getPassword(), newPassword) != 1) {
            throw new UsernameNotFoundException("Account credentials changed");
        }
        return new ChatPrincipal(principal.getUserId(), principal.getUsername(), newPassword, principal.isEnabled());
    }

    public void requireEnabled(ChatPrincipal principal) {
        User current = accounts.findActiveAccount(principal.getUserId());
        if (current == null || !Boolean.TRUE.equals(current.getStatus())
                || !principal.getUsername().equals(current.getUsername())) throw ChatException.unauthorized();
    }
}
