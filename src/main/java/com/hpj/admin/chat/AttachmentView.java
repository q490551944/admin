package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.hpj.admin.entity.chat.ChatAttachment;

/** Only public metadata and authenticated application URLs leave the storage boundary. */
public record AttachmentView(@JsonSerialize(using = ToStringSerializer.class) Long id,
        String origFilename, String contentType, Long sizeBytes, Integer width, Integer height,
        String status, String contentUrl, String thumbnailUrl, String uploadUrl) {
    public static AttachmentView from(ChatAttachment attachment) {
        return of(attachment.getId(), attachment.getOrigFilename(), attachment.getContentType(),
                attachment.getSizeBytes(), attachment.getWidth(), attachment.getHeight(), attachment.getStatus().name());
    }

    public static AttachmentView of(Long id, String name, String type, Long size, Integer width, Integer height, String status) {
        String base = "/api/chat/v1/attachments/" + id;
        return new AttachmentView(id, name, type, size, width, height, status,
                base + "/content", base + "/thumbnail", "UPLOADING".equals(status) ? base + "/upload" : null);
    }
}
