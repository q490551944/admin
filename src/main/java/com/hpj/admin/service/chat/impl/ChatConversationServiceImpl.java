package com.hpj.admin.service.chat.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpj.admin.entity.chat.ChatConversation;
import com.hpj.admin.mapper.chat.ChatConversationMapper;
import com.hpj.admin.service.chat.ChatConversationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation>
        implements ChatConversationService {
}
