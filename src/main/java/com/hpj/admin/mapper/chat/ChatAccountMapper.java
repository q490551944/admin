package com.hpj.admin.mapper.chat;

import com.hpj.admin.entity.User;
import com.hpj.admin.chat.ChatUserView;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/** 复用现有 user 表完成认证查询和旧密码升级，不依赖额外用户字段。 */
public interface ChatAccountMapper {
    @Select("SELECT id AS user_id, username AS name, NULL AS avatar FROM user "
            + "WHERE status = TRUE AND id != #{userId} AND LOWER(username) LIKE #{pattern} ESCAPE '!' "
            + "ORDER BY username, id LIMIT 30")
    List<ChatUserView> searchColleagues(@Param("userId") long userId, @Param("pattern") String pattern);

    @Select("SELECT id, username, status FROM user WHERE id = #{id} FOR UPDATE")
    User lockAccount(@Param("id") long id);

    // 只读取历史表结构保证存在的字段；最多两条已足够判定用户名是否重复。
    @Select("SELECT id, username, password, status FROM user WHERE username = #{username} LIMIT 2")
    List<User> findByUsername(@Param("username") String username);

    /** 读取当前账号状态；是否启用由 ChatAccounts 判断，SQL 本身不排除停用账号。 */
    @Select("SELECT id, username, status FROM user WHERE id = #{id}")
    User findActiveAccount(@Param("id") long id);

    /** 仅当旧密码仍匹配时升级，避免覆盖并发修改后的凭证。 */
    @Update("UPDATE user SET password = #{newPassword} WHERE id = #{id} AND password = #{oldPassword}")
    int upgradePassword(@Param("id") long id, @Param("oldPassword") String oldPassword,
                        @Param("newPassword") String newPassword);
}
