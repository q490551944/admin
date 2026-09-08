package com.hpj.admin.common.config.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** chat 配置绑定：功能开关、认证兼容选项及预留的附件存储参数。 */
@Data
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private boolean enabled;
    private final Security security = new Security();
    private final Attachment attachment = new Attachment();

    @Data
    public static class Security {
        // 仅约束 WebSocket 来源：空列表表示只允许同源，不支持通配符，也不启用 REST 跨域。
        private List<String> allowedOrigins = new ArrayList<>();
        // 历史 DES 密码迁移开关；开启后成功登录会升级为 BCrypt。
        private boolean allowLegacyDesPasswords;
        // 旧接口此前不要求 Spring Security 角色，因此保护选项需由部署显式开启。
        private boolean protectLegacyEndpoints;
    }

    /** 附件模块的配置预留，配置存在本身不代表已实现上传或清理任务。 */
    @Data
    public static class Attachment {
        private String endpoint = "http://localhost:9000";
        private String bucket = "chat-attachments";
        private String accessKey = "";
        private String secretKey = "";
        private boolean secure;
        /** 尚未关联消息的附件保留时长，供后续清理流程使用。 */
        private Duration unattachedRetention = Duration.ofHours(24);
    }
}
