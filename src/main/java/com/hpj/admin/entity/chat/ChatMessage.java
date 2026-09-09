package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 消息持久化模型；发送业务由 ChatMessagingService 统一处理事务和幂等。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    private Long conversationId;
    private Long senderId;
    private MessageType messageType;
    /** 客户端请求标识，与 senderId 组成数据库唯一键，用于消息请求去重。 */
    private String clientRequestId;
    /** 文字消息正文；数据库约束要求图片消息的正文为空。 */
    private String body;
    /** 图片消息关联的附件；文字消息必须为空。 */
    private Long attachmentId;
    private MessageStatus status;
    private String contentHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 软删除时间；会话摘要查询排除此类消息。 */
    private LocalDateTime deletedAt;
}
