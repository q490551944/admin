package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Explicit performance gate: 100 authenticated connections, 10k persisted rows, real HTTP and STOMP. */
@SpringBootTest(classes = ChatTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=classpath:/chat-test.yml",
                "spring.datasource.url=jdbc:h2:mem:chat_performance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1"})
class ChatPerformanceTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatRoomService rooms;
    @Autowired MeterRegistry metrics;
    @Autowired org.apache.ibatis.session.SqlSessionFactory sessions;

    @Test
    @EnabledIfSystemProperty(named = "chat.performance", matches = "true")
    void baseline() throws Exception {
        ChatIntegrationTest.clean(jdbc);
        for (int i = 1; i <= 100; i++) jdbc.update("INSERT INTO user(id,username,password,status) VALUES(?,?,?,TRUE)",
                i, "load" + i, ChatIntegrationTest.HASH);
        long room = rooms.create(1, "Performance baseline").getId();
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        var rows = new ArrayList<Object[]>();
        for (int i = 1; i <= 10000; i++) rows.add(new Object[]{i, room, "seed" + i, "history " + i, start.plusSeconds(i * 8L)});
        jdbc.batchUpdate("INSERT INTO chat_message(id,conversation_id,sender_id,message_type,client_request_id,body,status,created_at) VALUES(?,?,1,'TEXT',?,?,'SENT',?)", rows);
        // Inspect the chosen access path with the same order and limit used by the default history query.
        String database = jdbc.execute((java.sql.Connection connection) -> connection.getMetaData().getDatabaseProductName());
        var parameters = new HashMap<String,Object>(); parameters.put("conversationId", room);
        parameters.put("cursor", null); parameters.put("forward", false); parameters.put("limit", 31);
        String sql = sessions.getConfiguration().getMappedStatement("com.hpj.admin.mapper.chat.ChatMessageMapper.history")
                .getBoundSql(parameters).getSql();
        var plan = jdbc.queryForList("EXPLAIN " + sql, room, 31);
        String explain = plan.toString();
        if (database.equals("MySQL")) assertThat(plan).anySatisfy(row -> assertThat(row.get("key")).isEqualTo("idx_chat_message_history"));
        else assertThat(explain.toLowerCase(Locale.ROOT)).contains("index");

        var driver = new ChatWebSocketIntegrationTest(); driver.port = port; driver.json = json;
        var clients = new ArrayList<ChatWebSocketIntegrationTest.Browser>();
        var wires = new ArrayList<ChatWebSocketIntegrationTest.Wire>();
        var pool = Executors.newFixedThreadPool(100);
        var historyMs = new ArrayList<Double>();
        var deliveryMs = new ArrayList<Double>();
        try {
            for (int i = 1; i <= 100; i++) {
                var client = driver.login("load" + i);
                clients.add(client);
                var wire = driver.connect(client);
                driver.confirmedSubscription(wire, "room", driver.topic(room));
                wires.add(wire);
            }
            assertThat(metrics.get("chat.websocket.connections").gauge().value()).isEqualTo(100);
            // Warm the database/JIT, then run 100 concurrent HTTP first-page requests.
            for (int i = 0; i < 10; i++) clients.get(0).request("GET", "/api/chat/v1/conversations/" + room + "/messages", null, null);
            for (int round = 0; round < 3; round++) {
                var requests = new ArrayList<Callable<Double>>();
                var gate = new CountDownLatch(1);
                for (var client : clients) requests.add(() -> {
                    gate.await(); long begun = System.nanoTime();
                    var response = client.request("GET", "/api/chat/v1/conversations/" + room + "/messages", null, null);
                    double elapsed = (System.nanoTime() - begun) / 1_000_000.0;
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(json.readTree(response.body()).path("items").size()).isEqualTo(30);
                    return elapsed;
                });
                var futures = requests.stream().map(pool::submit).toList(); gate.countDown();
                for (var future : futures) historyMs.add(future.get(30, TimeUnit.SECONDS));
            }
            // Each measured send must reach every one of the 100 online recipients.
            for (int i = 0; i < 20; i++) {
                String request = "perf-" + i;
                long begun = System.nanoTime();
                var receives = new ArrayList<Future<Double>>();
                for (var wire : wires) receives.add(pool.submit(() -> {
                    String frame = wire.frame();
                    var payload = json.readTree(frame.substring(frame.indexOf("\n\n") + 2));
                    assertThat(payload.path("message").path("clientRequestId").asText()).isEqualTo(request);
                    return (System.nanoTime() - begun) / 1_000_000.0;
                }));
                driver.sendText(wires.get(i), room, request, "latency sample " + i);
                for (var result : receives) deliveryMs.add(result.get(15, TimeUnit.SECONDS));
            }
        } finally { driver.closeConnections(); pool.shutdownNow(); }
        Collections.sort(historyMs); Collections.sort(deliveryMs);
        double historyP95 = percentile(historyMs), deliveryP95 = percentile(deliveryMs);
        var report = new LinkedHashMap<String,Object>();
        report.put("database", database); report.put("connectedUsers", 100); report.put("historyRows", 10000);
        report.put("httpSamples", historyMs.size()); report.put("deliverySamples", deliveryMs.size());
        report.put("historyP95Ms", historyP95); report.put("deliveryP95Ms", deliveryP95); report.put("explain", explain);
        report.put("historyServiceMaxMs", metrics.get("chat.history.duration").timer().max(TimeUnit.MILLISECONDS));
        report.put("model", "100 connected users; 3 bursts of 100 concurrent history requests; 20 sequential sends to all 100 recipients. End-to-end timings include client transport.");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/chat-performance-" + database.toLowerCase(Locale.ROOT) + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(historyP95).as("History P95 ms").isLessThan(500);
        assertThat(deliveryP95).as("Delivery P95 ms").isLessThan(1000);
    }

    private static double percentile(List<Double> values) { return values.get((int)Math.ceil(values.size() * .95) - 1); }
}
