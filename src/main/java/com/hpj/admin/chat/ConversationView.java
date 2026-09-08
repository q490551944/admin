package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.hpj.admin.entity.chat.ConversationStatus;
import com.hpj.admin.entity.chat.ConversationType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ConversationView {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private ConversationType type;
    private String name;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;
    private ConversationStatus status;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastMessageId;
    private String lastMessagePreview;
    private LocalDateTime lastActivityAt;
    private LocalDateTime createdAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long peerUserId;
}
