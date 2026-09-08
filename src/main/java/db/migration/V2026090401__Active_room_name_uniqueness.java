package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Statement;
import java.util.Locale;

/** 保留已解散房间的历史名称，仅让活动公共房间占用名称唯一键。 */
public class V2026090401__Active_room_name_uniqueness extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        String database = context.getConnection().getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        try (Statement sql = context.getConnection().createStatement()) {
            // 大小写已由 Java 归一化；MySQL 使用二进制排序规则，保留重音等代码点差异。
            String collation = database.contains("h2") ? "" : " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin";
            sql.execute("ALTER TABLE chat_conversation ADD COLUMN active_room_name VARCHAR(50)" + collation + """

                GENERATED ALWAYS AS (
                  CASE WHEN status = 'ACTIVE' AND type = 'PUBLIC_ROOM' THEN normalized_name ELSE NULL END
                )
                """);
            // 非活动公共房间生成 NULL，不占用名称；先建新索引，再删除旧唯一约束。
            sql.execute("CREATE UNIQUE INDEX uk_chat_active_room_name ON chat_conversation(active_room_name)");
            // MySQL 通过 DROP INDEX 删除唯一约束，H2 对应 DROP CONSTRAINT。
            sql.execute("ALTER TABLE chat_conversation DROP " + (database.contains("h2") ? "CONSTRAINT " : "INDEX ")
                    + "uk_chat_conversation_room_name");
        }
    }
}
