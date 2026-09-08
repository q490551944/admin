package com.hpj.admin.common.config.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private boolean enabled;
    private final Security security = new Security();
    private final Attachment attachment = new Attachment();

    @Data
    public static class Security {
        // Empty means same-origin only. Wildcards are deliberately not supported.
        private List<String> allowedOrigins = new ArrayList<>();
        private boolean allowLegacyDesPasswords;
        // Opt-in because the pre-existing endpoints did not require Spring Security roles.
        private boolean protectLegacyEndpoints;
    }

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
