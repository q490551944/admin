package com.hpj.admin.monitor;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.hpj.admin.chat.security.ChatAccounts;
import com.hpj.admin.chat.security.ChatSecurityConfiguration;
import com.hpj.admin.chat.security.LegacySecurityConfiguration;
import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.controller.chat.ChatSessionController;
import com.hpj.admin.monitor.api.MonitoringSessionController;
import com.hpj.admin.monitor.security.MonitoringAccounts;
import com.hpj.admin.monitor.security.MonitoringSecurityConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Actual production filter chains and employee SQL, without unrelated middleware clients. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MonitoringProperties.class, ChatProperties.class})
@MapperScan("com.hpj.admin.mapper.chat")
@Import({MonitoringSecurityConfiguration.class, MonitoringAccounts.class, MonitoringSessionController.class,
        ChatSecurityConfiguration.class, ChatAccounts.class, ChatSessionController.class,
        LegacySecurityConfiguration.class, MonitoringSecurityTestApplication.Probe.class})
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class, FlywayAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class, JacksonAutoConfiguration.class,
        ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class,
        SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
public class MonitoringSecurityTestApplication {
    @RestController
    static class Probe {
        @GetMapping("/api/monitor/v1/security-probe") String read() { return "monitor"; }
        @PostMapping("/api/monitor/v1/security-probe") String write() { return "monitor"; }
        @GetMapping("/sys/users/security-probe") String employees() { return "legacy-employees"; }
        @PostMapping("/sys/users/security-probe") String updateEmployees() { return "legacy-employees"; }
        @PostMapping("/groovy/script-executions") String script() { return "legacy-script"; }
    }
}
