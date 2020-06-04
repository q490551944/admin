package com.hpj.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * role
 *
 * @author
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("role")
public class Role extends SuperEntity {

    /**
     * 角色名
     */
    private String roleName;


    private static final long serialVersionUID = 1L;
}