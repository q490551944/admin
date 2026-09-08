package com.hpj.admin.chat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.chat.ChatError;
import com.hpj.admin.chat.ChatException;
import com.hpj.admin.common.config.chat.ChatProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Configuration
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatSecurityConfiguration {
    @Bean
    public PasswordEncoder chatPasswordEncoder(ChatProperties properties) {
        return new ChatPasswordEncoder(properties.getSecurity().isAllowLegacyDesPasswords());
    }

    @Bean
    public DaoAuthenticationProvider chatAuthenticationProvider(ChatAccounts accounts, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(accounts);
        provider.setPasswordEncoder(encoder);
        provider.setUserDetailsPasswordService(accounts);
        return provider;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain chatSecurity(HttpSecurity http, DaoAuthenticationProvider provider,
                                            ChatAccounts accounts, ObjectMapper json) throws Exception {
        http.securityMatcher("/api/chat/**", "/ws/chat", "/ws/chat/**")
            .authenticationProvider(provider)
            .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/chat/v1/csrf-token").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/chat/v1/sessions").permitAll()
                    .anyRequest().authenticated())
            .requestCache(cache -> cache.disable())
            .httpBasic(basic -> basic.disable())
            .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request, response, error) ->
                            writeError(response, json, ChatException.unauthorized()))
                    .accessDeniedHandler((request, response, error) -> {
                        var auth = SecurityContextHolder.getContext().getAuthentication();
                        boolean anonymous = auth == null || auth instanceof AnonymousAuthenticationToken;
                        writeError(response, json, anonymous ? ChatException.unauthorized()
                                : new ChatException(403, "ACCESS_DENIED", "请求被拒绝，请刷新页面获取有效的 CSRF 凭证"));
                    }))
            .formLogin(login -> login.loginProcessingUrl("/api/chat/v1/sessions")
                    .successHandler((request, response, authentication) -> {
                        response.setStatus(HttpServletResponse.SC_CREATED);
                        response.setHeader("Location", request.getContextPath() + "/api/chat/v1/sessions/current");
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        json.writeValue(response.getOutputStream(), ChatIdentity.require(authentication).publicView());
                    })
                    .failureHandler((request, response, error) -> writeError(response, json,
                            new ChatException(401, "INVALID_CREDENTIALS", "用户名或密码错误，或账号已停用"))))
            .logout(logout -> logout.logoutRequestMatcher(
                            new AntPathRequestMatcher("/api/chat/v1/sessions/current", "DELETE"))
                    .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                    .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .addFilterAfter(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) throws ServletException, IOException {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken)) {
                        try {
                            accounts.requireEnabled(ChatIdentity.require(authentication));
                        } catch (ChatException error) {
                            if (request.getSession(false) != null) request.getSession(false).invalidate();
                            SecurityContextHolder.clearContext();
                            writeError(response, json, error);
                            return;
                        }
                    }
                    chain.doFilter(request, response);
                }
            }, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper json, ChatException error)
            throws IOException {
        response.setStatus(error.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), ChatError.from(error));
    }
}
