package com.hpj.admin.chat.security;

import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/** 兼容旧接口的兜底安全链；聊天关闭时仍拒绝其 API 和 WebSocket 入口。 */
@Configuration
@EnableConfigurationProperties(MonitoringProperties.class)
public class LegacySecurityConfiguration {
    @Bean
    @Order(2)
    public SecurityFilterChain legacySecurity(HttpSecurity http, ChatProperties properties,
                                              MonitoringProperties monitoring) throws Exception {
        http.authorizeHttpRequests(auth -> {
            // 监控使用员工身份；匿名改密码/启用账号或执行任意 Groovy 都会绕过该身份边界。
            // 监控查看权限不授予员工管理或脚本执行权限。
            if (monitoring.isEnabled()) {
                auth.requestMatchers("/sys/users/**", "/groovy/**").hasRole("ADMIN");
            }
            // 默认保留旧接口访问方式；已能授予 ROLE_ADMIN 的部署可显式启用敏感接口保护。
            if (properties.isEnabled() && properties.getSecurity().isProtectLegacyEndpoints()) {
                auth.requestMatchers("/sys/users/**", "/groovy/**", "/kafka/**", "/test", "/test/**")
                    .hasRole("ADMIN");
            }
            // 开启聊天时由 Order(1) 接管这些路径，关闭时不能落入下方 permitAll。
            auth.requestMatchers("/api/chat/**", "/ws/chat", "/ws/chat/**", "/api/monitor/**").denyAll()
                .anyRequest().permitAll();
        }).csrf(csrf -> {
            if (monitoring.isEnabled()) {
                csrf.requireCsrfProtectionMatcher(new AndRequestMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER,
                        new OrRequestMatcher(new AntPathRequestMatcher("/sys/users/**"),
                                new AntPathRequestMatcher("/groovy/**"))));
            } else {
                csrf.disable();
            }
        })
          .requestCache(cache -> cache.disable())
          .formLogin(login -> login.disable())
          .logout(logout -> logout.disable())
          .httpBasic(basic -> basic.disable());
        return http.build();
    }
}
