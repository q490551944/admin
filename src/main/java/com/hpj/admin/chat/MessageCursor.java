package com.hpj.admin.chat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

/** 游标绑定会话，保留数据库微秒精度；只能作为位置，不能替代权限校验。 */
public record MessageCursor(long conversationId, LocalDateTime time, long id) {
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1|" + conversationId + "|" + time + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static MessageCursor decode(String value, long conversationId) {
        try {
            if (value == null || value.isBlank() || value.length() > 200) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 4 || !fields[0].equals("1")) throw new IllegalArgumentException();
            MessageCursor cursor = new MessageCursor(Long.parseLong(fields[1]), LocalDateTime.parse(fields[2]), Long.parseLong(fields[3]));
            if (cursor.conversationId != conversationId || cursor.id < 0 || cursor.time.getNano() % 1000 != 0)
                throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException error) {
            throw new ChatException(422, "INVALID_CURSOR", "消息游标无效或不属于当前会话");
        }
    }
}
