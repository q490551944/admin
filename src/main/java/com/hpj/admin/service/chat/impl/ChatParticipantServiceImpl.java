package com.hpj.admin.service.chat.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpj.admin.entity.chat.ChatParticipant;
import com.hpj.admin.mapper.chat.ChatParticipantMapper;
import com.hpj.admin.service.chat.ChatParticipantService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatParticipantServiceImpl extends ServiceImpl<ChatParticipantMapper, ChatParticipant>
        implements ChatParticipantService {
}
