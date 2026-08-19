package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_participant")
public class ChatParticipant extends BaseEntity {

    private Long conversationId;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
}
