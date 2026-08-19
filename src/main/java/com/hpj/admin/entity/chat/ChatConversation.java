package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_conversation")
public class ChatConversation extends BaseEntity {

    private ConversationType type;
    private String name;
    private String normalizedName;
    private String directKey;
    private Long ownerId;
    private ConversationStatus status;
    private Long lastMessageId;
    private LocalDateTime lastActivityAt;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime dissolvedAt;
}
