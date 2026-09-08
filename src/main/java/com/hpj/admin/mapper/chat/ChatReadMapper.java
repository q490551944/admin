package com.hpj.admin.mapper.chat;

import com.hpj.admin.chat.ConversationView;
import com.hpj.admin.entity.chat.ChatConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface ChatReadMapper {
    String PROJECTION = """
        SELECT c.id, c.type,
          CASE WHEN c.type = 'PUBLIC_ROOM' THEN c.name ELSE
            (SELECT u.username FROM chat_participant other
             JOIN user u ON u.id = other.user_id
             WHERE other.conversation_id = c.id AND other.user_id != #{userId}
               AND other.deleted_at IS NULL ORDER BY other.user_id LIMIT 1)
          END AS name,
          c.owner_id, c.status, c.last_message_id,
          CASE WHEN m.message_type = 'IMAGE' THEN '[图片]'
               ELSE COALESCE(SUBSTRING(m.body, 1, 120), '') END AS last_message_preview,
          COALESCE(c.last_activity_at, c.created_at) AS last_activity_at, c.created_at,
          (SELECT other.user_id FROM chat_participant other
           WHERE other.conversation_id = c.id AND other.user_id != #{userId}
             AND other.deleted_at IS NULL ORDER BY other.user_id LIMIT 1) AS peer_user_id
        FROM chat_conversation c
        LEFT JOIN chat_message m ON m.id = c.last_message_id AND m.conversation_id = c.id
          AND m.deleted_at IS NULL AND m.status = 'SENT'
        """;

    @Select(PROJECTION + """
        WHERE c.status = 'ACTIVE' AND (
          c.type = 'PUBLIC_ROOM' OR
          (c.type = 'DIRECT_MESSAGE' AND EXISTS (
            SELECT 1 FROM chat_participant mine
            WHERE mine.conversation_id = c.id AND mine.user_id = #{userId}
              AND mine.deleted_at IS NULL
          ))
        )
        ORDER BY COALESCE(c.last_activity_at, c.created_at) DESC, c.id DESC
        """)
    List<ConversationView> visibleConversations(@Param("userId") long userId);

    @Select(PROJECTION + " WHERE c.id = #{conversationId}")
    ConversationView conversationView(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("SELECT * FROM chat_conversation WHERE id = #{id} FOR UPDATE")
    ChatConversation lockConversation(@Param("id") long id);

    @Select("""
        SELECT COUNT(*) FROM chat_participant
        WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND deleted_at IS NULL
        """)
    int participantCount(@Param("conversationId") long conversationId, @Param("userId") long userId);
}
