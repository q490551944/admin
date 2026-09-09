package com.hpj.admin.chat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 在显式指定的独立 MySQL 上复用消息契约测试，仅创建/删除自己生成的临时数据库。 */
@EnabledIfSystemProperty(named = "chat.mysql.test.url", matches = "jdbc:mysql://[^/]+/")
@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/chat-test.yml")
class ChatMySqlMessagingTest extends ChatMessagingIntegrationTest {
    private static final String schema = "chat_test_" + UUID.randomUUID().toString().replace("-", "");
    private static String server;
    private static String user;
    private static String password;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        server = System.getProperty("chat.mysql.test.url");
        user = System.getProperty("chat.mysql.test.user", "root");
        password = System.getProperty("chat.mysql.test.password", "");
        try (var connection = DriverManager.getConnection(server, user, password); var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        registry.add("spring.datasource.url", () -> server + schema);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @AfterAll static void cleanup() throws Exception {
        if (server == null) return;
        try (var connection = DriverManager.getConnection(server, user, password); var sql = connection.createStatement()) {
            sql.execute("DROP DATABASE " + schema);
        }
    }

    @Test void storesFiveThousandSupplementaryCharacters() {
        String body = "😀".repeat(5000);
        MessageView saved = send(1, room, "full-unicode", body);
        assertThat(saved.getBody()).isEqualTo(body);
        assertThat(messages.history(1, room, null, null, 30).items().get(0).getBody()).isEqualTo(body);
    }
}
