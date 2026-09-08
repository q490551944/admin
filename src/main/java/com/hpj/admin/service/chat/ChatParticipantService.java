package com.hpj.admin.service.chat;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hpj.admin.entity.chat.ChatParticipant;

/** 参与关系的通用 CRUD；私聊访问校验仍需由业务层检查有效参与记录。 */
public interface ChatParticipantService extends IService<ChatParticipant> {
}
