package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 请求 ID 按原始字符区分，避免 MySQL 默认大小写不敏感排序规则把两个请求误判为重试。 */
public class V2026090801__Message_request_id_collation extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        if (context.getConnection().getMetaData().getDatabaseProductName().equalsIgnoreCase("H2")) return;
        try (var sql = context.getConnection().createStatement()) {
            sql.execute("ALTER TABLE chat_message MODIFY client_request_id VARCHAR(64) "
                    + "CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL");
        }
    }
}
