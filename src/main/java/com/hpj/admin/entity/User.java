package com.hpj.admin.entity;


import com.baomidou.mybatisplus.annotation.TableName;
import com.hpj.admin.common.extend.SuperEntity;
import com.hpj.admin.enums.Sex;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

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

    public static void main(String[] args) throws ParseException {
        String startTime = "2022-10-1";
        String endTime = "2022-10-20";

    }

}

