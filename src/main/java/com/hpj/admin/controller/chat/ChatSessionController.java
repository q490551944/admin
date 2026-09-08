package com.hpj.admin.controller.chat;

import com.hpj.admin.chat.security.ChatIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 查询会话身份和 CSRF 凭证；登录、退出由 ChatSecurityConfiguration 的过滤器处理。 */
@RestController
@RequestMapping("/api/chat/v1")
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatSessionController {
    /** 获取绑定当前 Session 的凭证，供 HTTP 写请求和 STOMP CONNECT 使用。 */
    @GetMapping("/csrf-token")
    public Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("headerName", csrf.getHeaderName(), "parameterName", csrf.getParameterName(), "token", csrf.getToken());
    }

    /** 仅返回可公开的身份字段，不暴露密码或安全上下文。 */
    @GetMapping("/sessions/current")
    public Map<String, String> me(Authentication authentication) {
        return ChatIdentity.require(authentication).publicView();
    }
}
