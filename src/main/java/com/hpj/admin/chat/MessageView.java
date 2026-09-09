package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonFormat;
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
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;
    private String status;

    @JsonIgnore private String attachmentFilename;
    @JsonIgnore private String attachmentContentType;
    @JsonIgnore private Long attachmentSizeBytes;
    @JsonIgnore private Integer attachmentWidth;
    @JsonIgnore private Integer attachmentHeight;

    public AttachmentView getAttachment() {
        return attachmentId == null ? null : AttachmentView.of(attachmentId, attachmentFilename,
                attachmentContentType, attachmentSizeBytes, attachmentWidth, attachmentHeight, "ATTACHED");
    }

    public String getCursor() {
        return new MessageCursor(conversationId, createdAt, id).encode();
    }
}
