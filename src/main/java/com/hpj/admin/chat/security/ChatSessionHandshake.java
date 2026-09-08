package com.hpj.admin.chat.security;

import com.hpj.admin.chat.ChatException;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;

/** 将已认证 HTTP Session 绑定到 WebSocket，供后续帧处理及出站投递重新验证。 */
public class ChatSessionHandshake implements HandshakeInterceptor {
    public static final String SESSION = "chat.httpSession";
    public static final String SESSION_ID = "chat.httpSessionId";
    public static final String USER_ID = "chat.userId";
    public static final String CSRF = "chat.csrf";
    private final ChatAccounts accounts;

    public ChatSessionHandshake(ChatAccounts accounts) { this.accounts = accounts; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servlet)) return false;
        var http = servlet.getServletRequest();
        HttpSession session = http.getSession(false);
        if (session == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            var authentication = http.getUserPrincipal() instanceof Authentication auth ? auth : null;
            ChatPrincipal principal = ChatIdentity.require(authentication);
            accounts.requireEnabled(principal);
            CsrfToken csrf = new HttpSessionCsrfTokenRepository().loadToken(http);
            if (csrf == null) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            // 同时保存会话引用和握手时的身份快照，以识别退出、Session ID 轮换及账号切换。
            attributes.put(SESSION, session);
            attributes.put(SESSION_ID, session.getId());
            attributes.put(USER_ID, principal.getUserId());
            attributes.put(CSRF, csrf);
            return true;
        } catch (ChatException | IllegalStateException error) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    /** 从仍有效的 HTTP Session 读取认证信息，不复用握手时缓存的 Authentication。 */
    public static Authentication authenticatedSession(Map<String, Object> attributes) {
        try {
            if (attributes == null || !(attributes.get(SESSION) instanceof HttpSession session)) {
                throw ChatException.unauthorized();
            }
            if (!session.getId().equals(attributes.get(SESSION_ID))) throw ChatException.unauthorized();
            int timeout = session.getMaxInactiveInterval();
            // WebSocket 存活不代表 HTTP Session 仍有效，需显式核对其空闲超时。
            if (timeout > 0 && System.currentTimeMillis() - session.getLastAccessedTime() >= timeout * 1000L) {
                throw ChatException.unauthorized();
            }
            Object saved = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(saved instanceof SecurityContext context)) throw ChatException.unauthorized();
            var authentication = context.getAuthentication();
            ChatPrincipal principal = ChatIdentity.require(authentication);
            if (!Long.valueOf(principal.getUserId()).equals(attributes.get(USER_ID))) throw ChatException.unauthorized();
            return authentication;
        } catch (IllegalStateException error) {
            // 容器在 Session 销毁后访问其属性会抛此异常，统一转换为登录失效。
            throw ChatException.unauthorized();
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {}
}
