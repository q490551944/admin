package com.hpj.admin.chat;

import com.hpj.admin.entity.chat.ChatAuditEvent;
import com.hpj.admin.mapper.chat.ChatAuditEventMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.hpj.admin.chat.security.ChatPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/chat-test.yml",
                "spring.datasource.url=jdbc:h2:mem:chat_messages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ChatMessagingIntegrationTest {
    @Autowired ChatMessagingService messages;
    @Autowired ChatRoomService rooms;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean ChatAuditEventMapper audits;
    long room;

    @BeforeEach void seed() {
        ChatIntegrationTest.clean(jdbc);
        for (int id = 1; id <= 3; id++) jdbc.update("INSERT INTO user(id,username,password,status) VALUES(?,?,?,TRUE)",
                id, List.of("alice", "bob", "carol").get(id - 1), ChatIntegrationTest.HASH);
        room = rooms.create(1, "Message test").getId();
    }

    MessageView send(long user, long conversation, String request, String body) {
        return messages.send(user, new TextMessageRequest(conversation, request, body), null, null);
    }

    @Test void persistsNormalizedTextWithSummaryAuditAndIdempotentRetry() {
        MessageView first = send(1, room, "stable", "  hello 😀\n世界  ");
        assertThat(first.getBody()).isEqualTo("hello 😀\n世界");
        assertThat(first.getSenderId()).isEqualTo(1);
        assertThat(first.getSenderName()).isEqualTo("alice");
        assertThat(first.getStatus()).isEqualTo("SENT");
        assertThat(first.getCreatedAt().getNano() % 1000).isZero();
        assertThat(send(1, room, "stable", "hello 😀\n世界")).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='MESSAGE_CREATED'", Integer.class)).isEqualTo(1);
        assertThat(rooms.visible(1).get(0).getLastMessageId()).isEqualTo(first.getId());
        assertThat(rooms.visible(1).get(0).getLastMessagePreview()).isEqualTo(first.getBody());
        assertThatThrownBy(() -> send(1, room, "stable", "different")).isInstanceOf(ChatException.class)
                .extracting(e -> ((ChatException)e).getStatus()).isEqualTo(409);
        long other = rooms.create(1, "Other room").getId();
        assertThatThrownBy(() -> send(1, other, "stable", first.getBody())).isInstanceOf(ChatException.class);
        assertThat(send(2, room, "stable", "bob's request").getId()).isNotEqualTo(first.getId());
    }

    @Test void validationAndPermissionsRejectWritesAndDissolvedHistory() {
        for (String body : List.of("", " \n\t", "x".repeat(5001), "\u0000", "\uD800"))
            assertThatThrownBy(() -> send(1, room, "invalid", body)).isInstanceOf(ChatException.class);
        new TextMessageRequest(room, "unicode", "😀".repeat(5000));
        assertThatThrownBy(() -> new TextMessageRequest(room, "bad id", "hello")).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> new TextMessageRequest(room, "x".repeat(65), "hello")).isInstanceOf(ChatException.class);
        jdbc.update("INSERT INTO chat_conversation(id,type,direct_key,status) VALUES(101,'DIRECT_MESSAGE','1:2','ACTIVE')");
        jdbc.update("INSERT INTO chat_participant(id,conversation_id,user_id) VALUES(201,101,1),(202,101,2)");
        assertThatThrownBy(() -> send(3, 101, "intrusion", "secret")).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> messages.history(3, 101, null, null, 30)).isInstanceOf(ChatException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
        send(1, 101, "private", "hello bob");
        assertThat(messages.history(2, 101, null, null, 30).items()).hasSize(1);
        rooms.dissolve(1, room);
        assertThatThrownBy(() -> send(1, room, "closed", "late")).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> messages.history(1, room, null, null, 30)).isInstanceOf(ChatException.class);
    }

    @Test void concurrentRetriesProduceOneMessageAndOneAudit() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(pool.submit(() -> {
                ready.countDown(); start.await(); return send(1, room, "concurrent", "same").getId();
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            Set<Long> ids = new HashSet<>();
            for (var result : results) ids.add(result.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='MESSAGE_CREATED'", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void requestIdsAreCaseSensitiveAndSummaryTimeRemainsMonotonic() {
        MessageView upper = send(1, room, "CaseId", "first");
        MessageView lower = send(1, room, "caseid", "second");
        assertThat(lower.getId()).isNotEqualTo(upper.getId());
        assertThat(lower.getCreatedAt()).isAfter(upper.getCreatedAt());
        jdbc.update("UPDATE chat_conversation SET last_activity_at='2030-01-01 00:00:00' WHERE id=?", room);
        MessageView correctedClock = send(1, room, "clock", "after clock rollback");
        assertThat(correctedClock.getCreatedAt()).isAfter(LocalDateTime.of(2030,1,1,0,0));
        assertThat(messages.history(1, room, null, lower.getCursor(), 30).items()).extracting(MessageView::getId)
                .containsExactly(correctedClock.getId());
    }

    @Test void concurrentReuseAcrossConversationsCannotCreateTwoMessages() throws Exception {
        long other = rooms.create(1, "Concurrent other").getId();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (long id : List.of(room, other)) results.add(pool.submit(() -> {
                start.await();
                try { send(1, id, "global-key", "same"); return 201; }
                catch (ChatException error) { return error.getStatus(); }
            }));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void rollbackRestoresMessagesAndSummary() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            send(1, room, "rollback", "never committed"); status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
        assertThat(rooms.visible(1).get(0).getLastMessageId()).isNull();
        doThrow(new IllegalStateException("audit unavailable")).when(audits).insert(any(ChatAuditEvent.class));
        assertThatThrownBy(() -> send(1, room, "audit-failure", "rollback")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
        assertThat(rooms.visible(1).get(0).getLastMessageId()).isNull();
    }

    @Test void historyPagesTiedTimestampsWithoutGapsAndCatchesUpInForwardOrder() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123456000);
        for (int i = 1; i <= 65; i++) jdbc.update("INSERT INTO chat_message(id,conversation_id,sender_id,message_type,client_request_id,body,status,created_at) VALUES(?,?,1,'TEXT',?,?,'SENT',?)",
                i, room, "seed" + i, "message " + i, timestamp);
        var latest = messages.history(1, room, null, null, 30);
        assertThat(latest.items()).extracting(MessageView::getId).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(36,65).boxed().toList());
        var older = messages.history(1, room, latest.beforeCursor(), null, 30);
        var oldest = messages.history(1, room, older.beforeCursor(), null, 30);
        assertThat(older.items()).hasSize(30); assertThat(oldest.items()).hasSize(5);
        assertThat(oldest.hasMore()).isFalse();
        var missed = messages.history(1, room, null, oldest.afterCursor(), 30);
        assertThat(missed.items()).extracting(MessageView::getId).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(6,35).boxed().toList());
        assertThat(missed.hasMore()).isTrue();
        assertThat(messages.history(1, room, null, latest.afterCursor(), 30).items()).isEmpty();
        for (String cursor : List.of("invalid", new MessageCursor(room + 1, timestamp, 1).encode()))
            assertThatThrownBy(() -> messages.history(1, room, cursor, null, 30)).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> messages.history(1, room, latest.beforeCursor(), latest.afterCursor(), 30)).isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> messages.history(1, room, null, null, 101)).isInstanceOf(ChatException.class);
    }

    @Test void emptyHistoryHasRecoveryCursorAndHttpPreservesLongIds() throws Exception {
        var empty = messages.history(1, room, null, null, 30);
        assertThat(empty.afterCursor()).isNotBlank();
        MessageView message = send(1, room, "empty-recovery", "saved");
        assertThat(messages.history(1, room, null, empty.afterCursor(), 30).items()).hasSize(1);
        mvc.perform(get("/api/chat/v1/conversations/" + room + "/messages")).andExpect(status().isUnauthorized());
        var principal = new ChatPrincipal(1, "alice", "", true);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        mvc.perform(get("/api/chat/v1/conversations/" + room + "/messages").with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(message.getId().toString()))
                .andExpect(jsonPath("$.items[0].senderId").value("1"))
                .andExpect(jsonPath("$.items[0].body").value("saved"));
        mvc.perform(get("/api/chat/v1/conversations/" + room + "/messages?limit=bad").with(authentication(auth)))
                .andExpect(status().isUnprocessableEntity());
    }
}
