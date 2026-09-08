package com.hpj.admin.service.chat;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hpj.admin.entity.chat.ChatMessage;

/** 消息实体的通用 CRUD 接口，消息发送、幂等处理和 ACK 业务仍待实现。 */
public interface ChatMessageService extends IService<ChatMessage> {
}
