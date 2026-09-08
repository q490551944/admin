package com.hpj.admin.service.chat;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hpj.admin.entity.chat.ChatAuditEvent;

/** 审计实体的通用 CRUD 接口；业务事件写入时需由调用方保证与业务变更同事务。 */
public interface ChatAuditEventService extends IService<ChatAuditEvent> {
}
