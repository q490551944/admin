package com.hpj.admin.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpj.admin.entity.chat.ChatConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
    @Update("UPDATE chat_conversation SET status='DISSOLVED', dissolved_at=#{now}, updated_at=#{now}, "
            + "version=version+1 WHERE id=#{id} AND owner_id=#{ownerId} AND status='ACTIVE' AND version=#{version}")
    int markDissolved(@Param("id") long id, @Param("ownerId") long ownerId,
                     @Param("version") int version, @Param("now") LocalDateTime now);
}
