package com.hpj.admin.chat.security;

import com.hpj.admin.common.config.chat.ChatProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 兼容旧接口的兜底安全链；聊天关闭时仍拒绝其 API 和 WebSocket 入口。 */
@Configuration
public class LegacySecurityConfiguration {
    @Bean
    @Order(2)
    public SecurityFilterChain legacySecurity(HttpSecurity http, ChatProperties properties) throws Exception {
        http.authorizeHttpRequests(auth -> {
            // 默认保留旧接口访问方式；已能授予 ROLE_ADMIN 的部署可显式启用敏感接口保护。
            if (properties.isEnabled() && properties.getSecurity().isProtectLegacyEndpoints()) {
                auth.requestMatchers("/sys/users/**", "/groovy/**", "/kafka/**", "/test", "/test/**")
                    .hasRole("ADMIN");
            }
            // 开启聊天时由 Order(1) 接管这些路径，关闭时不能落入下方 permitAll。
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
