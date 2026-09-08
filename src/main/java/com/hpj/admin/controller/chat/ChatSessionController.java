package com.hpj.admin.controller.chat;

import com.hpj.admin.chat.security.ChatIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/chat/v1")
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatSessionController {
    @GetMapping("/csrf-token")
    public Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("headerName", csrf.getHeaderName(), "parameterName", csrf.getParameterName(), "token", csrf.getToken());
    }

    @GetMapping("/sessions/current")
    public Map<String, String> me(Authentication authentication) {
        return ChatIdentity.require(authentication).publicView();
    }
}
