package com.hpj.admin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.common.config.monitor.MonitoringProperties;
import com.hpj.admin.common.config.monitor.MonitoringPageConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.SQLTransientConnectionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit browser-test entry point, excluded from the production artifact. It imports the real
 * security chains and employee mapper, binds only to an ephemeral loopback port, and owns a fresh
 * in-memory H2 database. The test controls require a runner-generated token passed only via the
 * environment; neither the token nor authentication material appears in readiness output.
 */
@Configuration(proxyBeanMethods = false)
@Import({MonitoringSecurityTestApplication.class, MonitoringPageConfiguration.class, MonitoringE2eApplication.Fixture.class})
public class MonitoringE2eApplication {
    private static final String PASSWORD = "Monitor-Test-26!";

    public static void main(String[] args) {
        requireControlToken();
        var app = new SpringApplication(MonitoringE2eApplication.class);
        app.setDefaultProperties(Map.of("spring.config.location", "classpath:/chat-test.yml"));
        // These isolation boundaries also take precedence over command-line and environment values:
        // a mistakenly inherited datasource/server setting must never seed a real employee database.
        app.addInitializers(context -> {
            Map<String, Object> isolated = new HashMap<>();
            isolated.put("spring.datasource.url", "jdbc:h2:mem:monitor_e2e_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
            isolated.put("spring.datasource.username", "sa");
            isolated.put("spring.datasource.password", "");
            isolated.put("spring.datasource.driver-class-name", "org.h2.Driver");
            isolated.put("spring.datasource.type", "com.zaxxer.hikari.HikariDataSource");
            isolated.put("spring.datasource.hikari.jdbc-url", isolated.get("spring.datasource.url"));
            isolated.put("spring.datasource.hikari.username", "sa");
            isolated.put("spring.datasource.hikari.password", "");
            isolated.put("spring.datasource.hikari.driver-class-name", "org.h2.Driver");
            isolated.put("spring.flyway.url", isolated.get("spring.datasource.url"));
            isolated.put("spring.flyway.user", "sa");
            isolated.put("spring.flyway.password", "");
            isolated.put("spring.flyway.locations", "classpath:db/migration");
            isolated.put("spring.flyway.enabled", "true");
            isolated.put("server.address", "127.0.0.1");
            isolated.put("server.port", "0");
            isolated.put("server.servlet.context-path", "");
            isolated.put("server.ssl.enabled", "false");
            isolated.put("chat.enabled", "false");
            isolated.put("monitor.enabled", "true");
            isolated.put("monitor.allowed-user-ids[0]", "1");
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("monitor-e2e-isolation", isolated));
        });
        app.run(args);
    }

    @Bean
    IdentityOutage monitorE2eIdentityOutage() { return new IdentityOutage(); }

    private static byte[] requireControlToken() {
        String token = System.getenv("MONITOR_E2E_CONTROL_TOKEN");
        if (token == null || token.length() < 32 || token.length() > 256 || token.isBlank()) {
            throw new IllegalArgumentException("Set MONITOR_E2E_CONTROL_TOKEN to a fresh random test token (32-256 characters)");
        }
        return token.getBytes(StandardCharsets.UTF_8);
    }

    /** Injects a bounded database failure at the real mapper boundary; normal reads still use H2 SQL. */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "update",
                    args = {MappedStatement.class, Object.class})
    })
    static final class IdentityOutage implements Interceptor {
        final AtomicBoolean enabled = new AtomicBoolean();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (enabled.get() && statement.getId().startsWith("com.hpj.admin.mapper.chat.ChatAccountMapper.")) {
                throw new SQLTransientConnectionException("Injected E2E employee database outage");
            }
            return invocation.proceed();
        }
    }

    @RestController
    static final class Fixture {
        private final JdbcTemplate jdbc;
        private final MonitoringProperties properties;
        private final IdentityOutage outage;
        private final ServletWebServerApplicationContext context;
        private final Environment environment;
        private final ObjectMapper json;
        private final byte[] controlToken;
        private final String passwordHash = new BCryptPasswordEncoder().encode(PASSWORD);

        Fixture(JdbcTemplate jdbc, MonitoringProperties properties, IdentityOutage outage,
                ServletWebServerApplicationContext context, Environment environment,
                ObjectMapper json) {
            this.jdbc = jdbc;
            this.properties = properties;
            this.outage = outage;
            this.context = context;
            this.environment = environment;
            this.json = json;
            this.controlToken = requireControlToken();
        }

        @EventListener(ApplicationReadyEvent.class)
        void ready() throws Exception {
            reset();
            String ready = json.writeValueAsString(Map.of("baseURL", "http://127.0.0.1:"
                    + context.getWebServer().getPort(), "chatEnabled", false, "monitorEnabled", true));
            Path output = Path.of(environment.getProperty("monitor.e2e.ready-file", "target/monitor-e2e-ready.json"))
                    .toAbsolutePath();
            Files.createDirectories(output.getParent());
            Path pending = Files.createTempFile(output.getParent(), "monitor-ready-", ".tmp");
            try {
                Files.writeString(pending, ready);
                try {
                    Files.move(pending, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(pending, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(pending);
            }
            System.out.println("MONITOR_E2E_READY " + ready);
        }

        @GetMapping("/__monitor_test/state")
        synchronized ResponseEntity<Map<String, Object>> state(HttpServletRequest request) {
            requireControl(request);
            return response();
        }

        @PostMapping("/__monitor_test/control")
        synchronized ResponseEntity<Map<String, Object>> control(HttpServletRequest request, @RequestBody Control change) {
            requireControl(request);
            if ((change.userId() == null) != (change.enabled() == null)
                    || (change.userId() != null && !List.of(1L, 2L, 3L).contains(change.userId()))
                    || (change.allowedUserIds() != null && change.allowedUserIds().stream()
                    .anyMatch(id -> id == null || !List.of(1L, 2L, 3L).contains(id)))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid fixed-fixture control");
            }
            if (Boolean.TRUE.equals(change.reset())) reset();
            if (change.outage() != null) outage.enabled.set(change.outage());
            if (change.userId() != null) {
                jdbc.update("UPDATE user SET status=? WHERE id=?", change.enabled(), change.userId());
            }
            if (change.allowedUserIds() != null) properties.setAllowedUserIds(List.copyOf(change.allowedUserIds()));
            if (change.monitorEnabled() != null) properties.setEnabled(change.monitorEnabled());
            return response();
        }

        private void requireControl(HttpServletRequest request) {
            String supplied = request.getHeader("X-Monitor-Test-Token");
            if (!"127.0.0.1".equals(request.getRemoteAddr()) || supplied == null || supplied.length() > 256
                    || !MessageDigest.isEqual(controlToken, supplied.getBytes(StandardCharsets.UTF_8))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
        }

        private void reset() {
            outage.enabled.set(false);
            properties.setEnabled(true);
            properties.setAllowedUserIds(List.of(1L));
            jdbc.update("DELETE FROM user");
            jdbc.update("INSERT INTO user(id,username,password,status) VALUES(1,'monitor-allowed',?,TRUE)", passwordHash);
            jdbc.update("INSERT INTO user(id,username,password,status) VALUES(2,'monitor-denied',?,TRUE)", passwordHash);
            jdbc.update("INSERT INTO user(id,username,password,status) VALUES(3,'monitor-disabled',?,FALSE)", passwordHash);
        }

        private ResponseEntity<Map<String, Object>> response() {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of(
                    "outage", outage.enabled.get(), "monitorEnabled", properties.isEnabled(),
                    "allowedUserIds", properties.getAllowedUserIds(), "chatEnabled", false));
        }
    }

    record Control(Boolean reset, Boolean outage, Long userId, Boolean enabled,
                   List<Long> allowedUserIds, Boolean monitorEnabled) { }
}
