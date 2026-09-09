package com.hpj.admin.chat;

import com.hpj.admin.entity.chat.*;
import com.hpj.admin.mapper.chat.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatMessagingService {
    private final ChatMessageMapper messages;
    private final ChatReadMapper reads;
    private final ChatConversationMapper conversations;
    private final ChatAuditEventMapper audits;
    private final ChatRoomService rooms;
    private final ApplicationEventPublisher events;
    private final ChatAttachmentService attachments;
    private final ChatMetrics metrics;

    public ChatMessagingService(ChatMessageMapper messages, ChatReadMapper reads, ChatConversationMapper conversations,
            ChatAuditEventMapper audits, ChatRoomService rooms, ApplicationEventPublisher events, ChatAttachmentService attachments, ChatMetrics metrics) {
        this.messages = messages;
        this.reads = reads;
        this.conversations = conversations;
        this.audits = audits;
        this.rooms = rooms;
        this.events = events;
        this.attachments = attachments;
        this.metrics = metrics;
    }

    @Transactional
    public MessageView send(long senderId, TextMessageRequest request, String username, String sessionId) {
        return send(senderId, new ChatMessageRequest(request.conversationId(), request.clientRequestId(),
                MessageType.TEXT, request.body(), null), username, sessionId);
    }

    @Transactional
    public MessageView send(long senderId, ChatMessageRequest request, String username, String sessionId) {
        long started = System.nanoTime();
        // 与解散共用同一把数据库行锁，也使同一会话的时间游标与提交顺序一致。
        ChatConversation conversation = reads.lockConversation(request.conversationId());
        if (conversation == null || conversation.getStatus() != ConversationStatus.ACTIVE) throw ChatException.notFound();
        rooms.requireAccess(senderId, request.conversationId());
        ChatMessage existing = messages.byRequest(senderId, request.clientRequestId());
        if (existing != null) return replay(existing, request, username, sessionId);
        ChatAttachment attachment = request.type() == MessageType.IMAGE
                ? attachments.readyForMessage(senderId, request.conversationId(), request.attachmentId()) : null;

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (conversation.getLastActivityAt() != null && !now.isAfter(conversation.getLastActivityAt()))
            now = conversation.getLastActivityAt().plusNanos(1000);
        ChatMessage message = new ChatMessage();
        message.setConversationId(request.conversationId());
        message.setSenderId(senderId);
        message.setMessageType(request.type());
        message.setClientRequestId(request.clientRequestId());
        message.setBody(request.body());
        message.setAttachmentId(request.attachmentId());
        message.setStatus(MessageStatus.SENT);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        metrics.trackPersistence(started);
        try {
            messages.insert(message);
        } catch (DuplicateKeyException error) {
            // 同一 sender/request 在不同会话竞争时仍由全局唯一索引裁决。
            existing = messages.committedRequest(senderId, request.clientRequestId());
            if (existing == null) throw error;
            return replay(existing, request, username, sessionId);
        }
        if (attachment != null) attachments.attach(attachment, message.getId());
        if (conversations.recordMessage(request.conversationId(), message.getId(), now) != 1)
            throw ChatException.notFound();
        ChatAuditEvent audit = new ChatAuditEvent();
        audit.setEventType(AuditEventType.MESSAGE_CREATED);
        audit.setActorUserId(senderId);
        audit.setConversationId(request.conversationId());
        audit.setMessageId(message.getId());
        audit.setCreatedAt(now);
        audits.insert(audit);
        MessageView view = messages.view(message.getId());
        List<MessageCommitted.DirectRecipient> recipients = conversation.getType() == ConversationType.DIRECT_MESSAGE
                ? reads.directRecipients(conversation.getId()).stream()
                    .map(user -> new MessageCommitted.DirectRecipient(user.name(),
                            reads.conversationView(conversation.getId(), user.userId())))
                    .toList()
                : List.of();
        events.publishEvent(new MessageCommitted(view, username, sessionId, true, started, recipients));
        return view;
    }

    private MessageView replay(ChatMessage existing, ChatMessageRequest request, String username, String sessionId) {
        if (!Objects.equals(existing.getConversationId(), request.conversationId())
                || existing.getMessageType() != request.type() || !Objects.equals(existing.getBody(), request.body())
                || !Objects.equals(existing.getAttachmentId(), request.attachmentId())
                || existing.getStatus() != MessageStatus.SENT || existing.getDeletedAt() != null)
            throw new ChatException(409, "MESSAGE_REQUEST_CONFLICT", "此请求 ID 已用于其他消息，请勿更改重试内容");
        MessageView view = messages.view(existing.getId());
        events.publishEvent(new MessageCommitted(view, username, sessionId, false));
        return view;
    }

    public record HistoryPage(List<MessageView> items, String beforeCursor, String afterCursor, boolean hasMore) {}

    @Transactional(readOnly = true)
    public HistoryPage history(long userId, long conversationId, String before, String after, int limit) {
        long started = System.nanoTime();
        try {
        rooms.requireAccess(userId, conversationId);
        if (limit < 1 || limit > 100 || (before != null && after != null))
            throw new ChatException(422, "INVALID_PAGINATION", "limit 须为 1–100，before 与 after 不能同时使用");
        boolean forward = after != null;
        String value = forward ? after : before;
        MessageCursor cursor = value == null ? null : MessageCursor.decode(value, conversationId);
        List<MessageView> result = new ArrayList<>(messages.history(conversationId, cursor, forward, limit + 1));
        boolean hasMore = result.size() > limit;
        if (hasMore) result.remove(result.size() - 1);
        if (!forward) Collections.reverse(result);
        return new HistoryPage(result, result.isEmpty() ? before : result.get(0).getCursor(),
                result.isEmpty() ? (after != null ? after : new MessageCursor(conversationId,
                        LocalDateTime.of(1970, 1, 1, 0, 0), 0).encode()) : result.get(result.size() - 1).getCursor(), hasMore);
        } finally { metrics.history(started); }
    }
}
