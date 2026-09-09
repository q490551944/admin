package com.hpj.admin.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAttachmentCleanup {
    private final ChatAttachmentService service;
    public ChatAttachmentCleanup(ChatAttachmentService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${chat.attachment.cleanup-interval-ms:60000}", initialDelayString = "${chat.attachment.cleanup-interval-ms:60000}")
    public void clean() { service.cleanupExpired(); }
}
