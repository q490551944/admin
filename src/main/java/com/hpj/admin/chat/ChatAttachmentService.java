package com.hpj.admin.chat;

import com.hpj.admin.common.config.chat.ChatProperties;
import com.hpj.admin.entity.chat.*;
import com.hpj.admin.mapper.chat.*;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatAttachmentService {
    private final ChatAttachmentMapper attachments;
    private final ChatReadMapper reads;
    private final ChatAuditEventMapper audits;
    private final ChatRoomService rooms;
    private final ChatProperties properties;
    private final ChatImageValidator images;
    private final ChatObjectStorage storage;
    private final TransactionTemplate transaction;
    private final ChatMetrics metrics;
    private final ChatAccessAudit accessAudit;
    private final Semaphore uploads = new Semaphore(2);

    public ChatAttachmentService(ChatAttachmentMapper attachments, ChatReadMapper reads, ChatAuditEventMapper audits,
            ChatRoomService rooms, ChatProperties properties, ChatImageValidator images, ChatObjectStorage storage,
            PlatformTransactionManager transactions, ChatMetrics metrics, ChatAccessAudit accessAudit) {
        this.attachments = attachments;
        this.reads = reads;
        this.audits = audits;
        this.rooms = rooms;
        this.properties = properties;
        this.images = images;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactions);
        this.metrics = metrics;
        this.accessAudit = accessAudit;
        var retention = properties.getAttachment().getUnattachedRetention();
        if (retention == null || retention.isZero() || retention.isNegative())
            throw new IllegalArgumentException("chat.attachment.unattached-retention must be positive");
    }

    public record Prepare(String fileName, String contentType, Long sizeBytes) {}

    public AttachmentView prepare(long userId, long conversationId, Prepare request) {
        if (request == null || request.fileName() == null || request.fileName().isBlank()
                || request.fileName().length() > 255 || request.fileName().chars().anyMatch(Character::isISOControl))
            throw new ChatException(422, "INVALID_FILENAME", "文件名须为 1–255 个字符且不含控制字符");
        if (request.contentType() == null || !ChatImageValidator.TYPES.contains(request.contentType()))
            throw new ChatException(415, "INVALID_IMAGE", "仅支持 JPEG、PNG、GIF 和 WebP 图片");
        if (request.sizeBytes() == null || request.sizeBytes() <= 0 || request.sizeBytes() > ChatImageValidator.MAX_BYTES)
            throw new ChatException(413, "IMAGE_SIZE_LIMIT", "单张图片不能超过 10 MB");
        return transaction.execute(status -> {
            lockRoom(userId, conversationId);
            var now = LocalDateTime.now();
            var attachment = new ChatAttachment();
            attachment.setConversationId(conversationId);
            attachment.setUploaderId(userId);
            attachment.setStorageBucket(properties.getAttachment().getBucket());
            attachment.setStorageObjectKey("chat/" + conversationId + "/" + UUID.randomUUID());
            attachment.setOrigFilename(request.fileName().replace('\\', '_').replace('/', '_').strip());
            attachment.setContentType(request.contentType());
            attachment.setSizeBytes(request.sizeBytes());
            attachment.setSha256("0".repeat(64));
            attachment.setStatus(AttachmentStatus.UPLOADING);
            attachment.setCreatedAt(now);
            attachment.setUpdatedAt(now);
            attachment.setExpiresAt(now.plus(properties.getAttachment().getUnattachedRetention()));
            attachments.insert(attachment);
            return AttachmentView.from(attachment);
        });
    }

    public AttachmentView upload(long userId, long id, InputStream input, long contentLength) {
        ChatAttachment initial = require(id);
        rooms.requireAccess(userId, initial.getConversationId());
        if (initial.getUploaderId() != userId) throw denied(userId, initial.getConversationId());
        if (!uploads.tryAcquire()) throw new ChatException(429, "UPLOAD_BUSY", "图片正在处理中，请稍后重试");
        var attempted = new AtomicBoolean();
        try {
            if (initial.getStatus() != AttachmentStatus.UPLOADING || expired(initial))
                throw new ChatException(409, "ATTACHMENT_NOT_UPLOADING", "附件不可上传，请重新选择图片");
            attempted.set(true);
            if (contentLength > ChatImageValidator.MAX_BYTES)
                throw new ChatException(413, "IMAGE_SIZE_LIMIT", "单张图片不能超过 10 MB");
            byte[] bytes;
            try { bytes = input.readNBytes(ChatImageValidator.MAX_BYTES + 1); }
            catch (java.io.IOException error) { throw new ChatException(422, "UPLOAD_INTERRUPTED", "图片上传中断，请重试"); }
            if (bytes.length > ChatImageValidator.MAX_BYTES)
                throw new ChatException(413, "IMAGE_SIZE_LIMIT", "单张图片不能超过 10 MB");
            if (bytes.length != initial.getSizeBytes())
                throw new ChatException(422, "IMAGE_SIZE_MISMATCH", "上传内容与声明大小不符");
            var validated = images.validate(bytes);
            AttachmentView result = transaction.execute(status -> {
                // Only the attachment is locked during storage I/O. Text sends and dissolution
                // never wait for an upload; cleanup cannot remove the registered object keys.
                ChatAttachment attachment = attachments.lock(id);
                if (attachment == null) throw missing();
                if (attachment.getStatus() != AttachmentStatus.UPLOADING || expired(attachment))
                    throw new ChatException(409, "ATTACHMENT_NOT_UPLOADING", "附件不可上传，请重新选择图片");
                storage.put(attachment.getStorageBucket(), attachment.getStorageObjectKey(), bytes, validated.contentType());
                storage.put(attachment.getStorageBucket(), thumbnailKey(attachment), validated.thumbnail(), "image/png");
                rooms.requireAccess(userId, attachment.getConversationId());
                if (expired(attachment)) throw new ChatException(409, "ATTACHMENT_NOT_UPLOADING", "附件已过期，请重新选择图片");
                // Browser MIME hints can follow a renamed extension; persist the decoded format.
                attachment.setContentType(validated.contentType());
                attachment.setWidth(validated.width());
                attachment.setHeight(validated.height());
                attachment.setSha256(validated.sha256());
                attachment.setStatus(AttachmentStatus.READY);
                attachment.setUpdatedAt(LocalDateTime.now());
                attachments.updateById(attachment);
                var audit = new ChatAuditEvent();
                audit.setEventType(AuditEventType.ATTACHMENT_UPLOADED);
                audit.setActorUserId(userId);
                audit.setConversationId(attachment.getConversationId());
                audit.setPayloadJson("{\"attachmentId\":\"" + id + "\"}");
                audit.setCreatedAt(LocalDateTime.now());
                audits.insert(audit);
                return AttachmentView.from(attachment);
            });
            metrics.uploaded.increment();
            return result;
        } catch (RuntimeException error) {
            metrics.uploadFailed.increment();
            if (attempted.get()) {
                try {
                    transaction.executeWithoutResult(status -> {
                        var attachment = attachments.lock(id);
                        // Never demote a committed READY or ATTACHED row after an ambiguous commit result.
                        if (attachment != null && attachment.getStatus() == AttachmentStatus.UPLOADING) {
                            attachment.setStatus(AttachmentStatus.FAILED);
                            attachment.setExpiresAt(LocalDateTime.now());
                            attachment.setUpdatedAt(LocalDateTime.now());
                            attachments.updateById(attachment);
                        }
                    });
                } catch (RuntimeException markingFailure) { error.addSuppressed(markingFailure); }
            }
            throw error;
        } finally { uploads.release(); }
    }

    public record Content(byte[] bytes, String contentType) {}

    public Content content(long userId, long id, boolean thumbnail) {
        var attachment = require(id);
        rooms.requireAccess(userId, attachment.getConversationId());
        if (attachment.getStatus() != AttachmentStatus.ATTACHED
                && (attachment.getStatus() != AttachmentStatus.READY || attachment.getUploaderId() != userId || expired(attachment)))
            throw missing();
        return new Content(storage.read(attachment.getStorageBucket(),
                thumbnail ? thumbnailKey(attachment) : attachment.getStorageObjectKey(), ChatImageValidator.MAX_BYTES),
                thumbnail ? "image/png" : attachment.getContentType());
    }

    /** Called inside the message transaction after its conversation lock has been acquired. */
    public ChatAttachment readyForMessage(long userId, long conversationId, long id) {
        // Reject unfinished uploads without waiting for their storage transaction while holding
        // the conversation lock. Recheck READY under the attachment lock before association.
        var snapshot = require(id);
        if (snapshot.getConversationId() != conversationId || snapshot.getUploaderId() != userId)
            throw denied(userId, snapshot.getConversationId());
        if (snapshot.getStatus() != AttachmentStatus.READY)
            throw new ChatException(409, "ATTACHMENT_NOT_READY", "图片尚未上传完成、已发送或已过期");
        var attachment = attachments.lock(id);
        if (attachment == null) throw missing();
        if (attachment.getConversationId() != conversationId || attachment.getUploaderId() != userId)
            throw denied(userId, attachment.getConversationId());
        if (attachment.getStatus() != AttachmentStatus.READY || attachment.getMessageId() != null || expired(attachment))
            throw new ChatException(409, "ATTACHMENT_NOT_READY", "图片尚未上传完成、已发送或已过期");
        return attachment;
    }

    public void attach(ChatAttachment attachment, long messageId) {
        attachment.setMessageId(messageId);
        attachment.setStatus(AttachmentStatus.ATTACHED);
        attachment.setUpdatedAt(LocalDateTime.now());
        attachments.updateById(attachment);
    }

    public int cleanupExpired() {
        int cleaned = 0;
        for (long id : attachments.expired(LocalDateTime.now())) {
            try {
                Boolean removed = transaction.execute(status -> {
                    var attachment = attachments.lock(id);
                    if (attachment == null || attachment.getStatus() == AttachmentStatus.ATTACHED
                            || attachment.getMessageId() != null || !expired(attachment)) return false;
                    // Keep the row until both deletes succeed, so failures can be retried safely.
                    storage.delete(attachment.getStorageBucket(), thumbnailKey(attachment));
                    storage.delete(attachment.getStorageBucket(), attachment.getStorageObjectKey());
                    attachments.deleteById(id);
                    return true;
                });
                if (Boolean.TRUE.equals(removed)) cleaned++;
            } catch (RuntimeException error) {
                metrics.cleanupFailed.increment();
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Attachment {} cleanup failed; will retry", id, error);
            }
        }
        return cleaned;
    }

    private void lockRoom(long userId, long conversationId) {
        if (reads.lockConversation(conversationId) == null) throw ChatException.notFound();
        rooms.requireAccess(userId, conversationId);
    }
    private ChatException denied(long userId, long conversationId) {
        accessAudit.denied(userId, conversationId);
        return ChatException.forbidden();
    }
    private ChatAttachment require(long id) {
        var attachment = attachments.selectById(id);
        if (attachment == null) throw missing();
        return attachment;
    }
    private static boolean expired(ChatAttachment attachment) {
        return attachment.getExpiresAt() != null && !attachment.getExpiresAt().isAfter(LocalDateTime.now());
    }
    private static String thumbnailKey(ChatAttachment attachment) { return attachment.getStorageObjectKey() + ".thumb.png"; }
    private static ChatException missing() { return new ChatException(404, "ATTACHMENT_NOT_FOUND", "图片不存在或尚不可用"); }
}
