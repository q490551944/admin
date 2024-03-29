package com.hpj.admin.entity;


import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.SuperEntity;
import com.hpj.admin.enums.Sex;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @author huangpeijun
 * @date 2020/3/3
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("user")
public class User extends SuperEntity {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z][\\w]{0,19}$")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "[\\w]{1,20}$")
    private String password;

    private Boolean status;

    private Sex sex;


}

