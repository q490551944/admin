package com.hpj.admin.chat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in vendor check against a disposable MySQL server.
 * Creates and removes only its own randomly named schema, never a supplied database.
 */
@EnabledIfSystemProperty(named = "chat.mysql.test.url", matches = "jdbc:mysql://.+")
class ChatMySqlMigrationTest {
    @Test void upgradesExistingDataAndReleasesOnlyDissolvedRoomNames() throws Exception {
        String server = System.getProperty("chat.mysql.test.url");
        String user = System.getProperty("chat.mysql.test.user", "root");
        String password = System.getProperty("chat.mysql.test.password", "");
        String schema = "chat_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(server, user, password);
             var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try {
                var baseline = Flyway.configure().dataSource(server, user, password)
                        .defaultSchema(schema).locations("classpath:db/migration").target("2026081901").load();
                assertThat(baseline.migrate().migrationsExecuted).isEqualTo(2);
                connection.setCatalog(schema);
                try (var data = connection.createStatement()) {
                data.execute("INSERT INTO user(id,username,password,status) VALUES(1,'alice','test',TRUE)");
                data.execute("INSERT INTO chat_conversation(id,type,name,normalized_name,owner_id,status) "
                        + "VALUES(101,'PUBLIC_ROOM','Engineering','engineering',1,'DISSOLVED')");
                data.execute("INSERT INTO chat_message(id,conversation_id,sender_id,message_type,client_request_id,body,status) "
                        + "VALUES(201,101,1,'TEXT','retain','History survives migration','SENT')");
                var upgraded = Flyway.configure().dataSource(server, user, password)
                        .defaultSchema(schema).locations("classpath:db/migration").load();
                assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);
                assertThat(upgraded.migrate().migrationsExecuted).isZero();
                data.execute("INSERT INTO chat_conversation(id,type,name,normalized_name,owner_id,status) "
                        + "VALUES(102,'PUBLIC_ROOM','Engineering','engineering',1,'ACTIVE')");
                assertThatThrownBy(() -> data.execute("INSERT INTO chat_conversation(id,type,name,normalized_name,owner_id,status) "
                        + "VALUES(103,'PUBLIC_ROOM','Engineering','engineering',1,'ACTIVE')")).isInstanceOf(SQLException.class);
                assertThat(data.executeUpdate("UPDATE chat_conversation SET status='DISSOLVED',dissolved_at=CURRENT_TIMESTAMP,"
                        + "updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=102 AND owner_id=1 AND status='ACTIVE' AND version=0")).isEqualTo(1);
                data.execute("INSERT INTO chat_conversation(id,type,name,normalized_name,owner_id,status) "
                        + "VALUES(104,'PUBLIC_ROOM','Engineering','engineering',1,'ACTIVE')");
                try (var result = data.executeQuery("SELECT body FROM chat_message WHERE id=201")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("History survives migration");
                }
                try (var result = data.executeQuery("SELECT COUNT(*) FROM chat_conversation WHERE normalized_name='engineering'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(3);
                }
                data.execute("INSERT INTO chat_conversation(id,type,name,normalized_name,owner_id,status) "
                        + "VALUES(105,'PUBLIC_ROOM','Éngineering','éngineering',1,'ACTIVE')");
                }
            } finally {
                // The target is generated locally and CREATE succeeded above; no user schema is deleted.
                try (var cleanup = connection.createStatement()) { cleanup.execute("DROP DATABASE " + schema); }
            }
        }
    }
}
