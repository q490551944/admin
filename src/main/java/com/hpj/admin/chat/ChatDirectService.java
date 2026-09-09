package com.hpj.admin.chat;

import com.hpj.admin.entity.User;
import com.hpj.admin.entity.chat.*;
import com.hpj.admin.mapper.chat.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
@ConditionalOnProperty(prefix = "chat", name = "enabled", havingValue = "true")
public class ChatDirectService {
    private final ChatAccountMapper accounts;
    private final ChatConversationMapper conversations;
    private final ChatParticipantMapper participants;
    private final ChatReadMapper reads;
    private final ChatRoomService rooms;

    public ChatDirectService(ChatAccountMapper accounts, ChatConversationMapper conversations,
            ChatParticipantMapper participants, ChatReadMapper reads, ChatRoomService rooms) {
        this.accounts = accounts;
        this.conversations = conversations;
        this.participants = participants;
        this.reads = reads;
        this.rooms = rooms;
    }

    @Transactional(readOnly = true)
    public List<ChatUserView> search(long userId, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        if (query.codePointCount(0, query.length()) > 100)
            throw new ChatException(422, "INVALID_USER_QUERY", "搜索关键词最多 100 个字符");
        // LIKE 的通配符按字面搜索；绑定参数同时避免 SQL 注入。空查询返回前 30 位有效同事。
        String pattern = "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        return accounts.searchColleagues(userId, pattern);
    }

    @Transactional
    public ConversationView open(long userId, long peerId) {
        if (peerId <= 0 || peerId == userId)
            throw new ChatException(422, "INVALID_PEER", "请选择其他有效员工");
        long low = Math.min(userId, peerId), high = Math.max(userId, peerId);
        // 始终按 ID 顺序锁定双方账号，串行化同一对用户的双向创建并复核账号状态。
        // 唯一索引仍是数据库最终约束；锁定已有行避免不存在的 direct_key 上的间隙锁竞争。
        User first = accounts.lockAccount(low), second = accounts.lockAccount(high);
        User actor = userId == low ? first : second, peer = peerId == low ? first : second;
        if (actor == null || !Boolean.TRUE.equals(actor.getStatus())) throw ChatException.unauthorized();
        if (peer == null || !Boolean.TRUE.equals(peer.getStatus())) throw ChatException.notFound();
        String key = low + ":" + high;
        // 重开只读会话，不加会话写锁，避免与“发消息先锁会话、再校验用户外键”形成反向锁序。
        ChatConversation conversation = conversations.findDirect(key);
        if (conversation != null) {
            // 不复活已失效会话或软删除的参与关系。
            rooms.requireAccess(userId, conversation.getId());
            if (reads.participantCount(conversation.getId(), peerId) == 0) throw ChatException.notFound();
            return reads.conversationView(conversation.getId(), userId);
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        conversation = new ChatConversation();
        conversation.setType(ConversationType.DIRECT_MESSAGE);
        conversation.setDirectKey(key);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setVersion(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversation.setLastActivityAt(now);
        conversations.insert(conversation);
        for (long participantId : new long[]{low, high}) {
            ChatParticipant participant = new ChatParticipant();
            participant.setConversationId(conversation.getId());
            participant.setUserId(participantId);
            participant.setCreatedAt(now);
            participants.insert(participant);
        }
        return reads.conversationView(conversation.getId(), userId);
    }
}
