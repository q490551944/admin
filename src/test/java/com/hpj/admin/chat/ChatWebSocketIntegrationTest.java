package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real HTTP cookies, embedded Tomcat, native WebSocket and the production STOMP channel. */
@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/chat-test.yml",
                "spring.datasource.url=jdbc:h2:mem:chat_ws;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
@Import(ChatWebSocketIntegrationTest.SessionProbe.class)
class ChatWebSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatRoomService rooms;
    @Autowired ChatMessagingService messages;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired @Qualifier("brokerChannel") ExecutorSubscribableChannel brokerChannel;
    @Autowired SessionProbe probe;
    private final List<Wire> connections = new ArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionProbe {
        final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();
        @Bean ServletListenerRegistrationBean<HttpSessionListener> testSessionListener() {
            return new ServletListenerRegistrationBean<>(new HttpSessionListener() {
                @Override public void sessionCreated(HttpSessionEvent event) {
                    HttpSession session = event.getSession();
                    sessions.put(session.getId(), session);
                }
                @Override public void sessionDestroyed(HttpSessionEvent event) {
                    sessions.values().removeIf(session -> session == event.getSession());
                }
            });
        }
    }

    @BeforeEach void seed() {
        ChatIntegrationTest.clean(jdbc);
        for (int id = 1; id <= 3; id++) {
            jdbc.update("INSERT INTO user(id,username,password,status) VALUES(?,?,?,TRUE)",
                    id, List.of("alice", "bob", "carol").get(id - 1), ChatIntegrationTest.HASH);
        }
    }

    @AfterEach void closeConnections() {
        connections.forEach(Wire::close);
    }

    String origin() { return "http://localhost:" + port; }
    String topic(long id) { return "/topic/chat/conversations/" + id; }

    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;
        HttpResponse<String> request(String method, String path, String body, String contentType) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(origin() + path)).timeout(Duration.ofSeconds(8));
            if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
            if (contentType != null) builder.header("Content-Type", contentType);
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        void refreshCsrf() throws Exception {
            var response = request("GET", "/api/chat/v1/csrf-token", null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            csrf = json.readTree(response.body()).path("token").asText();
        }
    }

    Browser login(String username) throws Exception {
        Browser browser = new Browser();
        browser.refreshCsrf();
        var response = browser.request("POST", "/api/chat/v1/sessions",
                "username=" + username + "&password=" + ChatIntegrationTest.PASSWORD,
                "application/x-www-form-urlencoded");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains("/api/chat/v1/sessions/current");
        assertThat(response.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        browser.refreshCsrf();
        return browser;
    }

    Wire open(Browser browser, String allowedOrigin, String csrf, boolean expectConnected) throws Exception {
        Wire wire = new Wire();
        wire.socket = browser.client.newWebSocketBuilder().header("Origin", allowedOrigin)
                .subprotocols("v12.stomp").connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/chat"), wire).get(8, TimeUnit.SECONDS);
        connections.add(wire);
        wire.send("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n"
                + (csrf == null ? "" : "X-CSRF-TOKEN:" + csrf + "\n")
                + "login:forged-user\nuser:3\n\n");
        if (expectConnected) assertThat(wire.frame()).startsWith("CONNECTED\n");
        return wire;
    }

    Wire connect(Browser browser) throws Exception { return open(browser, origin(), browser.csrf, true); }

    void assertHandshakeRejected(Browser browser, String requestedOrigin, int status) {
        assertThatThrownBy(() -> open(browser, requestedOrigin, browser.csrf, false))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).isInstanceOf(WebSocketHandshakeException.class);
                    assertThat(((WebSocketHandshakeException) error.getCause()).getResponse().statusCode()).isEqualTo(status);
                });
    }

    void subscribed(Wire wire, long id) throws Exception {
        wire.send("SUBSCRIBE\nid:room\ndestination:" + topic(id) + "\nack:auto\n\n");
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(topic(id));
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        await().atMost(Duration.ofSeconds(3)).until(() -> !broker.getSubscriptionRegistry().findSubscriptions(message).isEmpty());
    }

    void confirmedSubscription(Wire wire, String id, String destination) throws Exception {
        wire.send("SUBSCRIBE\nid:" + id + "\ndestination:" + destination + "\nreceipt:ready-" + id + "\nack:auto\n\n");
        assertThat(wire.frame()).startsWith("RECEIPT\n").contains("receipt-id:ready-" + id);
    }

    void sendText(Wire wire, long conversation, String requestId, String body) throws Exception {
        wire.send("SEND\ndestination:/app/chat.messages.send\ncontent-type:application/json\n\n"
                + json.writeValueAsString(Map.of("conversationId", String.valueOf(conversation),
                        "clientRequestId", requestId, "type", "TEXT", "body", body)));
    }

    @Test void privateQueueReceiptWaitsForResolvedBrokerSubscription() throws Exception {
        Wire alice = connect(login("alice"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ChannelInterceptor gate = new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var headers = SimpMessageHeaderAccessor.wrap(message);
                if (headers.getMessageType() == SimpMessageType.SUBSCRIBE
                        && headers.getDestination().startsWith("/queue/chat.acks")) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test gate timed out"); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                }
                return message;
            }
        };
        brokerChannel.addInterceptor(gate);
        try {
            alice.send("SUBSCRIBE\nid:acks\ndestination:/user/queue/chat.acks\nreceipt:registered\n\n");
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(alice.frames.poll(200, TimeUnit.MILLISECONDS)).isNull();
            release.countDown();
            assertThat(alice.frame()).startsWith("RECEIPT\n").contains("receipt-id:registered");
        } finally { release.countDown(); brokerChannel.removeInterceptor(gate); }
    }

    @Test void textIsBroadcastAcknowledgedPersistedAndRetryDoesNotBroadcastTwice() throws Exception {
        long room = rooms.create(1, "Text delivery").getId();
        Browser aliceBrowser = login("alice");
        Wire alice = connect(aliceBrowser);
        Wire bob = connect(login("bob"));
        confirmedSubscription(alice, "acks", "/user/queue/chat.acks");
        confirmedSubscription(alice, "room", topic(room));
        confirmedSubscription(bob, "room", topic(room));
        sendText(alice, room, "request-1", "  hello 😀 世界  ");
        JsonNode received = payload(bob.frame());
        assertThat(received.path("eventType").asText()).isEqualTo("MESSAGE_CREATED");
        JsonNode message = received.path("message");
        assertThat(message.path("senderId").asText()).isEqualTo("1");
        assertThat(message.path("body").asText()).isEqualTo("hello 😀 世界");
        assertThat(message.path("id").isTextual()).isTrue();
        List<JsonNode> senderFrames = List.of(payload(alice.frame()), payload(alice.frame()));
        assertThat(senderFrames).extracting(frame -> frame.path("eventType").asText())
                .containsExactlyInAnyOrder("MESSAGE_CREATED", "MESSAGE_ACK");
        assertThat(senderFrames.stream().filter(frame -> frame.path("eventType").asText().equals("MESSAGE_ACK"))
                .findFirst().orElseThrow().path("message")).isEqualTo(message);
        var history = aliceBrowser.request("GET", "/api/chat/v1/conversations/" + room + "/messages", null, null);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(json.readTree(history.body()).path("items").get(0)).isEqualTo(message);
        sendText(alice, room, "request-1", "hello 😀 世界");
        JsonNode replay = payload(alice.frame());
        assertThat(replay.path("eventType").asText()).isEqualTo("MESSAGE_ACK");
        assertThat(replay.path("message")).isEqualTo(message);
        assertThat(bob.frames.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(alice.frames.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(1);
    }

    @Test void businessRejectionIsPrivateAndConnectionCanSendAgain() throws Exception {
        long room = rooms.create(1, "Rejected delivery").getId();
        Wire alice = connect(login("alice"));
        Wire bob = connect(login("bob"));
        confirmedSubscription(alice, "acks", "/user/queue/chat.acks");
        confirmedSubscription(alice, "errors", "/user/queue/chat.errors");
        confirmedSubscription(bob, "errors", "/user/queue/chat.errors");
        confirmedSubscription(bob, "room", topic(room));
        sendText(alice, room, "empty", "   ");
        String errorFrame = alice.frame();
        assertThat(errorFrame).startsWith("MESSAGE\n").contains("subscription:errors");
        JsonNode rejection = payload(errorFrame);
        assertThat(rejection.path("eventType").asText()).isEqualTo("MESSAGE_REJECTED");
        assertThat(rejection.path("clientRequestId").asText()).isEqualTo("empty");
        assertThat(rejection.path("code").asText()).isEqualTo("EMPTY_MESSAGE");
        assertThat(bob.frames.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
        sendText(alice, room, "valid", "works after rejection");
        assertThat(payload(alice.frame()).path("eventType").asText()).isEqualTo("MESSAGE_ACK");
        assertThat(payload(bob.frame()).path("message").path("body").asText()).isEqualTo("works after rejection");
        sendText(alice, room, "valid", "changed");
        assertThat(payload(alice.frame()).path("code").asText()).isEqualTo("MESSAGE_REQUEST_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isEqualTo(1);
    }

    @Test void privateTextAndHistoryStayBetweenParticipantsAndAckTargetsSendingSession() throws Exception {
        Browser aliceBrowser = login("alice");
        var created = aliceBrowser.request("POST", "/api/chat/v1/direct-conversations", "{\"peer_user_id\":2}", "application/json");
        assertThat(created.statusCode()).isEqualTo(200);
        long id = json.readTree(created.body()).path("id").asLong();
        Wire alice = connect(aliceBrowser);
        Wire otherAlice = connect(login("alice"));
        Browser bobBrowser = login("bob");
        var reopened = bobBrowser.request("POST", "/api/chat/v1/direct-conversations", "{\"peer_user_id\":1}", "application/json");
        assertThat(json.readTree(reopened.body()).path("id").asLong()).isEqualTo(id);
        Wire bob = connect(bobBrowser);
        Browser carolBrowser = login("carol");
        Wire carol = connect(carolBrowser);
        confirmedSubscription(alice, "acks", "/user/queue/chat.acks");
        confirmedSubscription(otherAlice, "acks", "/user/queue/chat.acks");
        confirmedSubscription(carol, "acks", "/user/queue/chat.acks");
        confirmedSubscription(bob, "private", topic(id));
        sendText(alice, id, "private-send", "for bob");
        assertThat(payload(alice.frame()).path("eventType").asText()).isEqualTo("MESSAGE_ACK");
        assertThat(payload(bob.frame()).path("message").path("body").asText()).isEqualTo("for bob");
        assertThat(otherAlice.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
        assertThat(carol.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
        String path = "/api/chat/v1/conversations/" + id + "/messages";
        assertThat(bobBrowser.request("GET", path, null, null).statusCode()).isEqualTo(200);
        assertThat(carolBrowser.request("GET", path, null, null).statusCode()).isEqualTo(403);
    }

    @Test void noMessageBroadcastOrAckBeforeCommitOrAfterRollback() throws Exception {
        long room = rooms.create(1, "Message transaction").getId();
        Wire alice = connect(login("alice"));
        Wire bob = connect(login("bob"));
        confirmedSubscription(alice, "acks", "/user/queue/chat.acks");
        confirmedSubscription(bob, "room", topic(room));
        for (boolean rollback : List.of(true, false)) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                messages.send(1, new TextMessageRequest(room, "transaction", "committed only"), "alice", null);
                try {
                    assertThat(alice.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
                    assertThat(bob.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
                } catch (InterruptedException error) { throw new AssertionError(error); }
                if (rollback) status.setRollbackOnly();
            });
            if (rollback) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
                assertThat(alice.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
                assertThat(bob.frames.poll(150, TimeUnit.MILLISECONDS)).isNull();
            }
        }
        assertThat(payload(alice.frame()).path("eventType").asText()).isEqualTo("MESSAGE_ACK");
        assertThat(payload(bob.frame()).path("eventType").asText()).isEqualTo("MESSAGE_CREATED");
    }

    JsonNode payload(String frame) throws Exception { return json.readTree(frame.substring(frame.indexOf("\n\n") + 2)); }

    void assertError(Wire wire, int status, String code) throws Exception {
        String frame = wire.frame();
        assertThat(frame).startsWith("ERROR\n");
        assertThat(payload(frame).path("status").asInt()).isEqualTo(status);
        assertThat(payload(frame).path("code").asText()).isEqualTo(code);
    }

    @Test void handshakeAndConnectEnforceSessionOriginAndCsrf() throws Exception {
        assertHandshakeRejected(new Browser(), origin(), 401);
        Browser alice = login("alice");
        assertHandshakeRejected(alice, "https://evil.invalid", 403);
        assertError(open(alice, origin(), null, false), 403, "ACCESS_DENIED");
        assertError(open(alice, origin(), "wrong", false), 403, "ACCESS_DENIED");
        connect(alice);
    }

    @Test void privateSubscriptionsAndSendCannotForgeIdentityOrBypassPermissions() throws Exception {
        jdbc.update("INSERT INTO chat_conversation(id,type,direct_key,status) VALUES(101,'DIRECT_MESSAGE','1:2','ACTIVE')");
        jdbc.update("INSERT INTO chat_participant(id,conversation_id,user_id) VALUES(201,101,1),(202,101,2)");
        Browser alice = login("alice");
        subscribed(connect(alice), 101);
        Browser carol = login("carol");
        Wire outsider = connect(carol);
        outsider.send("SUBSCRIBE\nid:private\ndestination:" + topic(101) + "\n\n");
        assertError(outsider, 403, "ACCESS_DENIED");
        Wire forbiddenSend = connect(carol);
        forbiddenSend.send("SEND\ndestination:/app/chat.messages.send\ncontent-type:application/json\n\n"
                + "{\"conversationId\":\"101\",\"body\":\"intrusion\"}");
        assertError(forbiddenSend, 403, "ACCESS_DENIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='AUTH_DENIED' AND actor_user_id=3 AND conversation_id=101", Integer.class)).isEqualTo(2);
        Wire forgery = connect(alice);
        forgery.send("SEND\ndestination:/app/chat.messages.send\ncontent-type:application/json\n\n"
                + "{\"conversationId\":\"101\",\"senderId\":\"2\",\"body\":\"forged\"}");
        assertError(forgery, 403, "IDENTITY_FORGED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
    }

    @Test void dissolutionIsDeliveredOnlyAfterCommitAndNeverAfterRollback() throws Exception {
        Browser alice = login("alice");
        var created = alice.request("POST", "/api/chat/v1/rooms", "{\"name\":\"Live transaction\"}", "application/json");
        assertThat(created.statusCode()).isEqualTo(201);
        long id = json.readTree(created.body()).path("id").asLong();
        Wire bob = connect(login("bob"));
        subscribed(bob, id);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            rooms.dissolve(1, id);
            assertThat(bob.frames).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(bob.frames.poll(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_conversation WHERE id=?", String.class, id)).isEqualTo("ACTIVE");
        // A second real transaction must not emit while its commit is held back.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            rooms.dissolve(1, id);
            try { assertThat(bob.frames.poll(200, TimeUnit.MILLISECONDS)).isNull(); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        });
        String event = bob.frame();
        assertThat(event).startsWith("MESSAGE\n");
        assertThat(payload(event).path("eventType").asText()).isEqualTo("CONVERSATION_DISSOLVED");
        assertThat(payload(event).path("conversationId").asText()).isEqualTo(Long.toString(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_audit_event WHERE event_type='ROOM_DISSOLVED'", Integer.class)).isEqualTo(1);
        assertThat(alice.request("DELETE", "/api/chat/v1/rooms/" + id, null, null).statusCode()).isEqualTo(204);
        assertThat(bob.frames.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void dissolvedRoomRejectsNewSubscriptionsAndSendsFromExistingConnections() throws Exception {
        long id = rooms.create(1, "Closed room").getId();
        Browser alice = login("alice");
        Wire existing = connect(alice);
        subscribed(existing, id);
        rooms.dissolve(1, id);
        assertThat(existing.frame()).startsWith("MESSAGE\n");
        existing.send("SEND\ndestination:/app/chat.messages.send\ncontent-type:application/json\n\n"
                + "{\"conversationId\":\"" + id + "\",\"body\":\"too late\"}");
        assertError(existing, 404, "CONVERSATION_NOT_FOUND");
        Wire newcomer = connect(alice);
        newcomer.send("SUBSCRIBE\nid:closed\ndestination:" + topic(id) + "\n\n");
        assertError(newcomer, 404, "CONVERSATION_NOT_FOUND");
    }

    @Test void logoutStopsAnIdleSocketAndPreventsFurtherDelivery() throws Exception {
        long id = rooms.create(1, "Logout room").getId();
        Browser bob = login("bob");
        Wire wire = connect(bob);
        subscribed(wire, id);
        assertThat(bob.request("DELETE", "/api/chat/v1/sessions/current", null, null).statusCode()).isEqualTo(204);
        rooms.dissolve(1, id); // Outbound validation must drop this event even before the expiry sweep.
        // Tomcat can close on session destruction before the application's expiry sweep.
        assertThat(wire.closed.get(8, TimeUnit.SECONDS)).startsWith("1008:");
        assertThat(wire.frames).isEmpty();
        assertThat(bob.request("GET", "/api/chat/v1/conversations", null, null).statusCode()).isEqualTo(401);
    }

    @Test void sessionTimeoutClosesIdleConnectionWithoutInboundOrOutboundTraffic() throws Exception {
        Browser alice = login("alice");
        Wire wire = connect(alice);
        String cookie = alice.cookies.getCookieStore().getCookies().stream()
                .filter(value -> value.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        // The listener stores the same session object across fixation ID rotation.
        HttpSession session = probe.sessions.values().stream().filter(value -> {
            try { return value.getId().equals(cookie); } catch (IllegalStateException ignored) { return false; }
        }).findFirst().orElseThrow();
        session.setMaxInactiveInterval(1);
        assertThat(wire.closed.get(8, TimeUnit.SECONDS)).isEqualTo("1008:SESSION_EXPIRED");
        assertThat(alice.request("GET", "/api/chat/v1/sessions/current", null, null).statusCode()).isEqualTo(401);
    }

    static class Wire implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<String> closed = new CompletableFuture<>();
        final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            int end;
            while ((end = buffer.indexOf("\0")) >= 0) {
                String frame = buffer.substring(0, end).stripLeading();
                buffer.delete(0, end + 1);
                if (!frame.isBlank()) frames.add(frame);
            }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status + ":" + reason);
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        void send(String frame) throws Exception { socket.sendText(frame + "\0", true).get(5, TimeUnit.SECONDS); }
        String frame() throws Exception {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("Expected STOMP frame (close state: %s)", closed).isNotNull();
            return frame;
        }
        void close() { if (socket != null) socket.abort(); }
    }
}
