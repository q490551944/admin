package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Statement;
import java.util.Locale;

/** Keeps archived names intact while only active room names occupy the unique key. */
public class V2026090401__Active_room_name_uniqueness extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        String database = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        try (Statement sql = context.getConnection().createStatement()) {
            // Java already normalizes case. Preserve accents and other distinct code points
            // even when the existing database uses an accent-insensitive default collation.
            String collation = database.contains("h2") ? "" : " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin";
            sql.execute("ALTER TABLE chat_conversation ADD COLUMN active_room_name VARCHAR(50)" + collation + """

                GENERATED ALWAYS AS (
                  CASE WHEN status = 'ACTIVE' AND type = 'PUBLIC_ROOM' THEN normalized_name ELSE NULL END
                )
                """);
            sql.execute("CREATE UNIQUE INDEX uk_chat_active_room_name ON chat_conversation(active_room_name)");
            // MySQL uses DROP INDEX for unique constraints; H2 uses DROP CONSTRAINT.
            sql.execute("ALTER TABLE chat_conversation DROP " + (database.contains("h2") ? "CONSTRAINT " : "INDEX ")
                    + "uk_chat_conversation_room_name");
        }
    }
}
