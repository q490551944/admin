package com.hpj.admin.chat.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hpj.admin.chat.ChatException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 页面使用独立 Session Header；不携带 Header 的旧客户端仍使用容器 Cookie Session。 */
public class ChatWindowSessions {
    public static final String HEADER = "X-Chat-Session";
    public static final String ATTRIBUTE = ChatWindowSessions.class.getName();
    private final Map<String, Session> stored = new ConcurrentHashMap<>();
    private final MapSessionRepository repository = new MapSessionRepository(stored) {
        @Override public synchronized MapSession createSession() {
            MapSession session = super.createSession();
            super.save(session);
            return session;
        }
        @Override public synchronized void save(MapSession session) {
            // 已注销/轮换的 ID 不能被并发请求持有的旧快照重新写回。
            Session live = stored.get(session.getOriginalId());
            if (live == null) live = stored.get(session.getId());
            if (live != null && !live.isExpired()) super.save(session);
        }
        @Override public synchronized MapSession findById(String id) { return super.findById(id); }
        @Override public synchronized void deleteById(String id) { super.deleteById(id); }
    };
    private final Cache<String, String> tickets = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(10000).build();
    private final SessionRepositoryFilter<MapSession> sessions = new SessionRepositoryFilter<>(repository);

    public ChatWindowSessions(Duration timeout) {
        repository.setDefaultMaxInactiveInterval(timeout);
        sessions.setHttpSessionIdResolver(new HttpSessionIdResolver() {
            @Override public List<String> resolveSessionIds(HttpServletRequest request) {
                return List.of((String) request.getAttribute(ATTRIBUTE));
            }
            @Override public void setSessionId(HttpServletRequest request, HttpServletResponse response, String id) {
                response.setHeader(HEADER, id);
            }
            @Override public void expireSession(HttpServletRequest request, HttpServletResponse response) {
                response.setHeader(HEADER, "");
            }
        });
    }

    public OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                      FilterChain chain) throws ServletException, IOException {
                String id = request.getHeader(HEADER);
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if ("/ws/chat".equals(path) && request.getParameter("ticket") != null) {
                    // 原生 WebSocket 不支持自定义 Header，只接受短期且仅可消费一次的握手票据。
                    id = tickets.asMap().remove(request.getParameter("ticket"));
                    if (id == null) id = "invalid"; // 票据失效时绝不回退到共享 Cookie。
                }
                if (id == null) { chain.doFilter(request, response); return; }
                request.setAttribute(ATTRIBUTE, id);
                sessions.doFilter(request, response, chain);
            }
        };
    }

    public String ticket(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (request.getAttribute(ATTRIBUTE) == null || session == null) throw ChatException.unauthorized();
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, session.getId());
        return ticket;
    }

    /** WebSocket 每次复查仓库，避免复用握手请求中的 Session 快照而漏掉退出/轮换。 */
    public Authentication authentication(String id, long userId) {
        MapSession session = repository.findById(id);
        if (session == null) throw ChatException.unauthorized();
        Object saved = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(saved instanceof SecurityContext context)) throw ChatException.unauthorized();
        Authentication auth = context.getAuthentication();
        if (ChatIdentity.require(auth).getUserId() != userId) throw ChatException.unauthorized();
        return auth;
    }

    public void cleanExpired() { stored.entrySet().removeIf(entry -> entry.getValue().isExpired()); }
}
