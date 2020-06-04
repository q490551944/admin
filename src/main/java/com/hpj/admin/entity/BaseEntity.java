package com.hpj.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

/**
 * @author huangpeijun
 * @date 2020/3/10
 */
@Data
public class BaseEntity implements Serializable {

    /**
     * 主键ID
     */
    @TableId(type = IdType.ID_WORKER)
    protected Long id;

    /**
     * 逻辑删除字段：1=逻辑删除， 2=逻辑未删除
     */
    protected Boolean deleted;

    /**
     * 用户ID
     */
    protected Long createUser;
}
