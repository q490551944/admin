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
    String COLUMNS = "m.id, m.conversation_id, m.sender_id, u.username AS sender_name, "
            + "m.message_type AS type, m.client_request_id, m.body, m.attachment_id, m.created_at, m.status, "
            + "a.orig_filename AS attachment_filename, a.content_type AS attachment_content_type, "
            + "a.size_bytes AS attachment_size_bytes, a.width AS attachment_width, a.height AS attachment_height ";
    String JOINS = " JOIN user u ON u.id=m.sender_id LEFT JOIN chat_attachment a ON a.id=m.attachment_id ";
    String PROJECTION = "SELECT " + COLUMNS + "FROM chat_message m" + JOINS;

    @Select("SELECT * FROM chat_message WHERE sender_id=#{senderId} AND client_request_id=#{requestId}")
    ChatMessage byRequest(@Param("senderId") long senderId, @Param("requestId") String requestId);

    /** 唯一约束竞争失败后的当前读，避免 MySQL REPEATABLE READ 的旧快照看不到获胜请求。 */
    @Select("SELECT * FROM chat_message WHERE sender_id=#{senderId} AND client_request_id=#{requestId} FOR UPDATE")
    ChatMessage committedRequest(@Param("senderId") long senderId, @Param("requestId") String requestId);

    @Select(PROJECTION + "WHERE m.id=#{id}")
    MessageView view(@Param("id") long id);

    // Page the indexed message table before joining metadata. Otherwise MySQL can start with
    // the employee table and sort the entire conversation before applying LIMIT.
    @Select("<script>SELECT " + COLUMNS + "FROM (SELECT * FROM chat_message m "
            + "WHERE m.conversation_id=#{conversationId} AND m.deleted_at IS NULL AND m.status='SENT' "
            + "<if test='cursor != null'> AND <choose><when test='forward'>"
            + "(m.created_at &gt; #{cursor.time} OR (m.created_at=#{cursor.time} AND m.id &gt; #{cursor.id}))"
            + "</when><otherwise>"
            + "(m.created_at &lt; #{cursor.time} OR (m.created_at=#{cursor.time} AND m.id &lt; #{cursor.id}))"
            + "</otherwise></choose></if>"
            + "ORDER BY m.created_at <choose><when test='forward'>ASC, m.id ASC</when>"
            + "<otherwise>DESC, m.id DESC</otherwise></choose> LIMIT #{limit}) m" + JOINS
            + "ORDER BY m.created_at <choose><when test='forward'>ASC, m.id ASC</when>"
            + "<otherwise>DESC, m.id DESC</otherwise></choose></script>")
    List<MessageView> history(@Param("conversationId") long conversationId, @Param("cursor") MessageCursor cursor,
                              @Param("forward") boolean forward, @Param("limit") int limit);
}
