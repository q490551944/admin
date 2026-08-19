package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_audit_event")
public class ChatAuditEvent extends BaseEntity {

    private AuditEventType eventType;
    private Long actorUserId;
    private Long conversationId;
    private Long messageId;
    private String payloadJson;
    private LocalDateTime createdAt;
}
