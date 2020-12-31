package com.hpj.admin.common.extend;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
//    @TableId(type = IdType.ASSIGN_ID)
//    @JsonSerialize(using = ToStringSerializer.class)
//    public Long id;

    /**
     * 逻辑删除字段：1=逻辑删除， 2=逻辑未删除
     */
    @TableLogic
    public boolean deleted = false;

    /**
     * 用户ID
     */
    public Long createUser;
}
