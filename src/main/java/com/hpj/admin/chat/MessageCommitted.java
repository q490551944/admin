package com.hpj.admin.chat;

import java.util.List;

/** 重试只重发 ACK，不重复广播或修改会话摘要。 */
public record MessageCommitted(MessageView message, String username, String sessionId, boolean created,
                               long acceptedAtNanos, List<DirectRecipient> directRecipients) {
    /** 在写消息的事务内读取双方视角，提交后推送不再依赖数据库查询。 */
    public record DirectRecipient(String username, ConversationView conversation) {}

    public MessageCommitted(MessageView message, String username, String sessionId, boolean created, long acceptedAtNanos) {
        this(message, username, sessionId, created, acceptedAtNanos, List.of());
    }

    public MessageCommitted(MessageView message, String username, String sessionId, boolean created) {
        this(message, username, sessionId, created, System.nanoTime());
    }
}
