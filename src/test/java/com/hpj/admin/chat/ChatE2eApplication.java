package com.hpj.admin.chat;

import io.minio.*;
import com.hpj.admin.common.config.chat.ChatProperties;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit test entry point; this class and its accounts are absent from the production artifact. */
@Configuration(proxyBeanMethods = false)
@Import({ChatTestApplication.class, ChatAttachmentCleanup.class})
public class ChatE2eApplication {
    public static void main(String[] args) {
        String endpoint = System.getenv("CHAT_E2E_MINIO_ENDPOINT");
        String access = System.getenv("CHAT_E2E_MINIO_ACCESS_KEY");
        String secret = System.getenv("CHAT_E2E_MINIO_SECRET_KEY");
        if (endpoint == null || access == null || secret == null)
            throw new IllegalArgumentException("Set CHAT_E2E_MINIO_ENDPOINT, CHAT_E2E_MINIO_ACCESS_KEY and CHAT_E2E_MINIO_SECRET_KEY for an isolated test server");
        var app = new SpringApplication(ChatE2eApplication.class);
        app.setDefaultProperties(Map.of("spring.config.location", "classpath:/chat-test.yml",
                "spring.datasource.url", "jdbc:h2:mem:chat_e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
                "server.address", "127.0.0.1", "server.port", "0", "chat.attachment.endpoint", endpoint,
                "chat.attachment.access-key", access, "chat.attachment.secret-key", secret,
                "chat.attachment.bucket", "chat-e2e-" + UUID.randomUUID()));
        app.run(args);
    }

    @Bean Fixture fixture(JdbcTemplate jdbc, PasswordEncoder passwords, ChatProperties properties,
            ServletWebServerApplicationContext context, Environment environment) {
        return new Fixture(jdbc, passwords, properties, context, environment);
    }

    static class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final PasswordEncoder passwords;
        final ChatProperties properties;
        final ServletWebServerApplicationContext context;
        final Environment environment;
        MinioClient client;
        String ownedBucket;
        Fixture(JdbcTemplate jdbc, PasswordEncoder passwords, ChatProperties properties,
                ServletWebServerApplicationContext context, Environment environment) {
            this.jdbc = jdbc; this.passwords = passwords; this.properties = properties;
            this.context = context; this.environment = environment;
        }

        @EventListener(ApplicationReadyEvent.class)
        void ready() throws Exception {
            var config = properties.getAttachment();
            if (!config.getBucket().startsWith("chat-e2e-")) throw new IllegalArgumentException("Only ephemeral E2E buckets are permitted");
            client = MinioClient.builder().endpoint(config.getEndpoint()).credentials(config.getAccessKey(), config.getSecretKey()).build();
            client.makeBucket(MakeBucketArgs.builder().bucket(config.getBucket()).build());
            ownedBucket = config.getBucket();
            for (int i = 1; i <= 103; i++) {
                String name = i <= 3 ? List.of("alice", "bob", "carol").get(i - 1) : "load" + i;
                jdbc.update("INSERT INTO user(id,username,password,status) VALUES(?,?,?,TRUE)", i, name, passwords.encode("chat-e2e-only"));
            }
            String readyFile = environment.getProperty("chat.e2e.ready-file", "target/chat-e2e-ready.json");
            Path output = Path.of(readyFile); Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, "{\"baseURL\":\"http://127.0.0.1:" + context.getWebServer().getPort()
                    + "\",\"bucket\":\"" + ownedBucket + "\"}");
        }

        @Override public void close() throws Exception {
            if (ownedBucket == null) return;
            for (var item : client.listObjects(ListObjectsArgs.builder().bucket(ownedBucket).recursive(true).build()))
                client.removeObject(RemoveObjectArgs.builder().bucket(ownedBucket).object(item.get().objectName()).build());
            client.removeBucket(RemoveBucketArgs.builder().bucket(ownedBucket).build());
        }
    }
}
