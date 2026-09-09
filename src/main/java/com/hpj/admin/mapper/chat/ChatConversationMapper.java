package com.hpj.admin.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpj.admin.entity.chat.ChatConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;

/** 会话持久化接口，提供通用 CRUD 和带并发条件的解散更新。 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
    @Select("SELECT * FROM chat_conversation WHERE type='DIRECT_MESSAGE' AND direct_key=#{key}")
    ChatConversation findDirect(@Param("key") String key);

    /** 调用者已持有会话行锁，消息与摘要在同一事务提交。 */
    @Update("UPDATE chat_conversation SET last_message_id=#{messageId}, last_activity_at=#{now}, "
            + "updated_at=#{now}, version=version+1 WHERE id=#{id} AND status='ACTIVE'")
    int recordMessage(@Param("id") long id, @Param("messageId") long messageId, @Param("now") LocalDateTime now);

    /** 显式校验房主、活动状态和版本；返回 1 才表示更新成功，不依赖乐观锁插件。 */
    @Update("UPDATE chat_conversation SET status='DISSOLVED', dissolved_at=#{now}, updated_at=#{now}, "
            + "version=version+1 WHERE id=#{id} AND owner_id=#{ownerId} AND status='ACTIVE' AND version=#{version}")
    int markDissolved(@Param("id") long id, @Param("ownerId") long ownerId,
                     @Param("version") int version, @Param("now") LocalDateTime now);
}
