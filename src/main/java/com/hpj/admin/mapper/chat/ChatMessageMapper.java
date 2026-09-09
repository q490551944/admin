package com.hpj.admin.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpj.admin.entity.chat.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.hpj.admin.chat.MessageView;
import com.hpj.admin.chat.MessageCursor;
import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    String PROJECTION = "SELECT m.id, m.conversation_id, m.sender_id, u.username AS sender_name, "
            + "m.message_type AS type, m.client_request_id, m.body, m.attachment_id, m.created_at, m.status "
            + "FROM chat_message m JOIN user u ON u.id=m.sender_id ";

    @Select("SELECT * FROM chat_message WHERE sender_id=#{senderId} AND client_request_id=#{requestId}")
    ChatMessage byRequest(@Param("senderId") long senderId, @Param("requestId") String requestId);

    /** 唯一约束竞争失败后的当前读，避免 MySQL REPEATABLE READ 的旧快照看不到获胜请求。 */
    @Select("SELECT * FROM chat_message WHERE sender_id=#{senderId} AND client_request_id=#{requestId} FOR UPDATE")
    ChatMessage committedRequest(@Param("senderId") long senderId, @Param("requestId") String requestId);

    @Select(PROJECTION + "WHERE m.id=#{id}")
    MessageView view(@Param("id") long id);

    @Select("<script>" + PROJECTION
            + "WHERE m.conversation_id=#{conversationId} AND m.deleted_at IS NULL AND m.status='SENT' "
            + "<if test='cursor != null'> AND <choose><when test='forward'>"
            + "(m.created_at &gt; #{cursor.time} OR (m.created_at=#{cursor.time} AND m.id &gt; #{cursor.id}))"
            + "</when><otherwise>"
            + "(m.created_at &lt; #{cursor.time} OR (m.created_at=#{cursor.time} AND m.id &lt; #{cursor.id}))"
            + "</otherwise></choose></if>"
            + "ORDER BY m.created_at <choose><when test='forward'>ASC, m.id ASC</when>"
            + "<otherwise>DESC, m.id DESC</otherwise></choose> LIMIT #{limit}</script>")
    List<MessageView> history(@Param("conversationId") long conversationId, @Param("cursor") MessageCursor cursor,
                              @Param("forward") boolean forward, @Param("limit") int limit);
}
