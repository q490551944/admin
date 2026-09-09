package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.chat.security.ChatPrincipal;
import com.hpj.admin.entity.chat.ChatParticipant;
import com.hpj.admin.mapper.chat.ChatParticipantMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/chat-test.yml",
                "spring.datasource.url=jdbc:h2:mem:chat_direct;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@Import(ChatDirectIntegrationTest.AttachmentProbe.class)
class ChatDirectIntegrationTest {
    @Autowired ChatDirectService direct;
    @Autowired ChatRoomService rooms;
    @Autowired ChatMessagingService messages;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean ChatParticipantMapper participants;

    // 测试专用内容处理器，证明权限检查发生在图片读取之前；不代表已实现图片存储。
    @RestController static class AttachmentProbe {
        @GetMapping({"/api/chat/v1/attachments/{id}/content", "/api/chat/v1/attachments/{id}/thumbnail"})
        String read(@PathVariable long id) { return "test-only-image-bytes"; }
    }

    @BeforeEach void seed() {
        ChatIntegrationTest.clean(jdbc);
        for (int id = 1; id <= 4; id++) jdbc.update("INSERT INTO user(id,username,password,status) VALUES(?,?,?,?)",
                id, List.of("alice", "bob", "carol", "disabled").get(id - 1), ChatIntegrationTest.HASH, id != 4);
    }

    RequestPostProcessor as(long user) {
        var principal = new ChatPrincipal(user, jdbc.queryForObject("SELECT username FROM user WHERE id=?", String.class, user), "", true);
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test void searchesOnlyEnabledColleaguesWithLiteralKeywordsAndPublicFields() throws Exception {
        mvc.perform(get("/api/chat/v1/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chat/v1/users").with(as(1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].userId").value("2"))
                .andExpect(jsonPath("$[0].name").value("bob")).andExpect(jsonPath("$[0].password").doesNotExist());
        assertThat(direct.search(1, " BO ")).extracting(ChatUserView::name).containsExactly("bob");
        for (String keyword : List.of("%", "_", "' OR 1=1 --")) assertThat(direct.search(1, keyword)).isEmpty();
        jdbc.update("INSERT INTO user(id,username,password,status) VALUES(9,'a_b%!',?,TRUE)", ChatIntegrationTest.HASH);
        assertThat(direct.search(1, "_b%!")).extracting(ChatUserView::name).containsExactly("a_b%!");
        mvc.perform(get("/api/chat/v1/users").param("query", "x".repeat(101)).with(as(1)))
                .andExpect(status().isUnprocessableEntity());
        for (int i = 10; i < 50; i++) jdbc.update("INSERT INTO user(id,username,status) VALUES(?,?,TRUE)", i, "staff" + i);
        assertThat(direct.search(1, "")).hasSize(30);
    }

    @Test void opensSameConversationInBothDirectionsAndIgnoresClientActor() throws Exception {
        String result = mvc.perform(post("/api/chat/v1/direct-conversations").with(as(1)).with(csrf())
                        .contentType("application/json").content("{\"peer_user_id\":\"2\",\"userId\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("DIRECT_MESSAGE"))
                .andExpect(jsonPath("$.name").value("bob")).andExpect(jsonPath("$.peerUserId").value("2"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(result).path("id").asText();
        mvc.perform(post("/api/chat/v1/direct-conversations").with(as(2)).with(csrf())
                        .contentType("application/json").content("{\"peerUserId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.name").value("alice"));
        assertThat(direct.open(1, 2).getId().toString()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT direct_key FROM chat_conversation", String.class)).isEqualTo("1:2");
        assertThat(jdbc.queryForList("SELECT user_id FROM chat_participant ORDER BY user_id", Long.class)).containsExactly(1L, 2L);
        assertThat(rooms.visible(3)).isEmpty();
    }

    @Test void preservesLargeIdsAndOrdersIdsNumerically() throws Exception {
        long peer = 9007199254740993L;
        jdbc.update("INSERT INTO user(id,username,status) VALUES(?,'largeid',TRUE)", peer);
        mvc.perform(get("/api/chat/v1/users").param("query", "largeid").with(as(1)))
                .andExpect(jsonPath("$[0].userId").value(Long.toString(peer)));
        var opened = direct.open(peer, 2);
        assertThat(direct.open(2, peer).getId()).isEqualTo(opened.getId());
        assertThat(jdbc.queryForObject("SELECT direct_key FROM chat_conversation", String.class)).isEqualTo("2:" + peer);
    }

    @Test void rejectsInvalidPeersAndRequiresSessionCsrfAndEnabledAccount() throws Exception {
        for (String body : List.of("{}", "{\"peer_user_id\":null}", "{\"peer_user_id\":0}",
                "{\"peer_user_id\":-1}", "{\"peer_user_id\":1}", "{\"peer_user_id\":\"bad\"}",
                "{\"peer_user_id\":2.8}", "{\"peer_user_id\":true}", "{\"peer_user_id\":9223372036854775808}"))
            mvc.perform(post("/api/chat/v1/direct-conversations").with(as(1)).with(csrf())
                    .contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        for (int peer : List.of(4, 999)) mvc.perform(post("/api/chat/v1/direct-conversations").with(as(1)).with(csrf())
                .contentType("application/json").content("{\"peer_user_id\":" + peer + "}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/chat/v1/direct-conversations").with(as(1)).contentType("application/json")
                .content("{\"peer_user_id\":2}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/v1/direct-conversations").with(csrf()).contentType("application/json")
                .content("{\"peer_user_id\":2}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/chat/v1/users").with(as(4))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation", Integer.class)).isZero();
    }

    @Test void concurrentReciprocalRequestsCreateExactlyOneConversationAndTwoParticipants() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                long user = i % 2 + 1;
                futures.add(pool.submit(() -> { start.await(); return direct.open(user, 3 - user).getId(); }));
            }
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_participant", Integer.class)).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void participantFailureRollsBackWholeConversation() {
        doThrow(new IllegalStateException("participant write failed")).when(participants)
                .insert(argThat((ChatParticipant p) -> p != null && p.getUserId() == 2L));
        assertThatThrownBy(() -> direct.open(1, 2)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_participant", Integer.class)).isZero();
    }

    @Test void reopeningDoesNotWaitForMessageWriterConversationLock() throws Exception {
        long id = direct.open(1, 2).getId();
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var writer = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM chat_conversation WHERE id=? FOR UPDATE", Long.class, id);
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("open blocked on conversation"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return messages.send(1, new TextMessageRequest(id, "concurrent-open", "hello"), null, null);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var reopened = pool.submit(() -> direct.open(2, 1));
            assertThat(reopened.get(5, TimeUnit.SECONDS).getId()).isEqualTo(id);
            release.countDown();
            assertThat(writer.get(5, TimeUnit.SECONDS).getBody()).isEqualTo("hello");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void deniedHistoryAndSendPersistAuditAfterRollbackWithoutLeakingPrivateData() throws Exception {
        long id = direct.open(1, 2).getId();
        messages.send(1, new TextMessageRequest(id, "private", "only bob"), null, null);
        assertThat(messages.history(2, id, null, null, 30).items()).hasSize(1);
        mvc.perform(get("/api/chat/v1/conversations/" + id + "/messages").with(as(3)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.items").doesNotExist());
        assertThatThrownBy(() -> messages.send(3, new TextMessageRequest(id, "intrusion", "secret"), null, null))
                .isInstanceOf(ChatException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='AUTH_DENIED' AND actor_user_id=3 AND conversation_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(1);
    }

    @Test void reopeningCannotRestoreRevokedParticipationAndAuditsWithoutLockingItself() {
        long id = direct.open(1, 2).getId();
        jdbc.update("UPDATE chat_participant SET deleted_at=CURRENT_TIMESTAMP WHERE conversation_id=? AND user_id=1", id);
        assertThatThrownBy(() -> direct.open(1, 2)).isInstanceOf(ChatException.class);
        assertThat(rooms.visible(1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='AUTH_DENIED'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> direct.open(2, 1)).isInstanceOf(ChatException.class);
        jdbc.update("UPDATE chat_participant SET deleted_at=NULL WHERE conversation_id=?", id);
        jdbc.update("UPDATE user SET status=FALSE WHERE id=2");
        assertThatThrownBy(() -> direct.open(1, 2)).isInstanceOf(ChatException.class);
    }

    @Test void attachmentContentAndThumbnailCheckActualConversationBeforeReading() throws Exception {
        long id = direct.open(1, 2).getId();
        jdbc.update("INSERT INTO chat_attachment(id,conversation_id,uploader_id,storage_bucket,storage_object_key,orig_filename,content_type,size_bytes,sha256,status) VALUES(501,?,1,'private','object','test.png','image/png',10,?,'READY')", id, "0".repeat(64));
        for (String kind : List.of("content", "thumbnail")) {
            String path = "/api/chat/v1/attachments/501/" + kind;
            for (long participant : List.of(1L, 2L)) mvc.perform(get(path).with(as(participant)))
                    .andExpect(status().isOk()).andExpect(content().string("test-only-image-bytes"));
            mvc.perform(get(path).param("conversationId", "999").with(as(3)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/chat/v1/attachments/999/" + kind).with(as(3))).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='AUTH_DENIED' AND actor_user_id=3", Integer.class)).isEqualTo(2);
    }
}
