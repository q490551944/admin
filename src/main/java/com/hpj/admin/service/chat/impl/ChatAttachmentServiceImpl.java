package com.hpj.admin.service.chat.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpj.admin.entity.chat.ChatAttachment;
import com.hpj.admin.mapper.chat.ChatAttachmentMapper;
import com.hpj.admin.service.chat.ChatAttachmentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAttachmentServiceImpl extends ServiceImpl<ChatAttachmentMapper, ChatAttachment>
        implements ChatAttachmentService {
}
