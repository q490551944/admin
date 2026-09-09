package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.hpj.admin.entity.chat.ConversationStatus;
import com.hpj.admin.entity.chat.ConversationType;
import lombok.Data;
import java.time.LocalDateTime;

/** 会话列表的展示投影；Long 标识序列化为字符串，避免 JavaScript 整数精度丢失。 */
@Data
public class ConversationView {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private ConversationType type;
    /** 公共房间名称，或相对于查询用户的私聊对方用户名。 */
    private String name;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;
    private ConversationStatus status;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastMessageId;
    /** 最近有效已发送消息的摘要；图片显示占位文字，无有效消息时为空字符串。 */
    private String lastMessagePreview;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime lastActivityAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;
    /** 查询用户之外的有效参与者 ID；没有对应参与者时为空。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long peerUserId;
}
