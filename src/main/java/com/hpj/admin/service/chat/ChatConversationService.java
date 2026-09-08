package com.hpj.admin.service.chat;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hpj.admin.entity.chat.ChatConversation;

/** 会话实体的通用 CRUD；房间权限、名称校验和审计事务由 ChatRoomService 统一处理。 */
public interface ChatConversationService extends IService<ChatConversation> {
}
