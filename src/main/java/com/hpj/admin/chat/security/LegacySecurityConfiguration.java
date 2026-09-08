package com.hpj.admin.chat.security;

import com.hpj.admin.common.config.chat.ChatProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class LegacySecurityConfiguration {
    @Bean
    @Order(2)
    public SecurityFilterChain legacySecurity(HttpSecurity http, ChatProperties properties) throws Exception {
        http.authorizeHttpRequests(auth -> {
            // Keep legacy APIs backward-compatible by default. Deployments that already
            // provide ROLE_ADMIN can explicitly harden these sensitive endpoints.
            if (properties.isEnabled() && properties.getSecurity().isProtectLegacyEndpoints()) {
                auth.requestMatchers("/sys/users/**", "/groovy/**", "/kafka/**", "/test", "/test/**")
                    .hasRole("ADMIN");
            }
            auth.requestMatchers("/api/chat/**", "/ws/chat", "/ws/chat/**").denyAll()
                .anyRequest().permitAll();
        }).csrf(csrf -> csrf.disable())
          .requestCache(cache -> cache.disable())
          .formLogin(login -> login.disable())
          .logout(logout -> logout.disable())
          .httpBasic(basic -> basic.disable());
        return http.build();
    }
}
