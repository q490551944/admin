package com.hpj.admin.chat;

import com.hpj.admin.entity.chat.*;
import com.hpj.admin.mapper.chat.ChatAuditEventMapper;
import com.hpj.admin.mapper.chat.ChatConversationMapper;
import com.hpj.admin.mapper.chat.ChatReadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 房间业务入口：统一处理可见范围、房主权限，以及房间变更和审计的事务一致性。
 * 通用的实体 CRUD 服务不包含这些业务约束。
 */
@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatRoomService {
    private final ChatConversationMapper conversations;
    private final ChatReadMapper reads;
    private final ChatAuditEventMapper audits;
    private final ApplicationEventPublisher events;
    private final ChatAccessAudit accessAudit;

    public ChatRoomService(ChatConversationMapper conversations, ChatReadMapper reads,
                           ChatAuditEventMapper audits, ApplicationEventPublisher events, ChatAccessAudit accessAudit) {
        this.conversations = conversations;
        this.reads = reads;
        this.audits = audits;
        this.events = events;
        this.accessAudit = accessAudit;
    }

    /** 查询活动公共房间及当前用户仍参与的私聊，按最近活跃时间倒序返回。 */
    @Transactional(readOnly = true)
    public List<ConversationView> visible(long userId) {
        return reads.visibleConversations(userId);
    }

    /** 创建公共房间；房间记录与创建审计在同一事务中提交。 */
    @Transactional
    public ConversationView create(long userId, String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        // 固定大小写归一化规则，避免服务器语言环境改变同名判断。
        String normalized = name.toLowerCase(Locale.ROOT);
        // 按 Unicode 代码点计数，并检查小写转换后的长度，兼顾补充字符与归一化扩展。
        if (name.codePointCount(0, name.length()) < 2 || name.codePointCount(0, name.length()) > 50
                || normalized.codePointCount(0, normalized.length()) > 50) {
            throw new ChatException(422, "INVALID_ROOM_NAME", "房间名称去除首尾空格后须为 2–50 个字符");
        }
        ChatConversation room = new ChatConversation();
        room.setType(ConversationType.PUBLIC_ROOM);
        room.setName(name);
        room.setNormalizedName(normalized);
        room.setOwnerId(userId);
        room.setStatus(ConversationStatus.ACTIVE);
        room.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        room.setCreatedAt(now);
        room.setUpdatedAt(now);
        room.setLastActivityAt(now);
        try {
            // 由数据库活动房间名称唯一索引裁决并发创建，避免先查后写的竞争窗口。
            conversations.insert(room);
        } catch (DuplicateKeyException error) {
            throw new ChatException(409, "ROOM_NAME_EXISTS", "有效讨论组中已存在同名房间");
        }
        audit(AuditEventType.ROOM_CREATED, userId, room.getId());
        return reads.conversationView(room.getId(), userId);
    }

    /** 房主逻辑解散公共房间；重复请求成功返回，不重复写审计或发送事件。 */
    @Transactional
    public void dissolve(long userId, long id) {
        // 行锁串行化同一房间的解散请求，权限检查必须先于幂等返回。
        ChatConversation room = reads.lockConversation(id);
        if (room == null || room.getType() != ConversationType.PUBLIC_ROOM) throw ChatException.notFound();
        if (!Long.valueOf(userId).equals(room.getOwnerId())) throw ChatException.forbidden();
        if (room.getStatus() == ConversationStatus.DISSOLVED) return;
        if (conversations.markDissolved(id, userId, room.getVersion(), LocalDateTime.now()) != 1) {
            throw new ChatException(409, "ROOM_CHANGED", "房间状态已变更，请刷新后重试");
        }
        audit(AuditEventType.ROOM_DISSOLVED, userId, id);
        // 这里只发布事务内事件，RoomEventPublisher 在事务提交后才广播。
        events.publishEvent(new ConversationDissolved(id));
    }

    /** 校验活动会话访问权：公共房间对已认证用户可见，私聊要求有效参与记录。 */
    @Transactional(readOnly = true)
    public void requireAccess(long userId, long conversationId) {
        ChatConversation conversation = conversations.selectById(conversationId);
        if (conversation == null || conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw ChatException.notFound();
        }
        if (conversation.getType() == ConversationType.DIRECT_MESSAGE
                && reads.participantCount(conversationId, userId) == 0) {
            accessAudit.denied(userId, conversationId);
            throw ChatException.forbidden();
        }
    }

    /** 加入调用方事务，确保业务变更失败时审计也一并回滚。 */
    private void audit(AuditEventType type, long actorId, long conversationId) {
        ChatAuditEvent event = new ChatAuditEvent();
        event.setEventType(type);
        event.setActorUserId(actorId);
        event.setConversationId(conversationId);
        event.setCreatedAt(LocalDateTime.now());
        audits.insert(event);
    }
}
