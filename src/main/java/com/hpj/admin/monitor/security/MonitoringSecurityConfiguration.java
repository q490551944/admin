package com.hpj.admin.monitor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.chat.security.ChatPasswordEncoder;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Always claims the monitoring API, including when the feature is disabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MonitoringProperties.class, ChatProperties.class})
public class MonitoringSecurityConfiguration {
    public static final String CONTEXT_KEY = "MONITOR_SECURITY_CONTEXT";
    public static final String CSRF_KEY = "MONITOR_CSRF_TOKEN";
    private static final String BASE = "/api/monitor/v1";
    private static final AntPathRequestMatcher CSRF_ENDPOINT = new AntPathRequestMatcher(BASE + "/csrf-token", "GET");
    private static final AntPathRequestMatcher LOGIN_ENDPOINT = new AntPathRequestMatcher(BASE + "/sessions", "POST");

    @Bean
    @Order(0)
    public SecurityFilterChain monitoringSecurityFilterChain(HttpSecurity http, MonitoringProperties properties,
                                                             ChatProperties chat, MonitoringAccounts accounts,
                                                             ObjectMapper json) throws Exception {
        HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
        contexts.setSpringSecurityContextKey(CONTEXT_KEY);
        HttpSessionCsrfTokenRepository csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setSessionAttributeName(CSRF_KEY);

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(accounts::loadUserByUsername);
        provider.setUserDetailsPasswordService(accounts::updatePassword);
        provider.setPasswordEncoder(new ChatPasswordEncoder(chat.getSecurity().isAllowLegacyDesPasswords()));
        // Neither provider nor encoder is a bean or parent manager shared with the chat chain.
        ProviderManager authentication = new ProviderManager(provider);

        http.securityMatcher("/api/monitor", "/api/monitor/**")
                .authenticationManager(authentication)
                .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        if (!properties.isEnabled()) {
                            write(response, json, 404, MonitoringError.MONITOR_DISABLED);
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, SecurityContextHolderFilter.class)
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, BASE + "/csrf-token").permitAll()
                        .requestMatchers(HttpMethod.POST, BASE + "/sessions").permitAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                write(response, json, 401, MonitoringError.SESSION_EXPIRED))
                        .accessDeniedHandler((request, response, error) -> {
                            if (error instanceof CsrfException) write(response, json, 403, MonitoringError.CSRF_INVALID);
                            else write(response, json, 403, MonitoringError.ACCESS_DENIED);
                        }))
                .formLogin(login -> login.loginProcessingUrl(BASE + "/sessions")
                        .successHandler((request, response, authenticated) -> {
                            MonitoringPrincipal principal = (MonitoringPrincipal) authenticated.getPrincipal();
                            response.setStatus(HttpServletResponse.SC_CREATED);
                            response.setHeader("Location", request.getContextPath() + BASE + "/sessions/current");
                            jsonResponse(response);
                            json.writeValue(response.getOutputStream(), principal.publicView());
                        })
                        .failureHandler((request, response, failure) -> {
                            if (failure instanceof AuthenticationServiceException) {
                                write(response, json, 503, MonitoringError.IDENTITY_UNAVAILABLE);
                            } else {
                                write(response, json, 401, MonitoringError.INVALID_CREDENTIALS);
                            }
                        }))
                .logout(logout -> logout.logoutRequestMatcher(new AntPathRequestMatcher(BASE + "/sessions/current", "DELETE"))
                        // The same container session can also contain a cookie-based chat identity.
                        .invalidateHttpSession(false).clearAuthentication(true)
                        .addLogoutHandler((request, response, authenticated) -> removeMonitorAttributes(request))
                        .logoutSuccessHandler((request, response, authenticated) -> {
                            response.setHeader("Cache-Control", "no-store");
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        }))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .addFilterAfter(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        // Authentication ceremonies remain possible for denied users and during an identity outage.
                        if (CSRF_ENDPOINT.matches(request) || LOGIN_ENDPOINT.matches(request)) {
                            chain.doFilter(request, response);
                            return;
                        }
                        Authentication current = SecurityContextHolder.getContext().getAuthentication();
                        if (current == null || current instanceof AnonymousAuthenticationToken || !current.isAuthenticated()) {
                            chain.doFilter(request, response);
                            return;
                        }
                        if (!(current.getPrincipal() instanceof MonitoringPrincipal principal)) {
                            expireMonitoringIdentity(request);
                            write(response, json, 401, MonitoringError.SESSION_EXPIRED);
                            return;
                        }
                        try {
                            accounts.requireEnabled(principal);
                        } catch (AuthenticationServiceException failure) {
                            write(response, json, 503, MonitoringError.IDENTITY_UNAVAILABLE);
                            return;
                        } catch (AuthenticationException invalid) {
                            expireMonitoringIdentity(request);
                            write(response, json, 401, MonitoringError.SESSION_EXPIRED);
                            return;
                        }
                        if (!properties.getAllowedUserIds().contains(principal.getUserId())) {
                            write(response, json, 403, MonitoringError.ACCESS_DENIED);
                            return;
                        }
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static void expireMonitoringIdentity(HttpServletRequest request) {
        removeMonitorAttributes(request);
        SecurityContextHolder.clearContext();
    }

    private static void removeMonitorAttributes(HttpServletRequest request) {
        try {
            var session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(CONTEXT_KEY);
                session.removeAttribute(CSRF_KEY);
            }
        } catch (IllegalStateException alreadyInvalidated) {
            // A concurrent container/chat logout already removed the session.
        }
    }

    private static void jsonResponse(HttpServletResponse response) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
    }

    private static void write(HttpServletResponse response, ObjectMapper json, int status, MonitoringError error)
            throws IOException {
        response.setStatus(status);
        jsonResponse(response);
        json.writeValue(response.getOutputStream(), error);
    }
}
