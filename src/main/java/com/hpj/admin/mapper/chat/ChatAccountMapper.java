package com.hpj.admin.mapper.chat;

import com.hpj.admin.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

public interface ChatAccountMapper {
    // Select only columns guaranteed by the existing user migration.
    @Select("SELECT id, username, password, status FROM user WHERE username = #{username} LIMIT 2")
    List<User> findByUsername(@Param("username") String username);

    @Select("SELECT id, username, status FROM user WHERE id = #{id}")
    User findActiveAccount(@Param("id") long id);

    @Update("UPDATE user SET password = #{newPassword} WHERE id = #{id} AND password = #{oldPassword}")
    int upgradePassword(@Param("id") long id, @Param("oldPassword") String oldPassword,
                        @Param("newPassword") String newPassword);
}
