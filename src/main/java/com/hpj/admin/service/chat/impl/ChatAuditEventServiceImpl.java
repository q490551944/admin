package com.hpj.admin.service.chat.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpj.admin.entity.chat.ChatAuditEvent;
import com.hpj.admin.mapper.chat.ChatAuditEventMapper;
import com.hpj.admin.service.chat.ChatAuditEventService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAuditEventServiceImpl extends ServiceImpl<ChatAuditEventMapper, ChatAuditEvent>
        implements ChatAuditEventService {
}
