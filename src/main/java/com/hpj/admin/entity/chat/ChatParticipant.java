package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 会话参与关系；活动私聊的访问校验依赖当前用户未被软删除的参与记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_participant")
public class ChatParticipant extends BaseEntity {

    private Long conversationId;
    private Long userId;
    private LocalDateTime createdAt;
    /** 非空表示参与关系已失效；查询权限时必须显式排除此类记录。 */
    private LocalDateTime deletedAt;
}
