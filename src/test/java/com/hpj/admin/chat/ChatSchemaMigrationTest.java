package com.hpj.admin.chat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSchemaMigrationTest {

    private String jdbcUrl;
    private Flyway flyway;

    @BeforeEach
    void setUp() {
        jdbcUrl = "jdbc:h2:mem:chat_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1";
        flyway = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load();
    }

    @Test
    void migratesEmptyDatabaseAndIsSafeToRunAgain() throws SQLException {
        MigrateResult first = flyway.migrate();
        MigrateResult second = flyway.migrate();

        assertEquals(4, first.migrationsExecuted);
        assertEquals(0, second.migrationsExecuted);
        try (Connection connection = openConnection()) {
            for (String table : new String[]{
                    "chat_conversation", "chat_participant", "chat_message",
                    "chat_attachment", "chat_audit_event"}) {
                try (ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
                    assertTrue(tables.next(), () -> "Missing table " + table);
                }
            }
        }
    }

    @Test
    void databaseConstraintsProtectConversationAndMessageInvariants() throws SQLException {
        flyway.migrate();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO user (id, username, password, status) VALUES (1, 'alice', 'x', 1)");
            statement.executeUpdate("INSERT INTO user (id, username, password, status) VALUES (2, 'bob', 'x', 1)");
            statement.executeUpdate("INSERT INTO chat_conversation "
                    + "(id, type, name, normalized_name, owner_id, status) "
                    + "VALUES (101, 'PUBLIC_ROOM', 'Engineering', 'engineering', 1, 'ACTIVE')");

            assertSqlFails(statement, "INSERT INTO chat_conversation "
                    + "(id, type, name, normalized_name, owner_id, status) "
                    + "VALUES (102, 'PUBLIC_ROOM', 'engineering', 'engineering', 2, 'ACTIVE')");

            statement.executeUpdate("INSERT INTO chat_conversation "
                    + "(id, type, direct_key, status) VALUES (201, 'DIRECT_MESSAGE', '1:2', 'ACTIVE')");
            assertSqlFails(statement, "INSERT INTO chat_conversation "
                    + "(id, type, direct_key, status) VALUES (202, 'DIRECT_MESSAGE', '1:2', 'ACTIVE')");

            statement.executeUpdate("INSERT INTO chat_participant "
                    + "(id, conversation_id, user_id) VALUES (301, 201, 1)");
            assertSqlFails(statement, "INSERT INTO chat_participant "
                    + "(id, conversation_id, user_id) VALUES (302, 201, 1)");

            statement.executeUpdate("INSERT INTO chat_message "
                    + "(id, conversation_id, sender_id, message_type, client_request_id, body, status) "
                    + "VALUES (401, 201, 1, 'TEXT', 'request-1', 'hello', 'SENT')");
            assertSqlFails(statement, "INSERT INTO chat_message "
                    + "(id, conversation_id, sender_id, message_type, client_request_id, body, status) "
                    + "VALUES (402, 201, 1, 'TEXT', 'request-1', 'again', 'SENT')");
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    private void assertSqlFails(Statement statement, String sql) {
        assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
    }
}
