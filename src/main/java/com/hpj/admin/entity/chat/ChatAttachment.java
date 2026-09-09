package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 图片上传、消息关联和过期清理共用的存储元数据。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_attachment")
public class ChatAttachment extends BaseEntity {

    private Long conversationId;
    /** 未关联消息时为空；非空值受唯一约束保护，限制一条消息关联一个附件。 */
    private Long messageId;
    private Long uploaderId;
    /** 与 storageObjectKey 共同定位存储对象，数据库约束二者组合唯一。 */
    private String storageBucket;
    private String storageObjectKey;
    private String origFilename;
    private String contentType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    /** 附件内容的 SHA-256 摘要。 */
    private String sha256;
    private AttachmentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 附件到期时间，供后续清理流程结合状态判断是否可回收。 */
    private LocalDateTime expiresAt;
}
