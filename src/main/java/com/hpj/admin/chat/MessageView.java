package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.hpj.admin.entity.chat.MessageType;
import lombok.Data;
import java.time.LocalDateTime;

/** 历史、广播和 ACK 共用的消息投影，不暴露存储路径或内部状态。 */
@Data
public class MessageView {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long senderId;
    private String senderName;
    private MessageType type;
    private String clientRequestId;
    private String body;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long attachmentId;
    private LocalDateTime createdAt;
    private String status;

    public String getCursor() {
        return new MessageCursor(conversationId, createdAt, id).encode();
    }
}
