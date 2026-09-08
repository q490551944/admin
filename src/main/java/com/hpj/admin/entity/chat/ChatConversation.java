package com.hpj.admin.entity.chat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.hpj.admin.common.extend.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 公共房间与私聊共用的会话记录；类型决定名称、房主及私聊唯一键的取值约束。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_conversation")
public class ChatConversation extends BaseEntity {

    private ConversationType type;
    private String name;
    /** 房间名称去首尾空白并按 Locale.ROOT 转小写后的值，用于活动房间同名判断。 */
    private String normalizedName;
    /** 私聊唯一键；公共房间必须为空，与 type 一起受数据库唯一约束保护。 */
    private String directKey;
    /** 公共房间创建者，也是解散权限的归属者；私聊为空。 */
    private Long ownerId;
    private ConversationStatus status;
    /** 会话摘要关联的最后一条消息标识。 */
    private Long lastMessageId;
    private LocalDateTime lastActivityAt;
    /** 解散更新显式携带此版本并递增，不依赖全局 MyBatis-Plus 乐观锁插件。 */
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 逻辑解散时间；会话、消息和审计数据仍保留。 */
    private LocalDateTime dissolvedAt;
}
