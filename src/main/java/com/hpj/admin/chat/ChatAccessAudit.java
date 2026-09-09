package com.hpj.admin.chat;

import com.hpj.admin.entity.chat.*;
import com.hpj.admin.mapper.chat.ChatAuditEventMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.*;
import java.time.LocalDateTime;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAccessAudit {
    private final ApplicationEventPublisher events;
    private final ChatAuditEventMapper audits;
    public ChatAccessAudit(ApplicationEventPublisher events, ChatAuditEventMapper audits) {
        this.events = events;
        this.audits = audits;
    }

    public record Denied(long actorId, long conversationId) {}

    public void denied(long actorId, long conversationId) {
        events.publishEvent(new Denied(actorId, conversationId));
    }

    // 先等拒绝请求的事务释放锁，再独立提交审计。否则外键检查会等待原事务持有的会话/账号锁。
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Denied denied) {
        ChatAuditEvent audit = new ChatAuditEvent();
        audit.setEventType(AuditEventType.AUTH_DENIED);
        audit.setActorUserId(denied.actorId());
        audit.setConversationId(denied.conversationId());
        audit.setCreatedAt(LocalDateTime.now());
        audits.insert(audit);
    }
}
