package com.hpj.admin.chat;

import com.hpj.admin.common.config.chat.ChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPropertiesTest {

    @Test
    void bindsFeatureFlagAndAttachmentStorageConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("chat-test", Map.of(
                "chat.enabled", "true",
                "chat.attachment.endpoint", "https://minio.internal",
                "chat.attachment.bucket", "company-chat",
                "chat.attachment.access-key", "access",
                "chat.attachment.secret-key", "secret",
                "chat.attachment.secure", "true",
                "chat.attachment.unattached-retention", "48h"
        )));

        ChatProperties properties = Binder.get(environment)
                .bind("chat", Bindable.of(ChatProperties.class))
                .orElseThrow(IllegalStateException::new);

        assertTrue(properties.isEnabled());
        assertEquals("https://minio.internal", properties.getAttachment().getEndpoint());
        assertEquals("company-chat", properties.getAttachment().getBucket());
        assertEquals("access", properties.getAttachment().getAccessKey());
        assertEquals("secret", properties.getAttachment().getSecretKey());
        assertTrue(properties.getAttachment().isSecure());
        assertEquals(Duration.ofHours(48), properties.getAttachment().getUnattachedRetention());
    }
}
