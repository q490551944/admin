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

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatRoomService {
    private final ChatConversationMapper conversations;
    private final ChatReadMapper reads;
    private final ChatAuditEventMapper audits;
    private final ApplicationEventPublisher events;

    public ChatRoomService(ChatConversationMapper conversations, ChatReadMapper reads,
                           ChatAuditEventMapper audits, ApplicationEventPublisher events) {
        this.conversations = conversations;
        this.reads = reads;
        this.audits = audits;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<ConversationView> visible(long userId) {
        return reads.visibleConversations(userId);
    }

    @Transactional
    public ConversationView create(long userId, String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        String normalized = name.toLowerCase(Locale.ROOT);
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
            conversations.insert(room);
        } catch (DuplicateKeyException error) {
            throw new ChatException(409, "ROOM_NAME_EXISTS", "有效讨论组中已存在同名房间");
        }
        audit(AuditEventType.ROOM_CREATED, userId, room.getId());
        return reads.conversationView(room.getId(), userId);
    }

    @Transactional
    public void dissolve(long userId, long id) {
        ChatConversation room = reads.lockConversation(id);
        if (room == null || room.getType() != ConversationType.PUBLIC_ROOM) throw ChatException.notFound();
        if (!Long.valueOf(userId).equals(room.getOwnerId())) throw ChatException.forbidden();
        if (room.getStatus() == ConversationStatus.DISSOLVED) return;
        if (conversations.markDissolved(id, userId, room.getVersion(), LocalDateTime.now()) != 1) {
            throw new ChatException(409, "ROOM_CHANGED", "房间状态已变更，请刷新后重试");
        }
        audit(AuditEventType.ROOM_DISSOLVED, userId, id);
        events.publishEvent(new ConversationDissolved(id));
    }

    @Transactional(readOnly = true)
    public void requireAccess(long userId, long conversationId) {
        ChatConversation conversation = conversations.selectById(conversationId);
        if (conversation == null || conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw ChatException.notFound();
        }
        if (conversation.getType() == ConversationType.DIRECT_MESSAGE
                && reads.participantCount(conversationId, userId) == 0) throw ChatException.forbidden();
    }

    private void audit(AuditEventType type, long actorId, long conversationId) {
        ChatAuditEvent event = new ChatAuditEvent();
        event.setEventType(type);
        event.setActorUserId(actorId);
        event.setConversationId(conversationId);
        event.setCreatedAt(LocalDateTime.now());
        audits.insert(event);
    }
}
