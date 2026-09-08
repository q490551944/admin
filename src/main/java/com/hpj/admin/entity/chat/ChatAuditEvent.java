package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 聊天审计记录；当前房间创建和解散事件随业务事务写入。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_audit_event")
public class ChatAuditEvent extends BaseEntity {

    private AuditEventType eventType;
    /** 操作人标识；房间业务从服务端认证身份中取得该值。 */
    private Long actorUserId;
    private Long conversationId;
    private Long messageId;
    /** 事件扩展信息的 JSON 文本，未提供额外信息时可为空。 */
    private String payloadJson;
    private LocalDateTime createdAt;
}
