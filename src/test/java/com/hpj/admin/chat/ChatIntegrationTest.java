package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpj.admin.entity.chat.ChatAuditEvent;
import com.hpj.admin.mapper.chat.ChatAuditEventMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/chat-test.yml")
@AutoConfigureMockMvc
class ChatIntegrationTest {
    static final String PASSWORD = "TestPassword123";
    static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatRoomService rooms;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean ChatAuditEventMapper audits;

    @BeforeEach
    void seed() {
        clean(jdbc);
        for (long id = 1; id <= 4; id++) {
            jdbc.update("INSERT INTO user(id, username, password, status) VALUES(?,?,?,?)",
                    id, List.of("alice", "bob", "carol", "disabled").get((int) id - 1), HASH, id != 4);
        }
    }

    static void clean(JdbcTemplate jdbc) {
        jdbc.update("UPDATE chat_conversation SET last_message_id = NULL");
        jdbc.update("UPDATE chat_message SET attachment_id = NULL");
        jdbc.update("DELETE FROM chat_audit_event");
        jdbc.update("DELETE FROM chat_attachment");
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM chat_participant");
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM user");
    }

    record Login(MockHttpSession session, String csrf) {}

    Login login(String username) throws Exception {
        var initial = mvc.perform(get("/api/chat/v1/csrf-token")).andExpect(status().isOk()).andReturn();
        MockHttpSession before = (MockHttpSession) initial.getRequest().getSession();
        String oldId = before.getId();
        String token = json.readTree(initial.getResponse().getContentAsString()).path("token").asText();
        MvcResult result = mvc.perform(post("/api/chat/v1/sessions").session(before)
                .header("X-CSRF-TOKEN", token).param("username", username).param("password", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/chat/v1/sessions/current"))
                .andExpect(jsonPath("$.password").doesNotExist()).andReturn();
        MockHttpSession after = (MockHttpSession) result.getRequest().getSession();
        assertThat(after.getId()).isNotEqualTo(oldId);
        String newToken = json.readTree(mvc.perform(get("/api/chat/v1/csrf-token").session(after))
                .andReturn().getResponse().getContentAsString()).path("token").asText();
        return new Login(after, newToken);
    }

    @Test
    void authenticationCsrfAndIdentityAreEnforced() throws Exception {
        mvc.perform(get("/api/chat/v1/conversations")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(post("/api/chat/v1/rooms").contentType("application/json").content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
        Login alice = login("alice");
        // The fallback chain must not expose Spring's default CSRF-less GET /logout.
        mvc.perform(get("/logout").session(alice.session)).andExpect(status().isNotFound());
        assertThat(alice.session.isInvalid()).isFalse();
        mvc.perform(get("/api/chat/v1/sessions/current").session(alice.session))
                .andExpect(jsonPath("$.id").value("1")).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(post("/api/chat/v1/rooms").session(alice.session)
                .contentType("application/json").content("{\"name\":\"Test\"}")).andExpect(status().isForbidden());
        String response = mvc.perform(post("/api/chat/v1/rooms").session(alice.session)
                .header("X-CSRF-TOKEN", alice.csrf).contentType("application/json")
                .content("{\"name\":\"  Team Room  \",\"ownerId\":2,\"senderId\":2}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerId").value("1"))
                .andExpect(jsonPath("$.name").value("Team Room")).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).path("id").isTextual()).isTrue();
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM chat_audit_event", Long.class)).isEqualTo(1L);
        mvc.perform(delete("/api/chat/v1/sessions/current").session(alice.session)
                .header("X-CSRF-TOKEN", alice.csrf)).andExpect(status().isNoContent());
        assertThat(alice.session.isInvalid()).isTrue();
        mvc.perform(get("/api/chat/v1/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyCsrfProtectedDeleteEndsTheCurrentSession() throws Exception {
        Login alice = login("alice");
        mvc.perform(delete("/api/chat/v1/sessions/current").session(alice.session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/v1/sessions/current").session(alice.session)
                .header("X-CSRF-TOKEN", alice.csrf)).andExpect(status().isMethodNotAllowed());
        assertThat(alice.session.isInvalid()).isFalse();
        mvc.perform(get("/api/chat/v1/sessions/current").session(alice.session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("1"));
        mvc.perform(delete("/api/chat/v1/sessions/current").session(alice.session)
                .header("X-CSRF-TOKEN", alice.csrf)).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(alice.session.isInvalid()).isTrue();
    }

    @Test
    void chatPageAndLoginAssetsAreReachableWithoutAuthentication() throws Exception {
        mvc.perform(get("/chat/").queryParam("mode", "live")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat/index.html?mode=live"));
        mvc.perform(get("/chat/index.html")).andExpect(status().isOk());
        mvc.perform(get("/chat/login.html")).andExpect(status().isOk());
        mvc.perform(get("/chat/login.js")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("./index.html?mode=live")));
    }

    @Test
    void invalidDisabledAndAmbiguousAccountsCannotLogin() throws Exception {
        for (String username : List.of("missing", "disabled", "alice")) {
            var csrf = mvc.perform(get("/api/chat/v1/csrf-token")).andReturn();
            mvc.perform(post("/api/chat/v1/sessions")
                    .session((MockHttpSession) csrf.getRequest().getSession())
                    .header("X-CSRF-TOKEN", json.readTree(csrf.getResponse().getContentAsString()).path("token").asText())
                    .param("username", username).param("password", username.equals("alice") ? "wrong" : PASSWORD))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        jdbc.update("INSERT INTO user(id,username,password,status) VALUES(5,'alice',?,TRUE)", HASH);
        assertThatThrownBy(() -> login("alice")).isInstanceOf(AssertionError.class);
    }

    @Test
    void deactivatedAccountInvalidatesItsSession() throws Exception {
        Login alice = login("alice");
        jdbc.update("UPDATE user SET status=FALSE WHERE id=1");
        mvc.perform(get("/api/chat/v1/conversations").session(alice.session)).andExpect(status().isUnauthorized());
        assertThat(alice.session.isInvalid()).isTrue();
    }

    @Test
    void listReturnsOnlyVisibleActiveConversationsWithSafeSummaries() throws Exception {
        Login alice = login("alice");
        mvc.perform(get("/api/chat/v1/conversations").session(alice.session)).andExpect(content().json("[]"));
        long publicId = rooms.create(2, "Public room").getId();
        long archivedId = rooms.create(1, "Archived").getId();
        rooms.dissolve(1, archivedId);
        jdbc.update("INSERT INTO chat_conversation(id,type,direct_key,status) VALUES(101,'DIRECT_MESSAGE','1:2','ACTIVE')");
        jdbc.update("INSERT INTO chat_conversation(id,type,direct_key,status) VALUES(102,'DIRECT_MESSAGE','2:3','ACTIVE')");
        jdbc.update("INSERT INTO chat_conversation(id,type,direct_key,status) VALUES(103,'DIRECT_MESSAGE','1:3','ACTIVE')");
        jdbc.update("INSERT INTO chat_participant(id,conversation_id,user_id) VALUES(201,101,1),(202,101,2),(203,102,2),(204,102,3),(205,103,1),(206,103,3)");
        jdbc.update("UPDATE chat_participant SET deleted_at=CURRENT_TIMESTAMP WHERE id=205");
        jdbc.update("INSERT INTO chat_message(id,conversation_id,sender_id,message_type,client_request_id,body,status) VALUES(301,101,2,'TEXT','summary','hello private','SENT')");
        jdbc.update("UPDATE chat_conversation SET last_message_id=301,last_activity_at='2030-01-01 12:00:00' WHERE id=101");
        String response = mvc.perform(get("/api/chat/v1/conversations").session(alice.session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(response);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("id").asText()).isEqualTo("101");
        assertThat(list.get(0).path("name").asText()).isEqualTo("bob");
        assertThat(list.get(0).path("lastMessagePreview").asText()).isEqualTo("hello private");
        assertThat(list.get(1).path("id").asText()).isEqualTo(Long.toString(publicId));
        assertThat(response).doesNotContain("password", "directKey", "normalizedName", "carol");
        assertThatThrownBy(() -> rooms.requireAccess(3, 101)).isInstanceOf(ChatException.class)
                .extracting(error -> ((ChatException) error).getStatus()).isEqualTo(403);
        assertThatThrownBy(() -> rooms.requireAccess(1, archivedId)).isInstanceOf(ChatException.class);
        assertThat(jdbc.queryForList("SELECT index_name FROM information_schema.indexes WHERE table_name='chat_participant'"))
                .extracting(row -> row.get("index_name")).contains("idx_chat_participant_user");
    }

    @Test
    void roomValidationConflictOwnershipAndNameReuse() throws Exception {
        Login alice = login("alice");
        Login bob = login("bob");
        for (String name : List.of("", " ", "a", "x".repeat(51))) {
            mvc.perform(post("/api/chat/v1/rooms").session(alice.session).header("X-CSRF-TOKEN", alice.csrf)
                    .contentType("application/json").content(json.writeValueAsString(Map.of("name", name))))
                    .andExpect(status().isUnprocessableEntity());
        }
        long room = rooms.create(1, "  Engineering  ").getId();
        mvc.perform(post("/api/chat/v1/rooms").session(alice.session).header("X-CSRF-TOKEN", alice.csrf)
                .contentType("application/json").content("{\"name\":\"engineering\"}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/chat/v1/rooms/" + room).session(bob.session).header("X-CSRF-TOKEN", bob.csrf))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO chat_message(id,conversation_id,sender_id,message_type,client_request_id,body,status) VALUES(999,?,1,'TEXT','retain','history','SENT')", room);
        mvc.perform(delete("/api/chat/v1/rooms/" + room).session(alice.session).header("X-CSRF-TOKEN", alice.csrf))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/chat/v1/rooms/" + room).session(alice.session).header("X-CSRF-TOKEN", alice.csrf))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT name FROM chat_conversation WHERE id=?", String.class, room)).isEqualTo("Engineering");
        assertThat(jdbc.queryForObject("SELECT status FROM chat_conversation WHERE id=?", String.class, room)).isEqualTo("DISSOLVED");
        assertThat(jdbc.queryForObject("SELECT body FROM chat_message WHERE id=999", String.class)).isEqualTo("history");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='ROOM_DISSOLVED'", Integer.class)).isEqualTo(1);
        long second = rooms.create(1, "Engineering").getId();
        rooms.dissolve(1, second);
        assertThat(rooms.create(2, "engineering").getId()).isNotEqualTo(room).isNotEqualTo(second);
    }

    @Test
    void sameNameConcurrencyHasExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try { rooms.create(1, "Concurrent"); return 201; }
                    catch (ChatException error) { return error.getStatus(); }
                }));
            }
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409, 409, 409, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void auditFailureRollsBackRoomAndOuterRollbackPreservesRoom() {
        doThrow(new IllegalStateException("audit unavailable")).when(audits).insert(any(ChatAuditEvent.class));
        assertThatThrownBy(() -> rooms.create(1, "Must rollback")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation", Integer.class)).isZero();
    }

    @Test
    void roomDissolutionParticipatesInCallerTransaction() {
        long id = rooms.create(1, "Keep me").getId();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            rooms.dissolve(1, id);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM chat_conversation WHERE id=?", String.class, id)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='ROOM_DISSOLVED'", Integer.class)).isZero();
    }
}
