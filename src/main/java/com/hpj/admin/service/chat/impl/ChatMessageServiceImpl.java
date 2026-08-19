package com.hpj.admin.service.chat.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpj.admin.entity.chat.ChatMessage;
import com.hpj.admin.mapper.chat.ChatMessageMapper;
import com.hpj.admin.service.chat.ChatMessageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage>
        implements ChatMessageService {
}
