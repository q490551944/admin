package com.hpj.admin.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpj.admin.entity.chat.ChatAuditEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatAuditEventMapper extends BaseMapper<ChatAuditEvent> {
}
