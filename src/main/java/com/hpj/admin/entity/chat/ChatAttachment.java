package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_attachment")
public class ChatAttachment extends BaseEntity {

    private Long conversationId;
    private Long messageId;
    private Long uploaderId;
    private String storageBucket;
    private String storageObjectKey;
    private String origFilename;
    private String contentType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private String sha256;
    private AttachmentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
}
