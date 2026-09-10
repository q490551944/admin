package com.hpj.admin.monitor.api;

import com.hpj.admin.monitor.security.MonitoringPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Only public identity fields leave this controller; login and logout are handled by the monitor chain. */
@RestController
@RequestMapping("/api/monitor/v1")
public class MonitoringSessionController {
    @GetMapping("/csrf-token")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName(), "token", token.getToken());
    }

    @GetMapping("/sessions/current")
    public Map<String, String> current(Authentication authentication) {
        return ((MonitoringPrincipal) authentication.getPrincipal()).publicView();
    }
}
