package com.hpj.admin.chat;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 员工目录只返回现有账号的公开标识，不序列化 User 实体或密码。 */
public record ChatUserView(@JsonSerialize(using = ToStringSerializer.class) Long userId,
                           String name, String avatar) {}
