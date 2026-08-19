package com.hpj.admin.common.config.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private boolean enabled;
    private final Attachment attachment = new Attachment();

    @Data
    public static class Attachment {
        private String endpoint = "http://localhost:9000";
        private String bucket = "chat-attachments";
        private String accessKey = "";
        private String secretKey = "";
        private boolean secure;
        private Duration unattachedRetention = Duration.ofHours(24);
    }
}
