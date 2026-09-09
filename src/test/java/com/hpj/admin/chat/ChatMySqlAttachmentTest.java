package com.hpj.admin.chat;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfSystemProperty(named = "chat.mysql.test.url", matches = "jdbc:mysql://[^/]+/")
@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/chat-test.yml")
class ChatMySqlAttachmentTest extends ChatAttachmentIntegrationTest {
    private static final String schema = "chat_test_" + UUID.randomUUID().toString().replace("-", "");
    private static String server, user, password;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        server = System.getProperty("chat.mysql.test.url");
        user = System.getProperty("chat.mysql.test.user", "root"); password = System.getProperty("chat.mysql.test.password", "");
        try (var connection = DriverManager.getConnection(server, user, password); var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        registry.add("spring.datasource.url", () -> server + schema);
        registry.add("spring.datasource.username", () -> user); registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }
    @AfterAll static void cleanup() throws Exception {
        if (server == null) return;
        try (var connection = DriverManager.getConnection(server, user, password); var sql = connection.createStatement()) {
            sql.execute("DROP DATABASE " + schema);
        }
    }
}
