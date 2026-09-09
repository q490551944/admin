package com.hpj.admin.mapper.chat;

import com.hpj.admin.chat.ConversationView;
import com.hpj.admin.chat.ChatUserView;
import com.hpj.admin.entity.chat.ChatConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 聊天专用查询：会话投影、参与关系校验，以及业务事务内的行锁读取。 */
public interface ChatReadMapper {
    // 按查询用户计算私聊显示名；摘要只取同一会话内未删除且已发送的最后一条消息。
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

    /** 公共房间无需参与记录；私聊必须有本人的有效参与记录，同活跃时间按 ID 倒序。 */
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

    /** 按 ID 获取投影，不执行可见性过滤；调用方须先确认访问权限或新建归属。 */
    @Select(PROJECTION + " WHERE c.id = #{conversationId}")
    ConversationView conversationView(@Param("conversationId") long conversationId, @Param("userId") long userId);

    /** 用户目的地只投递给私聊中未删除且账号有效的参与者。 */
    @Select("""
        SELECT u.id AS user_id, u.username AS name, NULL AS avatar
        FROM chat_participant p JOIN user u ON u.id = p.user_id
        WHERE p.conversation_id = #{conversationId} AND p.deleted_at IS NULL AND u.status = TRUE
        ORDER BY p.user_id
        """)
    List<ChatUserView> directRecipients(@Param("conversationId") long conversationId);

    /** 必须在事务内调用，使行锁覆盖后续状态检查和更新。 */
    @Select("SELECT * FROM chat_conversation WHERE id = #{id} FOR UPDATE")
    ChatConversation lockConversation(@Param("id") long id);

    /** 软删除的参与记录不再赋予私聊访问权限。 */
    @Select("""
        SELECT COUNT(*) FROM chat_participant
        WHERE conversation_id = #{conversationId} AND user_id = #{userId} AND deleted_at IS NULL
        """)
    int participantCount(@Param("conversationId") long conversationId, @Param("userId") long userId);
}
