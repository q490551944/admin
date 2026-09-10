package com.hpj.admin.monitor.security;

import com.hpj.admin.entity.User;
import com.hpj.admin.mapper.chat.ChatAccountMapper;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/** Reuses employee queries without depending on the conditional chat identity beans. */
@Service
public class MonitoringAccounts {
    private final ChatAccountMapper accounts;

    public MonitoringAccounts(ChatAccountMapper accounts) { this.accounts = accounts; }

    // Method references are passed to a local provider; this bean is deliberately not a global UserDetailsService.
    public MonitoringPrincipal loadUserByUsername(String username) {
        List<User> matches = query(() -> accounts.findByUsername(username));
        if (matches == null) throw unavailable();
        if (matches.size() != 1) throw new UsernameNotFoundException("Invalid credentials");
        User account = matches.get(0);
        if (account == null || account.getId() == null || account.getId() <= 0
                || account.getUsername() == null || account.getUsername().isBlank()) throw unavailable();
        return new MonitoringPrincipal(account.getId(), account.getUsername(),
                account.getPassword() == null ? "" : account.getPassword(), Boolean.TRUE.equals(account.getStatus()));
    }

    public UserDetails updatePassword(UserDetails user, String newPassword) {
        if (!(user instanceof MonitoringPrincipal principal)) throw new BadCredentialsException("Invalid credentials");
        int updated = query(() -> accounts.upgradePassword(principal.getUserId(), principal.getPassword(), newPassword));
        if (updated != 1) throw new BadCredentialsException("Account credentials changed");
        return new MonitoringPrincipal(principal.getUserId(), principal.getUsername(), newPassword, principal.isEnabled());
    }

    /** Rechecks enabled/deleted/renamed employees for every protected monitoring request. */
    public void requireEnabled(MonitoringPrincipal principal) {
        User current = query(() -> accounts.findActiveAccount(principal.getUserId()));
        if (current == null || !Boolean.TRUE.equals(current.getStatus())
                || !principal.getUsername().equals(current.getUsername())) {
            throw new BadCredentialsException("Monitoring identity is no longer valid");
        }
    }

    private static <T> T query(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            // Authentication providers may log this exception: do not attach SQL, credentials, or the original cause.
            throw unavailable();
        }
    }

    private static InternalAuthenticationServiceException unavailable() {
        return new InternalAuthenticationServiceException("Monitoring identity service unavailable");
    }
}
