package com.hpj.admin.mapper.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpj.admin.entity.chat.ChatAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachment> {
    @Select("SELECT * FROM chat_attachment WHERE id=#{id} FOR UPDATE")
    ChatAttachment lock(@Param("id") long id);

    @Select("SELECT id FROM chat_attachment WHERE message_id IS NULL AND status IN ('UPLOADING','READY','FAILED') "
            + "AND expires_at <= #{now} ORDER BY expires_at, id LIMIT 100")
    List<Long> expired(@Param("now") LocalDateTime now);
}
